package com.taxonomy.portfolio.reformulation;

import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic local lifecycle checks; real database/HTTP recovery stays in LocalRecoveryTest. */
class ReformulationExecutionReservationTest {
    @Test void retiredDeliveryNeverEnters() throws Exception {
        var reservation = new Reservation();
        reservation.retire();
        reservation.runOnce(() -> { throw new AssertionError("Retired delivery entered"); });
    }

    @Test void duplicateDeliveryCannotDetachOrCleanUpTheActiveRunner() throws Exception {
        var reservation = new Reservation();
        var entered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var calls = new AtomicInteger();
        Thread worker = launch(() -> reservation.runOnce(() -> {
            check(!Thread.holdsLock(reservation.value), "Work ran while holding the retirement monitor");
            calls.incrementAndGet();
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.set(true); }
        }), failure);
        try {
            await(entered);
            reservation.runOnce(() -> calls.incrementAndGet());
            check(calls.get() == 1, "Duplicate delivery performed work");
            reservation.retire();
            join(worker);
            check(interrupted.get(), "Duplicate delivery detached the original runner");
            check(failure.get() == null, "Worker failure: " + failure.get());
        } finally { worker.interrupt(); join(worker); }
    }

    @Test void alreadyAdmittedFinalizationIsNotInterruptedByRetirement() throws Exception {
        var reservation = new Reservation();
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread worker = launch(() -> reservation.runOnce(() -> {
            check(reservation.beginFinalization(), "Finalization was rejected before retirement");
            check(!reservation.beginFinalization(), "Finalization was admitted twice");
            admitted.countDown();
            await(release);
        }), failure);
        try {
            await(admitted);
            reservation.retire();
            release.countDown();
            join(worker);
            check(failure.get() == null, "Retirement interrupted admitted finalization: " + failure.get());
        } finally { release.countDown(); worker.interrupt(); join(worker); }
    }

    @Test void exceptionalCompletionDetachesBeforeTheExecutorReusesTheThread() throws Exception {
        var reservation = new Reservation();
        var detached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var expected = new IllegalStateException("authored callback failure");
        Thread worker = launch(() -> {
            try {
                reservation.runOnce(() -> { throw expected; });
                throw new AssertionError("Callback failure was swallowed");
            } catch (IllegalStateException actual) {
                check(actual == expected, "Callback exception was replaced");
            }
            detached.countDown();
            // The same worker now belongs to unrelated executor work.
            await(release);
        }, failure);
        try {
            await(detached);
            reservation.retire();
            release.countDown();
            join(worker);
            check(failure.get() == null, "Old reservation interrupted reused worker: " + failure.get());
            reservation.runOnce(() -> { throw new AssertionError("Completed delivery entered again"); });
        } finally { release.countDown(); worker.interrupt(); join(worker); }
    }

    @Test void retirementBeforeFinalizationRejectsPublication() throws Exception {
        var reservation = new Reservation();
        reservation.retire();
        check(!reservation.beginFinalization(), "Retired delivery admitted publication");
    }

    /** The same assertions can run without starting an application or a database. */
    public static void main(String[] args) throws Exception {
        var checks = new ReformulationExecutionReservationTest();
        checks.retiredDeliveryNeverEnters();
        checks.duplicateDeliveryCannotDetachOrCleanUpTheActiveRunner();
        checks.alreadyAdmittedFinalizationIsNotInterruptedByRetirement();
        checks.exceptionalCompletionDetachesBeforeTheExecutorReusesTheThread();
        checks.retirementBeforeFinalizationRejectsPublication();
        System.out.println("REFORMULATION_RESERVATION_OK checks=5");
    }

    private static Thread launch(Runnable action, AtomicReference<Throwable> failure) {
        Thread worker = new Thread(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
        }, "reformulation-reservation-check");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "Lifecycle barrier timed out"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Unexpected interruption", interrupted);
        }
    }

    private static void join(Thread worker) throws InterruptedException {
        worker.join(5_000);
        check(!worker.isAlive(), "Worker did not return");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Keep the production reservation private rather than widening the application API for tests. */
    private static final class Reservation {
        private final Object value;
        private final Method runOnce;
        private final Method retire;
        private final Method beginFinalization;

        Reservation() throws ReflectiveOperationException {
            Class<?> type = Class.forName(ReformulationExecutionService.class.getName() + "$LocalExecution");
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            value = constructor.newInstance();
            runOnce = method(type, "runOnce", Runnable.class);
            retire = method(type, "retire");
            beginFinalization = method(type, "beginFinalization");
        }

        void runOnce(Runnable work) { invoke(runOnce, work); }
        void retire() { invoke(retire); }
        boolean beginFinalization() { return (boolean) invoke(beginFinalization); }

        private static Method method(Class<?> type, String name, Class<?>... arguments) throws NoSuchMethodException {
            Method method = type.getDeclaredMethod(name, arguments);
            method.setAccessible(true);
            return method;
        }

        private Object invoke(Method method, Object... arguments) {
            try { return method.invoke(value, arguments); }
            catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
                if (wrapped.getCause() instanceof Error failure) throw failure;
                throw new AssertionError(wrapped.getCause());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }
}
