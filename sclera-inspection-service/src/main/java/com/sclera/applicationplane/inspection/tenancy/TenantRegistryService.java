package com.sclera.applicationplane.inspection.tenancy;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns {@code public.tenant_registry} and tenant provisioning: registry row →
 * CREATE SCHEMA → per-schema Flyway ({@code classpath:db/tenant}, each schema
 * has its own flyway history) → one-time copy of this org's legacy rows from
 * the shared public schema.
 *
 * Provisioning is idempotent and cached; safe to call per request.
 */
@Service
public class TenantRegistryService {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistryService.class);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Set<String> provisioned = ConcurrentHashMap.newKeySet();

    public TenantRegistryService(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** Idempotent bootstrap — also created by V2 migration; this covers pre-V2 databases. */
    public void ensureRegistryTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS public.tenant_registry (
                    org_id             UUID PRIMARY KEY,
                    schema_name        VARCHAR(63) NOT NULL UNIQUE,
                    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    copied_public_data BOOLEAN     NOT NULL DEFAULT FALSE,
                    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
    }

    public List<String> listActiveSchemas() {
        return jdbc.queryForList(
                "SELECT schema_name FROM public.tenant_registry WHERE status = 'ACTIVE'", String.class);
    }

    /** Ensure the org's schema exists, is migrated, and has its legacy public rows. */
    public String ensureTenant(UUID orgId) {
        String schema = TenantSchemas.schemaFor(orgId);
        if (provisioned.contains(schema)) {
            return schema;
        }
        synchronized (schema.intern()) {
            if (provisioned.contains(schema)) {
                return schema;
            }
            jdbc.update("""
                    INSERT INTO public.tenant_registry (org_id, schema_name)
                    VALUES (?, ?) ON CONFLICT (org_id) DO NOTHING""", orgId, schema);
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + TenantSchemas.requireValid(schema) + "\"");
            migrateSchema(schema);
            copyPublicDataOnce(orgId, schema);
            provisioned.add(schema);
            log.info("Tenant provisioned: org={} schema={}", orgId, schema);
        }
        return schema;
    }

    /** Run the tenant table migrations against one schema (own flyway history inside it). */
    public void migrateSchema(String schema) {
        TenantSchemas.requireValid(schema);
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/tenant")
                .load()
                .migrate();
    }

    /**
     * One-time lift of this org's rows out of the legacy shared public schema
     * into its own schema (identical DDL ⇒ INSERT…SELECT *). Guarded by the
     * registry's copied_public_data flag.
     */
    private void copyPublicDataOnce(UUID orgId, String schema) {
        Boolean copied = jdbc.queryForObject(
                "SELECT copied_public_data FROM public.tenant_registry WHERE org_id = ?",
                Boolean.class, orgId);
        if (Boolean.TRUE.equals(copied)) {
            return;
        }
        String s = "\"" + schema + "\"";
        int inspections = jdbc.update(
                "INSERT INTO " + s + ".inspection SELECT * FROM public.inspection " +
                "WHERE org_id = ? ON CONFLICT (id) DO NOTHING", orgId);
        int answers = jdbc.update(
                "INSERT INTO " + s + ".inspection_answer SELECT a.* FROM public.inspection_answer a " +
                "JOIN public.inspection i ON a.inspection_id = i.id " +
                "WHERE i.org_id = ? ON CONFLICT (id) DO NOTHING", orgId);
        jdbc.update("UPDATE public.tenant_registry SET copied_public_data = TRUE WHERE org_id = ?", orgId);
        log.info("Copied legacy public rows into {}: inspections={} answers={}", schema, inspections, answers);
    }
}
