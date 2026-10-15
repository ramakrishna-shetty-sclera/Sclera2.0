package com.sclera.applicationplane.procedure.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Two roles against one database.
 *
 * Requests run as the role in {@code spring.datasource}, which owns nothing and
 * is not a superuser — that is the only reason Postgres applies row-level
 * security to it, and therefore the only reason property isolation exists.
 * Schema creation, migrations and the tenant registry need privileges that role
 * deliberately lacks, and use the owner.
 *
 * <p><strong>Both are declared here, and the request one cannot be left to
 * Spring Boot.</strong> {@code DataSourceAutoConfiguration} is conditional on
 * there being no {@code DataSource} bean at all, so declaring the owner alone
 * silently suppresses Boot's and leaves the owner as the only connection in the
 * application. Everything still works — migrations, tests, the lot — while the
 * whole point has quietly gone: Hibernate connects as the owner, row-level
 * security is bypassed, and isolation tests pass with no isolation present.
 * {@code PropertyIsolationIT} asserts {@code current_user} for exactly this
 * reason.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** Serves requests. Not the owner, so RLS applies. */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties appDataSourceProperties) {
        return appDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * Owns the tables; used by Flyway and tenant provisioning only.
     *
     * Kept small on purpose: a few connections cover schema creation and
     * migration, which happen once per tenant per boot. Sizing it like the
     * request pool would waste connections the request pool wants.
     */
    @Bean
    public DataSource ownerDataSource(
            DataSourceProperties appDataSourceProperties,
            @Value("${sclera.datasource.owner.username}") String username,
            @Value("${sclera.datasource.owner.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(appDataSourceProperties.determineUrl());
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(3);
        ds.setPoolName("sclera-owner");
        return ds;
    }

    /**
     * The qualifier is not decoration. With two {@code DataSource} beans,
     * {@code @Primary} beats matching on the parameter name — so without it
     * this builds on the <em>request</em> datasource while being called
     * ownerJdbcTemplate, and provisioning fails with "permission denied for
     * schema public" from a class that looks like it is holding the owner.
     */
    @Bean
    public JdbcTemplate ownerJdbcTemplate(@Qualifier("ownerDataSource") DataSource ownerDataSource) {
        return new JdbcTemplate(ownerDataSource);
    }
}
