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
        factory.setManagedTypes(PersistenceManagedTypes.of(ClusterAnalysisRun.class.getName(), ClusterAnalysisWork.class.getName(),
                ClusterAnalysisInput.class.getName(), ClusterAnalysisEvent.class.getName(),
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

    void largePayloadsAndAllFourTablesSurviveANewPersistenceFactory() {
        String businessText = "Fähigkeiten zuverlässig prüfen. ".repeat(6_000);
        String frozenInput = JSON.writeValueAsString(Map.of("root", "CP", "capturedContent", "Unveränderte Eingabe. ".repeat(9_000)));
        String reason = "Begründung mit vollständiger Evidenz. ".repeat(6_000);
        var scope = scope("workspace", "draft", "repository");
        var context = context("large", scope, businessText, null, null);
        var command = command(scope, businessText);
        store.admit(context, command, null, Map.of(CP, frozenInput, IP, "{\"root\":\"IP\"}"));
        assertEquals(command, store.start(task(context, CP)).command());
        store.start(task(context, IP));
        // A reverse completion order must preserve deterministic root/result assembly.
        store.accept(complete(task(context, IP), result(IP, 70, reason)));
        store.accept(complete(task(context, CP), result(CP, 40, reason)));
        assertEquals(1, count("ClusterAnalysisRun", "id", context.operationId()));
        assertEquals(2, count("ClusterAnalysisWork", "operationId", context.operationId()));
        assertEquals(2, count("ClusterAnalysisInput", "operationId", context.operationId()));
        assertEquals(5, count("ClusterAnalysisEvent", "operationId", context.operationId()));
        try (var reread = existing(database)) {
            assertEquals(command, reread.store.command(context));
            assertEquals(frozenInput, reread.store.shard(context, CP));
            var snapshot = reread.store.snapshot(context);
            assertEquals(ClusterAnalysisState.COMPLETED, snapshot.state());
            assertEquals(Map.of("CP", 40, "IP", 70), snapshot.result().getRawScores());
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
        var cp = task(context, CP); var ip = task(context, IP);
        store.start(cp); store.start(ip);
        var completion = completion(cp, 40);
        assertThrows(IllegalStateException.class, () -> completions.commit(new PreparedAnalysisCompletion<>(completion, () -> {
            store.persistResult(cp, result(CP, 40, "rolled back"));
            throw new IllegalStateException("Simulated failure before transaction commit");
        })));
        assertTrue(completions.find(cp.taskId()).isEmpty());
        assertEquals(0, count("AnalysisTaskCompletionRecord", "operationId", context.operationId()));
        assertThrows(IllegalStateException.class, () -> store.accept(completion));
        assertEquals(0, store.snapshot(context).completedRoots());
        assertContinuousEvents(context, 3);

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
            var other = complete(ip, result(IP, 70, "committed"));
            var acceptCp = pool.submit(() -> { start.await(20, TimeUnit.SECONDS); return store.accept(completion); });
            tasks.add(acceptCp);
            var acceptIp = pool.submit(() -> { start.await(20, TimeUnit.SECONDS); return store.accept(other); });
            tasks.add(acceptIp);
            assertTrue(acceptCp.get(30, TimeUnit.SECONDS)); assertTrue(acceptIp.get(30, TimeUnit.SECONDS));
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
        assertEquals(Map.of("CP", 40, "IP", 70), snapshot.result().getRawScores());
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
    private static AnalysisCompletionMessage completion(SubtaxonomyAnalysisTask task, int score) {
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
