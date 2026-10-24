package com.taxonomy.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/** Portable JDBC implementation behind {@link SchemaContractMigration}. */
final class SchemaContractMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaContractMigrator.class);
    private static final String SHARED_SCOPE = "__shared__";
    private static final String UNSPECIFIED_SESSION_SCOPE = "__unspecified__";

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
    private static final Set<String> HYPOTHESIS_LEGACY_COLUMNS = Set.of(
            "workspace_id", "source_node_id", "target_node_id",
            "relation_type", "analysis_session_id");
    private static final Set<String> HYPOTHESIS_TARGET_COLUMNS = Set.of(
            "repository_id", "workspace_scope_key", "source_node_id",
            "target_node_id", "relation_type", "analysis_session_scope_key");

    private final DataSource dataSource;

    SchemaContractMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                migrateScopedUniqueness(
                        connection,
                        "relation_proposal",
                        "uk_relation_proposal_scope",
                        List.of(
                                PROPOSAL_LEGACY_COLUMNS,
                                PROPOSAL_WORKSPACE_NULLABLE_COLUMNS,
                                WORKSPACE_SCOPE_KEY_COLUMNS),
                        REPOSITORY_SCOPE_KEY_COLUMNS);
                migrateScopedUniqueness(
                        connection,
                        "taxonomy_relation",
                        "uk_taxonomy_relation_scope",
                        List.of(
                                RELATION_WORKSPACE_NULLABLE_COLUMNS,
                                WORKSPACE_SCOPE_KEY_COLUMNS),
                        REPOSITORY_SCOPE_KEY_COLUMNS);
                migrateHypothesisUniqueness(connection);
                ensurePasswordChangeColumn(connection);
                connection.commit();
                log.info("Verified repository, workspace, hypothesis and account schema contracts");
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
            Set<String> targetColumns) throws SQLException {
        TableRef table = findTable(connection, logicalTableName);
        if (table == null) {
            log.debug("Schema migration skipped: table {} does not exist", logicalTableName);
            return;
        }

        normalizeWorkspaceScope(connection, table);
        ensureColumn(connection, table, "repository_id", "VARCHAR(255)");
        bindLegacyRowsToRepositories(connection, table, logicalTableName);
        ensureRepositoryForeignKey(connection, table, logicalTableName);
        assertNoScopedDuplicates(connection, table);
        replaceLegacyUniqueIndexes(
                connection, table, logicalTableName, legacyColumnSets);

        boolean targetExists = uniqueIndexes(connection, table).stream()
                .anyMatch(index -> new LinkedHashSet<>(index.columns()).equals(targetColumns));
        if (!targetExists) {
            execute(connection, "ALTER TABLE " + qualified(connection, table)
                    + " ADD CONSTRAINT " + quoted(connection, targetConstraint)
                    + " UNIQUE (repository_id, source_node_id, target_node_id, "
                    + "relation_type, workspace_scope_key)");
            log.info("Created tenant-scoped unique constraint {} on {}",
                    targetConstraint, logicalTableName);
        }
    }

    /** Immutable recorded parent links are the only legacy branch authority. */
    private void migrateHypothesisUniqueness(Connection connection) throws SQLException {
        TableRef table = findTable(connection, "relation_hypothesis");
        if (table == null) return;
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET workspace_id = TRIM(workspace_id) WHERE workspace_id IS NOT NULL");
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET analysis_session_id = NULLIF(TRIM(analysis_session_id), '')");
        normalizeWorkspaceScope(connection, table);
        ensureColumn(connection, table, "analysis_session_scope_key", "VARCHAR(255)");
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET analysis_session_scope_key = " + sessionScopeExpression());
        ensureColumn(connection, table, "repository_id", "VARCHAR(255)");
        bindLegacyRowsToRepositories(connection, table, "relation_hypothesis");
        ensureRepositoryForeignKey(connection, table, "relation_hypothesis");
        String branchType = isSqlServer(connection)
                ? "NVARCHAR(255) COLLATE Latin1_General_100_BIN2"
                : connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("oracle")
                ? "NVARCHAR2(255)" : "VARCHAR(255)";
        ensureColumn(connection, table, "branch_name", branchType);
        TableRef evidence = findTable(connection, "relation_evidence");
        if (evidence != null) {
            ensureColumn(connection, evidence, "repository_id", "VARCHAR(255)");
            ensureColumn(connection, evidence, "workspace_scope_key", "VARCHAR(255)");
            ensureColumn(connection, evidence, "branch_name", branchType);
        }
        Map<String, Map<String, String>> parents = branchRows(connection, table);
        Map<String, Map<String, String>> projects = branchRows(connection, findTable(connection, "arch_project"));
        Map<String, Map<String, String>> snapshots = branchRows(connection, findTable(connection, "req_analysis_snapshot"));
        Map<String, Map<String, String>> requirements = branchRows(connection, findTable(connection, "project_requirement"));
        Map<String, Long> failures = new TreeMap<>();
        List<Long> failedIds = new ArrayList<>();
        for (var entry : parents.entrySet()) {
            try {
                String branch = resolveHypothesisBranch(entry.getValue(), projects, snapshots, requirements);
                entry.getValue().put("branch_name", branch);
            } catch (BranchAuthorityFailure failure) {
                failures.merge(failure.getMessage(), 1L, Long::sum);
                if (failedIds.size() < 20) failedIds.add(Long.valueOf(entry.getKey()));
            }
        }
        if (!failures.isEmpty()) throw branchFailure(failures, failedIds);
        // Validate all populated evidence keys before changing any authority value.
        Map<String, Map<String, String>> evidenceRows = branchRows(connection, evidence);
        for (var entry : evidenceRows.entrySet()) {
            var row = entry.getValue();
            var parent = parents.get(row.get("hypothesis_id"));
            String reason = parent == null ? "missing_evidence_parent" : null;
            if (parent != null) {
                for (String key : List.of("repository_id", "workspace_scope_key", "branch_name")) {
                    if (row.get(key) != null && !Objects.equals(row.get(key), parent.get(key))) {
                        reason = "conflicting_evidence_scope";
                    }
                }
            }
            if (reason != null) {
                failures.merge(reason, 1L, Long::sum);
                if (failedIds.size() < 20) failedIds.add(Long.valueOf(entry.getKey()));
            }
        }
        if (!failures.isEmpty()) throw branchFailure(failures, failedIds);
        for (var entry : parents.entrySet()) {
            branchUpdate(connection, "UPDATE " + qualified(connection, table)
                    + " SET branch_name=? WHERE id=?", entry.getValue().get("branch_name"), Long.valueOf(entry.getKey()));
        }
        for (var entry : evidenceRows.entrySet()) {
            var parent = parents.get(entry.getValue().get("hypothesis_id"));
            branchUpdate(connection, "UPDATE " + qualified(connection, evidence)
                    + " SET repository_id=?, workspace_scope_key=?, branch_name=? WHERE id=?",
                    parent.get("repository_id"), parent.get("workspace_scope_key"), parent.get("branch_name"),
                    Long.valueOf(entry.getKey()));
        }
        // SQL Server's default case-insensitive collation cannot represent Git refs.
        // Preflight above precedes constraint changes; reruns complete each committed DDL phase.
        ensureBinaryBranchCollation(connection, table, evidence);
        assertNoHypothesisDuplicates(connection, table);
        // Obsolete keys must not block SQL Server's authority-column NOT NULL changes.
        replaceLegacyHypothesisUniqueIndexes(connection, table);
        for (String column : List.of("repository_id", "workspace_scope_key", "analysis_session_scope_key", "branch_name")) {
            branchNotNull(connection, table, column);
        }
        ensureBranchUnique(connection, table, "uq_hypothesis_parent_scope",
                List.of("id", "repository_id", "workspace_scope_key", "branch_name"));
        ensureBranchUnique(connection, table, "uq_hypothesis_branch_session_relation",
                List.of("repository_id", "workspace_scope_key", "branch_name", "source_node_id",
                        "target_node_id", "relation_type", "analysis_session_scope_key"));
        if (evidence != null) {
            for (String column : List.of("hypothesis_id", "repository_id", "workspace_scope_key", "branch_name")) {
                branchNotNull(connection, evidence, column);
            }
            List<String> source = List.of("hypothesis_id", "repository_id", "workspace_scope_key", "branch_name");
            List<String> target = List.of("id", "repository_id", "workspace_scope_key", "branch_name");
            if (!hasExactBranchForeignKey(connection, evidence, table, source, target)) {
                execute(connection, "ALTER TABLE " + qualified(connection, evidence)
                        + " ADD CONSTRAINT fk_evidence_hypothesis_scope FOREIGN KEY (" + String.join(",", source)
                        + ") REFERENCES " + qualified(connection, table) + " (" + String.join(",", target) + ")");
            }
        }
        // The business key's leading columns are also the exact-scope query index.
    }

    private String resolveHypothesisBranch(Map<String, String> row,
            Map<String, Map<String, String>> projects, Map<String, Map<String, String>> snapshots,
            Map<String, Map<String, String>> requirements) {
        String branch = row.get("branch_name");
        Map<String, String> snapshot = null;
        String projectId = row.get("project_id");
        String requirementId = row.get("requirement_id");
        if (row.get("analysis_snapshot_id") != null) {
            snapshot = snapshots.get(row.get("analysis_snapshot_id"));
            if (snapshot == null) throw new BranchAuthorityFailure("missing_snapshot");
            if (projectId != null && !projectId.equals(snapshot.get("project_id")))
                throw new BranchAuthorityFailure("conflicting_project_link");
            if (requirementId != null && !requirementId.equals(snapshot.get("requirement_id")))
                throw new BranchAuthorityFailure("conflicting_requirement_link");
            projectId = snapshot.get("project_id");
            requirementId = snapshot.get("requirement_id");
            if (projectId == null) throw new BranchAuthorityFailure("missing_project");
        }
        if (projectId == null) {
            if (branch == null || requirementId != null) throw new BranchAuthorityFailure("missing_provenance");
            return branch;
        }
        var project = projects.get(projectId);
        if (project == null) throw new BranchAuthorityFailure("missing_project");
        String projectBranch = project.get("branch_name");
        String workspace = row.get("workspace_id");
        String canonical;
        try {
            var identity = new com.taxonomy.workspace.model.RepositoryTenantIdentity(
                    project.get("repository_id"), project.get("workspace_scope"), projectBranch);
            canonical = identity.scopeKey();
            if (!canonical.equals(project.get("scope_key"))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new BranchAuthorityFailure("branch_scope_mismatch");
        }
        if (!Objects.equals(row.get("repository_id"), project.get("repository_id")))
            throw new BranchAuthorityFailure("repository_mismatch");
        String workspaceScope = workspace == null ? "CENTRAL" : "WORKSPACE:" + workspace;
        if (!Objects.equals(workspace, project.get("workspace_id"))
                || !workspaceScope.equals(project.get("workspace_scope")))
            throw new BranchAuthorityFailure("workspace_mismatch");
        if (branch != null && !branch.equals(projectBranch)) throw new BranchAuthorityFailure("branch_scope_mismatch");
        if (snapshot != null && (!canonical.equals(snapshot.get("scope_key"))
                || !Objects.equals(workspace, snapshot.get("workspace_id"))
                || (snapshot.get("branch_name") != null && !projectBranch.equals(snapshot.get("branch_name")))))
            throw new BranchAuthorityFailure("branch_scope_mismatch");
        if (requirementId != null) {
            var requirement = requirements.get(requirementId);
            if (requirement == null) throw new BranchAuthorityFailure("missing_requirement");
            if (!projectId.equals(requirement.get("project_id")) || !canonical.equals(requirement.get("scope_key")))
                throw new BranchAuthorityFailure("conflicting_requirement_link");
        }
        return projectBranch;
    }

    private Map<String, Map<String, String>> branchRows(Connection c, TableRef table) throws SQLException {
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        if (table == null) return rows;
        try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT * FROM " + qualified(c, table) + " ORDER BY id")) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    String key = rs.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT);
                    // Read only authority fields; never load evidence, prompts or DSL into diagnostics.
                    if (Set.of("id", "hypothesis_id", "repository_id", "workspace_id", "workspace_scope_key",
                            "branch_name", "project_id", "requirement_id", "analysis_snapshot_id", "scope_key",
                            "workspace_scope").contains(key)) {
                        String value=rs.getString(i);
                        boolean exact=key.equals("scope_key") || (table.name().equalsIgnoreCase("relation_evidence")
                                && Set.of("repository_id", "workspace_scope_key", "branch_name").contains(key));
                        row.put(key, exact ? value : normalizeOptional(value));
                    }
                }
                rows.put(row.get("id"), row);
            }
        }
        return rows;
    }

    private IllegalStateException branchFailure(Map<String, Long> reasons, List<Long> ids) {
        return new IllegalStateException("Hypothesis branch authority unresolved: counts=" + reasons
                + ", row IDs (at most 20)=" + ids + ". Data retained. Restore recorded snapshot/project provenance "
                + "from a backup and retry; never infer a branch from current workspace or status.");
    }
    private static final class BranchAuthorityFailure extends RuntimeException {
        BranchAuthorityFailure(String reason) { super(reason); }
    }
    private void branchUpdate(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
    private boolean isSqlServer(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("microsoft");
    }
    private void ensureBranchUnique(Connection c, TableRef table, String name, List<String> columns) throws SQLException {
        List<IndexDefinition> indexes = isSqlServer(c) ? sqlServerBranchUniqueIndexes(c, table) : uniqueIndexes(c, table);
        if (indexes.stream().noneMatch(index -> index.columns().equals(columns))) {
            execute(c, "ALTER TABLE " + qualified(c, table) + " ADD CONSTRAINT " + name
                    + " UNIQUE (" + String.join(",", columns) + ")");
        }
    }
    private List<IndexDefinition> sqlServerBranchUniqueIndexes(Connection c, TableRef table) throws SQLException {
        return sqlServerBranchUniqueIndexes(c, table, true);
    }
    private List<IndexDefinition> sqlServerBranchUniqueIndexes(
            Connection c, TableRef table, boolean enforcingOnly) throws SQLException {
        Map<String, Map<Short, String>> indexes = new LinkedHashMap<>();
        try (PreparedStatement query = c.prepareStatement("""
                SELECT i.name, ic.key_ordinal, col.name
                FROM sys.indexes i
                JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id
                JOIN sys.columns col ON col.object_id=ic.object_id AND col.column_id=ic.column_id
                JOIN sys.tables t ON t.object_id=i.object_id
                JOIN sys.schemas s ON s.schema_id=t.schema_id
                WHERE s.name=? AND t.name=? AND i.is_unique=1
                  AND i.is_hypothetical=0 AND ic.key_ordinal>0
                """ + (enforcingOnly ? " AND i.has_filter=0 AND i.is_disabled=0" : ""))) {
            query.setString(1, table.schema());
            query.setString(2, table.name());
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) indexes.computeIfAbsent(rs.getString(1), unused -> new TreeMap<>())
                        .put(rs.getShort(2), rs.getString(3).toLowerCase(Locale.ROOT));
            }
        }
        return indexes.entrySet().stream()
                .map(entry -> new IndexDefinition(entry.getKey(), List.copyOf(entry.getValue().values())))
                .toList();
    }
    private void branchNotNull(Connection c, TableRef table, String column) throws SQLException {
        try (ResultSet columns = c.getMetaData().getColumns(table.catalog(), table.schema(), table.name(), "%")) {
            while (columns.next()) {
                if (!column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) continue;
                if (columns.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) return;
                String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
                String alter = product.contains("oracle") ? " MODIFY (" + column + " NOT NULL)"
                        : isSqlServer(c) ? " ALTER COLUMN " + column + " "
                        + (column.equals("hypothesis_id") ? "BIGINT" : column.equals("branch_name")
                            ? "NVARCHAR(255) COLLATE Latin1_General_100_BIN2" : "VARCHAR(255)") + " NOT NULL"
                        : " ALTER COLUMN " + column + " SET NOT NULL";
                execute(c, "ALTER TABLE " + qualified(c, table) + alter);
                return;
            }
        }
    }
    private boolean hasExactBranchForeignKey(Connection c, TableRef source, TableRef target,
            List<String> sourceColumns, List<String> targetColumns) throws SQLException {
        Map<String, Map<Short, String>> keys = new LinkedHashMap<>();
        if (c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("oracle")) {
            // Oracle JDBC imported-key metadata filters parent constraints to P,
            // omitting this FK's UNIQUE parent key. Inspect its actual column pairs.
            try (PreparedStatement query = c.prepareStatement("""
                    SELECT f.constraint_name, fc.position, fc.column_name, pc.column_name
                    FROM all_constraints f
                    JOIN all_constraints p ON p.owner=f.r_owner AND p.constraint_name=f.r_constraint_name
                    JOIN all_cons_columns fc ON fc.owner=f.owner AND fc.constraint_name=f.constraint_name
                      AND fc.table_name=f.table_name
                    JOIN all_cons_columns pc ON pc.owner=p.owner AND pc.constraint_name=p.constraint_name
                      AND pc.table_name=p.table_name AND pc.position=fc.position
                    WHERE f.constraint_type='R' AND f.status='ENABLED' AND p.constraint_type IN ('P','U')
                      AND f.owner=? AND f.table_name=? AND p.owner=? AND p.table_name=?
                    """)) {
                query.setString(1, source.schema());
                query.setString(2, source.name());
                query.setString(3, target.schema());
                query.setString(4, target.name());
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) keys.computeIfAbsent(rs.getString(1), unused -> new TreeMap<>())
                            .put(rs.getShort(2), rs.getString(3).toLowerCase(Locale.ROOT)
                                    + ":" + rs.getString(4).toLowerCase(Locale.ROOT));
                }
            }
        } else {
            try (ResultSet rs = c.getMetaData().getImportedKeys(source.catalog(), source.schema(), source.name())) {
                while (rs.next()) {
                    if (!target.name().equalsIgnoreCase(rs.getString("PKTABLE_NAME"))
                            || !Objects.equals(target.schema(), rs.getString("PKTABLE_SCHEM"))) continue;
                    keys.computeIfAbsent(rs.getString("FK_NAME"), unused -> new TreeMap<>())
                            .put(rs.getShort("KEY_SEQ"), rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT)
                                    + ":" + rs.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < sourceColumns.size(); i++) expected.add(sourceColumns.get(i) + ":" + targetColumns.get(i));
        // Hibernate may order the same four source/target pairs differently.
        // Compare the complete mapping, retaining cardinality and each pairing.
        return keys.values().stream().anyMatch(key -> key.size() == expected.size()
                && new LinkedHashSet<>(key.values()).equals(new LinkedHashSet<>(expected)));
    }
    private void ensureBinaryBranchCollation(Connection c, TableRef parent, TableRef evidence) throws SQLException {
        if (!isSqlServer(c)) return;
        for (TableRef table : evidence == null ? List.of(parent) : List.of(evidence, parent)) {
            String collation;
            try (PreparedStatement query = c.prepareStatement("SELECT collation_name FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME='branch_name'")) {
                query.setString(1, table.schema()); query.setString(2, table.name());
                try (ResultSet rs = query.executeQuery()) { collation = rs.next() ? rs.getString(1) : null; }
            }
            if ("Latin1_General_100_BIN2".equals(collation)) continue;
            // Existing populated schemas must be migrated before Hibernate. A fresh schema may
            // already have Hibernate-created keys on the newly created branch column.
            if (evidence != null) {
                Set<String> foreignKeys = new LinkedHashSet<>();
                try (ResultSet rs = c.getMetaData().getImportedKeys(evidence.catalog(), evidence.schema(), evidence.name())) {
                    while (rs.next()) if ("branch_name".equalsIgnoreCase(rs.getString("FKCOLUMN_NAME")))
                        foreignKeys.add(rs.getString("FK_NAME"));
                }
                for (String name : foreignKeys) execute(c, "ALTER TABLE " + qualified(c, evidence)
                        + " DROP CONSTRAINT " + quoted(c, name));
            }
            // Discover actual SQL Server dependencies, including Hibernate-created
            // unique keys and non-unique scope indexes, before changing collation.
            Map<String, Boolean> indexes = new LinkedHashMap<>();
            try (PreparedStatement query = c.prepareStatement("""
                    SELECT DISTINCT i.name, i.is_unique_constraint, i.is_primary_key
                    FROM sys.indexes i
                    JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id
                    JOIN sys.columns col ON col.object_id=ic.object_id AND col.column_id=ic.column_id
                    JOIN sys.tables t ON t.object_id=i.object_id
                    JOIN sys.schemas s ON s.schema_id=t.schema_id
                    WHERE s.name=? AND t.name=? AND col.name='branch_name' AND i.name IS NOT NULL
                    """)) {
                query.setString(1, table.schema());
                query.setString(2, table.name());
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) indexes.put(rs.getString(1), rs.getBoolean(2) || rs.getBoolean(3));
                }
            }
            // Do not probe with failing DDL inside the migration transaction.
            for (var index : indexes.entrySet()) {
                execute(c, index.getValue()
                        ? "ALTER TABLE " + qualified(c, table)
                                + " DROP CONSTRAINT " + quoted(c, index.getKey())
                        : "DROP INDEX " + quoted(c, index.getKey())
                                + " ON " + qualified(c, table));
            }
            execute(c, "ALTER TABLE " + qualified(c, table)
                    + " ALTER COLUMN branch_name NVARCHAR(255) COLLATE Latin1_General_100_BIN2 NOT NULL");
        }
    }

    private void normalizeWorkspaceScope(
            Connection connection,
            TableRef table) throws SQLException {
        ensureColumn(connection, table, "workspace_scope_key", "VARCHAR(255)");
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET workspace_id = NULL"
                + " WHERE workspace_id IS NOT NULL AND TRIM(workspace_id) = ''");
        execute(connection, "UPDATE " + qualified(connection, table)
                + " SET workspace_scope_key = " + workspaceScopeExpression());
    }

    private void replaceLegacyHypothesisUniqueIndexes(Connection c, TableRef table) throws SQLException {
        List<Set<String>> legacyColumns = List.of(HYPOTHESIS_LEGACY_COLUMNS, HYPOTHESIS_TARGET_COLUMNS);
        if (!isSqlServer(c)) {
            replaceLegacyUniqueIndexes(c, table, "relation_hypothesis", legacyColumns);
            return;
        }
        // Filtered and disabled legacy keys still physically depend on these columns.
        for (IndexDefinition index : sqlServerBranchUniqueIndexes(c, table, false)) {
            Set<String> columns = new LinkedHashSet<>(index.columns());
            if (legacyColumns.stream().noneMatch(expected -> expected.size() == index.columns().size()
                    && expected.equals(columns))) continue;
            boolean constraint;
            try (PreparedStatement query = c.prepareStatement("""
                    SELECT i.is_unique_constraint, i.is_primary_key
                    FROM sys.indexes i
                    JOIN sys.tables t ON t.object_id=i.object_id
                    JOIN sys.schemas s ON s.schema_id=t.schema_id
                    WHERE s.name=? AND t.name=? AND i.name=?
                    """)) {
                query.setString(1, table.schema());
                query.setString(2, table.name());
                query.setString(3, index.name());
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Missing SQL Server hypothesis unique index: " + index.name());
                    constraint = rs.getBoolean(1) || rs.getBoolean(2);
                }
            }
            execute(c, constraint
                    ? "ALTER TABLE " + qualified(c, table) + " DROP CONSTRAINT " + quoted(c, index.name())
                    : "DROP INDEX " + quoted(c, index.name()) + " ON " + qualified(c, table));
            log.info("Dropped legacy unique constraint/index {} on relation_hypothesis columns {}",
                    index.name(), index.columns());
        }
    }

    private void replaceLegacyUniqueIndexes(
            Connection connection,
            TableRef table,
            String logicalTableName,
            List<Set<String>> legacyColumnSets) throws SQLException {
        for (IndexDefinition index : uniqueIndexes(connection, table)) {
            Set<String> columns = new LinkedHashSet<>(index.columns());
            if (legacyColumnSets.contains(columns)) {
                dropConstraintOrIndex(connection, table, index.name());
                log.info("Dropped legacy unique constraint/index {} on {} columns {}",
                        index.name(), logicalTableName, index.columns());
            }
        }
    }

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
                if (sourceRepositories.containsKey(workspaceId)
                        && !Objects.equals(sourceRepositories.get(workspaceId), repositoryId)) {
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
                if (repositoryId == null) {
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
            TableRef table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM (SELECT repository_id, "
                + "source_node_id, target_node_id, relation_type, "
                + workspaceScopeExpression() + " AS scope_key, COUNT(*) AS duplicate_count FROM "
                + qualified(connection, table)
                + " GROUP BY repository_id, source_node_id, target_node_id, relation_type, "
                + workspaceScopeExpression()
                + " HAVING COUNT(*) > 1) taxonomy_duplicates";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            long duplicateGroups = result.getLong(1);
            if (duplicateGroups > 0) {
                throw new IllegalStateException("Table " + table.name()
                        + " contains " + duplicateGroups
                        + " duplicate repository/source/target/type/workspace groups. "
                        + "Resolve them before starting the upgraded application.");
            }
        }
    }

    private void assertNoHypothesisDuplicates(
            Connection connection,
            TableRef table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM (SELECT repository_id, branch_name, "
                + workspaceScopeExpression() + " AS workspace_key, "
                + "source_node_id, target_node_id, relation_type, "
                + sessionScopeExpression() + " AS session_key, COUNT(*) AS duplicate_count FROM "
                + qualified(connection, table)
                + " GROUP BY repository_id, branch_name, " + workspaceScopeExpression()
                + ", source_node_id, target_node_id, relation_type, "
                + sessionScopeExpression()
                + " HAVING COUNT(*) > 1) hypothesis_duplicates";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            long duplicateGroups = result.getLong(1);
            if (duplicateGroups > 0) {
                throw new IllegalStateException("Table " + table.name()
                        + " contains " + duplicateGroups
                        + " duplicate repository/workspace/session relation groups. "
                        + "Resolve them before starting the upgraded application.");
            }
        }
    }

    private static String workspaceScopeExpression() {
        return "COALESCE(NULLIF(TRIM(workspace_id), ''), '" + SHARED_SCOPE + "')";
    }

    private static String sessionScopeExpression() {
        return "COALESCE(NULLIF(TRIM(analysis_session_id), ''), '"
                + UNSPECIFIED_SESSION_SCOPE + "')";
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
            Connection connection,
            TableRef table) throws SQLException {
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
            Connection connection,
            String logicalName) throws SQLException {
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
