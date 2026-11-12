package com.taxonomy.composition.persistence;

import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HsqlLegacyUpgradeMigratorTest {

    @Test
    void ordinaryHibernateUpdateUpgradesAnExistingWorkspaceWithoutChangingItsCapturedSource(@TempDir Path directory)
            throws Exception {
        String database = "jdbc:hsqldb:file:" + directory.resolve("old-workspace") + ";shutdown=true";
        var existing = new DriverManagerDataSource(database, "SA", "");
        var business = Flyway.configure().dataSource(existing).locations("classpath:db/restore/hsqldb");
        business.target("1").load().migrate();
        PrincipalSchemaMigration.migrate(existing);
        business.target("19").load().migrate();
        String commit = "a".repeat(40);
        try (var connection = DriverManager.getConnection(database, "SA", "");
             var insert = connection.prepareStatement("""
                     insert into user_workspace (workspace_id, username, current_branch, created_at,
                         provisioning_status, topology_mode, archived, is_default, shared,
                         source_repository_id, source_branch, base_commit)
                     values ('existing-copy','owner','draft',CURRENT_TIMESTAMP,
                         'PROVISIONING','INTERNAL_SHARED',false,false,false,'original-source','main',?)
                     """)) {
            insert.setString(1, commit);
            assertEquals(1, insert.executeUpdate());
        }

        // This is the regular Hibernate update path on an existing HSQL installation.
        try (var factory = new Configuration().addAnnotatedClass(UserWorkspace.class)
                .setProperty("hibernate.connection.url", database)
                .setProperty("hibernate.connection.username", "SA")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.search.enabled", "false")
                .buildSessionFactory()) {
            var retained = factory.fromTransaction(session -> session.createSelectionQuery(
                            "from UserWorkspace where workspaceId = :id", UserWorkspace.class)
                    .setParameter("id", "existing-copy").getSingleResult());
            assertEquals(0, retained.getProvisioningAttempt());
            assertEquals(WorkspaceProvisioningStatus.PROVISIONING, retained.getProvisioningStatus());
            assertEquals("original-source", retained.getSourceRepositoryId());
            assertEquals("main", retained.getSourceBranch());
            assertEquals(commit, retained.getBaseCommit());
            assertNull(retained.getProvisioningLeaseOwner());
            assertNull(retained.getProvisioningLeaseUntil());
            assertNull(retained.getProvisioningAttemptAt());
        }
    }

    @Test
    void preHibernateHookUsesRecordedHypothesisProjectAndPreservesItAfterWorkspaceChanges() throws Exception {
        try(Connection connection=legacySchema()) {
            execute(connection,"INSERT INTO arch_project VALUES (1,'known','old','w-1')");
            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);
            hypothesisTable(connection);
            execute(connection,"INSERT INTO relation_hypothesis VALUES (1,'primary','w-1','BP','CP','SUPPORTS','session',1)");
            execute(connection,"UPDATE user_workspace SET current_branch='changed'");
            var database=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            HsqlLegacyUpgradeMigrator.migrate(database);
            HsqlLegacyUpgradeMigrator.migrate(database);
            assertEquals("draft",scalar(connection,"SELECT branch_name FROM relation_hypothesis"));
            assertFalse(nullable(connection,"RELATION_HYPOTHESIS","BRANCH_NAME"));
        }
    }

    @Test
    void preHibernateHookRejectsUnlinkedLegacyHypothesisBeforePortfolioInference() throws Exception {
        try(Connection connection=legacySchema()) {
            hypothesisTable(connection);
            execute(connection,"INSERT INTO relation_hypothesis VALUES (1,'primary','w-1','BP','CP','SUPPORTS','session',NULL)");
            var database=new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true);
            var error=assertThrows(IllegalStateException.class,()->HsqlLegacyUpgradeMigrator.migrate(database));
            org.assertj.core.api.Assertions.assertThat(error).hasStackTraceContaining("missing_provenance");
            assertEquals("1",scalar(connection,"SELECT COUNT(*) FROM relation_hypothesis"));
        }
    }

    private void hypothesisTable(Connection connection) throws Exception {
        execute(connection,"CREATE TABLE relation_hypothesis(id BIGINT PRIMARY KEY,repository_id VARCHAR(255),workspace_id VARCHAR(255),source_node_id VARCHAR(255),target_node_id VARCHAR(255),relation_type VARCHAR(255),analysis_session_id VARCHAR(255),project_id BIGINT)");
    }

    @Test
    void bindsPopulatedLegacyParentChainAndPreservesBranchAfterRestart() throws Exception {
        try (Connection connection = legacySchema()) {
            execute(connection, "INSERT INTO arch_project VALUES (1, 'central', 'old-central', NULL)");
            execute(connection, "INSERT INTO arch_project VALUES (2, 'workspace', 'old-workspace', 'w-1')");
            execute(connection, "INSERT INTO project_requirement VALUES (10, 2)");
            execute(connection, "INSERT INTO project_req_version VALUES (20, 10)");
            execute(connection, "INSERT INTO req_analysis_job VALUES ('job-1', 2)");
            execute(connection, "INSERT INTO req_analysis_item VALUES (30, 'job-1', 10, 20)");
            execute(connection, "INSERT INTO req_analysis_snapshot VALUES ('snapshot-1', 2, 10, 20, 'job-1')");
            execute(connection, "INSERT INTO req_element_mapping VALUES (40, 'snapshot-1')");
            execute(connection, "INSERT INTO req_relation_mapping VALUES (50, 'snapshot-1')");

            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);
            String expected = new RepositoryTenantIdentity("primary", "WORKSPACE:w-1", "draft")
                    .scopeKey();
            assertEquals(expected, scalar(connection, "SELECT scope_key FROM arch_project WHERE id=2"));
            for (String table : new String[]{"project_requirement", "project_req_version",
                    "req_analysis_job", "req_analysis_item", "req_analysis_snapshot",
                    "req_element_mapping", "req_relation_mapping"}) {
                assertEquals(expected, scalar(connection, "SELECT scope_key FROM " + table), table);
            }
            assertEquals("2", scalar(connection,
                    "SELECT project_id FROM req_analysis_item WHERE id=30"));
            assertFalse(nullable(connection, "REQ_ANALYSIS_ITEM", "SCOPE_KEY"));
            assertFalse(nullable(connection, "ARCH_PROJECT", "REPOSITORY_ID"));

            execute(connection, "UPDATE user_workspace SET current_branch='review' WHERE workspace_id='w-1'");
            execute(connection, "UPDATE system_repository SET default_branch='main' WHERE repository_id='primary'");
            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);
            assertEquals(expected, scalar(connection, "SELECT scope_key FROM arch_project WHERE id=2"));
            assertEquals(expected, scalar(connection, "SELECT scope_key FROM req_analysis_snapshot"));
            assertEquals(new RepositoryTenantIdentity("primary", "CENTRAL", "draft").scopeKey(),
                    scalar(connection, "SELECT scope_key FROM arch_project WHERE id=1"));
        }
    }

    @Test
    void rejectsInconsistentExistingChildScopeInsteadOfReassigningIt() throws Exception {
        try (Connection connection = legacySchema()) {
            execute(connection, "INSERT INTO arch_project VALUES (2, 'workspace', 'old-workspace', 'w-1')");
            execute(connection, "ALTER TABLE project_requirement ADD COLUMN scope_key VARCHAR(1024)");
            execute(connection, "INSERT INTO project_requirement VALUES (10, 2, "
                    + "'v2|r7:primary|s7:CENTRAL|b5:draft')");
            var error = assertThrows(IllegalStateException.class,
                    () -> HsqlLegacyUpgradeMigrator.bindPortfolio(connection));
            assertTrue(error.getMessage().contains("conflicting tenant scope"));
        }
    }

    @Test
    void rejectsCaseFoldedDuplicateCentralBusinessKeys() throws Exception {
        try (Connection connection = legacySchema()) {
            execute(connection, "INSERT INTO arch_project VALUES (1, 'Same', 'old-1', NULL)");
            execute(connection, "INSERT INTO arch_project VALUES (2, ' same ', 'old-2', NULL)");
            var error = assertThrows(IllegalStateException.class,
                    () -> HsqlLegacyUpgradeMigrator.bindPortfolio(connection));
            assertTrue(error.getMessage().contains("ambiguous project_key"));
        }
    }

    @Test
    void rejectsAnalysisItemWhoseVersionBelongsToAnotherRequirement() throws Exception {
        try (Connection connection = legacySchema()) {
            execute(connection, "INSERT INTO arch_project VALUES (2, 'workspace', 'old-workspace', 'w-1')");
            execute(connection, "INSERT INTO project_requirement VALUES (10, 2)");
            execute(connection, "INSERT INTO project_requirement VALUES (11, 2)");
            execute(connection, "INSERT INTO project_req_version VALUES (20, 11)");
            execute(connection, "INSERT INTO req_analysis_job VALUES ('job-1', 2)");
            execute(connection, "INSERT INTO req_analysis_item VALUES (30, 'job-1', 10, 20)");

            var error = assertThrows(IllegalStateException.class,
                    () -> HsqlLegacyUpgradeMigrator.bindPortfolio(connection));
            assertTrue(error.getMessage().contains("inconsistent job/requirement/version parents"));
        }
    }

    @Test
    void existingCentralTenantsOnDifferentBranchesMayShareBusinessKey() throws Exception {
        try (Connection connection = legacySchema()) {
            String draft = new RepositoryTenantIdentity("primary", "CENTRAL", "draft").scopeKey();
            String review = new RepositoryTenantIdentity("primary", "CENTRAL", "review").scopeKey();
            execute(connection, "INSERT INTO arch_project VALUES (1, 'Same', '" + draft + "', NULL)");
            execute(connection, "INSERT INTO arch_project VALUES (2, 'same', '" + review + "', NULL)");
            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);
            assertEquals(draft, scalar(connection, "SELECT scope_key FROM arch_project WHERE id=1"));
            assertEquals(review, scalar(connection, "SELECT scope_key FROM arch_project WHERE id=2"));
        }
    }

    @Test
    void legacyAndBoundRowsCollidingInSameTenantFailClosed() throws Exception {
        try (Connection connection = legacySchema()) {
            String draft = new RepositoryTenantIdentity("primary", "CENTRAL", "draft").scopeKey();
            execute(connection, "INSERT INTO arch_project VALUES (1, 'Same', '" + draft + "', NULL)");
            execute(connection, "INSERT INTO arch_project VALUES (2, ' same ', 'legacy', NULL)");
            var error = assertThrows(IllegalStateException.class,
                    () -> HsqlLegacyUpgradeMigrator.bindPortfolio(connection));
            assertTrue(error.getMessage().contains("ambiguous project_key"));
        }
    }

    @Test
    void reusesEquivalentHibernateKeysInsteadOfAddingDuplicateConstraints() throws Exception {
        try (Connection connection = legacySchema()) {
            String scope = new RepositoryTenantIdentity("primary", "WORKSPACE:w-1", "draft").scopeKey();
            execute(connection, "ALTER TABLE project_requirement ADD COLUMN scope_key VARCHAR(1024)");
            execute(connection, "ALTER TABLE arch_project ADD CONSTRAINT hibernate_project_tenant "
                    + "UNIQUE (id, scope_key)");
            execute(connection, "ALTER TABLE project_requirement ADD CONSTRAINT hibernate_requirement_tenant "
                    + "UNIQUE (id, scope_key)");
            execute(connection, "ALTER TABLE project_requirement ADD CONSTRAINT hibernate_requirement_project "
                    + "FOREIGN KEY (project_id, scope_key) REFERENCES arch_project (id, scope_key)");
            execute(connection, "INSERT INTO arch_project VALUES (2, 'workspace', '" + scope + "', 'w-1')");
            execute(connection, "INSERT INTO project_requirement VALUES (10, 2, '" + scope + "')");

            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);
            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);

            assertEquals(scope, scalar(connection, "SELECT scope_key FROM project_requirement WHERE id=10"));
            var foreignKeys = new java.util.HashSet<String>();
            try (ResultSet keys = connection.getMetaData().getImportedKeys(
                    null, "PUBLIC", "PROJECT_REQUIREMENT")) {
                while (keys.next()) foreignKeys.add(keys.getString("FK_NAME"));
            }
            assertEquals(java.util.Set.of("HIBERNATE_REQUIREMENT_PROJECT"), foreignKeys);
            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE project_requirement SET scope_key='foreign-tenant' WHERE id=10"));
        }
    }

    @Test
    void rejectsBoundPortfolioRowsWithoutRepositoryCatalogueEntry() throws Exception {
        try (Connection connection = legacySchema()) {
            String scope = new RepositoryTenantIdentity("legacy-primary", "CENTRAL", "draft")
                    .scopeKey();
            execute(connection, "INSERT INTO arch_project VALUES (1, 'orphan', '" + scope + "', NULL)");

            assertThrows(SQLException.class,
                    () -> HsqlLegacyUpgradeMigrator.bindPortfolio(connection));
            assertEquals(scope, scalar(connection, "SELECT scope_key FROM arch_project WHERE id=1"));
            assertEquals("0", scalar(connection,
                    "SELECT COUNT(*) FROM system_repository WHERE repository_id='legacy-primary'"));

            execute(connection, "INSERT INTO system_repository VALUES ('legacy-primary', FALSE, 'draft')");
            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);
            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);
            assertEquals(scope, scalar(connection, "SELECT scope_key FROM arch_project WHERE id=1"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "DELETE FROM system_repository WHERE repository_id='legacy-primary'"));
        }
    }

    @Test
    void completedUpgradeRejectsCurrentVersionPointerToSiblingRequirement() throws Exception {
        try (Connection connection = legacySchema()) {
            execute(connection, "ALTER TABLE project_requirement ADD COLUMN current_version_id BIGINT");
            execute(connection, "INSERT INTO arch_project VALUES (2, 'workspace', 'legacy', 'w-1')");
            execute(connection, "INSERT INTO project_requirement (id,project_id) VALUES (10,2)");
            execute(connection, "INSERT INTO project_requirement (id,project_id) VALUES (11,2)");
            execute(connection, "INSERT INTO project_req_version VALUES (20,11)");
            HsqlLegacyUpgradeMigrator.bindPortfolio(connection);

            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE project_requirement SET current_version_id=20 WHERE id=10"));
        }
    }

    private static Connection legacySchema() throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:mem:upgrade_" + UUID.randomUUID().toString().replace("-", ""),
                "sa", "");
        execute(connection, "CREATE TABLE system_repository (repository_id VARCHAR(255) PRIMARY KEY, "
                + "primary_repo BOOLEAN NOT NULL, default_branch VARCHAR(255))");
        execute(connection, "INSERT INTO system_repository VALUES ('primary', TRUE, 'draft')");
        execute(connection, "CREATE TABLE user_workspace (id BIGINT PRIMARY KEY, "
                + "workspace_id VARCHAR(255), source_repository_id VARCHAR(255), "
                + "current_branch VARCHAR(255))");
        execute(connection, "INSERT INTO user_workspace VALUES (1, 'w-1', 'primary', 'draft')");
        execute(connection, "CREATE TABLE arch_project (id BIGINT PRIMARY KEY, "
                + "project_key VARCHAR(255), scope_key VARCHAR(255), workspace_id VARCHAR(255))");
        execute(connection, "CREATE TABLE solution_definition (id BIGINT PRIMARY KEY, "
                + "solution_key VARCHAR(255), scope_key VARCHAR(255), workspace_id VARCHAR(255))");
        execute(connection, "CREATE TABLE product_catalog (id BIGINT PRIMARY KEY, "
                + "product_key VARCHAR(255), scope_key VARCHAR(255), workspace_id VARCHAR(255))");
        execute(connection, "CREATE TABLE project_requirement (id BIGINT PRIMARY KEY, project_id BIGINT)");
        execute(connection, "CREATE TABLE project_req_version (id BIGINT PRIMARY KEY, requirement_id BIGINT)");
        execute(connection, "CREATE TABLE req_analysis_job (id VARCHAR(255) PRIMARY KEY, project_id BIGINT)");
        execute(connection, "CREATE TABLE req_analysis_item (id BIGINT PRIMARY KEY, "
                + "job_id VARCHAR(255), requirement_id BIGINT, requirement_version_id BIGINT)");
        execute(connection, "CREATE TABLE req_analysis_snapshot (id VARCHAR(255) PRIMARY KEY, "
                + "project_id BIGINT, requirement_id BIGINT, requirement_version_id BIGINT, job_id VARCHAR(255))");
        execute(connection, "CREATE TABLE req_element_mapping (id BIGINT PRIMARY KEY, snapshot_id VARCHAR(255))");
        execute(connection, "CREATE TABLE req_relation_mapping (id BIGINT PRIMARY KEY, snapshot_id VARCHAR(255))");
        return connection;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean nullable(Connection connection, String table, String column)
            throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(null, "PUBLIC", table, column)) {
            assertTrue(result.next());
            return result.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
