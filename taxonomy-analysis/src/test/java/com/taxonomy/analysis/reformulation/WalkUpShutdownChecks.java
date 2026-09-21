package com.taxonomy.analysis.reformulation;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Deliberately uncooperative children are released in finally; no test leaks threads. */
final class WalkUpShutdownChecks {
    private WalkUpShutdownChecks() {}

    static void failedChildDoesNotWaitForeverForSibling() throws Exception {
        exercise(false);
    }
    static void interruptedCallerDoesNotWaitForeverForChildren() throws Exception {
        exercise(true);
    }
    private static void exercise(boolean interruptCaller) throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var childInterrupted = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var childrenDone = new CountDownLatch(2);
        var parentCalled = new AtomicBoolean();
        var retainedInterrupt = new AtomicBoolean();
        var error = new AtomicReference<Throwable>();
        Thread caller = Thread.ofPlatform().daemon().start(() -> {
            try {
                WalkUpExecution.run(List.of(step("failing"), step("blocked"), step("parent", "failing", "blocked")), 2,
                        (node, children) -> () -> {
                            if (node.nodeId().equals("parent")) { parentCalled.set(true); return "unexpected"; }
                            entered.countDown();
                            try {
                                await(entered, 3);
                                if (!interruptCaller && node.nodeId().equals("failing")) throw new IllegalStateException("EXPECTED_FAILURE");
                                // Model arbitrary in-flight code which does not promptly honor interruption.
                                while (release.getCount() != 0) {
                                    try { release.await(); }
                                    catch (InterruptedException ignored) { childInterrupted.countDown(); }
                                }
                                return node.nodeId();
                            } finally { childrenDone.countDown(); }
                        });
                error.set(new AssertionError("Missing failure/interruption"));
            } catch (Throwable failure) { error.set(failure); }
            finally { retainedInterrupt.set(Thread.currentThread().isInterrupted()); returned.countDown(); }
        });
        try {
            await(entered, 3);
            if (interruptCaller) caller.interrupt();
            if (!returned.await(3, TimeUnit.SECONDS))
                throw new AssertionError("Walk-up caller waits indefinitely for an uncooperative child");
            if (!childInterrupted.await(1, TimeUnit.SECONDS)) throw new AssertionError("Unfinished child was not interrupted");
            String expected = interruptCaller ? "REFORMULATION_EXECUTION_INTERRUPTED" : "EXPECTED_FAILURE";
            if (error.get() == null || !expected.equals(error.get().getMessage())) throw new AssertionError("Original failure was replaced", error.get());
            if (interruptCaller && !retainedInterrupt.get()) throw new AssertionError("Caller interrupt status was lost");
            if (parentCalled.get()) throw new AssertionError("Dependent parent ran with failed children");
        } finally {
            release.countDown();
            caller.join(5_000);
            if (caller.isAlive() || !childrenDone.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test cleanup failed");
        }
    }
    private static WalkUpPlanner.Step step(String id, String... children) {
        return new WalkUpPlanner.Step(id, List.of(), List.of(children), List.of());
    }
    private static void await(CountDownLatch latch, int seconds) {
        try { if (!latch.await(seconds, TimeUnit.SECONDS)) throw new AssertionError("Test barrier not reached"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("failure")) failedChildDoesNotWaitForeverForSibling();
        if (args.length == 0 || args[0].equals("interrupt")) interruptedCallerDoesNotWaitForeverForChildren();
        System.out.println("WALK_UP_BOUNDED_SHUTDOWN_OK");
    }
}
