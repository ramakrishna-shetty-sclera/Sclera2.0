package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.QuestionTemplateResponse;
import com.sclera.applicationplane.procedure.service.QuestionTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service endpoint, called by inspection-service via Dapr invocation.
 * Not routed by the reverse proxy; guarded by HMAC signing + InternalEndpointFilter,
 * not by JWT — hence orgId arrives as an explicit parameter.
 *
 * orgId travels in the path, not as a query parameter: Dapr (1.15+) rejects '?' in
 * the invocation method name, and DaprInvocationHelper has no query-param overload.
 */
@RestController
@RequestMapping("/internal/api/v1/question-templates")
public class InternalQuestionTemplateController {

    private final QuestionTemplateService service;

    public InternalQuestionTemplateController(QuestionTemplateService service) {
        this.service = service;
    }

    @GetMapping("/{id}/orgs/{orgId}")
    public QuestionTemplateResponse getSnapshot(@PathVariable UUID id, @PathVariable UUID orgId) {
        return service.getForOrg(id, orgId);
    }
}
