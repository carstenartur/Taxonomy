package com.taxonomy.analysis.dag;

/** Typed progress phases of an analysis operation's task graph. */
public enum AnalysisProgressPhase {
    PLANNED,
    TASK_DISPATCHED,
    TASK_COMPLETED,
    DIAGNOSTICS_UPDATED,
    OPERATION_STOPPED,
    OPERATION_COMPLETED
}
