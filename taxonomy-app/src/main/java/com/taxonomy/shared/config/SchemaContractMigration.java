package com.taxonomy.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
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

/**
 * Idempotent schema contract migration for deployments that predate workspace,
 * repository isolation and mandatory password replacement.
 *
 * <p>Hibernate {@code ddl-auto=update} cannot remove obsolete unique
 * constraints reliably. This runner inspects JDBC metadata, drops only
 * constraints with proven legacy column sets, backfills non-null scope keys,
 * preserves workspace-to-source-repository provenance, and creates named
 * constraints that protect central and personal rows. It also prepares the
 * local-user password-change column.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "taxonomy.schema-migration.enabled",
        havingValue = "true", matchIfMissing = true)
public class SchemaContractMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaContractMigration.class);
    private static final String SHARED_SCOPE = "__shared__";

    private static final Set<String> PROPOSAL_LEGACY_COLUMNS = Set.of(
            "source_node_id", "target_node_id", "relation_type");
    private static final Set<String> PROPOSAL_WORKSPACE_NULLABLE_COLUMNS = Set.of(
            "source_node_id", "target_node_id", "relation_type", "workspace_id");
    private static final Set<String> RELATION_WORKSPACE_NULLABLE_COLUMNS = Set.of(
            "source_node_id", "target_node_id", "relation_type", "workspace_id");
    private static final Set<String> WORKSPACE_SCOPE_KEY_COLUMNS = Set.of(
            "source_node_id", "target_node_id", "relation_type", "workspace_scope_key");
    private static final Set<String> REPOSITORY_SCOPE_KEY_COLUMNS = Set.of(
            "repository_id", "source_node_id", "target_node_id",
            "relation_type", "workspace_scope_key");

    private final DataSource dataSource;

    public SchemaContractMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** Runs the complete idempotent contract migration. */
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                migrateScopedUniqueness(
                        connection,
                        "relation_proposal",
                        "uk_relation_proposal_scope",
                        List.of(PROPOSAL_LEGACY_COLUMNS, PROPOSAL_WORKSPACE_NULLABLE_COLUMNS),
                        WORKSPACE_SCOPE_KEY_COLUMNS,
                        false);
                migrateScopedUniqueness(
                        connection,
                        "taxonomy_relation",
                        "uk_taxonomy_relation_scope",
                        List.of(
                                RELATION_WORKSPACE_NULLABLE_COLUMNS,
                                WORKSPACE_SCOPE_KEY_COLUMNS),
                        REPOSITORY_SCOPE_KEY_COLUMNS,
                        true);
                ensurePasswordChangeColumn(connection);
                connection.commit();
                log.info("Verified repository, workspace and account schema contracts");
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection, error);
                throw new IllegalStateException(
                        "Unable to migrate Taxonomy schema contracts safely", error);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to open database for schema migration", error);
        }
    }

    private void migrateScopedUniqueness(
            Connection connection,
            String logicalTableName,
            String targetConstraint,
            List<Set<String>> legacyColumnSets,
            Set<String> targetColumns,
            boolean repositoryScoped) throws SQLException {
        TableRef table = findTable(connection, logicalTableName);
        if (table == null) {
            log.debug("Schema migration skipped: table {} does not exist", logicalTableName);
            return;
        }

        ensureColumn(connection, table, "workspace_scope_key", "VARCHAR(255)");
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET workspace_id = TRIM(workspace_id)"
                + " WHERE workspace_id IS NOT NULL");
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET workspace_id = NULL"
                + " WHERE workspace_id = ''");
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET workspace_scope_key = " + scopeExpression());

        if (repositoryScoped) {
            ensureColumn(connection, table, "repository_id", "VARCHAR(255)");
            bindLegacyRowsToRepositories(connection, table, logicalTableName);
            ensureRepositoryForeignKey(connection, table, logicalTableName);
        }

        assertNoScopedDuplicates(connection, table, repositoryScoped);

        for (IndexDefinition index : uniqueIndexes(connection, table)) {
            Set<String> columns = new LinkedHashSet<>(index.columns());
            if (legacyColumnSets.contains(columns)) {
                dropConstraintOrIndex(connection, table, index.name());
                log.info("Dropped legacy unique constraint/index {} on {} columns {}",
                        index.name(), logicalTableName, index.columns());
            }
        }

        boolean targetExists = uniqueIndexes(connection, table).stream()
                .anyMatch(index -> new LinkedHashSet<>(index.columns()).equals(targetColumns));
        if (!targetExists) {
            String columns = repositoryScoped
                    ? "repository_id, source_node_id, target_node_id, relation_type, workspace_scope_key"
                    : "source_node_id, target_node_id, relation_type, workspace_scope_key";
            execute(connection, "ALTER TABLE " + qualified(connection, table)
                    + " ADD CONSTRAINT " + quoted(connection, targetConstraint)
                    + " UNIQUE (" + columns + ")");
            log.info("Created tenant-scoped unique constraint {} on {}",
                    targetConstraint, logicalTableName);
        }
    }

    /**
     * Bind historic workspace rows to the source repository recorded by their
     * workspace. Only remaining central rows may fall back to the unique catalog
     * primary repository.
     */
    private void bindLegacyRowsToRepositories(
            Connection connection,
            TableRef tenantTable,
            String logicalTableName) throws SQLException {
        execute(connection, "UPDATE " + qualified(connection, tenantTable)
                + " SET repository_id = NULL"
                + " WHERE repository_id IS NOT NULL AND TRIM(repository_id) = ''");

        bindWorkspaceRowsToSourceRepositories(connection, tenantTable, logicalTableName);

        long unboundWorkspaceRows = singleLong(connection, "SELECT COUNT(*) FROM "
                + qualified(connection, tenantTable)
                + " WHERE repository_id IS NULL AND workspace_id IS NOT NULL");
        if (unboundWorkspaceRows > 0) {
            throw new IllegalStateException(
                    "Cannot bind " + unboundWorkspaceRows + " existing workspace "
                            + logicalTableName + " row(s): workspace source repository "
                            + "provenance is missing or ambiguous");
        }

        long unboundCentralRows = singleLong(connection, "SELECT COUNT(*) FROM "
                + qualified(connection, tenantTable)
                + " WHERE repository_id IS NULL AND workspace_id IS NULL");
        if (unboundCentralRows == 0) {
            return;
        }

        String primaryRepositoryId = requireExactlyOnePrimaryRepository(
                connection, logicalTableName, unboundCentralRows);
        String update = "UPDATE " + qualified(connection, tenantTable)
                + " SET repository_id = ?"
                + " WHERE repository_id IS NULL AND workspace_id IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setString(1, primaryRepositoryId);
            statement.executeUpdate();
        }
        log.info("Bound {} legacy central row(s) in {} to the primary repository",
                unboundCentralRows, logicalTableName);
    }

    private void bindWorkspaceRowsToSourceRepositories(
            Connection connection,
            TableRef tenantTable,
            String logicalTableName) throws SQLException {
        long unboundWorkspaceRows = singleLong(connection, "SELECT COUNT(*) FROM "
                + qualified(connection, tenantTable)
                + " WHERE repository_id IS NULL AND workspace_id IS NOT NULL");
        if (unboundWorkspaceRows == 0) {
            return;
        }

        TableRef workspaceTable = findTable(connection, "user_workspace");
        if (workspaceTable == null
                || !columnExists(connection, workspaceTable, "source_repository_id")) {
            throw new IllegalStateException(
                    "Cannot bind " + unboundWorkspaceRows + " existing workspace "
                            + logicalTableName + " row(s): user_workspace source repository "
                            + "provenance is unavailable");
        }

        Map<String, String> sourceRepositories = new LinkedHashMap<>();
        String workspaceSelect = "SELECT workspace_id, source_repository_id FROM "
                + qualified(connection, workspaceTable);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(workspaceSelect)) {
            while (result.next()) {
                String workspaceId = normalizeOptional(result.getString("workspace_id"));
                String repositoryId = normalizeOptional(result.getString("source_repository_id"));
                if (workspaceId == null) {
                    continue;
                }
                if (sourceRepositories.containsKey(workspaceId)) {
                    throw new IllegalStateException(
                            "Workspace " + workspaceId
                                    + " has ambiguous source repository provenance");
                }
                sourceRepositories.put(workspaceId, repositoryId);
            }
        }

        List<WorkspaceTenantRow> rows = new ArrayList<>();
        String rowSelect = "SELECT id, workspace_id FROM "
                + qualified(connection, tenantTable)
                + " WHERE repository_id IS NULL AND workspace_id IS NOT NULL";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(rowSelect)) {
            while (result.next()) {
                rows.add(new WorkspaceTenantRow(
                        result.getObject("id"),
                        normalizeOptional(result.getString("workspace_id"))));
            }
        }

        String update = "UPDATE " + qualified(connection, tenantTable)
                + " SET repository_id = ? WHERE id = ? AND repository_id IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            int batchSize = 0;
            for (WorkspaceTenantRow row : rows) {
                String repositoryId = sourceRepositories.get(row.workspaceId());
                if (repositoryId == null || repositoryId.isBlank()) {
                    continue;
                }
                statement.setString(1, repositoryId);
                statement.setObject(2, row.id());
                statement.addBatch();
                batchSize++;
            }
            if (batchSize > 0) {
                statement.executeBatch();
            }
        }
    }

    private String requireExactlyOnePrimaryRepository(
            Connection connection,
            String logicalTableName,
            long unboundCentralRows) throws SQLException {
        TableRef repositoryTable = findTable(connection, "system_repository");
        if (repositoryTable == null) {
            throw new IllegalStateException(
                    "Cannot bind legacy rows in " + logicalTableName
                            + ": system_repository is missing");
        }

        String primaryRepositoryId = null;
        int primaryCount = 0;
        String select = "SELECT repository_id, primary_repo FROM "
                + qualified(connection, repositoryTable);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(select)) {
            while (result.next()) {
                if (result.getBoolean("primary_repo")) {
                    primaryCount++;
                    primaryRepositoryId = normalizeOptional(result.getString("repository_id"));
                }
            }
        }
        if (primaryCount != 1 || primaryRepositoryId == null) {
            throw new IllegalStateException(
                    "Cannot bind " + unboundCentralRows + " existing central "
                            + logicalTableName + " row(s): expected exactly one primary "
                            + "repository, found " + primaryCount);
        }
        return primaryRepositoryId;
    }

    private void ensureRepositoryForeignKey(
            Connection connection,
            TableRef tenantTable,
            String logicalTableName) throws SQLException {
        TableRef repositoryTable = findTable(connection, "system_repository");
        if (repositoryTable == null) {
            throw new IllegalStateException(
                    "Cannot enforce repository scope for " + logicalTableName
                            + ": system_repository is missing");
        }
        if (hasImportedKey(
                connection,
                tenantTable,
                "repository_id",
                repositoryTable,
                "repository_id")) {
            return;
        }
        String constraintName = "fk_" + logicalTableName + "_repository";
        execute(connection, "ALTER TABLE " + qualified(connection, tenantTable)
                + " ADD CONSTRAINT " + quoted(connection, constraintName)
                + " FOREIGN KEY (repository_id) REFERENCES "
                + qualified(connection, repositoryTable) + " (repository_id)");
        log.info("Created repository foreign key {} on {}",
                constraintName, logicalTableName);
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
                        && targetTable.name().equalsIgnoreCase(keys.getString("PKTABLE_NAME"))
                        && targetColumn.equalsIgnoreCase(keys.getString("PKCOLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensurePasswordChangeColumn(Connection connection) throws SQLException {
        TableRef appUser = findTable(connection, "app_user");
        if (appUser == null || columnExists(connection, appUser, "must_change_password")) {
            return;
        }

        String product = connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT);
        String definition;
        if (product.contains("microsoft")) {
            definition = "BIT DEFAULT 0 NOT NULL";
        } else if (product.contains("oracle")) {
            definition = "NUMBER(1) DEFAULT 0 NOT NULL";
        } else {
            definition = "BOOLEAN DEFAULT FALSE NOT NULL";
        }
        ensureColumn(connection, appUser, "must_change_password", definition);
        log.info("Added app_user.must_change_password with a safe false default");
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
                + " ADD " + column + " " + definition);
    }

    private void assertNoScopedDuplicates(
            Connection connection,
            TableRef table,
            boolean repositoryScoped) throws SQLException {
        String repositoryProjection = repositoryScoped ? "repository_id, " : "";
        String sql = "SELECT COUNT(*) FROM (SELECT "
                + repositoryProjection
                + "source_node_id, target_node_id, relation_type, "
                + scopeExpression() + " AS scope_key, COUNT(*) AS duplicate_count FROM "
                + qualified(connection, table)
                + " GROUP BY " + repositoryProjection
                + "source_node_id, target_node_id, relation_type, "
                + scopeExpression()
                + " HAVING COUNT(*) > 1) taxonomy_duplicates";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            long duplicateGroups = result.getLong(1);
            if (duplicateGroups > 0) {
                throw new IllegalStateException("Table " + table.name()
                        + " contains " + duplicateGroups
                        + " duplicate tenant/source/target/type/workspace groups. "
                        + "Resolve them before starting the upgraded application.");
            }
        }
    }

    private static String scopeExpression() {
        return "COALESCE(NULLIF(TRIM(workspace_id), ''), '" + SHARED_SCOPE + "')";
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

    private List<IndexDefinition> uniqueIndexes(
            Connection connection, TableRef table) throws SQLException {
        Map<String, Map<Short, String>> byName = new LinkedHashMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet indexes = metadata.getIndexInfo(
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
        byName.forEach((name, columns) ->
                result.add(new IndexDefinition(name, List.copyOf(columns.values()))));
        result.sort(Comparator.comparing(IndexDefinition::name));
        return result;
    }

    private TableRef findTable(
            Connection connection, String logicalName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String preferredSchema = safeSchema(connection);
        List<TableRef> matches = new ArrayList<>();
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
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
                .sorted(Comparator.comparing(table ->
                        preferredSchema != null
                                && preferredSchema.equalsIgnoreCase(table.schema()) ? 0 : 1))
                .findFirst()
                .orElse(null);
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

    private long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        log.debug("Schema migration SQL: {}", sql);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String qualified(Connection connection, TableRef table) throws SQLException {
        String tableName = quoted(connection, table.name());
        return table.schema() == null || table.schema().isBlank()
                ? tableName : quoted(connection, table.schema()) + "." + tableName;
    }

    private String qualifiedIndex(
            Connection connection,
            TableRef table,
            String indexName) throws SQLException {
        String quotedIndex = quoted(connection, indexName);
        return table.schema() == null || table.schema().isBlank()
                ? quotedIndex : quoted(connection, table.schema()) + "." + quotedIndex;
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

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record WorkspaceTenantRow(Object id, String workspaceId) {
    }

    private record TableRef(String catalog, String schema, String name) {
    }

    private record IndexDefinition(String name, List<String> columns) {
    }
}
