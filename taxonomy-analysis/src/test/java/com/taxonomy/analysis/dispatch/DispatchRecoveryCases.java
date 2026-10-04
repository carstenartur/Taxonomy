package com.taxonomy.analysis.dispatch;

import com.taxonomy.analysis.dag.*;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Controlled repository/publisher boundaries; the dispatch service itself is real. */
final class DispatchRecoveryCases {
    enum Exit { SUCCESS, BROKER_UNAVAILABLE, RUNTIME_FAILURE, FATAL_FAILURE }
    record Case(String name, Exit exit, int queuedEvents) { }

    static List<Case> cases() {
        return List.of(
                new Case("reconnect during failed exchange is consumed", Exit.BROKER_UNAVAILABLE, 1),
                new Case("reconnect survives an unexpected scan failure", Exit.RUNTIME_FAILURE, 1),
                new Case("successful owner consumes pending repair", Exit.SUCCESS, 1),
                new Case("eight concurrent requests coalesce without parallel scans", Exit.SUCCESS, 8),
                new Case("broker failure without event is not polled", Exit.BROKER_UNAVAILABLE, 0),
                new Case("unexpected failure without event is propagated", Exit.RUNTIME_FAILURE, 0),
                new Case("fatal failure releases ownership for a later explicit event", Exit.FATAL_FAILURE, 0)
        );
    }

    static void verify(Case test) throws Exception {
        var store = new Store();
        var exchangeEntered = new CountDownLatch(1);
        var exchangeReleased = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var inExchange = new AtomicInteger();
        var runtimeFailure = new IllegalStateException("injected durable-store failure");
        var fatalFailure = new AssertionError("injected process failure");
        AnalysisTaskPublisher publisher = task -> {
            if (inExchange.incrementAndGet() != 1) throw new AssertionError("Parallel recovery publishers");
            try {
                if (calls.incrementAndGet() == 1) {
                    exchangeEntered.countDown();
                    await(exchangeReleased);
                    switch (test.exit()) {
                        case BROKER_UNAVAILABLE -> throw new AnalysisTransportUnavailableException("unavailable", null);
                        case RUNTIME_FAILURE -> throw runtimeFailure;
                        case FATAL_FAILURE -> throw fatalFailure;
                        case SUCCESS -> { }
                    }
                }
            } finally {
                inExchange.decrementAndGet();
            }
        };
        var service = new AnalysisDispatchService(store, publisher, 1, 10);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AnalysisDispatchService.RecoveryReport> owner = executor.submit(() ->
                    service.recover(AnalysisDispatchRecoveryTrigger.STARTUP));
            if (!exchangeEntered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Owner did not enter publish");
            for (int i = 0; i < test.queuedEvents(); i++) {
                var queued = executor.submit(() -> service.recover(AnalysisDispatchRecoveryTrigger.BROKER_RECONNECT))
                        .get(5, TimeUnit.SECONDS);
                check(queued.scanned() == 0, "Queued caller must not start its own scan");
            }
            exchangeReleased.countDown();
            try {
                var report = owner.get(5, TimeUnit.SECONDS);
                if (test.exit() == Exit.RUNTIME_FAILURE || test.exit() == Exit.FATAL_FAILURE)
                    throw new AssertionError("Original failure was swallowed");
                if (test.queuedEvents() > 0)
                    check(report.trigger() == AnalysisDispatchRecoveryTrigger.BROKER_RECONNECT,
                            "The pending event was not consumed before ownership was released");
            } catch (ExecutionException failure) {
                if (test.exit() == Exit.RUNTIME_FAILURE) check(failure.getCause() == runtimeFailure, "Wrong runtime failure");
                else if (test.exit() == Exit.FATAL_FAILURE) check(failure.getCause() == fatalFailure, "Wrong fatal failure");
                else throw failure;
            }
            boolean completed = test.exit() == Exit.SUCCESS || test.queuedEvents() > 0;
            check(store.settled == completed, "Pending reconnect left a recoverable intent stranded");
            int expectedCalls = test.queuedEvents() > 0 && test.exit() != Exit.SUCCESS ? 2 : 1;
            check(calls.get() == expectedCalls, "Unexpected publish count or automatic retry without an event");
            int before = store.pages.get();
            service.recover(AnalysisDispatchRecoveryTrigger.EXPLICIT_REPAIR);
            check(store.pages.get() > before, "Ownership was not released for the next explicit event");
            check(store.settled, "Explicit subsequent event could not recover pending work");
        } finally {
            exchangeReleased.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Leaked recovery test thread");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Controlled exchange was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** No database is substituted inside a tested transaction: this suite tests only event handoff. */
    private static final class Store extends AnalysisDispatchStore {
        private final AnalysisTaskMessage task;
        private final Intent intent;
        final AtomicInteger pages = new AtomicInteger();
        volatile boolean settled;

        Store() {
            super(unusedEntityManager(), unusedTransactions());
            var factory = new AnalysisMessageFactory(new AnalysisOperationContext("recovery-op",
                    new AnalysisSourceAuthority("repo", "ws", "draft", "c1"),
                    RequirementReference.adHoc("requirement"), null), Clock.systemUTC());
            task = factory.task(AnalysisTaskGraph.plan("recovery-op", List.of(TaxonomyShardRoot.of("CP")), false)
                    .tasks().getFirst());
            intent = new Intent(key(task.taskId()), task.taskId(), AnalysisDispatchStatus.DISPATCH_PENDING,
                    "", 0, 1L, null);
        }
        @Override public List<Intent> recoverable(Cursor cursor, int limit) {
            pages.incrementAndGet();
            return !settled && cursor.equals(Cursor.START) ? List.of(intent) : List.of();
        }
        @Override public Optional<Intent> find(String id) { return settled ? Optional.empty() : Optional.of(intent); }
        @Override AnalysisMessage decode(Intent ignored) { return task; }
        @Override public boolean acknowledge(String id) { settled = true; return true; }
        @Override public boolean markWaiting(String id, String kind) { return true; }
        @Override public long count(AnalysisDispatchStatus status) { return settled ? 0L : 1L; }
    }

    private static EntityManager unusedEntityManager() {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[] { EntityManager.class }, (proxy, method, args) -> {
                    throw new AssertionError("Unexpected database call: " + method.getName());
                });
    }
    private static PlatformTransactionManager unusedTransactions() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { throw new AssertionError("Unexpected transaction"); }
            public void commit(TransactionStatus status) { throw new AssertionError("Unexpected commit"); }
            public void rollback(TransactionStatus status) { throw new AssertionError("Unexpected rollback"); }
        };
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        for (Case test : cases()) {
            try { verify(test); System.out.println("PASS " + test.name()); }
            catch (AssertionError error) { failures++; System.out.println("FAIL " + test.name() + ": " + error.getMessage()); }
        }
        System.out.println("Recovery cases=" + cases().size() + ", failed=" + failures);
        if (failures != 0) throw new AssertionError(failures + " recovery regressions failed");
    }
}
