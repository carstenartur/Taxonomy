package com.taxonomy.shared.config;

import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retained-schema branch assertion fixture; callers must supply an isolated database. */
public final class HypothesisBranchMigrationContract {
    private HypothesisBranchMigrationContract() { }

    public static void verify(DataSource database) throws Exception {
        verifyInvalidRecordedAuthority(database);
        verifyCommittedPhaseRecovery(database);
        reset(database);
        try {
            createLegacy(database);
            String scope = new RepositoryTenantIdentity("repo", "CENTRAL", "main").scopeKey();
            sql(database, "INSERT INTO arch_project VALUES (10,'repo',NULL,'CENTRAL','main','" + scope + "')");
            sql(database, "INSERT INTO project_requirement VALUES (20,10,'" + scope + "')");
            sql(database, "INSERT INTO req_analysis_snapshot VALUES ('snap',10,20,NULL,'main','" + scope + "')");
            sql(database, "INSERT INTO relation_hypothesis (id,repository_id,source_node_id,target_node_id,relation_type,project_id) VALUES (1,'repo','BP','CP','SUPPORTS',10)");
            sql(database, "INSERT INTO relation_hypothesis (id,repository_id,source_node_id,target_node_id,relation_type,analysis_snapshot_id) VALUES (2,'repo','BP2','CP','SUPPORTS','snap')");
            sql(database, "INSERT INTO relation_evidence (id,hypothesis_id) VALUES (1,1)");
            var migration = new SchemaContractMigration(database);
            migration.migrate();
            migration.migrate();
            assertThat(scalar(database, "SELECT branch_name FROM relation_hypothesis WHERE id=1")).isEqualTo("main");
            assertThat(scalar(database, "SELECT branch_name FROM relation_hypothesis WHERE id=2")).isEqualTo("main");
            assertThat(scalar(database, "SELECT branch_name FROM relation_evidence WHERE id=1")).isEqualTo("main");
            insertExact(database, 3, "Main"); // Git ref comparisons must remain case-sensitive.
            insertExact(database, 4, "draft");
            try (var connection=database.getConnection(); var insert=connection.prepareStatement("INSERT INTO relation_hypothesis (id,repository_id,workspace_scope_key,branch_name,source_node_id,target_node_id,relation_type,analysis_session_scope_key) VALUES (6,'repo','__shared__',?,'BP','CP','SUPPORTS','__unspecified__')")) {
                insert.setNString(1, "レビュー/Δ"); insert.executeUpdate();
            }
            assertThat(scalar(database,"SELECT branch_name FROM relation_hypothesis WHERE id=6")).isEqualTo("レビュー/Δ");
            try(var connection=database.getConnection();var insert=connection.prepareStatement("INSERT INTO relation_hypothesis (id,repository_id,workspace_scope_key,branch_name,source_node_id,target_node_id,relation_type,analysis_session_scope_key) VALUES (7,'repo','__shared__',?,'BP','CP','SUPPORTS','__unspecified__')")) {
                insert.setNString(1,"枝".repeat(255)); insert.executeUpdate();
            }
            assertThat(scalar(database,"SELECT branch_name FROM relation_hypothesis WHERE id=7")).isEqualTo("枝".repeat(255));
            assertThatThrownBy(() -> insertExact(database, 5, "main")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql(database, "UPDATE relation_evidence SET branch_name='draft' WHERE id=1"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql(database, "UPDATE relation_hypothesis SET branch_name=NULL WHERE id=1"))
                    .isInstanceOf(SQLException.class);
            migration.migrate();
            assertThat(scalar(database, "SELECT branch_name FROM relation_hypothesis WHERE id=3")).isEqualTo("Main");
            // Existing branch is authority on restart, but explicit linked disagreement cannot pass.
            sql(database, "UPDATE relation_hypothesis SET project_id=10 WHERE id=4");
            assertThatThrownBy(migration::migrate).hasStackTraceContaining("branch_scope_mismatch");
            assertThat(scalar(database, "SELECT branch_name FROM relation_hypothesis WHERE id=4")).isEqualTo("draft");
        } finally { reset(database); }
        try {
            createLegacy(database);
            for (int id=1; id<=25; id++) sql(database, "INSERT INTO relation_hypothesis (id,repository_id,source_node_id,target_node_id,relation_type) VALUES (" + id + ",'repo','BP" + id + "','CP','SUPPORTS')");
            assertThatThrownBy(() -> new SchemaContractMigration(database).migrate())
                    .hasStackTraceContaining("missing_provenance=25")
                    .hasStackTraceContaining("row IDs (at most 20)=[1, 2, 3")
                    .hasStackTraceContaining("19, 20]")
                    .hasStackTraceContaining("Data retained");
            assertThat(scalar(database, "SELECT COUNT(*) FROM relation_hypothesis")).isEqualTo("25");
        } finally { reset(database); }
    }

    private static void verifyInvalidRecordedAuthority(DataSource database) throws Exception {
        for(String reason:List.of("missing_snapshot","missing_project","repository_mismatch","workspace_mismatch",
                "branch_scope_mismatch","missing_requirement","missing_evidence_parent","conflicting_evidence_scope")) {
            reset(database); createLegacy(database);
            String scope=new RepositoryTenantIdentity("repo","CENTRAL","main").scopeKey();
            sql(database,"INSERT INTO arch_project VALUES (10,'repo',NULL,'CENTRAL','main','"+scope+"')");
            sql(database,"INSERT INTO relation_hypothesis(id,repository_id,source_node_id,target_node_id,relation_type,project_id) VALUES(1,'repo','BP','CP','SUPPORTS',10)");
            switch(reason) {
                case "missing_snapshot" -> sql(database,"UPDATE relation_hypothesis SET analysis_snapshot_id='missing'");
                case "missing_project" -> sql(database,"UPDATE relation_hypothesis SET project_id=999");
                case "repository_mismatch" -> sql(database,"UPDATE arch_project SET repository_id='other',scope_key='v2|r5:other|s7:CENTRAL|b4:main'");
                case "workspace_mismatch" -> sql(database,"UPDATE arch_project SET workspace_id='other',workspace_scope='WORKSPACE:other',scope_key='v2|r4:repo|s15:WORKSPACE:other|b4:main'");
                case "branch_scope_mismatch" -> sql(database,"UPDATE arch_project SET branch_name='draft'");
                case "missing_requirement" -> sql(database,"UPDATE relation_hypothesis SET requirement_id=999");
                case "missing_evidence_parent" -> sql(database,"INSERT INTO relation_evidence VALUES(1,999)");
                case "conflicting_evidence_scope" -> {
                    sql(database,"ALTER TABLE relation_evidence ADD repository_id VARCHAR(255)");
                    sql(database,"INSERT INTO relation_evidence VALUES(1,1,'other')");
                }
            }
            assertThatThrownBy(()->new SchemaContractMigration(database).migrate()).hasStackTraceContaining(reason);
            assertThat(scalar(database,"SELECT COUNT(*) FROM relation_hypothesis")).isEqualTo("1");
        }
        reset(database);
    }

    private static void verifyCommittedPhaseRecovery(DataSource database) throws Exception {
        String indexFilter;
        try (Connection connection=database.getConnection()) {
            indexFilter=connection.getMetaData().getDatabaseProductName().contains("Microsoft")
                    ? " WHERE repository_id IS NOT NULL AND workspace_scope_key IS NOT NULL AND analysis_session_scope_key IS NOT NULL" : "";
        }
        for(String phase:List.of(" ADD branch_name "," SET branch_name=?", " ADD CONSTRAINT uq_hypothesis_parent_scope", " ADD CONSTRAINT uq_hypothesis_branch_session_relation", " DROP CONSTRAINT "," ADD CONSTRAINT fk_evidence_hypothesis_scope")) {
            reset(database);
            createLegacy(database);
            sql(database,"ALTER TABLE relation_hypothesis ADD workspace_scope_key VARCHAR(255)");
            sql(database,"ALTER TABLE relation_hypothesis ADD analysis_session_scope_key VARCHAR(255)");
            // Keep the committed-drop phase as a constraint; another phase covers an ordinary index.
            String legacyColumns="(repository_id,workspace_scope_key,source_node_id,target_node_id,relation_type,analysis_session_scope_key)";
            sql(database,(phase.equals(" ADD CONSTRAINT uq_hypothesis_parent_scope")
                    ? "CREATE UNIQUE INDEX uq_old_hypothesis ON relation_hypothesis"+legacyColumns+indexFilter
                    : "ALTER TABLE relation_hypothesis ADD CONSTRAINT uq_old_hypothesis UNIQUE"+legacyColumns));
            String scope=new RepositoryTenantIdentity("repo","CENTRAL","main").scopeKey();
            sql(database,"INSERT INTO arch_project VALUES(10,'repo',NULL,'CENTRAL','main','"+scope+"')");
            sql(database,"INSERT INTO relation_hypothesis(id,repository_id,source_node_id,target_node_id,relation_type,project_id) VALUES(1,'repo','BP','CP','SUPPORTS',10)");
            sql(database,"INSERT INTO relation_evidence(id,hypothesis_id) VALUES(1,1)");
            var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
            DataSource failing=new org.springframework.jdbc.datasource.AbstractDataSource() {
                public Connection getConnection() throws SQLException {
                    Connection connection=database.getConnection();
                    return OracleHypothesisSessionMigrationContract.proxy(Connection.class,(method,args)->{
                        Object result=OracleHypothesisSessionMigrationContract.invoke(connection,method,args);
                        if(method.getName().equals("createStatement")) {
                            return OracleHypothesisSessionMigrationContract.proxy(Statement.class,(operation,values)->{
                                Object executed=OracleHypothesisSessionMigrationContract.invoke(result,operation,values);
                                if(operation.getName().equals("execute") && ((String)values[0]).contains(phase)
                                        && interrupted.compareAndSet(false,true)) {
                                    connection.commit(); throw new SQLException("committed phase interruption");
                                }
                                return executed;
                            });
                        }
                        if(method.getName().equals("prepareStatement") && ((String)args[0]).contains(phase)) {
                            return OracleHypothesisSessionMigrationContract.proxy(java.sql.PreparedStatement.class,(operation,values)->{
                                Object executed=OracleHypothesisSessionMigrationContract.invoke(result,operation,values);
                                if(operation.getName().equals("executeUpdate") && interrupted.compareAndSet(false,true)) {
                                    connection.commit(); throw new SQLException("committed phase interruption");
                                }
                                return executed;
                            });
                        }
                        return result;
                    });
                }
                public Connection getConnection(String user,String password) throws SQLException { return getConnection(); }
            };
            assertThatThrownBy(()->new SchemaContractMigration(failing).migrate()).hasStackTraceContaining("committed phase interruption");
            assertThat(interrupted).as(phase).isTrue();
            new SchemaContractMigration(database).migrate();
            new SchemaContractMigration(database).migrate();
            assertThat(scalar(database,"SELECT branch_name FROM relation_hypothesis WHERE id=1")).isEqualTo("main");
            assertThat(scalar(database,"SELECT branch_name FROM relation_evidence WHERE id=1")).isEqualTo("main");
            assertThatThrownBy(()->sql(database,"UPDATE relation_evidence SET branch_name='other' WHERE id=1"))
                    .isInstanceOf(SQLException.class);
        }
        reset(database);
    }

    public static void createLegacy(DataSource database) throws SQLException {
        String product;
        try (Connection c=database.getConnection()) { product=c.getMetaData().getDatabaseProductName(); }
        String bool = product.contains("Oracle") ? "NUMBER(1)" : product.contains("Microsoft") ? "BIT" : "BOOLEAN";
        sql(database, "CREATE TABLE system_repository(repository_id VARCHAR(255) PRIMARY KEY, primary_repo " + bool + ")");
        sql(database, "INSERT INTO system_repository VALUES ('repo'," + (bool.equals("BOOLEAN") ? "true" : "1") + ")");
        sql(database, "CREATE TABLE arch_project(id BIGINT PRIMARY KEY, repository_id VARCHAR(255), workspace_id VARCHAR(255), workspace_scope VARCHAR(320), branch_name VARCHAR(255), scope_key VARCHAR(1024))".replace("BIGINT", product.contains("Oracle") ? "NUMBER(19)" : "BIGINT"));
        sql(database, "CREATE TABLE project_requirement(id INTEGER PRIMARY KEY, project_id INTEGER, scope_key VARCHAR(1024))");
        sql(database, "CREATE TABLE req_analysis_snapshot(id VARCHAR(36) PRIMARY KEY, project_id INTEGER, requirement_id INTEGER, workspace_id VARCHAR(255), branch_name VARCHAR(255), scope_key VARCHAR(1024))");
        sql(database, "CREATE TABLE relation_hypothesis(id BIGINT PRIMARY KEY, repository_id VARCHAR(255), workspace_id VARCHAR(255), source_node_id VARCHAR(255), target_node_id VARCHAR(255), relation_type VARCHAR(255), analysis_session_id VARCHAR(255), project_id INTEGER, requirement_id INTEGER, analysis_snapshot_id VARCHAR(36))".replace("BIGINT", product.contains("Oracle") ? "NUMBER(19)" : "BIGINT"));
        sql(database, "CREATE TABLE relation_evidence(id BIGINT PRIMARY KEY, hypothesis_id BIGINT)".replace("BIGINT", product.contains("Oracle") ? "NUMBER(19)" : "BIGINT"));
    }
    private static void insertExact(DataSource database, int id, String branch) throws SQLException {
        sql(database, "INSERT INTO relation_hypothesis (id,repository_id,workspace_scope_key,branch_name,source_node_id,target_node_id,relation_type,analysis_session_scope_key) VALUES (" + id + ",'repo','__shared__','" + branch + "','BP','CP','SUPPORTS','__unspecified__')");
    }
    public static void reset(DataSource database) throws SQLException {
        for (String table : List.of("relation_evidence", "relation_hypothesis", "req_analysis_snapshot", "project_requirement", "arch_project", "system_repository")) {
            try { sql(database, "DROP TABLE " + table); } catch (SQLException error) {
                // Metadata distinguishes absence from a failed destructive DDL statement.
                try (Connection c=database.getConnection(); var tables=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),"%",new String[]{"TABLE"})) {
                    while(tables.next()) if(table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) throw error;
                }
            }
        }
    }
    public static String scalar(DataSource database, String text) throws SQLException {
        try(Connection c=database.getConnection(); Statement s=c.createStatement();var r=s.executeQuery(text)) { r.next(); return r.getString(1); }
    }
    public static void sql(DataSource database, String text) throws SQLException {
        try(Connection c=database.getConnection(); Statement s=c.createStatement()) { s.execute(text); }
    }
}
