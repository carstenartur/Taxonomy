package com.taxonomy.analysis.reformulation;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Bounded ready-task execution; only the caller assembles the ordered result. */
final class WalkUpExecution {
    private WalkUpExecution() {}

    static void validateParallelism(int parallelism) {
        if (parallelism < 1 || parallelism > 8)
            throw new IllegalArgumentException("Reformulation parallelism must be between 1 and 8");
    }

    static <T> Map<String,T> run(List<WalkUpPlanner.Step> plan, int parallelism,
            BiFunction<WalkUpPlanner.Step,List<T>,Supplier<T>> prepare) {
        validateParallelism(parallelism);
        var ids = new HashSet<String>();
        for (var step : plan) {
            if (!ids.containsAll(step.childTaskIds()) || !ids.add(step.nodeId()))
                throw new IllegalArgumentException("Walk-up plan is not a unique topological order");
        }
        var results = new LinkedHashMap<String,T>();
        if (parallelism == 1) {
            for (var step : plan)
                results.put(step.nodeId(), Objects.requireNonNull(prepare.apply(step,
                        step.childTaskIds().stream().map(results::get).toList()).get(), "Missing node result"));
            return Collections.unmodifiableMap(results);
        }
        // This pool is distinct from the parent portfolio executor. No parent waits
        // for work queued into its own saturated executor, and groups remain serial.
        var workers = Executors.newFixedThreadPool(parallelism,
                Thread.ofPlatform().daemon().name("reformulation-node-", 0).factory());
        try {
            var completed = new ExecutorCompletionService<T>(workers);
            var running = new HashMap<Future<T>,String>();
            var submitted = new HashSet<String>();
            while (results.size() < plan.size()) {
                for (var step : plan) {
                    if (running.size() >= parallelism) break;
                    if (submitted.contains(step.nodeId()) || !results.keySet().containsAll(step.childTaskIds())) continue;
                    // Capture input and provider context on the caller, before dispatch.
                    Supplier<T> work = prepare.apply(step, step.childTaskIds().stream().map(results::get).toList());
                    running.put(completed.submit(() -> Objects.requireNonNull(work.get(), "Missing node result")), step.nodeId());
                    submitted.add(step.nodeId());
                }
                if (running.isEmpty()) throw new IllegalStateException("Walk-up has no executable task");
                var finished = completed.take();
                results.put(running.remove(finished), finished.get());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("REFORMULATION_EXECUTION_INTERRUPTED", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("REFORMULATION_NODE_FAILED", failed.getCause());
        } finally {
            // ExecutorService.close() joins forever, even after caller interruption.
            // Preserve promptly completed sibling checkpoints, but never let a
            // stuck provider/supplier prevent failure or shutdown from returning.
            workers.shutdown();
            try {
                if (Thread.currentThread().isInterrupted()
                        || !workers.awaitTermination(1, TimeUnit.SECONDS)) workers.shutdownNow();
            } catch (InterruptedException interrupted) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // Completion order never becomes evidence/document order. Late children
        // remain subject to the existing run/lease fences; they cannot publish.
        var ordered = new LinkedHashMap<String,T>();
        plan.forEach(step -> ordered.put(step.nodeId(), results.get(step.nodeId())));
        return Collections.unmodifiableMap(ordered);
    }
}
