package com.taxonomy.analysis.dispatch;

import com.taxonomy.analysis.dag.*;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises real database effects, not just the number of completion records. */
public final class AnalysisDurableEffectCases {
    public static void main(String[] args) throws Exception {
        concurrentSameTask();
        rollbackAndRetry(false);
        rollbackAndRetry(true);
        independentTasksOverlap();
        preparationIsOutsideCommitAndReplaySkipsEffect();
        wrongSourceCannotReuseWinner();
        System.out.println("Durable effect cases=6, failed=0");
    }

    static void concurrentSameTask() throws Exception {
        try (var db = new DispatchInsertConcurrencyCases.Database()) {
            var absent = new CountDownLatch(2);
            var store = new JpaAnalysisTaskCompletionStore(observeAbsent(db.em, absent), db.transactions);
            var task = db.task("same-task");
            var completion = db.factory("same-task").completed(task, AnalysisTaskOutcome.COMPLETED, 80, 3, null);
            var callbacks = new AtomicInteger();
            Callable<AnalysisCompletionMessage> delivery = () -> store.commit(new PreparedAnalysisCompletion<>(completion, () -> {
                check(TransactionSynchronizationManager.isActualTransactionActive(), "Effect needs the result transaction");
                callbacks.incrementAndGet();
                effect(db);
            }));
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(delivery);
                var second = pool.submit(delivery);
                check(first.get(15, TimeUnit.SECONDS).equals(second.get(15, TimeUnit.SECONDS)), "Different winners");
            }
            check(callbacks.get() == 1 && effects(db) == 1 && records(db) == 1,
                    "Concurrent deliveries must commit exactly one durable effect");
            System.out.println("PASS concurrent same task: one callback, one effect, one completion");
        }
    }

    static void rollbackAndRetry(boolean crash) throws Exception {
        try (var db = new DispatchInsertConcurrencyCases.Database()) {
            var store = new JpaAnalysisTaskCompletionStore(db.em, db.transactions);
            var task = db.task("rollback");
            var completion = db.factory("rollback").completed(task, AnalysisTaskOutcome.COMPLETED, 80, 3, null);
            Throwable expected = crash ? new AssertionError("simulated process failure") : new IllegalStateException("result failed");
            try {
                store.commit(new PreparedAnalysisCompletion<>(completion, () -> {
                    effect(db);
                    db.em.flush();
                    if (expected instanceof Error error) throw error;
                    throw (RuntimeException) expected;
                }));
                throw new AssertionError("Failed effect was accepted");
            } catch (Throwable actual) {
                check(actual == expected, "The original failure must propagate");
            }
            check(effects(db) == 0 && records(db) == 0, "Result and completion must roll back together");
            check(store.find(task.taskId()).isEmpty(), "No phantom completion after rollback");
            store.commit(new PreparedAnalysisCompletion<>(completion, () -> effect(db)));
            check(effects(db) == 1 && records(db) == 1, "A redelivery must recover after rollback");
            System.out.println("PASS rollback/crash=" + crash + ": no partial commit; retry succeeds");
        }
    }

    static void independentTasksOverlap() throws Exception {
        try (var db = new DispatchInsertConcurrencyCases.Database()) {
            var store = new JpaAnalysisTaskCompletionStore(db.em, db.transactions);
            var entered = new CountDownLatch(2);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                var futures = new java.util.ArrayList<Future<?>>();
                for (String operation : List.of("independent-a", "independent-b")) {
                    var completion = db.factory(operation).completed(db.task(operation), AnalysisTaskOutcome.COMPLETED, 80, 3, null);
                    futures.add(pool.submit(() -> store.commit(new PreparedAnalysisCompletion<>(completion, () -> {
                        entered.countDown();
                        await(entered);
                        effect(db);
                    }))));
                }
                for (var future : futures) future.get(15, TimeUnit.SECONDS);
            }
            check(effects(db) == 2 && records(db) == 2, "Distinct tasks were serialized or lost");
            System.out.println("PASS independent tasks overlap without a global lock");
        }
    }

    static void preparationIsOutsideCommitAndReplaySkipsEffect() throws Exception {
        try (var db = new DispatchInsertConcurrencyCases.Database()) {
            var task = db.task("prepare");
            var preparations = new AtomicInteger();
            var handlers = new AnalysisTaskHandlers(root -> {
                check(!TransactionSynchronizationManager.isActualTransactionActive(), "Provider preparation holds a DB transaction");
                preparations.incrementAndGet();
                return new PreparedAnalysisCompletion<>(db.factory("prepare").completed(root,
                        AnalysisTaskOutcome.COMPLETED, 80, 3, null), () -> {
                    check(TransactionSynchronizationManager.isActualTransactionActive(), "Result write escaped the transaction");
                    effect(db);
                });
            }, null);
            var prepared = handlers.prepare(task);
            var store = new JpaAnalysisTaskCompletionStore(db.em, db.transactions);
            var winner = store.commit(prepared);
            var restarted = new JpaAnalysisTaskCompletionStore(db.em, db.transactions);
            check(restarted.find(task.taskId()).orElseThrow().equals(winner), "Durable replay lost its winner");
            check(restarted.commit(new PreparedAnalysisCompletion<>(prepared.completion(), () -> {
                throw new AssertionError("A recorded task executed its durable effect again");
            })).equals(winner), "Replay changed its completion");
            check(preparations.get() == 1 && effects(db) == 1, "Preparation/effect count mismatch");
            System.out.println("PASS preparation outside transaction and restart-safe replay");
        }
    }

    static void wrongSourceCannotReuseWinner() throws Exception {
        try (var db = new DispatchInsertConcurrencyCases.Database()) {
            String operation = "source-collision";
            var store = new JpaAnalysisTaskCompletionStore(db.em, db.transactions);
            var task = db.task(operation);
            store.commit(new PreparedAnalysisCompletion<>(db.factory(operation).completed(task,
                    AnalysisTaskOutcome.COMPLETED, 80, 3, null), () -> effect(db)));
            var foreign = new AnalysisMessageFactory(new AnalysisOperationContext(operation,
                    new AnalysisSourceAuthority("another-repository", "ws", "draft", "c1"),
                    RequirementReference.adHoc("requirement"), null), Clock.systemUTC());
            var foreignTask = (SubtaxonomyAnalysisTask) foreign.task(AnalysisTaskGraph.plan(operation,
                    List.of(TaxonomyShardRoot.of("CP")), false).tasks().getFirst());
            try {
                store.commit(new PreparedAnalysisCompletion<>(foreign.completed(foreignTask,
                        AnalysisTaskOutcome.COMPLETED, 80, 3, null), () -> effect(db)));
                throw new AssertionError("A different authority reused the task winner");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("source identity"), "Wrong rejection");
            }
            check(effects(db) == 1 && records(db) == 1, "Rejected source produced an effect");
            System.out.println("PASS source mismatch fails closed before durable effects");
        }
    }

    private static EntityManager observeAbsent(EntityManager target, CountDownLatch absent) {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, arguments) -> {
                    Object value;
                    try { value = method.invoke(target, arguments); }
                    catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    if (method.getName().equals("find") && arguments[0] == AnalysisTaskCompletionRecord.class && value == null) {
                        absent.countDown();
                        await(absent);
                    }
                    return value;
                });
    }

    private static void effect(DispatchInsertConcurrencyCases.Database db) {
        db.jdbc.update("insert into qa_handler_effect(id) values (?)", UUID.randomUUID().toString());
    }
    private static int effects(DispatchInsertConcurrencyCases.Database db) {
        return db.jdbc.queryForObject("select count(*) from qa_handler_effect", Integer.class);
    }
    private static int records(DispatchInsertConcurrencyCases.Database db) {
        return db.jdbc.queryForObject("select count(*) from analysis_task_completion", Integer.class);
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "Required interleaving did not occur"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
