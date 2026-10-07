package com.sclera.applicationplane.helper.external.vocabulary;

import com.sclera.applicationplane.helper.tenancy.TenantContext;
import com.sclera.applicationplane.helper.tenancy.TenantRegistryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The vocabulary for another service, called over Dapr invocation — the
 * procedure service asking whether a target-type key exists before it lets a
 * version be published.
 *
 * <p><b>No {@code @PreAuthorize}, because there is no JWT.</b> Not routed by the
 * gateway ({@code /internal/**} never is); guarded by HMAC signing plus
 * {@code InternalEndpointFilter}, and permitted in {@code SecurityConfig} on that
 * basis. Hence the organization is an explicit path parameter.
 *
 * <p><b>Every parameter is in the path.</b> Dapr rejects {@code ?} in an
 * invocation method name and {@code DaprInvocationHelper} has no query-parameter
 * overload.
 *
 * <p><b>The schema is pinned explicitly.</b> With no JWT there is no
 * {@code OrgContext}, so the organization's schema is provisioned if need be and
 * the read runs inside {@link TenantContext#runAs}. A first call for an
 * organization that has never signed in therefore returns the seeded defaults
 * rather than failing.
 *
 * <p>Always every entry, active or not: the caller validates a published version
 * against the full list and decides for itself what is offered for new use.
 */
@RestController
@RequestMapping("/internal/api/v1/vocabulary")
public class InternalVocabularyController {

    private final VocabularyService service;
    private final TenantRegistryService tenantRegistry;

    public InternalVocabularyController(VocabularyService service, TenantRegistryService tenantRegistry) {
        this.service = service;
        this.tenantRegistry = tenantRegistry;
    }

    @GetMapping("/orgs/{orgId}")
    public Map<VocabularyKind, List<VocabularyEntryResponse>> all(@PathVariable UUID orgId) {
        tenantRegistry.ensureTenant(orgId);
        return TenantContext.runAs(orgId, () -> service.all(false));
    }
}