package com.sclera.applicationplane.procedure.support;

import com.sclera.applicationplane.procedure.event.TemplateEventPublisher;
import com.sclera.applicationplane.procedure.tenancy.TenantRegistryService;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base for integration tests that need the real database: Flyway, the
 * schema-per-tenant layer and the constraints only exist in Postgres.
 *
 * <ul>
 *   <li>One throwaway postgres:16-alpine container for the whole test run,
 *       started once and removed by Testcontainers afterwards. Nothing touches
 *       the docker-compose databases.</li>
 *   <li>OpenFGA is disabled, so checks allow and no tuples are written.</li>
 *   <li>Kafka events go to a mock publisher, so tests can count them.</li>
 *   <li>No JWT: {@link #actAsNewOrg()} fills OrgContext the way
 *       ScleraJwtConverter does, and provisions the org's schema the way
 *       TenantProvisioningFilter does. Each call uses a fresh random org, so
 *       every test starts from an empty tenant schema.</li>
 * </ul>
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("sclera_procedure");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("sclera.fga.enabled", () -> "false");
        // No broker needed: nothing is sent, and topic creation is skipped.
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("logging.level.com.sclera", () -> "INFO");
        registry.add("logging.level.org.springframework.security", () -> "WARN");
    }

    @MockBean
    protected TemplateEventPublisher events;

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

    @AfterEach
    void clearOrgContext() {
        OrgContext.clear();
    }
}
