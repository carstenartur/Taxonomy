package com.taxonomy.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Removes a rebuildable commit-history projection before its first complete
 * repository-tenant migration.
 *
 * <p>Historic commit-index rows do not contain trustworthy repository/workspace
 * provenance. A partial migration must not retain some rows while deleting
 * others, because persistent Hibernate Search documents for the deleted rows
 * could survive. The complete target contract is detected from JDBC metadata;
 * only while that contract is absent is the entire relational projection
 * deleted. Once complete, subsequent starts are idempotent and preserve rows.</p>
 */
final class CommitIndexProjectionResetMigrator {

    private static final Logger log = LoggerFactory.getLogger(
            CommitIndexProjectionResetMigrator.class);
    private static final String TABLE = "architecture_commit_index";
    private static final Set<String> TARGET_UNIQUE_COLUMNS = Set.of(
            "repository_id", "workspace_scope_key", "branch", "commit_id");

    private final DataSource dataSource;

    CommitIndexProjectionResetMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    void resetIfTargetContractIsIncomplete() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                TableRef table = findTable(connection, TABLE);
                if (table == null || targetContractComplete(connection, table)) {
                    connection.commit();
                    return;
                }
                long rows = countRows(connection, table);
                if (rows > 0) {
                    execute(connection, "DELETE FROM " + qualified(connection, table));
                    log.warn(
                            "Removed {} architecture commit-index projection row(s) before first complete tenant migration; authoritative JGit history remains intact",
                            rows);
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection, error);
                throw new IllegalStateException(
                        "Unable to reset incomplete commit-index projection safely",
                        error);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "Unable to open database for commit-index projection reset",
                    error);
        }
    }

    private boolean targetContractComplete(
            Connection connection,
            TableRef table) throws SQLException {
        for (String column : List.of(
                "repository_id", "workspace_scope_key", "branch", "commit_id")) {
            if (!columnExists(connection, table, column)
                    || columnNullable(connection, table, column)) {
                return false;
            }
        }

        TableRef repositoryTable = findTable(connection, "system_repository");
        if (repositoryTable == null
                || !hasImportedKey(
                        connection,
                        table,
                        "repository_id",
                        repositoryTable,
                        "repository_id")) {
            return false;
        }

        return uniqueIndexes(connection, table).stream()
                .anyMatch(index -> new LinkedHashSet<>(index.columns())
                        .equals(TARGET_UNIQUE_COLUMNS));
    }

    private boolean hasImportedKey(
            Connection connection,
            TableRef sourceTable,
            String sourceColumn,
            TableRef targetTable,
            String targetColumn) throws SQLException {
        try (ResultSet keys = connection.getMetaData().getImportedKeys(
                sourceTable.catalog(), sourceTable.schema(), sourceTable.name())) {
            while (keys.next()) {
                if (sourceColumn.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                        && targetTable.name().equalsIgnoreCase(
                                keys.getString("PKTABLE_NAME"))
                        && targetColumn.equalsIgnoreCase(
                                keys.getString("PKCOLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<IndexDefinition> uniqueIndexes(
            Connection connection,
            TableRef table) throws SQLException {
        Map<String, Map<Short, String>> byName = new LinkedHashMap<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                table.catalog(), table.schema(), table.name(), true, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                short type = indexes.getShort("TYPE");
                if (name == null || column == null
                        || type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                short ordinal = indexes.getShort("ORDINAL_POSITION");
                byName.computeIfAbsent(name, ignored -> new TreeMap<>())
                        .put(ordinal, column.toLowerCase(Locale.ROOT));
            }
        }
        List<IndexDefinition> result = new ArrayList<>();
        byName.forEach((name, columns) -> result.add(
                new IndexDefinition(name, List.copyOf(columns.values()))));
        return result;
    }

    private boolean columnExists(
            Connection connection,
            TableRef table,
            String logicalColumn) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                table.catalog(), table.schema(), table.name(), "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(logicalColumn)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnNullable(
            Connection connection,
            TableRef table,
            String logicalColumn) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                table.catalog(), table.schema(), table.name(), "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(logicalColumn)) {
                    return columns.getInt("NULLABLE")
                            != DatabaseMetaData.columnNoNulls;
                }
            }
        }
        return true;
    }

    private TableRef findTable(
            Connection connection,
            String logicalName) throws SQLException {
        String preferredSchema = safeSchema(connection);
        List<TableRef> matches = new ArrayList<>();
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) {
                    matches.add(new TableRef(
                            tables.getString("TABLE_CAT"),
                            tables.getString("TABLE_SCHEM"),
                            name));
                }
            }
        }
        return matches.stream()
                .sorted((left, right) -> Integer.compare(
                        preferredSchema != null
                                && preferredSchema.equalsIgnoreCase(left.schema()) ? 0 : 1,
                        preferredSchema != null
                                && preferredSchema.equalsIgnoreCase(right.schema()) ? 0 : 1))
                .findFirst()
                .orElse(null);
    }

    private long countRows(Connection connection, TableRef table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + qualified(connection, table))) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String qualified(Connection connection, TableRef table) throws SQLException {
        String tableName = quoted(connection, table.name());
        return table.schema() == null || table.schema().isBlank()
                ? tableName
                : quoted(connection, table.schema()) + "." + tableName;
    }

    private String quoted(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            return null;
        }
    }

    private void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record TableRef(String catalog, String schema, String name) {
    }

    private record IndexDefinition(String name, List<String> columns) {
    }
}
