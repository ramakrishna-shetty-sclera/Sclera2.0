package com.sclera.applicationplane.procedure.dto;

/**
 * What the signed-in caller may do with procedures and result types — the
 * frontend's only way to know, since Keycloak defines no realm roles and
 * "role" means an OpenFGA organization relation.
 *
 * Only what a screen actually gates on. {@code can_review} is declared in the
 * authorization model but enforced by no endpoint yet, so it is not here —
 * returning it would promise a capability that does not exist.
 * {@code can_import_templates} used to be excluded for the same reason; it is
 * included now that feature 11's {@code POST /api/v1/procedure-templates/import}
 * actually guards on it.
 */
public record PermissionsResponse(
        boolean canViewProcedures,
        boolean canManageTemplates,
        boolean canPublishTemplates,
        boolean canManageResultTypes,
        boolean canImportTemplates) {
}
