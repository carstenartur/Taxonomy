package com.taxonomy.analysis.dispatch;

import com.taxonomy.analysis.dag.*;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** Real concurrent transactions: both caller writes must survive a duplicate intent. */
public final class DispatchInsertConcurrencyCases {
    public static void main(String[] args) throws Exception {
        simultaneousFirstInsert();
        rollbackKeepsIntentsAtomic();
        duplicateThenRollbackPreservesWinner();
        publicationOrderIsPreserved();
        nonDuplicateConstraintFailurePropagates();
        foreignSourceIsRejected();
        reversedBatchesCommitWithoutChangingPublicationOrder();
        onlyUniqueKeyFailuresAreRecognized();
        System.out.println("Dispatch insertion cases=8, failed=0");
    }

    static void simultaneousFirstInsert() throws Exception {
        try (var db = new Database()) { concurrentDispatch(db); }
    }

    private static void concurrentDispatch(Database db) throws Exception {
        var task = db.task("dispatch-race");
        var bothReadAbsent = new CountDownLatch(2);
        EntityManager observed = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[] {EntityManager.class}, (proxy, method, arguments) -> {
                    Object result;
                    try { result = method.invoke(db.em, arguments); }
                    catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    if (method.getName().equals("find") && arguments[0] == AnalysisDispatchIntent.class && result == null) {
                        bothReadAbsent.countDown();
                        await(bothReadAbsent);
                    }
                    return result;
                });
        var store = new AnalysisDispatchStore(observed, db.transactions);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int commits = 0;
        try {
            var results = List.of(pool.submit(() -> record(1, db, store, task)),
                    pool.submit(() -> record(2, db, store, task)));
            for (var result : results) {
                try { result.get(15, TimeUnit.SECONDS); commits++; }
                catch (ExecutionException failure) {
                    if (!(failure.getCause() instanceof jakarta.persistence.PersistenceException)
                            && !(failure.getCause() instanceof org.springframework.dao.DataAccessException)) throw failure;
                    System.out.println("Observed duplicate dispatch transaction failure: " + failure.getCause().getClass().getSimpleName());
                }
            }
        } finally { stop(pool); }
        int siblings = db.jdbc.queryForObject("select count(*) from qa_operation_write", Integer.class);
        int intents = db.jdbc.queryForObject("select count(*) from analysis_dispatch_intent", Integer.class);
        System.out.println("dispatch commits=" + commits + ", operation writes=" + siblings + ", intents=" + intents);
        if (commits != 2 || siblings != 2 || intents != 1)
            throw new AssertionError("Concurrent idempotent dispatch rolled back an enclosing operation transaction");
    }

    private static void record(int id, Database db, AnalysisDispatchStore store, AnalysisTaskMessage task) {
        new TransactionTemplate(db.transactions).executeWithoutResult(status -> {
            db.jdbc.update("insert into qa_operation_write(id) values (?)", id);
            store.recordIntents(List.of(task));
        });
    }

    static void rollbackKeepsIntentsAtomic() {
        try (var db = new Database()) {
            var store = new AnalysisDispatchStore(db.em, db.transactions);
            var task = db.task("caller-rollback");
            var expected = new IllegalStateException("caller rollback");
            try {
                new TransactionTemplate(db.transactions).executeWithoutResult(status -> {
                    db.jdbc.update("insert into qa_operation_write(id) values (1)");
                    store.recordIntents(List.of(task));
                    throw expected;
                });
                throw new AssertionError("Rollback did not propagate");
            } catch (IllegalStateException failure) { if (failure != expected) throw failure; }
            if (store.find(AnalysisDispatchStore.key(task.taskId())).isPresent()
                    || db.jdbc.queryForObject("select count(*) from qa_operation_write", Integer.class) != 0)
                throw new AssertionError("Intent escaped its caller transaction");
            System.out.println("PASS caller rollback removes the intent and its sibling write");
        }
    }

    static void duplicateThenRollbackPreservesWinner() {
        try (var db = new Database()) {
            var store = new AnalysisDispatchStore(db.em, db.transactions);
            var task = db.task("winner");
            store.recordIntentsInNewTransaction(List.of(task));
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> {
                db.jdbc.update("insert into qa_operation_write(id) values (1)");
                store.recordIntents(List.of(task));
                status.setRollbackOnly();
            });
            if (store.find(AnalysisDispatchStore.key(task.taskId())).isEmpty()
                    || db.jdbc.queryForObject("select count(*) from qa_operation_write", Integer.class) != 0)
                throw new AssertionError("Losing caller damaged the committed winner");
            System.out.println("PASS duplicate caller rollback preserves the committed winner");
        }
    }

    static void publicationOrderIsPreserved() throws Exception {
        try (var db = new Database()) {
            var store = new AnalysisDispatchStore(db.em, db.transactions);
            var a = db.task("order-a");
            var b = db.task("order-b");
            List<AnalysisTaskMessage> input = List.of(a, b);
            var expected = input.stream().map(t -> AnalysisDispatchStore.key(t.taskId())).toList();
            if (!store.recordIntentsInNewTransaction(input).equals(expected))
                throw new AssertionError("Lock ordering changed publication order");
            store.acknowledge(expected.getFirst());
            if (!store.recordIntentsInNewTransaction(input).equals(List.of(expected.getLast())))
                throw new AssertionError("A settled task was published again");
            System.out.println("PASS stable insert ordering retains original publish order and settled filtering");
        }
    }

    static void nonDuplicateConstraintFailurePropagates() {
        try (var db = new Database()) {
            db.jdbc.execute("alter table analysis_dispatch_intent add constraint reject_operation check (operation_id <> 'reject')");
            var store = new AnalysisDispatchStore(db.em, db.transactions);
            boolean rejected = false;
            try {
                new TransactionTemplate(db.transactions).executeWithoutResult(status -> {
                    db.jdbc.update("insert into qa_operation_write(id) values (1)");
                    store.recordIntents(List.of(db.task("reject")));
                });
            } catch (jakarta.persistence.PersistenceException expected) { rejected = true; }
            if (!rejected || db.jdbc.queryForObject("select count(*) from qa_operation_write", Integer.class) != 0
                    || db.jdbc.queryForObject("select count(*) from analysis_dispatch_intent", Integer.class) != 0)
                throw new AssertionError("An unrelated constraint violation was swallowed");
            System.out.println("PASS non-duplicate constraint failures are not disguised as idempotent success");
        }
    }

    static void foreignSourceIsRejected() {
        try (var db = new Database()) {
            var store = new AnalysisDispatchStore(db.em, db.transactions);
            store.recordIntentsInNewTransaction(List.of(db.task("foreign")));
            var factory = new AnalysisMessageFactory(new AnalysisOperationContext("foreign",
                    new AnalysisSourceAuthority("other-repo", "ws", "draft", "c1"),
                    RequirementReference.adHoc("requirement"), null), Clock.systemUTC());
            var task = factory.task(AnalysisTaskGraph.plan("foreign", List.of(TaxonomyShardRoot.of("CP")), false)
                    .tasks().getFirst());
            try {
                store.recordIntentsInNewTransaction(List.of(task));
                throw new AssertionError("Different source reused an intent");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("source identity")) throw expected;
            }
            System.out.println("PASS mismatched dispatch source fails closed");
        }
    }

    static void reversedBatchesCommitWithoutChangingPublicationOrder() throws Exception {
        try (var db = new Database()) {
            var together = new CountDownLatch(2);
            var firstRead = ThreadLocal.withInitial(() -> true);
            EntityManager observed = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                    new Class<?>[]{EntityManager.class}, (proxy, method, arguments) -> {
                        Object result;
                        try { result = method.invoke(db.em, arguments); }
                        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                        if (method.getName().equals("find") && arguments[0] == AnalysisDispatchIntent.class
                                && result == null && firstRead.get()) {
                            firstRead.set(false);
                            together.countDown();
                            await(together);
                        }
                        return result;
                    });
            var store = new AnalysisDispatchStore(observed, db.transactions);
            var a = db.task("batch-a");
            var b = db.task("batch-b");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var forward = pool.submit(() -> store.recordIntentsInNewTransaction(List.of(a, b)));
                var reverse = pool.submit(() -> store.recordIntentsInNewTransaction(List.of(b, a)));
                if (!forward.get(15, TimeUnit.SECONDS).equals(List.of(AnalysisDispatchStore.key(a.taskId()), AnalysisDispatchStore.key(b.taskId())))
                        || !reverse.get(15, TimeUnit.SECONDS).equals(List.of(AnalysisDispatchStore.key(b.taskId()), AnalysisDispatchStore.key(a.taskId()))))
                    throw new AssertionError("Concurrent batch changed publication order");
            } finally { stop(pool); }
            if (db.jdbc.queryForObject("select count(*) from analysis_dispatch_intent", Integer.class) != 2)
                throw new AssertionError("Concurrent batch lost or duplicated intents");
            System.out.println("PASS reversed concurrent batches: both commit in original publication order");
        }
    }

    static void onlyUniqueKeyFailuresAreRecognized() {
        for (var error : List.of(new java.sql.SQLException("duplicate", "23505"),
                new java.sql.SQLException("duplicate", "23000", 1),
                new java.sql.SQLException("duplicate", "23000", 2601),
                new java.sql.SQLException("duplicate", "23000", 2627))) {
            if (!AnalysisInsertIfAbsent.duplicateKey(error)) throw new AssertionError("Known unique-key failure rejected");
        }
        for (var error : List.of(new java.sql.SQLException("not null", "23502"),
                new java.sql.SQLException("foreign key", "23503"),
                new java.sql.SQLException("check", "23513"),
                new java.sql.SQLException("check", "23000", 547),
                new java.sql.SQLException("network", "08006"),
                new java.sql.SQLException("timeout", "HYT00"),
                new java.sql.SQLException("unknown", (String) null))) {
            if (AnalysisInsertIfAbsent.duplicateKey(error)) throw new AssertionError("Non-unique failure swallowed");
        }
        var network = new java.sql.SQLException("network", "08006");
        network.setNextException(new java.sql.SQLException("duplicate detail", "23505"));
        if (AnalysisInsertIfAbsent.duplicateKey(network)) throw new AssertionError("A nested duplicate hid a connection failure");
        System.out.println("PASS only supported unique-key errors are eligible for containment");
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent interleaving was not reached"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Test threads did not terminate");
    }

    static final class Database implements AutoCloseable {
        final LocalContainerEntityManagerFactoryBean bean;
        final EntityManager em;
        final JpaTransactionManager transactions;
        final JdbcTemplate jdbc;
        Database() {
            var ds = new DriverManagerDataSource("jdbc:hsqldb:mem:review-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
            bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(ds);
            bean.setManagedTypes(PersistenceManagedTypes.of(AnalysisDispatchIntent.class.getName(), AnalysisTaskCompletionRecord.class.getName()));
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false"));
            bean.afterPropertiesSet();
            em = SharedEntityManagerCreator.createSharedEntityManager(bean.getObject());
            transactions = new JpaTransactionManager(bean.getObject());
            jdbc = new JdbcTemplate(ds);
            jdbc.execute("create table qa_operation_write(id integer primary key)");
            jdbc.execute("create table qa_handler_effect(id varchar(36) primary key)");
        }
        AnalysisMessageFactory factory(String operation) {
            return new AnalysisMessageFactory(new AnalysisOperationContext(operation,
                    new AnalysisSourceAuthority("repo", "ws", "draft", "c1"),
                    RequirementReference.adHoc("requirement"), null), Clock.systemUTC());
        }
        SubtaxonomyAnalysisTask task(String operation) {
            return (SubtaxonomyAnalysisTask) factory(operation).task(AnalysisTaskGraph.plan(operation,
                    List.of(TaxonomyShardRoot.of("CP")), false).tasks().getFirst());
        }
        @Override public void close() { bean.destroy(); }
    }
}
