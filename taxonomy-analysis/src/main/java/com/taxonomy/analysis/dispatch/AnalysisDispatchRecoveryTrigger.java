package com.taxonomy.analysis.dispatch;

/**
 * The only events that may scan for undelivered dispatch intents. There is
 * deliberately no periodic trigger: normal execution is pushed by the
 * after-commit publish and the broker.
 */
public enum AnalysisDispatchRecoveryTrigger {
    /** Application start, after the broker connection is first established. */
    STARTUP,
    /** An explicit operator/user repair or resume request. */
    EXPLICIT_REPAIR,
    /** The broker connection was re-established after a detected failure. */
    BROKER_RECONNECT
}
