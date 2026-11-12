package com.taxonomy;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dispatch.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real store/ledger transactions shared by the existing vendor lanes and the local HSQL test. */
final class ClusterAnalysisDatabaseContract implements AutoCloseable {
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP"), IP = TaxonomyShardRoot.of("IP");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String identity = UUID.randomUUID().toString();
    private final String owner = "cluster-db-" + identity;
    private final DataSource database;
    private final HikariDataSource ownedPool;
    private final LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
    private final EntityManager em;
    private final JpaTransactionManager transactions;
    private final ClusterAnalysisStore store;
    private final JpaAnalysisTaskCompletionStore completions;
    private final List<AnalysisTaskMessage> sent = new CopyOnWriteArrayList<>();
    private volatile Runnable beforeCompletionInsert = () -> { };
    private volatile Consumer<CompletionInsert> completionInsertObserver = ignored -> { };

    com.taxonomy.composition.analysis.ScopedAnalysisAdapterExecution adapterGateway(
            com.taxonomy.workspace.service.WorkspaceResolver resolver, com.taxonomy.workspace.service.WorkspaceDslReadPort git,
            com.taxonomy.security.service.PrincipalIdentityService identities) {
        return new com.taxonomy.composition.analysis.ScopedAnalysisAdapterExecution(resolver,git,identities,em,transactions,JSON);
    }

    void durableDocumentAdapterRetainsItsNativeResultAndFencesLateCompletion() throws Exception {
        var instant = java.time.Instant.now();
        var owner = new com.taxonomy.analysis.operation.AnalysisAdapterStore.Scope(identity, "repo", "ws", "main");
        var other = new com.taxonomy.analysis.operation.AnalysisAdapterStore.Scope(UUID.randomUUID().toString(), "repo", "ws", "main");
        String source = "f".repeat(40), inputs = "a".repeat(64);
        var service = new com.taxonomy.analysis.operation.AnalysisAdapterStore(em, transactions, Clock.fixed(instant, java.time.ZoneOffset.UTC));
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var observers = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        java.util.concurrent.Callable<com.taxonomy.analysis.operation.AnalysisAdapterStore.Admission> submit = () -> {
            ready.countDown(); assertTrue(start.await(10,TimeUnit.SECONDS));
            String token = UUID.randomUUID().toString();
            observers.add(token);
            return service.admit(owner,"REGULATION_MAPPING",source,inputs,"one-command",true,token);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(submit); var f2 = pool.submit(submit);
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            var first = f1.get(15,TimeUnit.SECONDS); var second = f2.get(15,TimeUnit.SECONDS);
            assertNotEquals(first.claimed(), second.claimed());
            var run = first.operation(); assertEquals(run.id(), second.operation().id());
            for (var token : observers) {
                assertEquals(run.id(),service.observe(owner,token).id());
                assertEquals(404, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> service.observe(other,token)).getStatusCode().value());
            }
            var reused = assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                    service.admit(owner,"REGULATION_MAPPING",source,inputs,"different",true,observers.element()));
            assertEquals(409,reused.getStatusCode().value());
            String result = "{\"fileName\":\"notice.pdf\",\"totalPages\":1,\"inputCharacters\":12,\"inputTruncated\":false,\"matches\":[{\"nodeCode\":\"CP\",\"confidence\":0.8}]}";
            var completed = service.settle(owner,run.id(),run.revision(),result,null);
            assertEquals(result, completed.resultJson());
            assertEquals(404, assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.read(other,run.id())).getStatusCode().value());
            assertEquals(completed, service.admit(owner,"REGULATION_MAPPING",source,inputs,"one-command",false).operation());
            String nextObserver = UUID.randomUUID().toString();
            var bounded = service.admit(owner,"REGULATION_MAPPING",source,inputs,"bounded-observers",true).operation();
            for (int i = 0; i < 32; i++) {
                var attached = service.admit(owner,"REGULATION_MAPPING",source,inputs,"bounded-observers",true,UUID.randomUUID().toString());
                assertFalse(attached.claimed());
                assertEquals(bounded.revision(),attached.operation().revision());
            }
            assertEquals(429,assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                    service.admit(owner,"REGULATION_MAPPING",source,inputs,"bounded-observers",true,nextObserver)).getStatusCode().value());
            assertEquals("COMPLETED",service.settle(owner,bounded.id(),bounded.revision(),result,null).state());
            assertFalse(service.admit(owner,"REGULATION_MAPPING",source,inputs,"bounded-observers",true,nextObserver).claimed());
            assertEquals(bounded.id(),service.observe(owner,nextObserver).id());
            var cancel = service.admit(owner,"REGULATION_MAPPING",source,inputs,"cancel",true).operation();
            assertEquals("CANCELLED", service.cancel(owner,cancel.id()).state());
            assertEquals("CANCELLED", service.settle(owner,cancel.id(),cancel.revision(),result,null).state());
            var orphan = service.admit(owner,"REGULATION_MAPPING",source,inputs,"orphan",true).operation();
            var recovered = new com.taxonomy.analysis.operation.AnalysisAdapterStore(em,transactions,Clock.fixed(
                    instant.plus(java.time.Duration.ofHours(1)),java.time.ZoneOffset.UTC));
            assertEquals("OUTCOME_UNKNOWN",recovered.read(owner,orphan.id()).failureCode());
            assertEquals("FAILED",recovered.settle(owner,orphan.id(),orphan.revision(),result,null).state());
            var foreign = service.admit(other,"REGULATION_MAPPING",source,inputs,"one-command",true).operation();
            var future = new com.taxonomy.analysis.operation.AnalysisAdapterStore(em,transactions,Clock.fixed(
                    instant.plus(java.time.Duration.ofDays(31)),java.time.ZoneOffset.UTC));
            future.sweepScope(owner,instant.plus(java.time.Duration.ofDays(31)).toEpochMilli());
            assertEquals("EXPIRED", future.read(owner,run.id()).state());
            assertEquals("EXPIRED",future.observe(owner,observers.element()).state());
            var expired = future.admit(owner,"REGULATION_MAPPING",source,inputs,"one-command",true);
            assertFalse(expired.claimed()); assertNull(expired.operation().resultJson());
            assertEquals("RUNNING",service.read(other,foreign.id()).state());
            var record = new com.taxonomy.analysis.operation.AnalysisAdapterBackup.Receipt(
                    new com.taxonomy.backup.SourceRecordId("analysis.adapter",run.id()),owner,run.kind(),source,run.requestKey(),inputs,
                    "COMPLETED",result,null,run.createdAt(),completed.updatedAt(),completed.revision());
            var target = new com.taxonomy.analysis.operation.AnalysisAdapterStore.Scope(other.actor(),"restored-repo",null,"other");
            var untrusted = new com.taxonomy.analysis.operation.AnalysisAdapterBackup.Receipt(record.sourceId(),owner,run.kind(),source,
                    run.requestKey(),inputs,"COMPLETED",result.replace("\"matches\"","\"unexpectedTrace\":\"fixture\",\"matches\""),
                    null,run.createdAt(),completed.updatedAt(),completed.revision());
            assertThrows(IllegalArgumentException.class, () -> com.taxonomy.analysis.operation.AnalysisAdapterBackup.restore(
                    em,transactions,untrusted,UUID.randomUUID().toString(),target));
            String remapped = UUID.randomUUID().toString();
            com.taxonomy.analysis.operation.AnalysisAdapterBackup.restore(em,transactions,record,remapped,target);
            var replayed = service.admit(target,"REGULATION_MAPPING",source,inputs,"one-command",false).operation();
            assertEquals(remapped,replayed.id()); assertEquals(result,replayed.resultJson()); assertEquals(completed.revision(),replayed.revision());
            future.sweepScope(owner,instant.plus(java.time.Duration.ofDays(121)).toEpochMilli());
            assertEquals(404, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> future.observe(owner,observers.element())).getStatusCode().value());
            assertEquals(404,assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> service.read(owner,run.id())).getStatusCode().value());
            assertEquals("RUNNING",service.read(other,foreign.id()).state());
        }
    }

    record CompletionInsert(Connection connection, boolean executingSql) { }

    void observeCompletionInserts(Consumer<CompletionInsert> observer) {
        completionInsertObserver = Objects.requireNonNull(observer);
    }

    static ClusterAnalysisDatabaseContract hsql() {
        return hsql(new DriverManagerDataSource(
                "jdbc:hsqldb:mem:cluster-contract-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", ""));
    }

    static ClusterAnalysisDatabaseContract hsql(DataSource database) {
        return pooled(database, "create-drop");
    }

    static ClusterAnalysisDatabaseContract pooledExisting(DataSource database) {
        return pooled(database, "validate");
    }

    private static ClusterAnalysisDatabaseContract pooled(DataSource database, String schemaMode) {
        var pool = new HikariDataSource();
        pool.setDataSource(database);
        // Independent concurrent deliveries and nested post-commit reads need
        // separate connections, while serial history setup must reuse them.
        pool.setMaximumPoolSize(4);
        pool.setMinimumIdle(0);
        try {
            return new ClusterAnalysisDatabaseContract(pool, schemaMode, pool);
        } catch (RuntimeException | Error failure) {
            pool.close();
            throw failure;
        }
    }

    static ClusterAnalysisDatabaseContract existing(DataSource database) {
        // A reopened persistence factory borrows the original fixture's pool.
        return new ClusterAnalysisDatabaseContract(database, "validate", null);
    }

    private ClusterAnalysisDatabaseContract(DataSource database, String schemaMode, HikariDataSource ownedPool) {
        this.database = database;
        this.ownedPool = ownedPool;
        factory.setDataSource(new DelegatingDataSource(database) {
            @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                return observe(super.getConnection(username, password));
            }
        });
        factory.setManagedTypes(PersistenceManagedTypes.of(com.taxonomy.analysis.operation.AnalysisAdapterOperation.class.getName(), ClusterAnalysisRun.class.getName(), ClusterAnalysisWork.class.getName(),
                ClusterAnalysisInput.class.getName(), ClusterAnalysisEvent.class.getName(), ClusterAnalysisCall.class.getName(), ClusterAnalysisAttempt.class.getName(),
                AnalysisDispatchIntent.class.getName(), AnalysisTaskCompletionRecord.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", schemaMode, "hibernate.search.enabled", "false"));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(Objects.requireNonNull(factory.getObject()));
        transactions = new JpaTransactionManager(factory.getObject());
        var dispatch = new AnalysisDispatchService(new AnalysisDispatchStore(em, transactions), task -> {
            var committed = new TransactionTemplate(transactions);
            committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertNotNull(committed.execute(status -> em.find(ClusterAnalysisRun.class, task.envelope().operationId())),
                    "Dispatch must observe the committed operation on another connection");
            sent.add(task);
        }, 20, 100);
        store = new ClusterAnalysisStore(em, transactions, JSON, dispatch);
        completions = new JpaAnalysisTaskCompletionStore(em, transactions);
    }

    void durableDiagnosticsSurviveANewPersistenceFactoryWithUnknownUsageAndScopedDetails() {
        var scope = scope("diagnostic-workspace", "draft", "diagnostic-repository");
        var context = context("diagnostics", scope, "requirement", null, null);
        store.admit(context, command(scope, "requirement"), null, Map.of(CP, "{}", IP, "{}"));
        var task = task(context, CP); store.start(task); var session = store.diagnostics(task);
        String request = "bounded-request".repeat(1000);
        long call = session.started("Mock", "CP"); session.prepared(call, request);
        var attempt = new com.taxonomy.analysis.service.LlmTransportMeter.Attempt(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "CUSTOM_OPENAI", com.taxonomy.analysis.service.LlmTransportMeter.Source.HTTP, 0);
        session.started(attempt);
        var detail = new LlmCallDetail(); detail.setRawResponse("retained-answer"); session.completed(call, detail, 1);
        assertEquals(1, count("ClusterAnalysisCall", "operationId", context.operationId()));
        assertEquals(1, count("ClusterAnalysisAttempt", "operationId", context.operationId()));
        try (var reread = existing(database); var observation = new DurableClusterAnalysisObservation(reread.store, new ClusterAnalysisSignals(), AnalysisEventPublisher.NONE)) {
            var retained = observation.callDetail(context.operationId(), call, owner, scope);
            assertEquals("retained-answer", retained.response()); assertTrue(retained.truncated()); assertEquals(request.length(), retained.promptLength());
            assertEquals(8192, retained.prompt().length());
            assertEquals("STARTED", retained.attempts().getFirst().state()); assertNull(retained.attempts().getFirst().usage().totalTokens());
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> observation.callDetail(context.operationId(), call, "foreign-owner", scope));
            assertEquals(1, observation.snapshot(context.operationId(), owner, scope).orElseThrow().snapshot().calls().size());
        }
    }

    void largePayloadsAndAllFourTablesSurviveANewPersistenceFactory() {
        String businessText = "Fähigkeiten zuverlässig prüfen. ".repeat(6_000);
        String frozenInput = JSON.writeValueAsString(Map.of("root", "CP", "capturedContent", "Unveränderte Eingabe. ".repeat(9_000)));
        String reason = "Begründung mit vollständiger Evidenz. ".repeat(6_000);
        var scope = scope("workspace", "draft", "repository");
        var context = context("large", scope, businessText, null, null);
        var command = command(scope, businessText);
        store.admit(context, command, null, Map.of(CP, frozenInput, IP, "{\"root\":\"IP\"}"));
        assertEquals(command, store.start(task(context, CP)).command());
        store.accept(complete(task(context, CP), result(CP, 40, reason)));
        store.start(task(context, IP));
        store.accept(complete(task(context, IP), result(IP, 70, reason)));
        assertEquals(1, count("ClusterAnalysisRun", "id", context.operationId()));
        assertEquals(2, count("ClusterAnalysisWork", "operationId", context.operationId()));
        assertEquals(2, count("ClusterAnalysisInput", "operationId", context.operationId()));
        assertEquals(5, count("ClusterAnalysisEvent", "operationId", context.operationId()));
        try (var reread = existing(database)) {
            assertEquals(command, reread.store.command(context));
            assertEquals(frozenInput, reread.store.shard(context, CP));
            var snapshot = reread.store.snapshot(context);
            assertEquals(ClusterAnalysisState.COMPLETED, snapshot.state());
            assertEquals(Map.of("CP", 40d, "IP", 70d), snapshot.result().getRawScores());
            assertEquals(Map.of("CP", reason, "IP", reason), snapshot.result().getReasons());
            assertEquals(List.of("CP", "IP"), snapshot.result().getTree().stream().map(TaxonomyNodeDto::getCode).toList());
            assertTrue(snapshot.tasks().stream().allMatch(work -> work.state().equals("COMPLETED")));
            var storedRoots = new TransactionTemplate(reread.transactions).execute(status -> reread.em.createQuery(
                            "select w.resultJson from ClusterAnalysisWork w where w.operationId=:id", String.class)
                    .setParameter("id", context.operationId()).getResultList());
            assertEquals(2, storedRoots.size());
            storedRoots.forEach(json -> assertTrue(JSON.readValue(json, AnalysisResult.class).getReasons().containsValue(reason)));
            reread.assertContinuousEvents(context, 5);
            assertTrue(reread.sent.isEmpty(), "Reading persisted state must not redispatch work");
        }
        assertEquals(1, count("ClusterAnalysisRun", "id", context.operationId()),
                "Closing a borrowed persistence factory must leave the original fixture usable");
    }

    void concurrentDuplicateCompletionRollsBackAndSettlesExactlyOnce() throws Exception {
        var context = context("concurrent", scope("workspace", "draft", "repository"), "requirement", null, null);
        store.admit(context, command(scope("workspace", "draft", "repository"), "requirement"), null, Map.of(CP, "{}", IP, "{}"));
        var cp = task(context, CP);
        store.start(cp);
        var completion = completion(cp, 40);
        assertThrows(IllegalStateException.class, () -> completions.commit(new PreparedAnalysisCompletion<>(completion, () -> {
            store.persistResult(cp, result(CP, 40, "rolled back"));
            throw new IllegalStateException("Simulated failure before transaction commit");
        })));
        assertTrue(completions.find(cp.taskId()).isEmpty());
        assertEquals(0, count("AnalysisTaskCompletionRecord", "operationId", context.operationId()));
        assertThrows(IllegalStateException.class, () -> store.accept(completion));
        assertEquals(0, store.snapshot(context).completedRoots());
        assertContinuousEvents(context, 2);

        var insertAttempts = new CountDownLatch(2); var releaseInserts = new CountDownLatch(1); var effects = new AtomicInteger();
        beforeCompletionInsert = () -> {
            insertAttempts.countDown();
            await(releaseInserts);
        };
        var prepared = new PreparedAnalysisCompletion<>(completion, () -> {
            effects.incrementAndGet();
            store.persistResult(cp, result(CP, 40, "committed"));
        });
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().daemon(true).name("cluster-completion-race-", 0).factory());
        var tasks = new ArrayList<Future<?>>();
        try {
            var first = pool.submit(() -> completions.commit(prepared));
            tasks.add(first);
            var second = pool.submit(() -> completions.commit(prepared));
            tasks.add(second);
            // Both absent-row reads must finish before either SQL insert: lock-based READ COMMITTED
            // may otherwise block the second read behind the first transaction's reservation.
            assertTrue(insertAttempts.await(20, TimeUnit.SECONDS), "Both deliveries must reach the JDBC insert barrier");
            assertEquals(0, effects.get(), "Neither delivery may persist an effect before the insert barrier opens");
            releaseInserts.countDown();
            assertEquals(completion, first.get(30, TimeUnit.SECONDS));
            assertEquals(completion, second.get(30, TimeUnit.SECONDS));
            assertEquals(1, effects.get(), "Concurrent duplicate must never invoke the losing result effect");
            assertEquals(1, count("AnalysisTaskCompletionRecord", "operationId", context.operationId()));
            assertEquals(0, store.snapshot(context).completedRoots(), "Only the coordinator settles committed work");
            var acceptCp = pool.submit(() -> { start.await(20, TimeUnit.SECONDS); return store.accept(completion); });
            tasks.add(acceptCp);
            var acceptDuplicate = pool.submit(() -> { start.await(20, TimeUnit.SECONDS); return store.accept(completion); });
            tasks.add(acceptDuplicate);
            assertNotEquals(acceptCp.get(30, TimeUnit.SECONDS), acceptDuplicate.get(30, TimeUnit.SECONDS),
                    "Concurrent coordinator deliveries must settle the first root exactly once");
            var ip = task(context, IP);
            store.start(ip);
            var other = complete(ip, result(IP, 70, "committed"));
            assertTrue(store.accept(other));
            assertFalse(store.accept(completion)); assertFalse(store.accept(other));
        } finally {
            releaseInserts.countDown();
            start.reset();
            tasks.forEach(task -> task.cancel(true));
            pool.shutdownNow();
            beforeCompletionInsert = () -> { };
        }
        var snapshot = store.snapshot(context);
        assertEquals(ClusterAnalysisState.COMPLETED, snapshot.state());
        assertEquals(2, snapshot.completedRoots());
        assertEquals(Map.of("CP", 40d, "IP", 70d), snapshot.result().getRawScores());
        assertEquals(Map.of("CP", "committed", "IP", "committed"), snapshot.result().getReasons());
        assertEquals(2, count("AnalysisTaskCompletionRecord", "operationId", context.operationId()));
        assertContinuousEvents(context, 5);
    }

    void ownerSourceAndRequirementScopeIsAppliedBeforeTheHistoryLimit() {
        var scope = scope("workspace", "draft", "repository");
        var selected = admit("selected", scope, 10L, 20L);
        admit("other-project", scope, 11L, 20L);
        admit("other-requirement", scope, 10L, 21L);
        var foreignScopes = List.of(scope("other-workspace", "draft", "repository"), scope("workspace", "other-branch", "repository"),
                scope("workspace", "draft", "other-repository"), new WorkspaceContext("other-" + owner, "workspace", "draft", "repository"));
        for (int i = 0; i < foreignScopes.size(); i++) admit("foreign-" + i, foreignScopes.get(i), 10L, 20L);
        // More recent rows than the public history limit must not crowd the selected scope out.
        for (int i = 0; i < 55; i++) admit("recent-foreign-" + i, foreignScopes.getFirst(), 10L, 20L);
        assertEquals(List.of(selected), store.recent(owner, scope, 10L, 20L));
        assertEquals(3, store.recent(owner, scope, null, null).size());
        assertTrue(store.recent("other-" + owner, scope, 10L, 20L).isEmpty());
        for (var foreignScope : foreignScopes) {
            assertThrows(SecurityException.class, () -> store.findAuthorized(selected.operationId(), foreignScope.username(), foreignScope));
            var authority = new AnalysisSourceAuthority(foreignScope.repositoryId(), foreignScope.workspaceId(), foreignScope.currentBranch(), "source");
            if (!authority.equals(selected.authority())) {
                var foreign = new AnalysisOperationContext(selected.operationId(), authority, selected.requirement(), selected.correlationId());
                assertThrows(IllegalStateException.class, () -> store.shard(foreign, CP));
                assertThrows(IllegalStateException.class, () -> store.events(foreign, 0, 100));
                var task = new AnalysisMessageFactory(foreign, Clock.systemUTC()).task(AnalysisTaskGraph.plan(foreign.operationId(), List.of(CP), false).tasks().getFirst());
                assertThrows(IllegalStateException.class, () -> store.start(task));
            }
        }
        assertEquals(selected, store.authorize(selected.operationId(), owner, scope));
        assertEquals(0, store.snapshot(selected).completedRoots());
        assertContinuousEvents(selected, 1);
    }

    void scopedExpiryFencesRedeliveryAndPreservesForeignDatabaseRows() {
        var workspace = scope("retention", "draft", "repository");
        var other = scope("retention-foreign", "draft", "repository");
        var selected = admit("expiry", workspace, null, null);
        var foreign = admit("other-expiry", other, null, null);
        var delivered = task(selected, CP);
        store.cancel(selected);
        store.cancel(foreign);
        var future = System.currentTimeMillis() + java.time.Duration.ofDays(31).toMillis();
        assertEquals(1, store.retention().sweepScope(workspace, future));
        assertEquals(0, store.retention().sweepScope(workspace, future));
        var gone = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> store.findAuthorized(selected.operationId(), owner, workspace));
        assertEquals(410, gone.getStatusCode().value());
        assertFalse(store.start(delivered).executable());
        assertFalse(store.accept(completion(delivered, 80)));
        assertNotNull(store.snapshot(foreign).result());
        assertThrows(SecurityException.class, () -> store.findAuthorized(selected.operationId(), owner, other));
        assertTrue(store.recent(owner, workspace, null, null).isEmpty());
    }

    private AnalysisOperationContext admit(String label, WorkspaceContext scope, Long project, Long requirement) {
        var context = context(label, scope, "requirement", project, requirement);
        store.admit(context, command(scope, "requirement"), null, Map.of(CP, "{}", IP, "{}"));
        return context;
    }
    private WorkspaceContext scope(String workspace, String branch, String repository) { return new WorkspaceContext(owner, workspace, branch, repository); }
    private AnalysisOperationContext context(String label, WorkspaceContext scope, String text, Long project, Long requirement) {
        String id = identity + "-" + label;
        return new AnalysisOperationContext(id, new AnalysisSourceAuthority(scope.repositoryId(), scope.workspaceId(), scope.currentBranch(), "source"),
                project == null ? RequirementReference.adHoc(text) : RequirementReference.of(project, requirement, "snapshot", text), id);
    }
    private static AnalyzeRequirementCommand command(WorkspaceContext scope, String text) {
        return new AnalyzeRequirementCommand(text, false, 20, "MOCK", scope.username(), scope, null,
                new AnalysisScope(Set.of("CP", "IP"), AnalysisMode.TAXONOMIES_ONLY));
    }
    private SubtaxonomyAnalysisTask task(AnalysisOperationContext context, TaxonomyShardRoot root) {
        return sent.stream().filter(task -> task.envelope().operationId().equals(context.operationId()) && task.routingRoot().equals(root))
                .map(SubtaxonomyAnalysisTask.class::cast).findFirst().orElseThrow();
    }
    private static AnalysisResult result(TaxonomyShardRoot root, int score, String reason) {
        var node = new TaxonomyNodeDto(); node.setCode(root.code()); node.setTaxonomyRoot(root.code());
        var result = new AnalysisResult(Map.of(root.code(), score), List.of(node)); result.setReasons(Map.of(root.code(), reason));
        result.setStatus("SUCCESS"); result.setProvider("MOCK"); return result;
    }
    private static AnalysisCompletionMessage completion(SubtaxonomyAnalysisTask task, double score) {
        return new AnalysisMessageFactory(ClusterAnalysisStore.context(task.envelope()), Clock.systemUTC())
                .completed(task, AnalysisTaskOutcome.COMPLETED, score, 1, null);
    }
    private AnalysisCompletionMessage complete(SubtaxonomyAnalysisTask task, AnalysisResult result) {
        return completions.commit(new PreparedAnalysisCompletion<>(completion(task, result.getRawScores().get(task.root().code())),
                () -> store.persistResult(task, result)));
    }
    private long count(String entity, String field, String operation) {
        return new TransactionTemplate(transactions).execute(status -> em.createQuery(
                "select count(r) from " + entity + " r where r." + field + "=:id", Long.class).setParameter("id", operation).getSingleResult());
    }
    private void assertContinuousEvents(AnalysisOperationContext context, long revision) {
        var events = store.events(context, 0, 100);
        assertEquals(revision, store.snapshot(context).revision());
        assertEquals(LongStream.rangeClosed(1, revision).boxed().toList(), events.stream().map(AnalysisProgressEvent::sequence).toList());
        assertTrue(events.stream().allMatch(event -> ClusterAnalysisStore.context(event.envelope()).equals(context)));
    }

    /** Observe real JDBC insertion attempts so the duplicate race never relies on a sleep. */
    private Connection observe(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            Object value = invoke(delegate, method, args);
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                    && sql.stripLeading().startsWith("insert into analysis_task_completion")) {
                var statement = (PreparedStatement) value;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (ignored, call, parameters) -> {
                    if (call.getName().equals("executeUpdate")) {
                        completionInsertObserver.accept(new CompletionInsert(delegate, false));
                        beforeCompletionInsert.run();
                        completionInsertObserver.accept(new CompletionInsert(delegate, true));
                    }
                    return invoke(statement, call, parameters);
                });
            }
            return value;
        });
    }
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(20, TimeUnit.SECONDS), "Concurrent insert barrier was not released"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    @Override public void close() {
        try {
            factory.destroy();
        } finally {
            if (ownedPool != null) ownedPool.close();
        }
    }
}
