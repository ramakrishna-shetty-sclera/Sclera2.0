package com.sclera.applicationplane.inspection.tenancy;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Schema-per-tenant naming. A tenant (org) UUID maps to schema
 * {@code t_<uuid-without-dashes>} â€” 34 chars, always a valid Postgres
 * identifier, collision-free, never derived from display names.
 */
public final class TenantSchemas {

    public static final String PUBLIC = "public";

    private static final Pattern VALID = Pattern.compile("^(public|t_[0-9a-f]{32})$");

    private TenantSchemas() {}

    public static String schemaFor(UUID orgId) {
        return "t_" + orgId.toString().replace("-", "").toLowerCase();
    }

    /** Defense in depth: anything reaching SET search_path must match exactly. */
    public static String requireValid(String schema) {
        if (schema == null || !VALID.matcher(schema).matches()) {
            throw new IllegalArgumentException("Illegal tenant schema name: " + schema);
        }
        return schema;
    }
}

