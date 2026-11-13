package com.taxonomy.relations.service;

/** Bounded response for an unavailable requirement coverage source; holds no database or Git diagnostics. */
public final class CoverageSourceUnavailable extends RuntimeException {
    public enum State { MISSING, STALE, FAILED }
    private final State state;
    private final String head;
    public CoverageSourceUnavailable(State state, String head) {
        super(state.name());
        this.state = state;
        this.head = head;
    }
    public State state() { return state; }
    public String head() { return head; }
}
