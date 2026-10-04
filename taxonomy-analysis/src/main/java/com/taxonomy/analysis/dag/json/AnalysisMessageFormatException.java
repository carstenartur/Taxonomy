package com.taxonomy.analysis.dag.json;

/** A message that must not be processed; transports route it to a failure destination. */
public final class AnalysisMessageFormatException extends RuntimeException {

    /** Typed rejection category; never contains message payload content. */
    public enum Kind { TOO_LARGE, MALFORMED, UNSUPPORTED_SCHEMA, UNKNOWN_TYPE, INVALID_CONTRACT }

    private final Kind kind;

    public AnalysisMessageFormatException(Kind kind, String message, Throwable cause) {
        super(kind + ": " + message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
