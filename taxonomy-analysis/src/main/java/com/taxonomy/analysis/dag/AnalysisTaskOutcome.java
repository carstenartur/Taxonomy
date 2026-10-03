package com.taxonomy.analysis.dag;

/**
 * Explicit terminal outcome of one task. A missing or failed root is never
 * silently represented as a zero score.
 */
public enum AnalysisTaskOutcome {
    /** The task executed and its result was recorded. */
    COMPLETED,
    /** The task executed, but its result is incomplete; warnings describe the gap. */
    PARTIAL,
    /** The task was not executed because a provider rate limit was already reached. */
    SKIPPED,
    /** The task failed; its result is absent. */
    FAILED,
    /** A cooperative stop (cancellation, time, memory, pending decision) ended the task. */
    STOPPED
}
