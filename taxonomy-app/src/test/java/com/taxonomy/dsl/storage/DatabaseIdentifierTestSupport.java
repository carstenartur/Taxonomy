package com.taxonomy.dsl.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

/** Test helpers for custom Flyway table names that may be stored as quoted identifiers. */
final class DatabaseIdentifierTestSupport {

    private DatabaseIdentifierTestSupport() {
    }

    static String quoteExistingTable(Connection connection, String logicalName)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String actualName = null;
        try (ResultSet tables = metadata.getTables(
                null, connection.getSchema(), "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String candidate = tables.getString("TABLE_NAME");
                if (logicalName.equalsIgnoreCase(candidate)) {
                    actualName = candidate;
                    break;
                }
            }
        }
        if (actualName == null) {
            throw new SQLException("Table not found: " + logicalName);
        }

        String quote = metadata.getIdentifierQuoteString();
        quote = quote == null ? "" : quote.trim();
        if (quote.isEmpty()) {
            return actualName;
        }
        return quote + actualName.replace(quote, quote + quote) + quote;
    }

    static void dropTable(DataSource dataSource, String logicalName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("drop table " + quoteExistingTable(connection, logicalName));
        }
    }
}
