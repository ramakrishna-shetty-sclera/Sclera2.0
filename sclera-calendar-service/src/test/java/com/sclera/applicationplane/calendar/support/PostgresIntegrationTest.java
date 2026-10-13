package com.sclera.applicationplane.calendar.support;

import com.sclera.applicationplane.calendar.tenancy.TenantRegistryService;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base for integration tests that need the real database: Flyway, the
 * schema-per-tenant layer, JSONB and the partial unique indexes only exist in
 * Postgres. One throwaway postgres:16-alpine container for the whole run;
 * OpenFGA is disabled; no JWT — {@link #actAsNewOrg()} fills OrgContext the way
 * ScleraJwtConverter does and provisions the org's schema the way
 * TenantProvisioningFilter does.
 *
 * Unlike procedure-service there is no owner/app-role split here (this service
 * follows the helper-service tenancy model), so the container credentials are
 * used directly.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("sclera_calendar");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("sclera.fga.enabled", () -> "false");
        // keep the read caches honest in tests: effectively no TTL staleness
        registry.add("sclera.calendar.cache-ttl-seconds", () -> "1");
        registry.add("logging.level.com.sclera", () -> "INFO");
        registry.add("logging.level.org.springframework.security", () -> "WARN");
    }

    @Autowired
    protected TenantRegistryService tenantRegistry;

    /** Acts as a user of a brand-new organization; returns the org id. */
    protected UUID actAsNewOrg() {
        UUID orgId = UUID.randomUUID();
        actAs(orgId, UUID.randomUUID());
        return orgId;
    }

    /** Acts as the given user in the given organization, provisioning its schema. */
    protected void actAs(UUID orgId, UUID userId) {
        OrgContext.clear();
        OrgContext.setOrgId(orgId);
        OrgContext.setUserId(userId);
        tenantRegistry.ensureTenant(orgId);
    }

    /**
     * Runs {@code work} as the given organization and always clears OrgContext
     * afterwards, so a failing assertion cannot leave the next call pointed at
     * the wrong tenant schema.
     */
    protected <T> T asOrg(UUID orgId, Supplier<T> work) {
        actAs(orgId, UUID.randomUUID());
        try {
            return work.get();
        } finally {
            OrgContext.clear();
        }
    }

    protected void asOrg(UUID orgId, Runnable work) {
        asOrg(orgId, () -> {
            work.run();
            return null;
        });
    }

    @AfterEach
    void clearRequestContext() {
        OrgContext.clear();
    }
}
