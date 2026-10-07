package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.EvaluationDtos.EvaluateRequest;
import com.sclera.applicationplane.procedure.dto.EvaluationDtos.EvaluationResponse;
import com.sclera.applicationplane.procedure.service.ProcedureTemplateService;
import com.sclera.applicationplane.procedure.tenancy.TenantContext;
import com.sclera.applicationplane.procedure.tenancy.TenantRegistryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Evaluation for the inspection service, called over Dapr invocation — the
 * call that will let it stop deciding pass and fail for itself.
 *
 * <p><b>No {@code @PreAuthorize}, because there is no JWT.</b> Not routed by
 * the gateway ({@code /internal/**} never is); guarded by HMAC signing plus
 * {@code InternalEndpointFilter} and permitted in {@code SecurityConfig} on
 * that basis. Hence the organization arrives as an explicit parameter.
 *
 * <p><b>Every parameter is in the path.</b> Dapr (1.15+) rejects {@code ?} in
 * an invocation method name and {@code DaprInvocationHelper} has no
 * query-parameter overload. The answers travel in the body, which the helper's
 * {@code invoke(appId, method, body, type)} sends as a POST and the HMAC
 * filter can still read.
 *
 * <p><b>The schema is pinned explicitly.</b> No JWT means no {@code OrgContext},
 * so the organization's schema is provisioned if need be and the work runs
 * inside {@link TenantContext#runAs}. The transaction opens inside it, which
 * is what puts the lookup in the right schema ({@code open-in-view} is off).
 *
 * <p>Modelled on {@code InternalQuestionTemplateController}, deleted with the
 * two-level model in {@code 3943f5c}.
 */
@RestController
@RequestMapping("/internal/api/v1/procedure-versions")
public class InternalEvaluationController {

    private final ProcedureTemplateService service;
    private final TenantRegistryService tenantRegistry;

    public InternalEvaluationController(ProcedureTemplateService service, TenantRegistryService tenantRegistry) {
        this.service = service;
        this.tenantRegistry = tenantRegistry;
    }

    /** A published version only; a draft is refused. See {@link ProcedureTemplateService#evaluatePublished}. */
    @PostMapping("/{versionId}/orgs/{orgId}/evaluate")
    public EvaluationResponse evaluate(@PathVariable UUID versionId, @PathVariable UUID orgId,
                                       @RequestBody(required = false) EvaluateRequest request) {
        tenantRegistry.ensureTenant(orgId);
        return TenantContext.runAs(orgId, () -> service.evaluatePublished(versionId, orgId, request));
    }
}
