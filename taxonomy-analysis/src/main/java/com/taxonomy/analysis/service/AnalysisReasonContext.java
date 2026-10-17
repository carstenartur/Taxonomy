package com.taxonomy.analysis.service;

import com.taxonomy.dto.AnalysisReasonLength;

/** One admitted generation preference, shared with the synchronous task transport and restored after use. */
public final class AnalysisReasonContext {
    public static final String SETTING = "analysis.reason-length";
    public static final String POLICY = "reason-length-v1";
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private AnalysisReasonContext() { }

    /** A missing runtime preference is resolved only at an entry boundary, never during prompt rendering. */
    public static AnalysisReasonLength resolve(AnalysisReasonLength requested, AnalysisRuntimeSettings settings) {
        if (requested != null) return requested;
        Scope current = CURRENT.get();
        if (current != null) return current.length;
        return AnalysisReasonLength.fromValue(settings == null ? "standard" : settings.getString(SETTING, "standard"));
    }

    /** Legacy persisted work runs with the former default even if live preferences have since changed. */
    public static AnalysisReasonLength effective(AnalysisReasonLength value) {
        return value == null ? AnalysisReasonLength.STANDARD : value;
    }

    public static AnalysisReasonLength current() {
        Scope current = CURRENT.get();
        return current == null ? null : current.length;
    }

    public static Scope open(AnalysisReasonLength value) { return new Scope(effective(value)); }

    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Scope previous = CURRENT.get();
        private final AnalysisReasonLength length;
        private boolean closed;

        private Scope(AnalysisReasonLength length) {
            this.length = length;
            CURRENT.set(this);
        }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || CURRENT.get() != this)
                throw new IllegalStateException("Analysis reason scopes must close in order on their owning thread");
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            closed = true;
        }
    }
}
