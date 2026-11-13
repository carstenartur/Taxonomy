package com.taxonomy.workspace.service;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercise the actual public schema and independent worker transactions on every supported database. */
public class WorkspaceFetchDatabaseContractTest {
    @Test void hsqlRestoreSchemaFencesTwoIndependentApplicationHandles() throws Exception {
        var db = new DriverManagerDataSource("jdbc:hsqldb:mem:fetch-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/restore/hsqldb/V23__Workspace_fetch_operation.sql"),
                new ClassPathResource("db/restore/hsqldb/V25__Central_fetch_and_selected_tags.sql")).execute(db);
        verify(db);
    }

    public static void verify(DataSource database) throws Exception {
        String fixture = UUID.randomUUID().toString();
        verifyScope(database, RepositoryContext.workspace("fetch-contract", fixture, "draft", "fetch-actor"), false);
        verifyScope(database, RepositoryContext.workspace("fetch-contract", fixture, "draft", "fetch-actor"), true);
        verifyScope(database, RepositoryContext.centralWrite(fixture, "main", "fetch-actor"), false);
        verifyScope(database, RepositoryContext.centralWrite(fixture, "main", "fetch-actor"), true);
    }

    private static void verifyScope(DataSource database, RepositoryContext scope, boolean selectedTag) throws Exception {
        var grant = new WorkspaceFetchAuthority.Grant(UUID.randomUUID().toString(), "LOCAL", "b".repeat(64));
        var command = new WorkspaceUpstreamService.Request("a".repeat(40), null);
        var source = new WorkspaceUpstreamService.Status("fetch-contract", "main", command.sourceCommit(), "refs/test", null);
        var capture = new WorkspaceUpstreamService.Capture(source, command.sourceCommit());
        // Keep synthetic fixture commands outside the live application's recovery poll.
        var clock = Clock.fixed(Instant.now().plus(Duration.ofDays(2)), ZoneOffset.UTC);
        try (var first = application(database); var second = application(database); var workers = Executors.newFixedThreadPool(2)) {
            var one = journal(first, clock);
            var two = journal(second, clock);
            var tag = new WorkspaceUpstreamService.TagRequest("release/selected", "c".repeat(40), command.sourceCommit());
            java.util.function.Function<WorkspaceFetchJournal, WorkspaceFetchJournal.View> admit = journal -> selectedTag
                    ? journal.admitTag(scope, grant, capture, tag, "db-contract", null)
                    : journal.admit(scope, grant, capture, command, "db-contract", null);
            String id = admit.apply(one).operationId();
            try {
                assertThat(admit.apply(two).operationId()).isEqualTo(id);
                var start = new CountDownLatch(1);
                var workerA = workers.submit(() -> { start.await(); return one.claim(id, "first-worker", true); });
                var workerB = workers.submit(() -> { start.await(); return two.claim(id, "second-worker", true); });
                start.countDown();
                var a = workerA.get(30, TimeUnit.SECONDS);
                var b = workerB.get(30, TimeUnit.SECONDS);
                assertThat(a == null ^ b == null).as("Exactly one independent worker wins, first=%s second=%s", a, b).isTrue();
                var previous = a == null ? b : a;
                var restarted = journal(second, Clock.offset(clock, Duration.ofMinutes(6)));
                var replacement = restarted.claim(id, "replacement", true);
                assertThat(replacement).isNotNull();
                assertThatThrownBy(() -> one.run(previous, (operation, fence) -> {
                    throw new AssertionError("The stale worker reached its Git callback");
                })).isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
                var settled = restarted.run(replacement, (operation, fence) -> {
                    fence.run();
                    if (selectedTag) {
                        assertThat(operation.selectedTag).isEqualTo(tag.name());
                        operation.resultTagObject = tag.sourceTagObject();
                    }
                    return new WorkspaceUpstreamService.Status("fetch-contract", "main", command.sourceCommit(), "refs/test", command.sourceCommit());
                });
                assertThat(one.status(scope, grant.principalId(), id)).isEqualTo(settled);
                assertThat(settled.attempts()).isEqualTo(2);
                assertThat(settled.workspaceId()).isEqualTo(scope.workspaceId());
                assertThat(settled.repositoryId()).isEqualTo(scope.repositoryId());
                assertThat(settled.sourceRepositoryId()).isEqualTo("fetch-contract");
                assertThat(settled.resultTagObject()).isEqualTo(selectedTag ? tag.sourceTagObject() : null);
                assertThat(settled.resultCommit()).isEqualTo(command.sourceCommit());
                assertThatThrownBy(() -> one.status(scope, UUID.randomUUID().toString(), id)).isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
                assertThat(two.archive(scope, grant.principalId(), id).outcome()).isEqualTo("COMPLETED");
                assertThatThrownBy(() -> admit.apply(one)).isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(410));
            } finally {
                String entity = scope.scope() == RepositoryScope.WORKSPACE ? "WorkspaceFetchOperation" : "CentralFetchOperation";
                first.inTransaction(session -> session.createMutationQuery("delete from " + entity + " f where f.id=:id")
                        .setParameter("id", id).executeUpdate());
            }
        }
    }

    private static SessionFactory application(DataSource db) {
        var cfg = new Configuration().setProperty("hibernate.hbm2ddl.auto", "validate")
                .setProperty("hibernate.search.enabled", "false").addAnnotatedClass(CentralFetchOperation.class).addAnnotatedClass(WorkspaceFetchOperation.class);
        cfg.getProperties().put("hibernate.connection.datasource", db);
        return cfg.buildSessionFactory();
    }

    private static WorkspaceFetchJournal journal(SessionFactory factory, Clock clock) {
        return new WorkspaceFetchJournal(SharedEntityManagerCreator.createSharedEntityManager(factory), new JpaTransactionManager(factory), clock);
    }
}
