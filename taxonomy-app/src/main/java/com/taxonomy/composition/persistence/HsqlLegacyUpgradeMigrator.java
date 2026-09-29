package com.taxonomy.composition.persistence;

import com.taxonomy.shared.config.SchemaContractMigration;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Pre-Hibernate upgrade of the released 1.3 HSQLDB application schema.
 *
 * <p>Hibernate cannot add a new NOT NULL column to a populated HSQLDB table.
 * The portable relation/commit-index migration used to run as an application
 * runner, after both Hibernate and the repository catalogue initializer. Run
 * that existing provenance-aware migration at Flyway's earlier boundary, and
 * bind portfolio rows through their exact parents before enforcing new required
 * columns. This code never guesses a tenant from a username or row ID.</p>
 */
final class HsqlLegacyUpgradeMigrator {

    private static final List<String> ROOTS = List.of(
            "arch_project", "solution_definition", "product_catalog");
    private final DataSource dataSource;

    private HsqlLegacyUpgradeMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    static void migrate(DataSource dataSource) {
        new HsqlLegacyUpgradeMigrator(dataSource).migrate();
    }

    private void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT).contains("hsql")) {
                return;
            }
            if (!tableExists(connection, "system_repository")) {
                return; // A fresh schema is created by Hibernate after this hook.
            }
            boolean legacy = !columnExists(connection, "system_repository", "version")
                    || (tableExists(connection, "taxonomy_relation")
                        && !columnExists(connection, "taxonomy_relation", "repository_id"))
                    || (tableExists(connection, "arch_project")
                        && !columnExists(connection, "arch_project", "repository_id"));
            ensureColumn(connection, "system_repository", "version",
                    "BIGINT DEFAULT 0 NOT NULL");
            if (legacy) bindLegacyWorkspaceProvenance(connection);
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to prepare HSQLDB 1.3 schema", error);
        }

        // Includes the existing exact workspace/primary-repository relation
        // binder and the rebuildable commit-index reset. The application runner
        // repeats these checks after Hibernate has created any new tables.
        new SchemaContractMigration(dataSource).migrate();

        try (Connection connection = dataSource.getConnection()) {
            enforceRelationColumns(connection);
            bindPortfolio(connection);
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to upgrade HSQLDB 1.3 tenants safely", error);
        }
    }

    private static void bindLegacyWorkspaceProvenance(Connection connection)
            throws SQLException {
        if (!tableExists(connection, "user_workspace")
                || !columnExists(connection, "user_workspace", "source_repository_id")) {
            return;
        }
        long missing = count(connection, "SELECT COUNT(*) FROM user_workspace "
                + "WHERE source_repository_id IS NULL OR TRIM(source_repository_id) = ''");
        if (missing == 0) return;
        String primary = primaryRepository(connection, true);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE user_workspace SET source_repository_id = ? "
                        + "WHERE source_repository_id IS NULL OR TRIM(source_repository_id) = ''")) {
            statement.setString(1, primary);
            statement.executeUpdate();
        }
    }

    private static void enforceRelationColumns(Connection connection) throws SQLException {
        for (String table : List.of(
                "taxonomy_relation", "relation_proposal", "relation_hypothesis")) {
            if (!tableExists(connection, table)) continue;
            setNotNull(connection, table, "repository_id");
            setNotNull(connection, table, "workspace_scope_key");
        }
        if (tableExists(connection, "relation_hypothesis")) {
            setNotNull(connection, "relation_hypothesis", "analysis_session_scope_key");
        }
    }

    static void bindPortfolio(Connection connection) throws SQLException {
        if (!tableExists(connection, "arch_project")) return;
        for (String table : ROOTS) {
            requireTable(connection, table);
            widenScopeKey(connection, table);
            ensureColumn(connection, table, "repository_id", "VARCHAR(255)");
            ensureColumn(connection, table, "workspace_scope", "VARCHAR(320)");
            ensureColumn(connection, table, "branch_name", "VARCHAR(255)");
        }
        String primary = primaryRepository(connection, false);
        String defaultBranch = primary == null ? null : primaryBranch(connection, primary);
        Map<String, Tenant> workspaces = workspaceTenants(connection);
        for (String table : ROOTS) {
            rejectTargetBusinessKeyCollisions(connection, table, primary, defaultBranch, workspaces);
            for (Row row : rows(connection, table)) {
                String workspaceId = row.text("workspace_id");
                Tenant tenant;
                String previous = row.text("scope_key");
                if (RepositoryTenantIdentity.isEncoded(previous)) {
                    // A completed tenant is immutable. Workspace's current branch
                    // can advance independently after this row was created.
                    var identity = RepositoryTenantIdentity.parse(previous);
                    tenant = new Tenant(identity.repositoryId(), identity.workspaceScope(),
                            identity.branch());
                    String expectedScope = workspaceId == null
                            ? RepositoryTenantIdentity.CENTRAL_SCOPE
                            : RepositoryTenantIdentity.WORKSPACE_SCOPE_PREFIX + workspaceId;
                    if (!expectedScope.equals(tenant.workspaceScope())) {
                        throw unsafe(table + " row " + row.id() + " conflicts with workspace_id");
                    }
                } else {
                    tenant = workspaceId == null
                            ? centralTenant(primary, defaultBranch, table, row.id())
                            : workspaces.get(workspaceId);
                }
                if (tenant == null) {
                    throw unsafe(table + " row " + row.id()
                            + " has an unknown or unbound workspace " + workspaceId);
                }
                requireMatch(row, "repository_id", tenant.repositoryId(), table);
                requireMatch(row, "workspace_scope", tenant.workspaceScope(), table);
                requireMatch(row, "branch_name", tenant.branch(), table);
                if (RepositoryTenantIdentity.isEncoded(previous)
                        && !previous.equals(tenant.scopeKey())) {
                    throw unsafe(table + " row " + row.id() + " has a conflicting tenant scope");
                }
                update(connection, "UPDATE " + table
                                + " SET repository_id=?, workspace_scope=?, branch_name=?, scope_key=? WHERE id=?",
                        tenant.repositoryId(), tenant.workspaceScope(), tenant.branch(),
                        tenant.scopeKey(), row.id());
            }
            for (String column : List.of("repository_id", "workspace_scope", "branch_name")) {
                setNotNull(connection, table, column);
            }
            ensureForeignKey(connection, table, "repository_id", "system_repository",
                    "repository_id", "fk_hsql_" + table + "_repository");
        }

        Map<String, Row> projects = indexedRows(connection, "arch_project");
        bindChild(connection, "project_requirement", "project_id", projects);
        Map<String, Row> requirements = indexedRows(connection, "project_requirement");
        bindChild(connection, "project_req_version", "requirement_id", requirements);
        Map<String, Row> versions = indexedRows(connection, "project_req_version");
        if (columnExists(connection, "project_requirement", "current_version_id")) {
            for (Row requirement : rows(connection, "project_requirement")) {
                String pointer = requirement.text("current_version_id");
                if (pointer == null) continue;
                Row version = versions.get(pointer);
                if (version == null
                        || !requirement.id().toString().equals(version.text("requirement_id"))
                        || !requirement.text("scope_key").equals(version.text("scope_key"))) {
                    throw unsafe("requirement " + requirement.id()
                            + " points to a version outside its exact tenant");
                }
            }
        }
        bindChild(connection, "req_analysis_job", "project_id", projects);
        Map<String, Row> jobs = indexedRows(connection, "req_analysis_job");

        ensureColumn(connection, "req_analysis_item", "project_id", "BIGINT");
        ensureScopeKey(connection, "req_analysis_item");
        for (Row item : rows(connection, "req_analysis_item")) {
            Row job = requireParent(jobs, item, "job_id", "req_analysis_item");
            Row requirement = requireParent(requirements, item, "requirement_id", "req_analysis_item");
            Row version = requireParent(versions, item, "requirement_version_id", "req_analysis_item");
            String projectId = job.text("project_id");
            if (!projectId.equals(requirement.text("project_id"))
                    || !requirement.id().toString().equals(version.text("requirement_id"))) {
                throw unsafe("analysis item " + item.id() + " has inconsistent job/requirement/version parents");
            }
            requireScope(item, job.text("scope_key"), "req_analysis_item");
            requireScope(requirement, job.text("scope_key"), "project_requirement");
            requireScope(version, job.text("scope_key"), "project_req_version");
            if (item.get("project_id") != null && !projectId.equals(item.text("project_id"))) {
                throw unsafe("analysis item " + item.id() + " has a conflicting project");
            }
            update(connection, "UPDATE req_analysis_item SET project_id=?, scope_key=? WHERE id=?",
                    job.get("project_id"), job.get("scope_key"), item.id());
        }
        setNotNull(connection, "req_analysis_item", "scope_key");
        setNotNull(connection, "req_analysis_item", "project_id");

        ensureScopeKey(connection, "req_analysis_snapshot");
        for (Row snapshot : rows(connection, "req_analysis_snapshot")) {
            Row project = requireParent(projects, snapshot, "project_id", "req_analysis_snapshot");
            Row requirement = requireParent(requirements, snapshot, "requirement_id", "req_analysis_snapshot");
            Row version = requireParent(versions, snapshot, "requirement_version_id", "req_analysis_snapshot");
            Row job = requireParent(jobs, snapshot, "job_id", "req_analysis_snapshot");
            if (!project.id().toString().equals(requirement.text("project_id"))
                    || !project.id().toString().equals(job.text("project_id"))
                    || !requirement.id().toString().equals(version.text("requirement_id"))) {
                throw unsafe("analysis snapshot " + snapshot.id() + " has inconsistent parents");
            }
            for (Row parent : List.of(requirement, version, job)) {
                requireScope(parent, project.text("scope_key"), "req_analysis_snapshot parent");
            }
            requireScope(snapshot, project.text("scope_key"), "req_analysis_snapshot");
            update(connection, "UPDATE req_analysis_snapshot SET scope_key=? WHERE id=?",
                    project.get("scope_key"), snapshot.id());
        }
        setNotNull(connection, "req_analysis_snapshot", "scope_key");
        Map<String, Row> snapshots = indexedRows(connection, "req_analysis_snapshot");
        if (columnExists(connection, "project_requirement", "current_snapshot_id")) {
            for (Row requirement : rows(connection, "project_requirement")) {
                String pointer = requirement.text("current_snapshot_id");
                if (pointer == null) continue;
                Row snapshot = snapshots.get(pointer);
                if (snapshot == null
                        || !requirement.id().toString().equals(snapshot.text("requirement_id"))
                        || !requirement.text("project_id").equals(snapshot.text("project_id"))
                        || !requirement.text("scope_key").equals(snapshot.text("scope_key"))) {
                    throw unsafe("requirement " + requirement.id()
                            + " points to a snapshot outside its exact tenant");
                }
            }
        }
        if (columnExists(connection, "req_analysis_item", "snapshot_id")) {
            for (Row item : rows(connection, "req_analysis_item")) {
                String pointer = item.text("snapshot_id");
                if (pointer == null) continue;
                Row snapshot = snapshots.get(pointer);
                if (snapshot == null
                        || !item.text("job_id").equals(snapshot.text("job_id"))
                        || !item.text("requirement_id").equals(snapshot.text("requirement_id"))
                        || !item.text("requirement_version_id")
                                .equals(snapshot.text("requirement_version_id"))
                        || !item.text("project_id").equals(snapshot.text("project_id"))
                        || !item.text("scope_key").equals(snapshot.text("scope_key"))) {
                    throw unsafe("analysis item " + item.id()
                            + " points to a snapshot outside its exact work identity");
                }
            }
        }
        bindChild(connection, "req_element_mapping", "snapshot_id", snapshots);
        bindChild(connection, "req_relation_mapping", "snapshot_id", snapshots);
        enforcePortfolioParentKeys(connection);
    }

    /** Non-cyclic tenant keys; current-version/snapshot pointers remain nullable. */
    private static void enforcePortfolioParentKeys(Connection connection) throws SQLException {
        for (String table : ROOTS) {
            ensureUnique(connection, table, "uq_hsql_" + table + "_id_scope",
                    "id, scope_key");
        }
        ensureUnique(connection, "project_requirement", "uq_hsql_req_id_scope",
                "id, scope_key");
        ensureUnique(connection, "project_requirement", "uq_hsql_req_id_project_scope",
                "id, project_id, scope_key");
        ensureUnique(connection, "project_req_version", "uq_hsql_version_id_scope",
                "id, scope_key");
        ensureUnique(connection, "project_req_version", "uq_hsql_version_id_req_scope",
                "id, requirement_id, scope_key");
        ensureUnique(connection, "req_analysis_job", "uq_hsql_job_id_scope",
                "id, scope_key");
        ensureUnique(connection, "req_analysis_job", "uq_hsql_job_id_project_scope",
                "id, project_id, scope_key");
        ensureUnique(connection, "req_analysis_snapshot", "uq_hsql_snapshot_id_scope",
                "id, scope_key");

        ensureCompositeForeignKey(connection, "project_requirement", "fk_hsql_req_project_scope",
                "project_id, scope_key", "arch_project", "id, scope_key");
        ensureCompositeForeignKey(connection, "project_req_version", "fk_hsql_version_req_scope",
                "requirement_id, scope_key", "project_requirement", "id, scope_key");
        if (columnExists(connection, "project_requirement", "current_version_id")) {
            ensureCompositeForeignKey(connection, "project_requirement",
                    "fk_hsql_req_current_version_scope",
                    "current_version_id, id, scope_key", "project_req_version",
                    "id, requirement_id, scope_key");
        }
        ensureCompositeForeignKey(connection, "req_analysis_job", "fk_hsql_job_project_scope",
                "project_id, scope_key", "arch_project", "id, scope_key");
        ensureCompositeForeignKey(connection, "req_analysis_item", "fk_hsql_item_job_scope",
                "job_id, project_id, scope_key", "req_analysis_job",
                "id, project_id, scope_key");
        ensureCompositeForeignKey(connection, "req_analysis_item", "fk_hsql_item_req_scope",
                "requirement_id, project_id, scope_key", "project_requirement",
                "id, project_id, scope_key");
        ensureCompositeForeignKey(connection, "req_analysis_item", "fk_hsql_item_version_scope",
                "requirement_version_id, requirement_id, scope_key", "project_req_version",
                "id, requirement_id, scope_key");
        ensureCompositeForeignKey(connection, "req_analysis_snapshot", "fk_hsql_snapshot_project_scope",
                "project_id, scope_key", "arch_project", "id, scope_key");
        ensureCompositeForeignKey(connection, "req_analysis_snapshot", "fk_hsql_snapshot_req_scope",
                "requirement_id, project_id, scope_key", "project_requirement",
                "id, project_id, scope_key");
        ensureCompositeForeignKey(connection, "req_analysis_snapshot", "fk_hsql_snapshot_version_scope",
                "requirement_version_id, requirement_id, scope_key", "project_req_version",
                "id, requirement_id, scope_key");
        ensureCompositeForeignKey(connection, "req_analysis_snapshot", "fk_hsql_snapshot_job_scope",
                "job_id, project_id, scope_key", "req_analysis_job",
                "id, project_id, scope_key");
        for (String table : List.of("req_element_mapping", "req_relation_mapping")) {
            ensureCompositeForeignKey(connection, table, "fk_hsql_" + table + "_snapshot_scope",
                    "snapshot_id, scope_key", "req_analysis_snapshot", "id, scope_key");
        }
    }

    private static void ensureUnique(Connection connection, String table, String name,
                                     String columns) throws SQLException {
        if (!uniqueKeyExists(connection, table, columns)) {
            execute(connection, "ALTER TABLE " + table + " ADD CONSTRAINT " + name
                    + " UNIQUE (" + columns + ")");
        }
    }

    private static boolean uniqueKeyExists(Connection connection, String table, String columns)
            throws SQLException {
        Set<String> expected = Set.of(columnNames(columns));
        Map<String, Set<String>> definitions = new HashMap<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                null, "PUBLIC", table.toUpperCase(Locale.ROOT), true, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (name != null && column != null && !indexes.getBoolean("NON_UNIQUE")) {
                    definitions.computeIfAbsent(name, ignored -> new HashSet<>())
                            .add(column.toUpperCase(Locale.ROOT));
                }
            }
        }
        // Hibernate and the upgrade may use different names for the same key.
        // Uniqueness applies to the column set, regardless of index ordering.
        return definitions.values().stream().anyMatch(expected::equals);
    }

    private static void ensureCompositeForeignKey(Connection connection, String table,
                                                   String name, String columns,
                                                   String parent, String parentColumns)
            throws SQLException {
        String[] childColumns = columnNames(columns);
        String[] referencedColumns = columnNames(parentColumns);
        Map<String, String> expected = new HashMap<>();
        for (int i = 0; i < childColumns.length; i++) {
            expected.put(childColumns[i], referencedColumns[i]);
        }
        Map<String, Map<String, String>> definitions = new HashMap<>();
        try (ResultSet keys = connection.getMetaData().getImportedKeys(null, "PUBLIC",
                table.toUpperCase(Locale.ROOT))) {
            while (keys.next()) {
                if (parent.equalsIgnoreCase(keys.getString("PKTABLE_NAME"))
                        && "PUBLIC".equalsIgnoreCase(keys.getString("PKTABLE_SCHEM"))) {
                    definitions.computeIfAbsent(keys.getString("FK_NAME"), ignored -> new HashMap<>())
                            .put(keys.getString("FKCOLUMN_NAME").toUpperCase(Locale.ROOT),
                                    keys.getString("PKCOLUMN_NAME").toUpperCase(Locale.ROOT));
                }
            }
        }
        if (definitions.values().stream().anyMatch(expected::equals)) return;
        execute(connection, "ALTER TABLE " + table + " ADD CONSTRAINT " + name
                + " FOREIGN KEY (" + columns + ") REFERENCES " + parent
                + " (" + parentColumns + ")");
    }

    private static String[] columnNames(String columns) {
        return java.util.Arrays.stream(columns.split(","))
                .map(String::trim).map(column -> column.toUpperCase(Locale.ROOT))
                .toArray(String[]::new);
    }

    private static void bindChild(Connection connection, String table, String parentColumn,
                                  Map<String, Row> parents) throws SQLException {
        requireTable(connection, table);
        ensureScopeKey(connection, table);
        for (Row row : rows(connection, table)) {
            Row parent = requireParent(parents, row, parentColumn, table);
            String scope = parent.text("scope_key");
            requireScope(row, scope, table);
            update(connection, "UPDATE " + table + " SET scope_key=? WHERE id=?", scope, row.id());
        }
        setNotNull(connection, table, "scope_key");
    }

    private static Row requireParent(Map<String, Row> parents, Row child,
                                     String parentColumn, String table) {
        Row parent = parents.get(child.text(parentColumn));
        if (parent == null) {
            throw unsafe(table + " row " + child.id()
                    + " has no exact " + parentColumn + " parent");
        }
        return parent;
    }

    private static void requireScope(Row row, String expected, String table) {
        String current = row.text("scope_key");
        if (current != null && !current.equals(expected)) {
            throw unsafe(table + " row " + row.id() + " has a conflicting tenant scope");
        }
    }

    private static void rejectTargetBusinessKeyCollisions(
            Connection connection, String table, String primary, String defaultBranch,
            Map<String, Tenant> workspaces) throws SQLException {
        String key = switch (table) {
            case "arch_project" -> "project_key";
            case "solution_definition" -> "solution_key";
            case "product_catalog" -> "product_key";
            default -> throw new IllegalArgumentException(table);
        };
        Set<String> identities = new HashSet<>();
        for (Row row : rows(connection, table)) {
            String previous = row.text("scope_key");
            String workspaceId = row.text("workspace_id");
            Tenant tenant;
            if (RepositoryTenantIdentity.isEncoded(previous)) {
                var parsed = RepositoryTenantIdentity.parse(previous);
                tenant = new Tenant(parsed.repositoryId(), parsed.workspaceScope(), parsed.branch());
            } else {
                tenant = workspaceId == null
                        ? centralTenant(primary, defaultBranch, table, row.id())
                        : workspaces.get(workspaceId);
            }
            if (tenant == null) {
                throw unsafe(table + " row " + row.id()
                        + " has an unknown or unbound workspace " + workspaceId);
            }
            String value = row.text(key);
            if (value == null || value.isBlank()) {
                throw unsafe(table + " row " + row.id() + " has no " + key);
            }
            String identity = tenant.scopeKey() + "\u0000" + value.toLowerCase(Locale.ROOT);
            if (!identities.add(identity)) {
                throw unsafe(table + " contains ambiguous " + key
                        + " values in tenant " + tenant.scopeKey());
            }
        }
    }

    private static Tenant centralTenant(String primary, String branch,
                                        String table, Object rowId) {
        if (primary == null || branch == null || branch.isBlank()) {
            throw unsafe(table + " row " + rowId + " requires exactly one primary repository and branch");
        }
        return new Tenant(primary, RepositoryTenantIdentity.CENTRAL_SCOPE, branch);
    }

    private static Map<String, Tenant> workspaceTenants(Connection connection)
            throws SQLException {
        Map<String, Tenant> tenants = new HashMap<>();
        for (Row row : rows(connection, "user_workspace")) {
            String repository = row.text("source_repository_id");
            String branch = row.text("current_branch");
            String workspaceId = row.text("workspace_id");
            if (repository != null && branch != null && workspaceId != null) {
                tenants.put(workspaceId, new Tenant(repository,
                        RepositoryTenantIdentity.WORKSPACE_SCOPE_PREFIX + workspaceId, branch));
            }
        }
        return tenants;
    }

    private static String primaryRepository(Connection connection, boolean required)
            throws SQLException {
        String result = null;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT repository_id FROM system_repository WHERE primary_repo = TRUE")) {
            while (rows.next()) {
                if (result != null) throw unsafe("more than one primary repository");
                result = rows.getString(1);
            }
        }
        if (required && (result == null || result.isBlank())) {
            throw unsafe("unbound workspace requires exactly one primary repository");
        }
        return result;
    }

    private static String primaryBranch(Connection connection, String repository)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT default_branch FROM system_repository WHERE repository_id=?")) {
            statement.setString(1, repository);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        }
    }

    private static void requireMatch(Row row, String column, String expected, String table) {
        String actual = row.text(column);
        if (actual != null && !actual.equals(expected)) {
            throw unsafe(table + " row " + row.id() + " has conflicting " + column);
        }
    }

    private static void ensureScopeKey(Connection connection, String table) throws SQLException {
        ensureColumn(connection, table, "scope_key", "VARCHAR(1024)");
        widenScopeKey(connection, table);
    }

    private static void widenScopeKey(Connection connection, String table) throws SQLException {
        if (columnExists(connection, table, "scope_key")
                && columnSize(connection, table, "scope_key") < 1024) {
            execute(connection, "ALTER TABLE " + table
                    + " ALTER COLUMN scope_key SET DATA TYPE VARCHAR(1024)");
        }
    }

    private static void ensureColumn(Connection connection, String table, String column,
                                     String definition) throws SQLException {
        if (tableExists(connection, table) && !columnExists(connection, table, column)) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static void setNotNull(Connection connection, String table, String column)
            throws SQLException {
        if (!columnExists(connection, table, column)) {
            throw unsafe(table + " missing expected column " + column);
        }
        if (count(connection, "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IS NULL") > 0) {
            throw unsafe(table + "." + column + " still has unbound rows");
        }
        if (columnNullable(connection, table, column)) {
            execute(connection, "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL");
        }
    }

    private static void ensureForeignKey(Connection connection, String table, String column,
                                         String parentTable, String parentColumn, String name)
            throws SQLException {
        try (ResultSet keys = connection.getMetaData().getImportedKeys(null, "PUBLIC",
                table.toUpperCase(Locale.ROOT))) {
            while (keys.next()) {
                if (column.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                        && parentTable.equalsIgnoreCase(keys.getString("PKTABLE_NAME"))
                        && parentColumn.equalsIgnoreCase(keys.getString("PKCOLUMN_NAME"))) return;
            }
        }
        execute(connection, "ALTER TABLE " + table + " ADD CONSTRAINT " + name
                + " FOREIGN KEY (" + column + ") REFERENCES " + parentTable
                + " (" + parentColumn + ")");
    }

    private static void requireTable(Connection connection, String table) throws SQLException {
        if (!tableExists(connection, table)) throw unsafe("partial legacy portfolio: missing " + table);
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null,
                table.toUpperCase(Locale.ROOT), new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(null, null,
                table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT))) {
            return columns.next();
        }
    }

    private static int columnSize(Connection connection, String table, String column)
            throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null,
                table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT))) {
            if (!columns.next()) throw unsafe(table + " missing " + column);
            return columns.getInt("COLUMN_SIZE");
        }
    }

    private static boolean columnNullable(Connection connection, String table, String column)
            throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null,
                table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT))) {
            if (!columns.next()) throw unsafe(table + " missing " + column);
            return columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
        }
    }

    private static Map<String, Row> indexedRows(Connection connection, String table)
            throws SQLException {
        Map<String, Row> result = new HashMap<>();
        for (Row row : rows(connection, table)) result.put(row.id().toString(), row);
        return result;
    }

    private static List<Row> rows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
            var rows = new java.util.ArrayList<Row>();
            while (result.next()) {
                Map<String, Object> columns = new HashMap<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                    columns.put(result.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT),
                            result.getObject(i));
                }
                rows.add(new Row(columns));
            }
            return rows;
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static IllegalStateException unsafe(String message) {
        return new IllegalStateException("Unsafe HSQLDB 1.3 upgrade: " + message
                + "; restore the stopped-writer backup before retrying");
    }

    private record Tenant(String repositoryId, String workspaceScope, String branch) {
        String scopeKey() {
            return new RepositoryTenantIdentity(repositoryId, workspaceScope, branch).scopeKey();
        }
    }

    private record Row(Map<String, Object> values) {
        Object id() { return get("id"); }
        Object get(String column) { return values.get(column); }
        String text(String column) {
            Object value = get(column);
            return value == null ? null : value.toString().trim();
        }
    }
}
