package com.sclera.applicationplane.inspection.authz;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sclera.controlplane.common.security.OrgContext;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.model.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * OpenFGA-backed authorization. Referenced from controllers as the SpEL bean
 * "fga", e.g. {@code @PreAuthorize("@fga.check('inspection', #id, 'can_view')")}.
 *
 * Identity comes from OrgContext (populated by ScleraJwtConverter):
 * FGA user = {@code user:<jwt sub>}, tenant = {@code organization:<org_id>}.
 *
 * Check decisions are cached per user/relation/object for
 * sclera.fga.check-cache-ttl-seconds (default 30s) — permission changes take
 * up to that long to be visible; error fallbacks are never cached.
 *
 * Checks FAIL CLOSED when OpenFGA is unreachable; tuple writes throw so the
 * surrounding DB transaction rolls back rather than leaving an object nobody
 * can access. With sclera.fga.enabled=false everything allows (JWT + org
 * scoping still apply), which keeps local runs working without OpenFGA.
 */
@Service("fga")
public class FgaAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(FgaAuthorizationService.class);

    private final ObjectProvider<OpenFgaClient> clientProvider;
    private final FgaProperties properties;
    private final Cache<String, Boolean> checkCache;
    private volatile boolean storeResolved;

    public FgaAuthorizationService(ObjectProvider<OpenFgaClient> clientProvider, FgaProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
        this.checkCache = properties.getCheckCacheTtlSeconds() > 0
                ? Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofSeconds(properties.getCheckCacheTtlSeconds()))
                        .build()
                : null;
        if (!properties.isEnabled()) {
            log.warn("OpenFGA is DISABLED (sclera.fga.enabled=false) — all fine-grained checks allow");
        }
    }

    /** Object-level check for the current user, e.g. check("inspection", id, "can_edit"). */
    public boolean check(String objectType, UUID objectId, String relation) {
        return checkInternal(relation, objectType + ":" + objectId);
    }

    /** Org-level check for the current user, e.g. checkOrg("can_manage_inspections"). */
    public boolean checkOrg(String relation) {
        UUID orgId = OrgContext.getOrgId();
        if (orgId == null) {
            return false;
        }
        return checkInternal(relation, "organization:" + orgId);
    }

    /**
     * Writes the tuples for a newly created object in ONE round trip:
     * org link + creator + optional assignee. A failure throws so the caller's
     * DB transaction rolls back.
     */
    public void grantCreated(String objectType, UUID objectId, UUID creatorId, UUID assigneeId) {
        if (!properties.isEnabled()) {
            return;
        }
        UUID orgId = OrgContext.getOrgId();
        if (orgId == null) {
            throw new IllegalStateException("No org in context — cannot write FGA tuples for " + objectType);
        }
        String object = objectType + ":" + objectId;
        List<ClientTupleKey> tuples = new ArrayList<>();
        tuples.add(new ClientTupleKey().user("organization:" + orgId).relation("org")._object(object));
        if (creatorId != null) {
            tuples.add(new ClientTupleKey().user("user:" + creatorId).relation("creator")._object(object));
        }
        if (assigneeId != null) {
            tuples.add(new ClientTupleKey().user("user:" + assigneeId).relation("assignee")._object(object));
        }
        writeTuples(tuples);
    }

    private boolean checkInternal(String relation, String object) {
        if (!properties.isEnabled()) {
            return true;
        }
        if (OrgContext.isPlatformAdmin()) {
            return true;
        }
        UUID userId = OrgContext.getUserId();
        if (userId == null) {
            return false;
        }
        String cacheKey = userId + "|" + relation + "|" + object;
        if (checkCache != null) {
            Boolean cached = checkCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        try {
            OpenFgaClient client = readyClient();
            Boolean allowed = client.check(new ClientCheckRequest()
                            .user("user:" + userId)
                            .relation(relation)
                            ._object(object))
                    .get()
                    .getAllowed();
            boolean result = Boolean.TRUE.equals(allowed);
            if (checkCache != null) {
                checkCache.put(cacheKey, result);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // fail closed, but never cache an error-driven denial
            log.error("OpenFGA check failed (user={}, relation={}, object={}): {}",
                    userId, relation, object, e.getMessage());
            return false;
        }
    }

    private void writeTuples(List<ClientTupleKey> tuples) {
        try {
            OpenFgaClient client = readyClient();
            client.write(new ClientWriteRequest().writes(tuples)).get();
            log.debug("FGA tuples written: {}", tuples.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted writing FGA tuples", e);
        } catch (ExecutionException e) {
            // re-creating the same tuples (e.g. retried request) is not an error
            String message = e.getCause() != null ? String.valueOf(e.getCause().getMessage()) : "";
            if (message.contains("already exists")) {
                log.debug("FGA tuples already exist");
                return;
            }
            throw new IllegalStateException("Failed to write FGA tuples", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write FGA tuples", e);
        }
    }

    /** Pre-resolves the store off the request path so the first API call doesn't pay for it. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!properties.isEnabled()) {
            return;
        }
        Thread warmup = new Thread(() -> {
            try {
                readyClient();
            } catch (Exception e) {
                log.warn("OpenFGA warm-up failed (will retry on first check): {}", e.getMessage());
            }
        }, "fga-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    /** Resolves the store (by id or name) on first use so boot doesn't require OpenFGA. */
    private OpenFgaClient readyClient() throws Exception {
        OpenFgaClient client = clientProvider.getObject();
        if (storeResolved) {
            return client;
        }
        synchronized (this) {
            if (storeResolved) {
                return client;
            }
            String storeId = properties.getStoreId();
            if (storeId == null || storeId.isBlank()) {
                storeId = client.listStores().get().getStores().stream()
                        .filter(s -> properties.getStoreName().equals(s.getName()))
                        .map(Store::getId)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "OpenFGA store '" + properties.getStoreName()
                                        + "' not found — run setup-openfga.ps1"));
            }
            client.setStoreId(storeId);
            String modelId = properties.getAuthorizationModelId();
            if (modelId != null && !modelId.isBlank()) {
                client.setAuthorizationModelId(modelId);
            }
            log.info("OpenFGA ready: store {} ({})", properties.getStoreName(), storeId);
            storeResolved = true;
            return client;
        }
    }
}
