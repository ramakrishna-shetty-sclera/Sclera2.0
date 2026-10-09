package com.sclera.applicationplane.procedure.support;

import com.sclera.applicationplane.procedure.event.GlobalTemplateEventPublisher;
import com.sclera.applicationplane.procedure.event.TemplateEventPublisher;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.applicationplane.procedure.tenancy.TenantRegistryService;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

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
 *
 * <p>The {@code test} profile is active rather than the default {@code dev},
 * which is what keeps {@code spring.data.redis.host} unset — sclera-common's
 * RedisCacheConfig is conditional on that property and fails the context start
 * when Redis is unreachable, so tests would otherwise need a Redis container
 * for a bean they never touch.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("sclera_procedure");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // Tests run as the same non-owner role production uses, created by
        // V5__app_role.sql. Running them as the container's superuser instead
        // would be the easy thing and would quietly defeat the point: Postgres
        // ignores row-level security for owners and superusers, so every
        // isolation test would pass without any isolation existing.
        registry.add("spring.datasource.username", () -> "sclera_app");
        registry.add("spring.datasource.password", () -> "sclera_app");
        // Migrations and tenant provisioning need privileges the request role
        // deliberately lacks.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("sclera.datasource.owner.username", POSTGRES::getUsername);
        registry.add("sclera.datasource.owner.password", POSTGRES::getPassword);
        registry.add("sclera.fga.enabled", () -> "false");
        // No broker needed: nothing is sent, and topic creation is skipped.
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("logging.level.com.sclera", () -> "INFO");
        registry.add("logging.level.org.springframework.security", () -> "WARN");
    }

    @MockBean
    protected TemplateEventPublisher events;

    @MockBean
    protected GlobalTemplateEventPublisher globalEvents;

    @Autowired
    protected TenantRegistryService tenantRegistry;

    /** Acts as a user of a brand-new organization; returns the org id. */
    protected UUID actAsNewOrg() {
        UUID orgId = UUID.randomUUID();
        actAs(orgId, UUID.randomUUID());
        return orgId;
    }

    /**
     * Acts as the given user in the given organization, provisioning its schema.
     *
     * Starts at organization level: no property selected, so only org-wide rows
     * are visible. {@code asProperty} narrows it.
     */
    protected void actAs(UUID orgId, UUID userId) {
        OrgContext.clear();
        PropertyContext.clear();
        OrgContext.setOrgId(orgId);
        OrgContext.setUserId(userId);
        tenantRegistry.ensureTenant(orgId);
    }

    /**
     * Scoped form of {@link #actAs}: runs {@code work} as the given organization
     * and always clears OrgContext afterwards, so a failing assertion cannot
     * leave the next call pointed at the wrong tenant schema.
     */
    protected <T> T asOrg(UUID orgId, Supplier<T> work) {
        actAs(orgId, UUID.randomUUID());
        try {
            return work.get();
        } finally {
            OrgContext.clear();
            PropertyContext.clear();
        }
    }

    protected void asOrg(UUID orgId, Runnable work) {
        asOrg(orgId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} as the given organization with one property selected —
     * what PropertyScopeFilter does when a request carries X-Sclera-Property.
     *
     * The grant check is the filter's job and is skipped here; these tests are
     * about what the database allows once a scope is set, which is the layer
     * that has to hold even when the one above it is wrong.
     */
    protected <T> T asProperty(UUID orgId, UUID propertyId, Supplier<T> work) {
        actAs(orgId, UUID.randomUUID());
        PropertyContext.set(List.of(propertyId));
        try {
            return work.get();
        } finally {
            OrgContext.clear();
            PropertyContext.clear();
        }
    }

    protected void asProperty(UUID orgId, UUID propertyId, Runnable work) {
        asProperty(orgId, propertyId, () -> {
            work.run();
            return null;
        });
    }

    @AfterEach
    void clearRequestContext() {
        OrgContext.clear();
        PropertyContext.clear();
    }
}
