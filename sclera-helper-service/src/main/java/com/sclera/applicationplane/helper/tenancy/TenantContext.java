package com.sclera.applicationplane.helper.tenancy;

import com.sclera.controlplane.common.security.OrgContext;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tenant override for code paths that have no JWT-derived OrgContext â€”
 * the HMAC-guarded /internal/** endpoints receive the orgId explicitly and
 * wrap their work in {@link #runAs}. Everywhere else the tenant comes from
 * OrgContext (populated by ScleraJwtConverter).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> OVERRIDE = new ThreadLocal<>();

    private TenantContext() {}

    public static <T> T runAs(UUID orgId, Supplier<T> work) {
        OVERRIDE.set(orgId);
        try {
            return work.get();
        } finally {
            OVERRIDE.remove();
        }
    }

    /** Resolution order: explicit override â†’ JWT OrgContext â†’ null (â‡’ public schema). */
    public static UUID currentOrgId() {
        UUID override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        try {
            return OrgContext.getOrgId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

