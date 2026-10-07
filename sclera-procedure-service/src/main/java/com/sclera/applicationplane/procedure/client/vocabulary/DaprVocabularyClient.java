package com.sclera.applicationplane.procedure.client.vocabulary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sclera.controlplane.common.dapr.DaprInvocationHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the vocabulary over Dapr service invocation. {@code DaprInvocationHelper}
 * signs the call with HMAC and carries the correlation id; both services must
 * share {@code sclera.event-listener.signing-secret}.
 *
 * <p>The call is shaped by two facts about Dapr: it rejects {@code ?} in an
 * invocation method name, and the helper has no query-parameter overload — so
 * there is no leading slash, no query string, and the organization is a path
 * segment.
 *
 * <p>Any failure becomes a {@link VocabularyUnavailableException}. The helper
 * raises its own exception types, and a caller deciding what to tell an author
 * should not have to know which of them means "could not read it".
 */
@Component
public class DaprVocabularyClient implements VocabularyClient {

    private static final Logger log = LoggerFactory.getLogger(DaprVocabularyClient.class);
    static final TypeReference<Map<VocabularyKind, List<VocabularyEntry>>> LISTS = new TypeReference<>() { };

    private final DaprInvocationHelper dapr;
    private final VocabularyProperties properties;

    public DaprVocabularyClient(DaprInvocationHelper dapr, VocabularyProperties properties) {
        this.dapr = dapr;
        this.properties = properties;
    }

    @Override
    public Vocabulary fetch(UUID orgId) {
        String appId = properties.getAppId();
        String method = "internal/api/v1/vocabulary/orgs/" + orgId;
        try {
            Map<VocabularyKind, List<VocabularyEntry>> lists = dapr.invokeGet(appId, method, LISTS);
            if (lists == null) {
                throw new VocabularyUnavailableException("The vocabulary service '" + appId + "' returned nothing", null);
            }
            return new Vocabulary(lists);
        } catch (VocabularyUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Could not read the vocabulary for org {} from {}: {}", orgId, appId, e.getMessage());
            throw new VocabularyUnavailableException("The vocabulary service '" + appId + "' could not be reached", e);
        }
    }
}