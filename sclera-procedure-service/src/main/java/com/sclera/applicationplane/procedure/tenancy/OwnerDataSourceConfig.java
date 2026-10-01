package com.sclera.applicationplane.procedure.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * A second connection to the same database, as the role that owns the tables.
 *
 * Requests run as {@code sclera_app}, which deliberately owns nothing and is
 * not a superuser — that is what makes row-level security apply to it. The
 * price is that it cannot CREATE SCHEMA, cannot run migrations and cannot write
 * the tenant registry, so provisioning needs a connection that can.
 *
 * Kept small on purpose: two or three connections are enough for schema
 * creation and migration, which happen once per tenant per boot. Sizing it like
 * the request pool would waste connections that the request pool wants.
 */
@Configuration
public class OwnerDataSourceConfig {

    @Bean
    public DataSource ownerDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${sclera.datasource.owner.username}") String username,
            @Value("${sclera.datasource.owner.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(3);
        ds.setPoolName("sclera-owner");
        return ds;
    }

    @Bean
    public JdbcTemplate ownerJdbcTemplate(DataSource ownerDataSource) {
        return new JdbcTemplate(ownerDataSource);
    }
}
