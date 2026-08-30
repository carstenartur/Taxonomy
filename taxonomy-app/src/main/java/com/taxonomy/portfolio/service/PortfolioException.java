package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.PromptBudgetExceededException;

/** Typed application exception mapped to an RFC 9457 problem response. */
public class PortfolioException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        VALIDATION,
        PAYLOAD_TOO_LARGE,
        ANALYSIS_FAILED,
        UNAVAILABLE
    }

    private final Kind kind;
    private final String code;

    public PortfolioException(Kind kind, String message) {
        this(kind, null, message, null);
    }

    public PortfolioException(Kind kind, String message, Throwable cause) {
        this(kind, null, message, cause);
    }

    public PortfolioException(Kind kind, String code, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
    }

    public Kind getKind() {
        return kind;
    }

    public String getCode() {
        return code;
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

    public static PortfolioException promptBudgetExceeded(
            PromptBudgetExceededException exception) {
        return new PortfolioException(
                Kind.PAYLOAD_TOO_LARGE,
                exception.getCode(),
                exception.getMessage(),
                exception);
    }

    public static PortfolioException analysisFailed(String message, Throwable cause) {
        return new PortfolioException(Kind.ANALYSIS_FAILED, message, cause);
    }

    public static PortfolioException unavailable(String message, Throwable cause) {
        return new PortfolioException(Kind.UNAVAILABLE, message, cause);
    }
}
