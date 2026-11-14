package com.taxonomy.workspace.backup;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Allows a read of an older staged source to omit only data that its installed schema predates. */
final class StagedWorkspaceSchema {
    private static final Set<String> PUBLICATION = Set.of("workspace_publication_operation", "workspace_publication_decision");
    private static final Set<String> HISTORY_INDEX = Set.of("history_index_operation", "history_index_decision", "history_index_purge_target");
    private static final String HISTORY = "restore_business_schema_history";

    private StagedWorkspaceSchema() { }

    static boolean predatesPublications(DataSource database) throws IOException {
        return missingTablesBefore(database, PUBLICATION, "32.1");
    }

    static boolean predatesHistoryIndex(DataSource database) throws IOException {
        return missingTablesBefore(database, HISTORY_INDEX, "32.3");
    }

    private static boolean missingTablesBefore(DataSource database, Set<String> required, String introduced) throws IOException {
        try (var connection = database.getConnection()) {
            if (!isStagedDatabaseType(connection)) return false;
            var tables = tableNames(connection);
            if (tables.containsAll(required)) return false;
            if (required.stream().anyMatch(tables::contains)) throw new IOException("Workspace journal schema is incomplete");
            return tables.contains(HISTORY) && historyPredates(connection, introduced);
        } catch (SQLException failed) {
            throw new IOException("Workspace source schema could not be verified", failed);
        }
    }

    static boolean missingColumnBefore(DataSource database, String table, String column, String introduced) throws IOException {
        try (var connection = database.getConnection()) {
            if (!isStagedDatabaseType(connection)) return false;
            var tables = tableNames(connection);
            if (!tables.contains(HISTORY) || !tables.contains(table)) return false;
            boolean foundTableColumns = false;
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(),
                    table.toUpperCase(Locale.ROOT), null)) {
                while (columns.next()) {
                    if (!table.equalsIgnoreCase(columns.getString("TABLE_NAME"))) continue;
                    foundTableColumns = true;
                    if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return false;
                }
            }
            return foundTableColumns && historyPredates(connection, introduced);
        } catch (SQLException failed) {
            throw new IOException("Workspace source column could not be verified", failed);
        }
    }

    private static boolean isStagedDatabaseType(Connection connection) throws SQLException {
        return "HSQL Database Engine".equals(connection.getMetaData().getDatabaseProductName())
                && "PUBLIC".equals(connection.getSchema());
    }

    private static Set<String> tableNames(Connection connection) throws SQLException {
        var names = new HashSet<String>();
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static boolean historyPredates(Connection connection, String introduced) throws SQLException {
        var bound = new SingleConnectionDataSource(connection, true);
        var settings = Flyway.configure().dataSource(bound).locations("classpath:db/restore/hsqldb").table(HISTORY);
        var current = settings.load().info().current();
        if (current == null || current.getVersion() == null) return false;
        var installed = current.getVersion();
        if (installed.compareTo(MigrationVersion.fromVersion("25")) < 0
                || installed.compareTo(MigrationVersion.fromVersion(introduced)) >= 0) return false;
        var source = settings.target(installed).load();
        if (!source.validateWithResult().validationSuccessful) return false;
        for (var migration : source.info().all()) {
            if (migration.getVersion() == null) return false;
            if (migration.getVersion().compareTo(installed) <= 0 && migration.getState() != MigrationState.SUCCESS) return false;
        }
        return true;
    }
}
