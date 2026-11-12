package com.taxonomy.workspace.service;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class WorkspaceFetchJournalTest {
    static final String ALICE = "8f0a4a04-b598-4700-bbcd-5ab26bbba1ea";
    static final WorkspaceFetchAuthority.Grant GRANT = new WorkspaceFetchAuthority.Grant(ALICE, "LOCAL", "9".repeat(64));
    static final String BOB = "9d8e2da1-fc06-4dc9-ac6b-1381904341de";
    static final RepositoryContext CONTEXT = RepositoryContext.workspace("source", "w1", "draft", "alice");
    static final String COMMIT = "a".repeat(40);
    static final WorkspaceUpstreamService.Status SOURCE = new WorkspaceUpstreamService.Status("source", "main", COMMIT, "refs/test", null);
    static final WorkspaceUpstreamService.Request COMMAND = new WorkspaceUpstreamService.Request(COMMIT, null);

    static class MutableClock extends Clock {
        long now = 1_750_000_000_000L;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    static class Database implements AutoCloseable {
        final String url;
        final SessionFactory factory;
        final MutableClock clock;
        final WorkspaceFetchJournal journal;
        Database() { this("jdbc:hsqldb:mem:wsfetch" + UUID.randomUUID(), new MutableClock()); }
        Database(String url, MutableClock clock) {
            this.url = url;
            this.clock = clock;
            factory = new Configuration().setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", "SA")
                    .setProperty("hibernate.connection.password", "").setProperty("hibernate.connection.pool_size", "8")
                    .setProperty("hibernate.hbm2ddl.auto", "update").setProperty("hibernate.search.enabled", "false")
                    .addAnnotatedClass(WorkspaceFetchOperation.class).buildSessionFactory();
            journal = new WorkspaceFetchJournal(SharedEntityManagerCreator.createSharedEntityManager(factory), new JpaTransactionManager(factory), clock);
        }
        @Override public void close() { factory.close(); }
    }

    @Test void twoWorkersAndAnExpiredOwnerCanOnlyPublishOneRecordedCommit() throws Exception {
        try (var db = new Database(); var workers = Executors.newFixedThreadPool(2)) {
            var journal = db.journal;
            String id = journal.admit(CONTEXT, GRANT, SOURCE, COMMAND, "same-command", null).operationId();
            var first = workers.submit(() -> journal.claim(id, "one", false));
            var second = workers.submit(() -> journal.claim(id, "two", false));
            var a = first.get();
            var b = second.get();
            assertThat(a == null ^ b == null).isTrue();
            var old = a == null ? b : a;
            db.clock.now += WorkspaceFetchJournal.LEASE_MILLIS + 1;
            assertThat(journal.due()).containsExactly(id);
            var replacement = journal.claim(id, "replacement", true);
            var writes = new AtomicInteger();
            var result = journal.run(replacement, (row, fence) -> {
                fence.run();
                writes.incrementAndGet();
                assertThat(row.actor).isEqualTo(ALICE);
                assertThat(row.executor).isEqualTo("replacement");
                return new WorkspaceUpstreamService.Status("source", "main", COMMIT, "refs/test", COMMIT);
            });
            assertThat(result.phase()).isEqualTo("COMPLETED");
            assertThat(result.attempts()).isEqualTo(2);
            assertThatThrownBy(() -> journal.run(old, (row, fence) -> { writes.incrementAndGet(); return SOURCE; }))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(writes.get()).isOne();
            assertThat(journal.status(CONTEXT, ALICE, id).phase()).isEqualTo("COMPLETED");
            assertThat(journal.due()).isEmpty();
        }
    }

    @Test void scopedKeysAreIdempotentAndAnIndependentManagerRecoversTheOriginalAuthority() {
        var time = new MutableClock();
        String url = "jdbc:hsqldb:mem:wsfetch" + UUID.randomUUID();
        String cause = UUID.randomUUID().toString();
        String id;
        try (var first = new Database(url, time)) {
            id = first.journal.admit(CONTEXT, GRANT, SOURCE, COMMAND, "retry", cause).operationId();
            assertThat(first.journal.admit(CONTEXT, GRANT, SOURCE, COMMAND, "retry", null).operationId()).isEqualTo(id);
            assertThatThrownBy(() -> first.journal.admit(CONTEXT, GRANT, SOURCE,
                    new WorkspaceUpstreamService.Request("b".repeat(40), null), "retry", null)).isInstanceOf(ResponseStatusException.class);
            first.journal.claim(id, "stopped-worker", false);
        }
        time.now += Duration.ofMinutes(6).toMillis();
        try (var restarted = new Database(url, time)) {
            assertThat(restarted.journal.due()).containsExactly(id);
            assertThat(restarted.journal.status(CONTEXT, ALICE, id).causationId()).isEqualTo(cause);
            for (var selection : java.util.List.of(RepositoryContext.workspace("different", "w1", "draft", "alice"),
                    RepositoryContext.workspace("source", "w2", "draft", "alice"), RepositoryContext.workspace("source", "w1", "other", "alice"))) {
                assertThatThrownBy(() -> restarted.journal.status(selection, ALICE, id)).isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("404");
            }
            assertThatThrownBy(() -> restarted.journal.status(CONTEXT, BOB, id)).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
    }
}
