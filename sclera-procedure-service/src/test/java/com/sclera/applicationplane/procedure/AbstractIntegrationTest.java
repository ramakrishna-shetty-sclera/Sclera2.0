package com.sclera.applicationplane.procedure;

import com.sclera.applicationplane.procedure.tenancy.TenantRegistryService;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base for integration tests. Starts one Postgres container for the whole test
 * run and provisions tenant schemas through the real
 * {@link TenantRegistryService}, so tests exercise the same Flyway path
 * production does — including the seed rows the tenant migration writes.
 *
 * <p>H2 is not an option: {@code CREATE SCHEMA}, {@code search_path} switching
 * and JSONB are the entire schema-per-tenant mechanism.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    /**
     * Started once per JVM rather than per class. Testcontainers reuses a static
     * container across test classes, which matters because provisioning a tenant
     * schema runs Flyway and is not cheap.
     */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("sclera_procedure")
            .withUsername("sclera")
            .withPassword("sclera");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected TenantRegistryService tenantRegistry;

    /**
     * Runs {@code work} as if a request from {@code orgId} had arrived.
     *
     * <p>Setting {@link OrgContext} is enough for both halves of tenancy: the
     * service reads it for its org filter, and {@code TenantContext} falls back
     * to it when resolving which schema to point {@code search_path} at. In
     * production {@code TenantProvisioningFilter} does this from the JWT, but it
     * only runs inside the security chain, so tests provision explicitly.
     */
    protected <T> T asOrg(UUID orgId, Supplier<T> work) {
        tenantRegistry.ensureTenant(orgId);
        OrgContext.setOrgId(orgId);
        OrgContext.setUserId(UUID.randomUUID());
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

    /** A leaked OrgContext would silently point the next test at the wrong schema. */
    @AfterEach
    void clearOrgContext() {
        OrgContext.clear();
    }
}
