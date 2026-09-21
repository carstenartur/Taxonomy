package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Scheduling contracts use graph task IDs, not invented catalogue elements. */
final class WalkUpExecutionChecks {
    private WalkUpExecutionChecks() {}
    private static WalkUpPlanner.Step step(String id, String... children) {
        return new WalkUpPlanner.Step(id, List.of(), List.of(children), List.of());
    }
    static void boundsReadyWorkAndJoinsParents() throws Exception {
        var plan = List.of(step("a"), step("b"), step("c"), step("d"), step("parent", "a", "b", "c", "d"));
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var prepared = new AtomicInteger(); var active = new AtomicInteger(); var maximum = new AtomicInteger();
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> WalkUpExecution.<String>run(plan, 2, (node, children) -> {
                prepared.incrementAndGet();
                return () -> {
                    int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
                    try {
                        if (node.nodeId().equals("a") || node.nodeId().equals("b")) { entered.countDown(); await(release); }
                        if (node.nodeId().equals("parent")) check(children.equals(List.of("a", "b", "c", "d")), "Incomplete or unordered child results");
                        return node.nodeId();
                    } finally { active.decrementAndGet(); }
                };
            }));
            try { check(entered.await(3, TimeUnit.SECONDS), "Ready children were not dispatched"); check(prepared.get() == 2, "More work queued than the bound"); }
            finally { release.countDown(); }
            var values = result.get(5, TimeUnit.SECONDS);
            check(new ArrayList<>(values.keySet()).equals(List.of("a", "b", "c", "d", "parent")), "Result order changed");
            check(maximum.get() == 2, "Concurrency bound was not respected");
        }
    }
    static void failureDrainsSiblingsAndDoesNotStartDescendants() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1); var failed = new CountDownLatch(1);
        var stored = new AtomicBoolean(); var calls = new CopyOnWriteArrayList<String>();
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> WalkUpExecution.<String>run(List.of(step("bad"), step("sibling"), step("parent", "bad", "sibling")), 2,
                    (node, children) -> () -> {
                        calls.add(node.nodeId()); entered.countDown(); await(entered);
                        if (node.nodeId().equals("bad")) { failed.countDown(); throw new IllegalStateException("EXPECTED_NODE_FAILURE"); }
                        await(release); stored.set(true); return node.nodeId();
                    }));
            try {
                check(failed.await(3, TimeUnit.SECONDS), "Failed child was not reached");
                try { result.get(100, TimeUnit.MILLISECONDS); throw new AssertionError("Failure returned while a sibling was still running"); }
                catch (TimeoutException waitingForSibling) { /* Closing must join the in-flight sibling. */ }
            } finally { release.countDown(); }
            try { result.get(5, TimeUnit.SECONDS); throw new AssertionError("Node failure was swallowed"); }
            catch (ExecutionException expected) { check(expected.getCause().getMessage().equals("EXPECTED_NODE_FAILURE"), "Wrong failure escaped"); }
            check(stored.get(), "In-flight sibling result did not finish before failure publication");
            check(!calls.contains("parent"), "Dependent parent ran despite missing child");
        }
    }
    static void malformedPlansAndLimitsFailBeforeWork() {
        for (int width : new int[] {0, -1, 9}) {
            try { WalkUpExecution.run(List.of(), width, (n, c) -> () -> "unexpected"); throw new AssertionError("Invalid width accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("parallelism"), "Wrong limit error"); }
        }
        for (var plan : List.of(List.of(step("a"), step("a")), List.of(step("a", "missing")), List.of(step("a", "b"), step("b", "a")))) {
            try { WalkUpExecution.run(plan, 2, (n, c) -> { throw new AssertionError("Malformed plan started work"); }); throw new AssertionError("Malformed plan accepted"); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains("topological"), "Wrong plan error"); }
        }
    }
    static void providerOverrideIsCapturedAndClearedOnFailure() throws Exception {
        var config = new LlmProviderConfig(null);
        var field = LlmProviderConfig.class.getDeclaredField("llmProviderConfig"); field.setAccessible(true); field.set(config, "LOCAL_ONNX");
        var service = new NodeReformulationService(null, config, new ObjectMapper());
        config.setRequestProvider(LlmProvider.CUSTOM_OPENAI);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var scoped = service.captureProvider(() -> {
                check(config.getActiveProvider() == LlmProvider.CUSTOM_OPENAI, "Lost run provider override");
                throw new IllegalStateException("EXPECTED_PROVIDER_FAILURE");
            });
            try { worker.submit(scoped::get).get(3, TimeUnit.SECONDS); throw new AssertionError("Expected failure missing"); }
            catch (ExecutionException expected) { check(expected.getCause().getMessage().equals("EXPECTED_PROVIDER_FAILURE"), "Wrong scoped error"); }
            check(worker.submit(config::getActiveProvider).get(3, TimeUnit.SECONDS) == LlmProvider.LOCAL_ONNX, "Child provider override leaked");
            check(config.getActiveProvider() == LlmProvider.CUSTOM_OPENAI, "Caller override was cleared");
        } finally { config.clearRequestProvider(); }
    }
    static void parallelismCannotBypassTheCallerTransactionGuard() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            new FrozenReformulationEngine(null, new ObjectMapper(), 2).synthesize(null, List.of(), List.of());
            throw new AssertionError("Parallel dispatch escaped caller transaction guard");
        } catch (IllegalStateException expected) { check(expected.getMessage().equals("LLM_CALL_INSIDE_TRANSACTION"), "Wrong transaction failure"); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        boundsReadyWorkAndJoinsParents(); failureDrainsSiblingsAndDoesNotStartDescendants();
        malformedPlansAndLimitsFailBeforeWork(); providerOverrideIsCapturedAndClearedOnFailure(); parallelismCannotBypassTheCallerTransactionGuard();
        System.out.println("WALK_UP_EXECUTION_BOUNDARIES_OK checks=5");
    }
}
