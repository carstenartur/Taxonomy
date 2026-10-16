package com.taxonomy.composition.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Types;
import java.util.Map;
import org.hsqldb.jdbc.JDBCDataSource;

/** Authored schema regression, executed by the adjacent JUnit test and offline probe alike. */
final class FractionalSchemaChecks {
    private static final Map<String, String> COLUMNS = Map.of(
            "req_element_mapping", "direct_score",
            "requirement_coverage", "score",
            "req_solution_link", "coverage_percent");

    static void upgradesHistoricalIntegerColumnsAndSurvivesRestart(Path directory) throws Exception {
        var source = new JDBCDataSource();
        source.setUrl("jdbc:hsqldb:file:" + directory.resolve("scores").toAbsolutePath());
        source.setUser("SA"); source.setPassword("");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            for (var entry : COLUMNS.entrySet()) {
                statement.execute("create table " + entry.getKey() + " (id integer primary key, "
                        + entry.getValue() + " integer" + (entry.getKey().equals("requirement_coverage") ? "" : " not null") + ")");
                statement.execute("insert into " + entry.getKey() + " values (1, 85)");
            }
            statement.execute("insert into requirement_coverage values (2, null)");
        }
        HsqlLegacyUpgradeMigrator.migrate(source);
        // Idempotent pre-Hibernate adoption must not depend on unrelated workspace tables.
        HsqlLegacyUpgradeMigrator.migrate(source);
        try (var connection = source.getConnection()) {
            for (var entry : COLUMNS.entrySet()) {
                assertDoubleColumn(connection, entry.getKey(), entry.getValue());
                try (var statement = connection.prepareStatement("insert into " + entry.getKey() + " values (?, ?)")) {
                    statement.setInt(1, 3); statement.setDouble(2, .125); statement.executeUpdate();
                }
            }
            connection.createStatement().execute("shutdown");
        }
        try (var connection = source.getConnection()) {
            for (var entry : COLUMNS.entrySet()) {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("select " + entry.getValue() + " from " + entry.getKey() + " where id in (1,3) order by id")) {
                    if (!rows.next() || rows.getDouble(1) != 85 || !rows.next() || rows.getDouble(1) != .125 || rows.next())
                        throw new AssertionError("Numeric persistence changed the score in " + entry.getKey());
                }
                try (var rows = connection.getMetaData().getColumns(null, "PUBLIC", entry.getKey().toUpperCase(java.util.Locale.ROOT), entry.getValue().toUpperCase(java.util.Locale.ROOT))) {
                    if (!rows.next()) throw new AssertionError("Column disappeared");
                    boolean nullable = rows.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls;
                    if (nullable != entry.getKey().equals("requirement_coverage")) throw new AssertionError("Nullability changed");
                }
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select score from requirement_coverage where id=2")) {
                if (!rows.next() || rows.getObject(1) != null) throw new AssertionError("Unknown legacy score became zero");
            }
            connection.createStatement().execute("shutdown");
        }
    }

    private static void assertDoubleColumn(Connection connection, String table, String column) throws Exception {
        try (var rows = connection.getMetaData().getColumns(null, "PUBLIC", table.toUpperCase(java.util.Locale.ROOT), column.toUpperCase(java.util.Locale.ROOT))) {
            if (!rows.next() || rows.getInt("DATA_TYPE") != Types.DOUBLE)
                throw new AssertionError(table + "." + column + " must accept fractional percentages before Hibernate starts");
        }
    }

    public static void main(String[] arguments) throws Exception {
        upgradesHistoricalIntegerColumnsAndSurvivesRestart(java.nio.file.Files.createTempDirectory("taxonomy-fractions-"));
        System.out.println("FRACTIONAL_SCHEMA_RESTART_OK");
    }
}
