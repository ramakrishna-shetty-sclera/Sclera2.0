package com.sclera.applicationplane.inspection.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Maps the current request's org (TenantContext override â†’ OrgContext) to a
 * schema name for Hibernate. No org (actuator, schema tooling, internal
 * plumbing outside runAs) â‡’ public.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        UUID orgId = TenantContext.currentOrgId();
        return orgId != null ? TenantSchemas.schemaFor(orgId) : TenantSchemas.PUBLIC;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}

