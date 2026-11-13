package com.taxonomy.workspace.service;

/** Durable qualification of a branch-local projection command against its captured Git source. */
public interface DerivedProjection {
    enum Kind { RELATION, VECTOR_BUILD, VECTOR_PURGE }

    record Source(String key, String generation) {}
    record Result(String generation, String model, int nodes, int relations,
                  Integer recovered, Integer superseded, Integer pending, String state, String currentCommit, String projectedCommit) {
        public Result(String generation, String model, int nodes, int relations, Integer recovered, Integer superseded, Integer pending) {
            this(generation, model, nodes, relations, recovered, superseded, pending, null, null, null);
        }
    }

    class DecisionRequired extends IllegalStateException {
        private final String code;
        private final boolean retryable;
        private final Result result;

        public DecisionRequired(String code, boolean retryable, Result result, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.retryable = retryable;
            this.result = result;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public Result result() { return result; }
    }

    Source capture(RepositoryContext scope, Kind kind, String expectedCommit);

    Result apply(RepositoryContext scope, Kind kind, String expectedCommit, Source source,
                 String operationId, Runnable authorize);

    String describe(Result result);
    Result readResult(String stored);

    void verifyHead(RepositoryContext scope, String expectedCommit);

    class HeadChanged extends IllegalStateException {
        private final String actual;

        public HeadChanged(String expected, String actual) {
            super("Projection expected Git head " + expected + " but observed " + actual);
            this.actual = actual;
        }

        public String actual() { return actual; }
    }

    class SourceUnavailable extends IllegalStateException {
        public SourceUnavailable(Throwable cause) { super("Captured projection source is unavailable", cause); }
    }

    class SourceChanged extends IllegalStateException {
        public SourceChanged(String message) { super(message); }
    }
}
