package com.taxonomy.versioning.service;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority.Grant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.taxonomy.versioning.service.HistoryIndexJournal.Kind.REBUILD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class HistoryIndexDatabaseContract {
    private static final Grant GRANT = new Grant("p-1", "LOCAL", "verified-binding");
    private static final Clock NOW = Clock.fixed(Instant.now().plus(Duration.ofDays(2)), ZoneOffset.UTC);

    /** A replacement row or an overlapping workspace must not be deleted by a retry. */
    public static void purgeTargets(javax.sql.DataSource db) {
        var jdbc = new JdbcTemplate(db);
        var journal = new HistoryIndexJournal(jdbc, new DataSourceTransactionManager(db), NOW);
        var selected = RepositoryContext.workspace("repo-purge", "__shared__", "draft", "owner");
        jdbc.update("insert into architecture_commit_index(id,repository_id,workspace_id) values(?,?,?)", 1L, "repo-purge", "__shared__");
        jdbc.update("insert into architecture_commit_index(id,repository_id,workspace_id) values(?,?,?)", 2L, "repo-purge", null);
        jdbc.update("insert into architecture_commit_index(id,repository_id,workspace_id) values(?,?,?)", 3L, "another", "__shared__");
        var admitted = journal.admit(selected, "draft", HistoryIndexJournal.Kind.PURGE, GRANT, "purge", null,
                () -> new HistoryIndexJournal.Capture(null, "owner"));
        String id = admitted.receipt().operationId();
        jdbc.update("delete from architecture_commit_index where id=1");
        jdbc.update("insert into architecture_commit_index(id,repository_id,workspace_id) values(?,?,?)", 4L, "repo-purge", "__shared__");
        assertThat(journal.admit(selected, "draft", HistoryIndexJournal.Kind.PURGE, GRANT, "purge", null,
                () -> { throw new AssertionError("Duplicate purge captured a later projection"); }).receipt().operationId()).isEqualTo(id);
        assertThat(jdbc.queryForList("select projection_id from history_index_purge_target where operation_id=?", Long.class, id))
                .containsExactly(1L);
        var claim = journal.claim(id, "worker-purge", false);
        assertThat(journal.execute(claim, row -> 0).count()).isZero();
        journal.archive(selected, GRANT.principalId(), id);
        assertThat(jdbc.queryForList("select projection_id from history_index_purge_target where operation_id=?", Long.class, id)).isEmpty();
        assertThat(jdbc.queryForList("select id from architecture_commit_index order by id", Long.class)).containsExactly(2L, 3L, 4L);
        jdbc.update("delete from history_index_decision where operation_id=?", id);
        jdbc.update("delete from history_index_operation where operation_id=?", id);
    }

    public static void independentWorkers(javax.sql.DataSource database) throws Exception {
        var jdbc = new JdbcTemplate(database);
        var current = new HistoryIndexJournal(jdbc, new DataSourceTransactionManager(database), NOW);
        var second = new HistoryIndexJournal(new JdbcTemplate(database), new DataSourceTransactionManager(database), NOW);
        var later = new HistoryIndexJournal(jdbc, new DataSourceTransactionManager(database), Clock.offset(NOW, Duration.ofMinutes(6)));
        var a = RepositoryContext.workspace("repo-history-test-" + UUID.randomUUID(), "common", "draft", "actor-a");
        var b = RepositoryContext.workspace("another-history-test", "common", "draft", "actor-a");
        String cause = UUID.randomUUID().toString();
        var first = current.admit(a, "review", REBUILD, GRANT, "same", cause, () -> new HistoryIndexJournal.Capture("a".repeat(40), "actor-a"));
        var other = current.admit(b, "review", REBUILD, GRANT, "other-" + UUID.randomUUID(), cause, () -> new HistoryIndexJournal.Capture("b".repeat(40), "actor-a"));
        String id = first.receipt().operationId();
        try {
            var replay = second.admit(a, "review", REBUILD, GRANT, "same", cause, () -> { throw new AssertionError("Replay recaptured a later head"); });
            assertThat(replay.receipt()).isEqualTo(first.receipt());
            assertThatThrownBy(() -> second.get(b, GRANT.principalId(), id)).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
            assertThatThrownBy(() -> second.admit(a, "other", REBUILD, GRANT, "same", cause, () -> null))
                    .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var one = pool.submit(() -> { start.await(); return current.claim(id, "worker-a", false); });
                var two = pool.submit(() -> { start.await(); return second.claim(id, "worker-b", false); });
                start.countDown();
                var old = java.util.stream.Stream.of(one.get(), two.get()).filter(java.util.Objects::nonNull).toList();
                assertThat(old).hasSize(1);
                var takeover = later.claim(id, "replacement", false);
                assertThat(takeover).isNotNull();
                assertThatThrownBy(() -> current.execute(old.getFirst(), row -> { throw new AssertionError("Fenced writer reached projection"); }))
                        .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
                var done = later.execute(takeover, row -> { assertThat(row.receipt().sourceCommit()).isEqualTo("a".repeat(40)); return 7; });
                assertThat(done.count()).isEqualTo(7);
                assertThat(done.attempts()).isEqualTo(2);
                assertThat(done.causationId()).isEqualTo(cause);
                assertThat(done.actor()).isEqualTo("p-1");
                assertThat(done.executor()).isEqualTo("replacement");
            }
            assertThat(later.archive(a, GRANT.principalId(), id).archivedAt()).isNotNull();
            assertThat(jdbc.queryForObject("select count(*) from history_index_decision where operation_id=?", Integer.class, id)).isEqualTo(5);
            var expired = new HistoryIndexJournal(jdbc, new DataSourceTransactionManager(database),
                    Clock.offset(NOW, Duration.ofDays(100)));
            expired.expire();
            assertThat(second.get(a, GRANT.principalId(), id).receipt().phase()).isEqualTo("EXPIRED");
            assertThatThrownBy(() -> current.admit(a, "review", REBUILD, GRANT, "same", cause, () -> { throw new AssertionError("Expired command replayed"); }))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(410));
            assertThat(jdbc.queryForObject("select count(*) from history_index_decision where operation_id=? and decision_code='RETENTION_EXPIRED'",
                    Integer.class, id)).isEqualTo(1);
            assertThat(second.get(b, GRANT.principalId(), other.receipt().operationId()).receipt().phase()).isEqualTo("ADMITTED");
        } finally {
            for (String row : java.util.List.of(id, other.receipt().operationId())) {
                jdbc.update("delete from history_index_decision where operation_id=?", row);
                jdbc.update("delete from history_index_operation where operation_id=?", row);
            }
        }
    }
}
