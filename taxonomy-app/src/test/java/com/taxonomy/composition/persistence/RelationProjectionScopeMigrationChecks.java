package com.taxonomy.composition.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The old display key can be present in central and real working-copy records. */
public final class RelationProjectionScopeMigrationChecks {
    private static final String REPO = "projection-scope-test";
    private static final String HEAD = "a".repeat(40);
    private static final Timestamp ORIGINAL = Timestamp.from(Instant.parse("2026-08-22T09:55:32Z"));

    public static void verify(DataSource database, Runnable migration) {
        var rows = new JdbcTemplate(database);
        rows.update("""
            insert into system_repository (repository_id, display_name, topology_mode, default_branch,
                primary_repo, created_at, storage_repository_name, slug, visibility, lifecycle_state, owner_type,
                owner_id, created_by, updated_at, version) values (?, 'Projection source', 'INTERNAL_SHARED', 'main',
                false, current_timestamp, 'projection-scope-test', 'projection-scope-test', 'ORGANIZATION',
                'ACTIVE', 'SYSTEM', 'system', 'system', current_timestamp, 0)
            """, REPO);
        var portable = new com.taxonomy.shared.config.SchemaContractMigration(database);
        // First application startup establishes the original JDBC tenancy contract;
        // records below then represent an already running installation.
        portable.migrate();
        seed(rows, "main", null);
        seed(rows, "historic", "__shared__");
        for (long id : new long[] {7001, 7002, 7003}) {
            rows.update("""
                    insert into taxonomy_node (id,code,name_en,node_level,has_embedding,incoming_relation_count,
                        outgoing_relation_count,requirement_coverage_count) values (?,?,?,1,false,0,0,0)
                    """, id, "projection-" + id, "Scope fixture " + id);
        }
        seedLegacyRelations(rows, null, 7002);
        seedLegacyRelations(rows, "__shared__", 7003);
        long centralHypothesis = seedHypothesis(rows, null, "main", "__shared__");
        long privateHypothesis = seedHypothesis(rows, "__shared__", "historic", "__shared__");
        try (var connection = database.getConnection()) {
            if (connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("HSQL Database Engine")) {
                portable.migrate();
                assertThat(rows.queryForObject("select workspace_scope_key from relation_decision_projection_checkpoint where repository_id=? and workspace_id is null",
                        String.class, REPO)).isEqualTo(" central");
            }
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
        }
        var originalIds = rows.queryForList("""
                select id from relation_decision_projection_checkpoint where repository_id=? order by id
                """, Long.class, REPO);
        // Older staged sources could store the private workspace key with an explicit prefix.
        long stagedHypothesis = seedHypothesis(rows, "editor", "staged", "WORKSPACE:editor");
        migration.run();
        assertThat(rows.queryForObject("select workspace_scope_key from relation_hypothesis where id=?",
                String.class, stagedHypothesis)).isEqualTo("editor");
        assertThat(rows.queryForObject("select workspace_scope_key from relation_evidence where hypothesis_id=?",
                String.class, stagedHypothesis)).isEqualTo("editor");
        assertThat(rows.queryForList("""
                select id from relation_decision_projection_checkpoint where repository_id=? order by id
                """, Long.class, REPO)).isEqualTo(originalIds);
        for (String table : new String[] {"relation_decision_projection", "relation_decision_projection_checkpoint", "relation_projection_recovery", "architecture_commit_index"}) {
            assertThat(rows.queryForObject("select workspace_scope_key from " + table + " where repository_id=? and branch='main'",
                    String.class, REPO)).isEqualTo(" central");
            assertThat(rows.queryForObject("select workspace_scope_key from " + table + " where repository_id=? and branch='historic'",
                    String.class, REPO)).isEqualTo("__shared__");
            assertThatThrownBy(() -> rows.update("update " + table + " set workspace_scope_key='' where repository_id=? and branch='historic'", REPO))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(rows.queryForObject("select workspace_scope_key from relation_evidence where hypothesis_id=?",
                String.class, centralHypothesis)).isEqualTo(" central");
        assertThat(rows.queryForObject("select workspace_scope_key from relation_evidence where hypothesis_id=?",
                String.class, privateHypothesis)).isEqualTo("__shared__");
        seedLegacyRelations(rows, "__shared__", 7002);
        for (String table : new String[] {"taxonomy_relation", "relation_proposal"}) {
            assertThat(rows.queryForList("select workspace_scope_key from " + table
                    + " where repository_id=? and target_node_id=7002 order by id", String.class, REPO))
                    .containsExactly(" central", "__shared__");
        }
        assertThatThrownBy(() -> rows.update("update relation_evidence set workspace_scope_key='__shared__' where hypothesis_id=?", centralHypothesis))
                .isInstanceOf(DataIntegrityViolationException.class);
        seedHypothesis(rows, "__shared__", "main", "__shared__");
        seed(rows, "main", "__shared__");
        portable.migrate();
        portable.migrate();
        assertThat(rows.queryForList("select workspace_scope_key from architecture_commit_index where repository_id=? and branch='main' order by id",
                String.class, REPO)).containsExactly(" central", "__shared__");
        assertThat(rows.queryForObject("select count(*) from relation_decision_projection_checkpoint where repository_id=? and branch='main'",
                Long.class, REPO)).isEqualTo(2);
        assertThat(rows.queryForObject("select last_observed_at from relation_projection_recovery where repository_id=? and workspace_id is null",
                Timestamp.class, REPO).toInstant()).isEqualTo(ORIGINAL.toInstant());
    }

    private static void seedLegacyRelations(JdbcTemplate rows, String owner, long target) {
        rows.update("""
                insert into taxonomy_relation (repository_id,workspace_id,workspace_scope_key,source_node_id,target_node_id,
                    relation_type,bidirectional,has_embedding) values (?,?,'__shared__',7001,?,'SUPPORTS',false,false)
                """, REPO, owner, target);
        rows.update("""
                insert into relation_proposal (repository_id,workspace_id,workspace_scope_key,source_node_id,target_node_id,
                    relation_type,status,confidence,created_at) values (?,?,'__shared__',7001,?,'SUPPORTS','PENDING',1,?)
                """, REPO, owner, target, ORIGINAL);
    }

    private static long seedHypothesis(JdbcTemplate rows, String owner, String branch, String storedKey) {
        rows.update("""
                insert into relation_hypothesis (repository_id, workspace_id, workspace_scope_key, branch_name,
                    source_node_id, target_node_id, relation_type, status, applied_in_current_analysis, analysis_session_scope_key, created_at)
                values (?, ?, ?, ?, 'BP', 'CP', 'SUPPORTS', 'PROVISIONAL', false, '__unspecified__', ?)
                """, REPO, owner, storedKey, branch, ORIGINAL);
        long id = rows.queryForObject("""
                select id from relation_hypothesis where repository_id=? and branch_name=? and workspace_scope_key=?
                """, Long.class, REPO, branch, storedKey);
        rows.update("""
                insert into relation_evidence (hypothesis_id, repository_id, workspace_scope_key, branch_name, evidence_type, created_at)
                values (?, ?, ?, ?, 'MANUAL', ?)
                """, id, REPO, storedKey, branch, ORIGINAL);
        return id;
    }

    private static void seed(JdbcTemplate rows, String branch, String workspaceId) {
        rows.update("""
                insert into architecture_commit_index (repository_id, workspace_id, workspace_scope_key, branch,
                    commit_id, author, commit_timestamp, indexed_at)
                values (?,?,'__shared__',?,?,'original-author',?,?)
                """, REPO, workspaceId, branch, HEAD, ORIGINAL, ORIGINAL);
        rows.update("""
                insert into relation_decision_projection (repository_id,workspace_id,workspace_scope_key,branch,
                    source_code,relation_type,target_code,relation_present,authoritative_commit_id,causation_id,updated_at,version)
                values (?,?,'__shared__',?,'A','SUPPORTS','B',true,?,'recorded-operation',?,0)
                """, REPO, workspaceId, branch, HEAD, ORIGINAL);
        rows.update("""
                insert into relation_decision_projection_checkpoint (repository_id,workspace_id,workspace_scope_key,branch,
                    authoritative_commit_id,relation_count,completed_at,version) values(?,?,'__shared__',?,?,1,?,0)
                """, REPO, workspaceId, branch, HEAD, ORIGINAL);
        rows.update("""
                insert into relation_projection_recovery (repository_id,workspace_id,workspace_scope_key,branch,authoritative_commit_id,
                    causation_id,status,attempt_count,failure_type,failure_message,first_observed_at,last_observed_at,version)
                values(?,?,'__shared__',?,?,'recorded-operation','PENDING',1,'Projection','Recorded failure',?,?,0)
                """, REPO, workspaceId, branch, HEAD, ORIGINAL, ORIGINAL);
    }

    private RelationProjectionScopeMigrationChecks() {}
}
