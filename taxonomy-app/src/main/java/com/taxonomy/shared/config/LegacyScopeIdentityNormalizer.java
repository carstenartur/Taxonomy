package com.taxonomy.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Canonicalizes legacy tenant identifiers before repository constraints are
 * derived by {@link SchemaContractMigrator}.
 *
 * <p>Repository and entity contexts strip surrounding whitespace. Persisting the
 * same representation during upgrade prevents rows from becoming invisible
 * after restart. Duplicate workspace metadata that collapses to one normalized
 * identity is rejected because its source-repository provenance is ambiguous.</p>
 */
final class LegacyScopeIdentityNormalizer {

    private static final Logger log = LoggerFactory.getLogger(
            LegacyScopeIdentityNormalizer.class);

    private static final String[] TENANT_TABLES = {
            "taxonomy_relation",
            "relation_proposal",
            "relation_hypothesis"
    };

    private final DataSource dataSource;

    LegacyScopeIdentityNormalizer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void normalize() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                rejectAmbiguousWorkspaceMetadata(connection);
                for (String table : TENANT_TABLES) {
                    if (tableExists(connection, table)
                            && columnExists(connection, table, "workspace_id")) {
                        execute(connection, "UPDATE " + table
                                + " SET workspace_id = TRIM(workspace_id)"
                                + " WHERE workspace_id IS NOT NULL");
                        execute(connection, "UPDATE " + table
                                + " SET workspace_id = NULL"
                                + " WHERE workspace_id = ''");
                    }
                }
                if (tableExists(connection, "relation_hypothesis")
                        && columnExists(
                                connection,
                                "relation_hypothesis",
                                "analysis_session_id")) {
                    execute(connection, "UPDATE relation_hypothesis"
                            + " SET analysis_session_id = TRIM(analysis_session_id)"
                            + " WHERE analysis_session_id IS NOT NULL");
                    execute(connection, "UPDATE relation_hypothesis"
                            + " SET analysis_session_id = NULL"
                            + " WHERE analysis_session_id = ''");
                }
                connection.commit();
                log.debug("Normalized legacy workspace and analysis-session identities");
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection, error);
                throw new IllegalStateException(
                        "Unable to normalize legacy tenant identities safely", error);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "Unable to open database for tenant identity normalization", error);
        }
    }

    private void rejectAmbiguousWorkspaceMetadata(Connection connection) throws SQLException {
        if (!tableExists(connection, "user_workspace")
                || !columnExists(connection, "user_workspace", "workspace_id")) {
            return;
        }
        String sql = "SELECT COUNT(*) FROM (SELECT TRIM(workspace_id) AS workspace_key"
                + " FROM user_workspace"
                + " WHERE workspace_id IS NOT NULL AND TRIM(workspace_id) <> ''"
                + " GROUP BY TRIM(workspace_id)"
                + " HAVING COUNT(*) > 1) normalized_workspaces";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            long duplicates = result.getLong(1);
            if (duplicates > 0) {
                throw new IllegalStateException(
                        "Cannot normalize workspace provenance: found " + duplicates
                                + " ambiguous normalized workspace identity group(s)");
            }
        }
    }

    private static boolean tableExists(Connection connection, String logicalName)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean columnExists(
            Connection connection,
            String logicalTable,
            String logicalColumn) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(
                connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                String table = columns.getString("TABLE_NAME");
                String column = columns.getString("COLUMN_NAME");
                if (table != null && column != null
                        && table.toLowerCase(Locale.ROOT)
                                .equals(logicalTable.toLowerCase(Locale.ROOT))
                        && column.toLowerCase(Locale.ROOT)
                                .equals(logicalColumn.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
