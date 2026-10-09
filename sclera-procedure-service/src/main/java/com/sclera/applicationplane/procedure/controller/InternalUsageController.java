package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.UsageDtos.ReportUsageRequest;
import com.sclera.applicationplane.procedure.dto.UsageDtos.ReportUsageResponse;
import com.sclera.applicationplane.procedure.service.ProcedureUsageService;
import com.sclera.applicationplane.procedure.tenancy.TenantContext;
import com.sclera.applicationplane.procedure.tenancy.TenantRegistryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Where a consumer reports which version of a procedure it is using, so an
 * author can see the impact before publishing a new one. Called by other
 * services over Dapr, never by a browser.
 *
 * <p><b>No {@code @PreAuthorize}, because there is no JWT.</b> Not routed by the
 * gateway ({@code /internal/**} never is); guarded by HMAC signing plus
 * {@code InternalEndpointFilter}, and permitted in {@code SecurityConfig} on
 * that basis. Hence the organization arrives as an explicit parameter.
 *
 * <p><b>Every parameter that is not the body is in the path.</b> Dapr rejects
 * {@code ?} in an invocation method name and {@code DaprInvocationHelper} has no
 * query-parameter overload.
 *
 * <p><b>The schema is pinned explicitly.</b> No JWT means no organization
 * context, so the organization's schema is provisioned if need be and the work
 * runs inside {@link TenantContext#runAs}. The transaction opens inside it,
 * which is what puts the write in the right schema.
 *
 * <p><b>No consumer calls this yet.</b> The inspection service does not report
 * usage today; wiring it to is the integration branch's work. This builds the
 * endpoint the way the evaluation endpoint was built, ahead of its caller.
 *
 * <p>Modelled on {@link InternalEvaluationController}.
 */
@RestController
@RequestMapping("/internal/api/v1/procedure-usage")
public class InternalUsageController {

    private final ProcedureUsageService service;
    private final TenantRegistryService tenantRegistry;

    public InternalUsageController(ProcedureUsageService service, TenantRegistryService tenantRegistry) {
        this.service = service;
        this.tenantRegistry = tenantRegistry;
    }

    /** Safe to repeat: the same consumer, reference and template updates one row. */
    @PostMapping("/orgs/{orgId}/report")
    public ReportUsageResponse report(@PathVariable UUID orgId, @Valid @RequestBody ReportUsageRequest request) {
        tenantRegistry.ensureTenant(orgId);
        return TenantContext.runAs(orgId, () -> service.reportUsage(request));
    }
}
