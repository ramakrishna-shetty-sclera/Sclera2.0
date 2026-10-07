package com.sclera.applicationplane.procedure.dto;

/**
 * What the signed-in caller may do with procedures and result types — the
 * frontend's only way to know, since Keycloak defines no realm roles and
 * "role" means an OpenFGA organization relation.
 *
 * Only what a screen actually gates on. {@code can_import_templates} and
 * {@code can_review} are declared in the authorization model but enforced by
 * no endpoint yet, so they are not here — returning them would promise a
 * capability that does not exist.
 */
public record PermissionsResponse(
        boolean canViewProcedures,
        boolean canManageTemplates,
        boolean canPublishTemplates,
        boolean canManageResultTypes) {
}
