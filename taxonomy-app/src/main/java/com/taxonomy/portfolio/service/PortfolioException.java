package com.taxonomy.portfolio.service;

/** Typed application exception mapped to an RFC 9457 problem response. */
public class PortfolioException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        VALIDATION,
        ANALYSIS_FAILED
    }

    private final Kind kind;

    public PortfolioException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public PortfolioException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public static PortfolioException notFound(String message) {
        return new PortfolioException(Kind.NOT_FOUND, message);
    }

    public static PortfolioException conflict(String message) {
        return new PortfolioException(Kind.CONFLICT, message);
    }

    public static PortfolioException validation(String message) {
        return new PortfolioException(Kind.VALIDATION, message);
    }

    public static PortfolioException analysisFailed(String message, Throwable cause) {
        return new PortfolioException(Kind.ANALYSIS_FAILED, message, cause);
    }
}
