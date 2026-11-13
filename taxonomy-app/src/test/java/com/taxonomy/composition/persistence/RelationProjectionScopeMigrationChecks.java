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
        migration.run();
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
