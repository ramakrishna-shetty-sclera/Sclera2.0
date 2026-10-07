package com.sclera.applicationplane.procedure.client.storage;

import com.sclera.applicationplane.procedure.client.storage.StorageDtos.ResolveLocationRequest;
import com.sclera.applicationplane.procedure.client.storage.StorageDtos.ResolveLocationResponse;
import com.sclera.controlplane.common.dapr.DaprInvocationHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One Dapr call, no business logic. The app-id is configuration, not a
 * constant, so cutting over to a real storage service later is a config
 * change here and nothing else — unlike the dead
 * {@code inspection.client.ProcedureTemplateClient}, which hardcodes its
 * target and is the pattern not to repeat.
 */
@Component
public class DaprStorageClient implements StorageClient {

    private static final Logger log = LoggerFactory.getLogger(DaprStorageClient.class);

    private final DaprInvocationHelper daprInvocationHelper;
    private final String appId;

    public DaprStorageClient(DaprInvocationHelper daprInvocationHelper,
                              @Value("${sclera.external.storage.app-id:sclera-helper-service}") String appId) {
        this.daprInvocationHelper = daprInvocationHelper;
        this.appId = appId;
    }

    @Override
    public boolean resolves(String location) {
        log.debug("Resolving document location against {}", appId);
        ResolveLocationResponse response = daprInvocationHelper.invoke(
                appId, "internal/api/v1/documents/resolve",
                new ResolveLocationRequest(location), ResolveLocationResponse.class);
        return response != null && response.exists();
    }
}
