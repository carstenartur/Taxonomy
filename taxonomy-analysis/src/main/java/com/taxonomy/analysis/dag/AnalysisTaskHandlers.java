package com.taxonomy.analysis.dag;

/**
 * Worker-side handlers a transport may invoke. A {@code null} handler means this
 * process does not execute that task family; its queues are left to other workers.
 */
public record AnalysisTaskHandlers(
        AnalysisTaskHandler<SubtaxonomyAnalysisTask, SubtaxonomyAnalysisCompleted> subtaxonomy,
        AnalysisTaskHandler<RelationAnalysisTask, RelationAnalysisCompleted> relation) {

    public static final AnalysisTaskHandlers NONE = new AnalysisTaskHandlers(null, null);

    public boolean handles(AnalysisTaskType type) {
        return switch (type) {
            case SUBTAXONOMY_ANALYSIS -> subtaxonomy != null;
            case RELATION_ANALYSIS -> relation != null;
        };
    }

    /** Execute {@code task} with the handler of its family. */
    public AnalysisCompletionMessage handle(AnalysisTaskMessage task) {
        AnalysisCompletionMessage completion = switch (task) {
            case SubtaxonomyAnalysisTask root -> require(subtaxonomy, task).handle(root);
            case RelationAnalysisTask relations -> require(relation, task).handle(relations);
        };
        if (completion == null || !task.taskId().equals(completion.taskId())) {
            throw new IllegalStateException("Handler returned no completion for " + task.taskId());
        }
        return completion;
    }

    private static <H> H require(H handler, AnalysisTaskMessage task) {
        if (handler == null) throw new IllegalStateException("No handler for " + task.taskType());
        return handler;
    }
}
