package com.sclera.applicationplane.procedure.tenancy;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Owns {@code public.tenant_registry} and tenant provisioning: registry row →
 * CREATE SCHEMA → per-schema Flyway ({@code classpath:db/tenant}, each schema
 * has its own flyway history).
 *
 * There is no longer a copy of legacy rows out of the public schema: the
 * tables it copied (question_template, template_section, question) were
 * dropped with the move to versioned procedure templates. The registry's
 * copied_public_data column is left in place, unused.
 *
 * Provisioning is idempotent and cached; safe to call per request.
 *
 * Everything here runs on the <em>owner</em> connection, not the request one.
 * Requests connect as a role that owns nothing so that row-level security
 * applies to it, and a role like that cannot CREATE SCHEMA, run migrations or
 * write the registry.
 */
@Service
public class TenantRegistryService {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistryService.class);

    /** Comes from configuration rather than a request, but it is interpolated into DDL. */
    private static final Pattern VALID_ROLE = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final String appRole;
    private final Set<String> provisioned = ConcurrentHashMap.newKeySet();

    public TenantRegistryService(@Qualifier("ownerJdbcTemplate") JdbcTemplate jdbc,
                                 @Qualifier("ownerDataSource") DataSource dataSource,
                                 @Value("${sclera.datasource.app-role}") String appRole) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        if (!VALID_ROLE.matcher(appRole).matches()) {
            throw new IllegalArgumentException("Illegal application role name: " + appRole);
        }
        this.appRole = appRole;
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

    /** Ensure the org's schema exists and is migrated. */
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
        grantAppRole(schema);
    }

    /**
     * Lets the request role reach this schema.
     *
     * Tenant schemas are created at runtime, so these grants cannot live in a
     * migration the way the public ones do. Run after <em>every</em> migration
     * pass rather than once at creation: a later migration adding a table would
     * otherwise leave it ungranted, and the symptom — one table permission-denied
     * while the rest of the schema works — is a confusing way to find that out.
     *
     * ALTER DEFAULT PRIVILEGES covers tables created after this point, so a
     * schema migrated in the same pass as a new migration is covered twice.
     * Granting what is already granted is a no-op, which is what makes calling
     * this unconditionally safe.
     */
    private void grantAppRole(String schema) {
        String s = TenantSchemas.requireValid(schema);
        jdbc.execute("GRANT USAGE ON SCHEMA \"" + s + "\" TO " + appRole);
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA \"" + s + "\" TO " + appRole);
        jdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA \"" + s + "\" TO " + appRole);
        jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA \"" + s
                + "\" GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + appRole);
        jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA \"" + s
                + "\" GRANT USAGE, SELECT ON SEQUENCES TO " + appRole);
    }

}
