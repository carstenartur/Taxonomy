package com.taxonomy.analysis.dag;

/** Typed progress phases of an analysis operation's task graph. */
public enum AnalysisProgressPhase {
    PLANNED,
    TASK_DISPATCHED,
    TASK_COMPLETED,
    OPERATION_STOPPED,
    OPERATION_COMPLETED
}
