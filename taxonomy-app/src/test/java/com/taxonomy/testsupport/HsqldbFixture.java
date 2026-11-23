package com.taxonomy.testsupport;

import org.hsqldb.DatabaseManager;
import org.hsqldb.jdbc.JDBCDataSource;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns the in-memory databases of one test, including any temporary migration source. */
public final class HsqldbFixture implements AutoCloseable {
    private final Map<String, JDBCDataSource> databases = new LinkedHashMap<>();

    public JDBCDataSource create(String prefix, String options) {
        String uri = "mem:" + prefix + UUID.randomUUID();
        var dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:" + uri + options);
        dataSource.setUser("sa");
        databases.put(uri, dataSource);
        return dataSource;
    }

    @Override
    public void close() throws SQLException {
        var openDatabases = DatabaseManager.getDatabaseURIs();
        SQLException failure = null;
        for (var database : databases.entrySet()) {
            // Closing an unused or already shut-down datasource would create a fresh database.
            if (!openDatabases.contains(database.getKey())) continue;
            try (var connection = database.getValue().getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (SQLException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        databases.clear();
        if (failure != null) throw failure;
    }
}
