package com.taxonomy.analysis.dag;

/** Executable task families of one analysis operation. */
public enum AnalysisTaskType {
    /** Root relevance and hierarchical scoring of one taxonomy root. */
    SUBTAXONOMY_ANALYSIS("subtaxonomy"),
    /** Relation search/generation over persisted scoring results. */
    RELATION_ANALYSIS("relation");

    private final String token;

    AnalysisTaskType(String token) {
        this.token = token;
    }

    /** Stable lower-case token used in deterministic task identifiers. */
    public String token() {
        return token;
    }
}
