package com.sclera.applicationplane.procedure.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Activates Hibernate SCHEMA multi-tenancy (resolver + search_path connection
 * provider) and, at startup, bootstraps the registry and rolls tenant
 * migrations across every ACTIVE schema — a failed tenant is logged, not fatal
 * for the fleet.
 */
@Configuration
public class MultiTenancyConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiTenancyConfig.class);

    @Bean
    public HibernatePropertiesCustomizer multiTenancyCustomizer(
            SchemaMultiTenantConnectionProvider connectionProvider,
            TenantIdentifierResolver tenantIdentifierResolver) {
        return properties -> {
            properties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
            properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
        };
    }

    @Bean
    public ApplicationRunner tenantSchemaMigrator(TenantRegistryService registry) {
        return args -> {
            registry.ensureRegistryTable();
            for (String schema : registry.listActiveSchemas()) {
                try {
                    registry.migrateSchema(schema);
                } catch (Exception e) {
                    log.error("Tenant schema migration failed for {} — continuing with the rest", schema, e);
                }
            }
        };
    }
}
