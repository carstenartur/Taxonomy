package com.taxonomy.composition.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationRecoveryService.Claim;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic checks of the real private reservation; no provider, DB or executor mock. */
public final class LocalExecutionChecks {
    private LocalExecutionChecks() {}

    public static void main(String[] args) throws Exception {
        lateClaimIsNotPublished();
        duplicateDeliveryOwnsNoCleanup();
        retirementBeforeStartOwnsNoCleanup();
        retirementDoesNotWaitForWork();
        retiredWorkerCannotFinalize();
        admittedFinalizationIsNotInterrupted();
        detachedRunnerIsNotInterruptedDuringCleanup();
        failureStillDetachesAndCleansUp();
        System.out.println("LOCAL_EXECUTION_CHECKS_OK checks=8");
    }

    public static void lateClaimIsNotPublished() throws Exception {
        var local = new Reservation();
        local.run(() -> {
            local.retire();
            Thread.interrupted();
            check(!local.attach(new Claim(null, "test-owner", 1)), "Retired reservation accepted a late claim");
            check(local.claim() == null, "Rejected late claim was published to heartbeat readers");
        }, () -> {});
    }

    public static void duplicateDeliveryOwnsNoCleanup() throws Exception {
        var local = new Reservation();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var bodyCount = new AtomicInteger(); var cleanupCount = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        Thread worker = launch(() -> local.run(() -> {
            bodyCount.incrementAndGet(); entered.countDown(); hold(release);
        }, cleanupCount::incrementAndGet), failure);
        try {
            await(entered);
            local.run(bodyCount::incrementAndGet, cleanupCount::incrementAndGet);
            check(bodyCount.get() == 1 && cleanupCount.get() == 0, "Duplicate delivery ran work or cleaned another owner");
        } finally { release.countDown(); join(worker, failure); }
        local.run(bodyCount::incrementAndGet, cleanupCount::incrementAndGet);
        check(bodyCount.get() == 1 && cleanupCount.get() == 1, "Finished reservation ran again");
    }

    public static void retirementBeforeStartOwnsNoCleanup() throws Exception {
        var local = new Reservation(); var calls = new AtomicInteger();
        local.retire(); local.run(calls::incrementAndGet, calls::incrementAndGet);
        check(calls.get() == 0, "Retired queue delivery ran work or cleanup");
        check(!local.attach(new Claim(null, "late", 1)) && local.claim() == null, "Unstarted reservation acquired a claim");
    }

    public static void retirementDoesNotWaitForWork() throws Exception {
        var local = new Reservation(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1); var failure = new AtomicReference<Throwable>();
        Thread worker = launch(() -> local.run(() -> { entered.countDown(); hold(release); }, () -> {}), failure);
        Thread stopper = null;
        try {
            await(entered);
            stopper = launch(() -> { local.retire(); stopped.countDown(); }, failure);
            await(stopped); // Work is deliberately still blocked: no wall-clock performance assertion.
            check(worker.isAlive(), "Test did not hold work across retirement");
        } finally { release.countDown(); join(worker, failure); if (stopper != null) join(stopper, failure); }
    }

    public static void retiredWorkerCannotFinalize() throws Exception {
        var local = new Reservation();
        local.run(() -> {
            check(local.attach(new Claim(null, "owner", 1)), "Active claim rejected");
            local.retire(); Thread.interrupted(); // A DB driver may consume interruption.
            check(!local.beginFinalization(), "Retired worker gained publication permission");
        }, () -> {});
    }

    public static void admittedFinalizationIsNotInterrupted() throws Exception {
        var local = new Reservation(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread worker = launch(() -> local.run(() -> {
            check(local.attach(new Claim(null, "owner", 1)), "Active claim rejected");
            check(local.beginFinalization(), "Live worker could not finalize");
            check(!local.beginFinalization(), "Finalization admitted twice");
            entered.countDown(); await(release);
            check(!Thread.currentThread().isInterrupted(), "Admitted commit was interrupted");
        }, () -> {}), failure);
        try { await(entered); local.retire(); }
        finally { release.countDown(); join(worker, failure); }
    }

    public static void detachedRunnerIsNotInterruptedDuringCleanup() throws Exception {
        var local = new Reservation(); var cleanup = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread worker = launch(() -> local.run(() -> {}, () -> {
            cleanup.countDown(); await(release);
            check(!Thread.currentThread().isInterrupted(), "Old reservation interrupted detached runner");
        }), failure);
        try { await(cleanup); local.retire(); }
        finally { release.countDown(); join(worker, failure); }
    }

    public static void failureStillDetachesAndCleansUp() throws Exception {
        var local = new Reservation(); var cleanup = new AtomicInteger();
        var expected = new IllegalArgumentException("controlled work failure");
        try { local.run(() -> { throw expected; }, cleanup::incrementAndGet); throw new AssertionError("Failure was swallowed"); }
        catch (IllegalArgumentException actual) { check(actual == expected, "Original exception changed"); }
        local.retire();
        check(!Thread.currentThread().isInterrupted(), "Failed body retained a runner");
        check(cleanup.get() == 1, "Cleanup not called exactly once after failure");
    }

    private static Thread launch(Runnable action, AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().daemon().start(() -> {
            try { action.run(); } catch (Throwable error) { failure.compareAndSet(null, error); }
        });
    }
    private static void join(Thread thread, AtomicReference<Throwable> failure) throws Exception {
        thread.join(5_000); check(!thread.isAlive(), "Reservation worker did not finish");
        if (failure.get() != null) throw new AssertionError("Reservation worker failed", failure.get());
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "Reservation barrier not reached"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError("Unexpected interruption", error); }
    }
    private static void hold(CountDownLatch release) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (release.getCount() != 0) {
            check(System.nanoTime() < deadline, "Work barrier not released");
            try { release.await(50, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* blocking driver */ }
        }
        Thread.interrupted();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Reflection keeps the scheduler's private lifecycle private instead of adding a test-only product API. */
    private static final class Reservation {
        private final Class<?> type;
        private final Object target;
        Reservation() throws Exception {
            type = Class.forName(ReformulationExecutionService.class.getName() + "$LocalExecution");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); target = constructor.newInstance();
        }
        void run(Runnable body, Runnable cleanup) { invoke("runOnce", new Class<?>[]{Runnable.class, Runnable.class}, body, cleanup); }
        void retire() { invoke("retire", new Class<?>[]{}); }
        boolean attach(Claim claim) { return (boolean) invoke("attach", new Class<?>[]{Claim.class}, claim); }
        Object claim() { return invoke("claim", new Class<?>[]{}); }
        boolean beginFinalization() { return (boolean) invoke("beginFinalization", new Class<?>[]{}); }
        private Object invoke(String name, Class<?>[] parameters, Object... values) {
            try { var method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method.invoke(target, values); }
            catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException runtime) throw runtime;
                if (error.getCause() instanceof Error fatal) throw fatal;
                throw new AssertionError(error.getCause());
            } catch (ReflectiveOperationException error) { throw new AssertionError("Missing reservation operation: " + name, error); }
        }
    }
}
