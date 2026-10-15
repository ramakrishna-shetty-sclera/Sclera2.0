package com.sclera.applicationplane.procedure.tenancy;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Pins a pooled connection to the request's scope on checkout and — CRITICAL
 * pool hygiene — resets it on release, so a pooled connection can never leak
 * one request's scope into the next.
 *
 * Two settings ride on the connection, one per boundary: search_path selects
 * the organization's schema, and sclera.property_ids tells the row-level
 * security policies which properties are visible inside it.
 */
@Component
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    public SchemaMultiTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        setPropertyScope(connection, "");
        return connection;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        setSearchPath(connection, TenantSchemas.PUBLIC);
        setPropertyScope(connection, "");
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = dataSource.getConnection();
        setSearchPath(connection, TenantSchemas.requireValid(tenantIdentifier));
        setPropertyScope(connection, PropertyContext.asSetting());
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        setSearchPath(connection, TenantSchemas.PUBLIC);
        setPropertyScope(connection, "");
        connection.close();
    }

    private void setSearchPath(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
        }
    }

    /**
     * Tells the connection which properties this request may see; the row-level
     * security policies on property-scoped tables read it.
     *
     * Cleared on release for the same reason search_path is: a pooled
     * connection must not carry one request's property scope into the next.
     * Clearing to empty rather than leaving it unset means the next checkout
     * cannot inherit a value even if something fails before it sets its own.
     *
     * Written through a bound parameter rather than concatenated into SET.
     * The schema name is matched against a strict pattern before it is
     * interpolated; this value is a list of ids that ultimately came from a
     * request header, so it goes through the driver instead. set_config is the
     * function form of SET and, unlike SET, accepts a parameter. The third
     * argument false makes it session-scoped, matching search_path above —
     * transaction-scoped would be cleared mid-use by Hibernate's own commits.
     */
    private void setPropertyScope(Connection connection, String propertyIds) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT set_config('sclera.property_ids', ?, false)")) {
            statement.setString(1, propertyIds);
            statement.execute();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return MultiTenantConnectionProvider.class.isAssignableFrom(unwrapType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return (T) this;
        }
        throw new IllegalArgumentException("Cannot unwrap to " + unwrapType);
    }
}
