package com.taxonomy.reformulation;

import java.util.function.Supplier;

/** Explicit per-run boundary for already validated node/reconciliation results; contains no ambient identity. */
public interface ReformulationStepExecutor {
    <T> T execute(String kind, Object input, Class<T> resultType, Supplier<T> work);

    @FunctionalInterface
    interface Operation {
        Object execute(String kind, Object input, Class<?> resultType, Supplier<?> work);
    }

    /** Typed adapter keeps per-run closures at the composition boundary, without ambient state. */
    static ReformulationStepExecutor of(Operation operation) {
        return new ReformulationStepExecutor() {
            @Override public <T> T execute(String kind, Object input, Class<T> resultType, Supplier<T> work) {
                return resultType.cast(operation.execute(kind, input, resultType, work));
            }
        };
    }

    /** Standalone callers keep the existing provider/parser path without persistence. */
    static ReformulationStepExecutor direct() {
        return new ReformulationStepExecutor() {
            @Override public <T> T execute(String kind, Object input, Class<T> resultType, Supplier<T> work) {
                return work.get();
            }
        };
    }
}
