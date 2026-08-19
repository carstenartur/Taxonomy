package com.taxonomy.portfolio.model;

/** Operator-declared cost boundary for automated AI work. */
public enum AiCostPolicy {
    /** Calls may have a marginal cost; only explicit user-triggered work is allowed. */
    METERED,
    /** The operator explicitly accepts unattended repeated calls to the configured provider. */
    UNMETERED
}
