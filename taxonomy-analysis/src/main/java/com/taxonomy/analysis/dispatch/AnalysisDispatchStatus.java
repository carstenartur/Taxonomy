package com.taxonomy.analysis.dispatch;

/** Durable delivery state of one task's dispatch intent. */
public enum AnalysisDispatchStatus {
    /** Persisted with the operation; the after-commit publish has not been acknowledged yet. */
    DISPATCH_PENDING,
    /** The broker accepted the task. Delivery and redelivery are now owned by the broker. */
    DISPATCHED,
    /** The broker was unreachable; the next startup, explicit repair or reconnect dispatches it. */
    WAITING_FOR_BROKER,
    /** The intent can never be published (e.g. it violates the message contract); visible, not retried. */
    DISPATCH_FAILED;

    public boolean recoverable() {
        return this == DISPATCH_PENDING || this == WAITING_FOR_BROKER;
    }
}
