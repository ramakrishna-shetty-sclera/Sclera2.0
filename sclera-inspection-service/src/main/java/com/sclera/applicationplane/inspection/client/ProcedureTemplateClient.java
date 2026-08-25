package com.sclera.applicationplane.inspection.client;

import com.sclera.controlplane.common.dapr.DaprInvocationHelper;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fetches question-template snapshots from procedure-service over Dapr service
 * invocation. DaprInvocationHelper (from sclera-common) HMAC-signs the request
 * and propagates the correlation id; both services must share the same
 * sclera.event-listener.signing-secret.
 */
@Component
public class ProcedureTemplateClient {

    private static final Logger log = LoggerFactory.getLogger(ProcedureTemplateClient.class);
    private static final String PROCEDURE_APP_ID = "sclera-procedure-service";

    private final DaprInvocationHelper daprInvocationHelper;

    public ProcedureTemplateClient(DaprInvocationHelper daprInvocationHelper) {
        this.daprInvocationHelper = daprInvocationHelper;
    }

    public TemplateSnapshot fetchTemplate(UUID templateId, UUID orgId) {
        // orgId goes in the path — Dapr rejects '?' in the invocation method name.
        String path = "internal/api/v1/question-templates/" + templateId + "/orgs/" + orgId;
        log.debug("Fetching template {} for org {} from {}", templateId, orgId, PROCEDURE_APP_ID);
        TemplateSnapshot snapshot =
                daprInvocationHelper.invokeGet(PROCEDURE_APP_ID, path, TemplateSnapshot.class);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Question template not found: " + templateId);
        }
        return snapshot;
    }
}
