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
        return quoteIdentifier(metadata, findExistingTable(connection, logicalName));
    }

    static String quoteExistingColumn(
            Connection connection, String logicalTableName, String logicalColumnName)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String actualTableName = findExistingTable(connection, logicalTableName);
        try (ResultSet columns = metadata.getColumns(
                null, connection.getSchema(), actualTableName, "%")) {
            while (columns.next()) {
                String candidate = columns.getString("COLUMN_NAME");
                if (logicalColumnName.equalsIgnoreCase(candidate)) {
                    return quoteIdentifier(metadata, candidate);
                }
            }
        }
        throw new SQLException(
                "Column not found: " + logicalTableName + "." + logicalColumnName);
    }

    static void dropTable(DataSource dataSource, String logicalName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("drop table " + quoteExistingTable(connection, logicalName));
        }
    }

    private static String findExistingTable(Connection connection, String logicalName)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(
                null, connection.getSchema(), "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String candidate = tables.getString("TABLE_NAME");
                if (logicalName.equalsIgnoreCase(candidate)) {
                    return candidate;
                }
            }
        }
        throw new SQLException("Table not found: " + logicalName);
    }

    private static String quoteIdentifier(DatabaseMetaData metadata, String identifier)
            throws SQLException {
        String quote = metadata.getIdentifierQuoteString();
        quote = quote == null ? "" : quote.trim();
        if (quote.isEmpty()) {
            return identifier;
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }
}
