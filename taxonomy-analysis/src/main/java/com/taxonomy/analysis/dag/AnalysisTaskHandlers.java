package com.taxonomy.analysis.dag;

/**
 * Worker-side computation ports. Preparation runs without a result transaction;
 * all durable effects are deferred to PreparedAnalysisCompletion.persistEffect.
 * A null preparation means that this process does not consume that task family.
 * The existing same-thread in-process AnalysisTaskHandler contract is unchanged.
 */
public record AnalysisTaskHandlers(
        AnalysisTaskPreparation<SubtaxonomyAnalysisTask, SubtaxonomyAnalysisCompleted> subtaxonomy,
        AnalysisTaskPreparation<RelationAnalysisTask, RelationAnalysisCompleted> relation) {

    public static final AnalysisTaskHandlers NONE = new AnalysisTaskHandlers(null, null);

    public boolean handles(AnalysisTaskType type) {
        return switch (type) {
            case SUBTAXONOMY_ANALYSIS -> subtaxonomy != null;
            case RELATION_ANALYSIS -> relation != null;
        };
    }

    public PreparedAnalysisCompletion<?> prepare(AnalysisTaskMessage task) {
        PreparedAnalysisCompletion<?> prepared = switch (task) {
            case SubtaxonomyAnalysisTask root -> require(subtaxonomy, task).prepare(root);
            case RelationAnalysisTask relations -> require(relation, task).prepare(relations);
        };
        if (prepared == null) throw new IllegalStateException("Handler returned no prepared completion");
        AnalysisTaskIdentity.requireSameSource(task.envelope(), prepared.completion().envelope());
        return prepared;
    }

    /**
     * Keep the prepare/commit boundary behind the framework-neutral worker ports.
     * The observer accounts for completed computation, even if result commit fails;
     * it is not the business mutation and must not perform durable work.
     */
    public AnalysisCompletionMessage prepareAndCommit(AnalysisTaskMessage task,
            AnalysisTaskCompletionStore store, Runnable preparedObserver) {
        var prepared = prepare(task);
        preparedObserver.run();
        return store.commit(prepared);
    }

    private static <H> H require(H handler, AnalysisTaskMessage task) {
        if (handler == null) throw new IllegalStateException("No handler for " + task.taskType());
        return handler;
    }
}
