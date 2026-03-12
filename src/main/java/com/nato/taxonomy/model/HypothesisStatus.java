package com.nato.taxonomy.model;

/**
 * Status lifecycle for relation hypotheses.
 */
public enum HypothesisStatus {
    /** Automatically generated, not yet reviewed. */
    PROVISIONAL,
    /** Suggested for review. */
    PROPOSED,
    /** Accepted by a user and promoted to a confirmed relation. */
    ACCEPTED,
    /** Rejected by a user. */
    REJECTED
}
