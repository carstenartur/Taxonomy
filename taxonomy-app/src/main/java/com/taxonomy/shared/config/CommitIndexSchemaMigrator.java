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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Portable migration for repository-scoped architecture commit projections. */
final class CommitIndexSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(
            CommitIndexSchemaMigrator.class);

    private static final String TABLE = "architecture_commit_index";
    private static final String CENTRAL_SCOPE = "__shared__";
    private static final Set<String> LEGACY_UNIQUE_COLUMNS = Set.of("commit_id");
    private static final Set<String> TARGET_UNIQUE_COLUMNS = Set.of(
            "repository_id",
            "workspace_scope_key",
            "branch",
            "commit_id");

    private final DataSource dataSource;

    CommitIndexSchemaMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                migrate(connection);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection, error);
                throw new IllegalStateException(
                        "Unable to migrate architecture commit-index tenancy safely",
                        error);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "Unable to open database for commit-index migration", error);
        }
    }

    private void migrate(Connection connection) throws SQLException {
        TableRef table = findTable(connection, TABLE);
        if (table == null) {
            log.debug("Commit-index schema migration skipped: table does not exist");
            return;
        }
        requireColumn(connection, table, "commit_id");
        requireColumn(connection, table, "branch");

        ensureColumn(connection, table, "repository_id", "VARCHAR(255)");
        ensureColumn(connection, table, "workspace_id", "VARCHAR(255)");
        ensureColumn(connection, table, "workspace_scope_key", "VARCHAR(255)");

        normalizeOptionalColumn(connection, table, "repository_id");
        normalizeOptionalColumn(connection, table, "workspace_id");
        normalizeOptionalColumn(connection, table, "workspace_scope_key");
        normalizeOptionalColumn(connection, table, "branch");
        normalizeOptionalColumn(connection, table, "commit_id");

        // workspace_scope_key is derivable from workspace_id even in a partially
        // completed upgrade. Derive it before deciding which projection rows are
        // irrecoverably ambiguous.
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET workspace_scope_key = " + workspaceScopeExpression());

        long ambiguousRows = singleLong(connection, "SELECT COUNT(*) FROM "
                + qualified(connection, table)
                + " WHERE repository_id IS NULL"
                + " OR branch IS NULL"
                + " OR commit_id IS NULL");
        if (ambiguousRows > 0) {
            execute(connection, "DELETE FROM " + qualified(connection, table)
                    + " WHERE repository_id IS NULL"
                    + " OR branch IS NULL"
                    + " OR commit_id IS NULL");
            log.warn(
                    "Removed {} ambiguous legacy commit-index row(s); authoritative JGit history remains available for rebuild",
                    ambiguousRows);
        }

        long duplicateGroups = duplicateGroupCount(connection, table);
        if (duplicateGroups > 0) {
            long rows = singleLong(connection,
                    "SELECT COUNT(*) FROM " + qualified(connection, table));
            execute(connection, "DELETE FROM " + qualified(connection, table));
            log.warn(
                    "Removed {} commit-index row(s) because {} tenant/branch duplicate group(s) cannot be reconciled safely; JGit remains authoritative",
                    rows,
                    duplicateGroups);
        }

        dropLegacyCommitUniqueness(connection, table);
        ensureRepositoryForeignKey(connection, table);
        ensureTargetUniqueness(connection, table);
        setNotNull(connection, table, "repository_id");
        setNotNull(connection, table, "workspace_scope_key");
        setNotNull(connection, table, "branch");
        setNotNull(connection, table, "commit_id");

        ensureIndex(connection, table,
                "idx_commit_index_repository", List.of("repository_id"));
        ensureIndex(connection, table,
                "idx_commit_index_repository_workspace",
                List.of("repository_id", "workspace_id"));
        ensureIndex(connection, table,
                "idx_commit_index_scope_branch",
                List.of("repository_id", "workspace_scope_key", "branch"));

        log.info("Verified repository/workspace/branch commit-index schema contract");
    }

    private void normalizeOptionalColumn(
            Connection connection,
            TableRef table,
            String column) throws SQLException {
        String identifier = quoted(connection, column);
        String product = connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT);
        String normalizedExpression = product.contains("oracle")
                ? "TRIM(" + identifier + ")"
                : "NULLIF(TRIM(" + identifier + "), '')";
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET " + identifier + " = " + normalizedExpression
                + " WHERE " + identifier + " IS NOT NULL");
    }

    private long duplicateGroupCount(
            Connection connection,
            TableRef table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM (SELECT repository_id, "
                + workspaceScopeExpression() + " AS workspace_key, branch, commit_id, "
                + "COUNT(*) AS duplicate_count FROM " + qualified(connection, table)
                + " GROUP BY repository_id, " + workspaceScopeExpression()
                + ", branch, commit_id HAVING COUNT(*) > 1) commit_duplicates";
        return singleLong(connection, sql);
    }

    private void dropLegacyCommitUniqueness(
            Connection connection,
            TableRef table) throws SQLException {
        for (IndexDefinition index : uniqueIndexes(connection, table)) {
            Set<String> columns = new LinkedHashSet<>(index.columns());
            if (columns.equals(LEGACY_UNIQUE_COLUMNS)) {
                dropConstraintOrIndex(connection, table, index.name());
                log.info("Dropped legacy global commit-index uniqueness {}", index.name());
            }
        }
    }

    private void ensureTargetUniqueness(
            Connection connection,
            TableRef table) throws SQLException {
        boolean exists = uniqueIndexes(connection, table).stream()
                .anyMatch(index -> new LinkedHashSet<>(index.columns())
                        .equals(TARGET_UNIQUE_COLUMNS));
        if (exists) {
            return;
        }
        execute(connection, "ALTER TABLE " + qualified(connection, table)
                + " ADD CONSTRAINT "
                + quoted(connection,
                        "uq_commit_index_repository_workspace_branch_commit")
                + " UNIQUE (repository_id, workspace_scope_key, branch, commit_id)");
    }

    private void ensureRepositoryForeignKey(
            Connection connection,
            TableRef table) throws SQLException {
        TableRef repositoryTable = findTable(connection, "system_repository");
        if (repositoryTable == null) {
            throw new IllegalStateException(
                    "Cannot scope architecture commit index: system_repository is missing");
        }
        if (hasImportedKey(
                connection,
                table,
                "repository_id",
                repositoryTable,
                "repository_id")) {
            return;
        }
        execute(connection, "ALTER TABLE " + qualified(connection, table)
                + " ADD CONSTRAINT "
                + quoted(connection, "fk_commit_index_repository")
                + " FOREIGN KEY (repository_id) REFERENCES "
                + qualified(connection, repositoryTable) + " (repository_id)");
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

    private void setNotNull(
            Connection connection,
            TableRef table,
            String column) throws SQLException {
        if (!columnNullable(connection, table, column)) {
            return;
        }
        String product = connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT);
        if (product.contains("oracle")) {
            execute(connection, "ALTER TABLE " + qualified(connection, table)
                    + " MODIFY (" + quoted(connection, column) + " NOT NULL)");
            return;
        }
        if (product.contains("microsoft")) {
            ColumnDefinition definition = columnDefinition(connection, table, column);
            execute(connection, "ALTER TABLE " + qualified(connection, table)
                    + " ALTER COLUMN " + quoted(connection, column) + " "
                    + definition.sqlType() + " NOT NULL");
            return;
        }
        execute(connection, "ALTER TABLE " + qualified(connection, table)
                + " ALTER COLUMN " + quoted(connection, column) + " SET NOT NULL");
    }

    private ColumnDefinition columnDefinition(
            Connection connection,
            TableRef table,
            String logicalColumn) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                table.catalog(), table.schema(), table.name(), "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(logicalColumn)) {
                    String type = columns.getString("TYPE_NAME");
                    int size = columns.getInt("COLUMN_SIZE");
                    String lower = type.toLowerCase(Locale.ROOT);
                    if (size > 0 && (lower.contains("char") || lower.contains("binary"))) {
                        return new ColumnDefinition(type + "(" + size + ")");
                    }
                    return new ColumnDefinition(type);
                }
            }
        }
        throw new IllegalStateException("Column not found: " + logicalColumn);
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
        throw new IllegalStateException("Column not found: " + logicalColumn);
    }

    private void ensureIndex(
            Connection connection,
            TableRef table,
            String indexName,
            List<String> columns) throws SQLException {
        if (indexNames(connection, table).stream()
                .anyMatch(name -> name.equalsIgnoreCase(indexName))) {
            return;
        }
        execute(connection, "CREATE INDEX " + quoted(connection, indexName)
                + " ON " + qualified(connection, table)
                + " (" + String.join(", ", columns) + ")");
    }

    private Set<String> indexNames(
            Connection connection,
            TableRef table) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                table.catalog(), table.schema(), table.name(), false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    private void ensureColumn(
            Connection connection,
            TableRef table,
            String column,
            String definition) throws SQLException {
        if (columnExists(connection, table, column)) {
            return;
        }
        execute(connection, "ALTER TABLE " + qualified(connection, table)
                + " ADD " + quoted(connection, column) + " " + definition);
    }

    private void requireColumn(
            Connection connection,
            TableRef table,
            String column) throws SQLException {
        if (!columnExists(connection, table, column)) {
            throw new IllegalStateException(
                    "Architecture commit index is missing required column " + column);
        }
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
        result.sort(Comparator.comparing(IndexDefinition::name));
        return result;
    }

    private void dropConstraintOrIndex(
            Connection connection,
            TableRef table,
            String name) throws SQLException {
        SQLException constraintFailure;
        try {
            execute(connection, "ALTER TABLE " + qualified(connection, table)
                    + " DROP CONSTRAINT " + quoted(connection, name));
            return;
        } catch (SQLException error) {
            constraintFailure = error;
        }

        String product = connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT);
        String sql = product.contains("microsoft")
                ? "DROP INDEX " + quoted(connection, name)
                        + " ON " + qualified(connection, table)
                : "DROP INDEX " + qualifiedIndex(connection, table, name);
        try {
            execute(connection, sql);
        } catch (SQLException indexFailure) {
            indexFailure.addSuppressed(constraintFailure);
            throw indexFailure;
        }
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
                .sorted(Comparator.comparing(table -> preferredSchema != null
                        && preferredSchema.equalsIgnoreCase(table.schema()) ? 0 : 1))
                .findFirst()
                .orElse(null);
    }

    private long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        log.debug("Commit-index schema migration SQL: {}", sql);
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

    private String qualifiedIndex(
            Connection connection,
            TableRef table,
            String indexName) throws SQLException {
        String quotedIndex = quoted(connection, indexName);
        return table.schema() == null || table.schema().isBlank()
                ? quotedIndex
                : quoted(connection, table.schema()) + "." + quotedIndex;
    }

    private String quoted(Connection connection, String identifier) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String canonical = identifier;
        if (metadata.storesUpperCaseIdentifiers()) {
            canonical = identifier.toUpperCase(Locale.ROOT);
        } else if (metadata.storesLowerCaseIdentifiers()) {
            canonical = identifier.toLowerCase(Locale.ROOT);
        }
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return canonical;
        }
        return quote + canonical.replace(quote, quote + quote) + quote;
    }

    private String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            return null;
        }
    }

    private static String workspaceScopeExpression() {
        return "COALESCE(NULLIF(TRIM(workspace_id), ''), '"
                + CENTRAL_SCOPE + "')";
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

    private record ColumnDefinition(String sqlType) {
    }
}
