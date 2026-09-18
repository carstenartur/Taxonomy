package com.taxonomy.interop;

/** Stable, bounded integration problem; never includes a remote response, endpoint credential or parser diagnostic. */
public final class IntegrationProblem extends RuntimeException {
    private final String code;
    private final int status;
    public IntegrationProblem(String code, int status, String detail) { super(detail); this.code = code; this.status = status; }
    public String code() { return code; }
    public int status() { return status; }
    public static IntegrationProblem conflict(String code) { return new IntegrationProblem(code, 409, "The reviewed integration state changed; compare and create a new preview"); }
    public static IntegrationProblem missing() { return new IntegrationProblem("NOT_FOUND", 404, "Integration resource not found"); }
}
