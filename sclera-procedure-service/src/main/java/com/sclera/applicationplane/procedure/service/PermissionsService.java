package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.authz.FgaAuthorizationService;
import com.sclera.applicationplane.procedure.dto.PermissionsResponse;
import org.springframework.stereotype.Service;

/**
 * What the signed-in caller may do, for the screens that gate on it.
 *
 * Four {@code checkOrg} calls rather than a batch check: this runs once per
 * app load, decisions are already cached
 * {@code sclera.fga.check-cache-ttl-seconds}, and a batch mechanism would be a
 * second way to do the same four checks for no gain at this size.
 */
@Service
public class PermissionsService {

    private final FgaAuthorizationService fga;

    public PermissionsService(FgaAuthorizationService fga) {
        this.fga = fga;
    }

    public PermissionsResponse forCurrentUser() {
        return new PermissionsResponse(
                fga.checkOrg("can_view"),
                fga.checkOrg("can_manage_templates"),
                fga.checkOrg("can_publish_templates"),
                fga.checkOrg("can_manage_result_types"));
    }
}
