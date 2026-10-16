package com.devprep.ai.auth;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** All repositories and transactions obtain connections in the authenticated schema. */
public final class WorkspaceDataSource extends DelegatingDataSource {
    private final Supplier<String> schema;
    public WorkspaceDataSource(DataSource target, Supplier<String> schema) { super(target); this.schema = schema; }
    @Override public Connection getConnection() throws SQLException { return prepare(super.getConnection()); }
    @Override public Connection getConnection(String username, String password) throws SQLException { return prepare(super.getConnection(username, password)); }
    private Connection prepare(Connection connection) throws SQLException {
        try {
            String name = schema.get();
            if (!name.matches("public|workspace_[a-f0-9]{32}")) throw new SQLException("Invalid workspace");
            connection.setSchema(name);
            return connection;
        } catch (SQLException | RuntimeException ex) { connection.close(); throw ex; }
    }
}
