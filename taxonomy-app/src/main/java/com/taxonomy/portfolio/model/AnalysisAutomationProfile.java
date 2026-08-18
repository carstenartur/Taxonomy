package com.taxonomy.portfolio.model;

/** Depth of the server-side, persisted requirement-analysis workflow. */
public enum AnalysisAutomationProfile {
    /** One immutable analysis snapshot without automatic catalogue proposals. */
    STANDARD,
    /** Full snapshot plus deterministic solution and product proposals for human review. */
    FULL,
    /** Multiple independent passes plus full deterministic proposal enrichment. */
    EXHAUSTIVE
}
