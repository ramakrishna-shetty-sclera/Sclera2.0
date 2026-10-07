package com.sclera.applicationplane.helper.support;

import com.sclera.applicationplane.helper.tenancy.TenantRegistryService;
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
 * Base for integration tests that need the real database: Flyway and the
 * schema-per-tenant layer only exist in Postgres.
 *
 * <ul>
 *   <li>One throwaway postgres:16-alpine container for the whole test run,
 *       started once and removed by Testcontainers afterwards. Nothing touches
 *       the docker-compose databases.</li>
 *   <li>OpenFGA is disabled, so checks allow.</li>
 *   <li>No JWT: {@link #actAsNewOrg()} fills OrgContext the way
 *       ScleraJwtConverter does and provisions the org's schema the way
 *       TenantProvisioningFilter does. Each call uses a fresh random org, so
 *       every test starts from a tenant schema holding only the seeds.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("sclera_helper");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("sclera.fga.enabled", () -> "false");
        registry.add("logging.level.org.springframework.security", () -> "WARN");
    }

    @Autowired
    protected TenantRegistryService tenantRegistry;

    /** Acts as a user of a brand-new organization; returns the org id. */
    protected UUID actAsNewOrg() {
        UUID orgId = UUID.randomUUID();
        actAs(orgId);
        return orgId;
    }

    protected void actAs(UUID orgId) {
        OrgContext.clear();
        OrgContext.setOrgId(orgId);
        OrgContext.setUserId(UUID.randomUUID());
        tenantRegistry.ensureTenant(orgId);
    }

    /** Runs {@code work} as the given organization, always clearing OrgContext afterwards. */
    protected <T> T asOrg(UUID orgId, Supplier<T> work) {
        actAs(orgId);
        try {
            return work.get();
        } finally {
            OrgContext.clear();
        }
    }

    @AfterEach
    void clearRequestContext() {
        OrgContext.clear();
    }
}