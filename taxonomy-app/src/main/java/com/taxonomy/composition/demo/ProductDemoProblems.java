package com.taxonomy.composition.demo;

import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioException.Kind;

/** Stable, machine-readable problem codes of the product demo (ProblemDetail property {@code code}). */
final class ProductDemoProblems {
    static final String RUN_CLOSED = "DEMO_RUN_CLOSED";
    static final String SCENARIO_CHANGED = "DEMO_SCENARIO_CHANGED";
    static final String ITERATION_UNAVAILABLE = "DEMO_ITERATION_UNAVAILABLE";
    static final String REQUIREMENT_MISMATCH = "DEMO_REQUIREMENT_MISMATCH";
    static final String PROVIDER_REJECTED = "DEMO_PROVIDER_REJECTED";
    static final String CATALOGUE_MISMATCH = "DEMO_CATALOGUE_MISMATCH";
    static final String RUN_NOT_FOUND = "DEMO_RUN_NOT_FOUND";
    static final String SCENARIO_NOT_FOUND = "DEMO_SCENARIO_NOT_FOUND";

    private ProductDemoProblems() { }

    static PortfolioException conflict(String code, String message) {
        return new PortfolioException(Kind.CONFLICT, code, message, null);
    }

    static PortfolioException validation(String code, String message) {
        return new PortfolioException(Kind.VALIDATION, code, message, null);
    }

    static PortfolioException notFound(String code, String message) {
        return new PortfolioException(Kind.NOT_FOUND, code, message, null);
    }

    static PortfolioException unavailable(String code, String message) {
        return new PortfolioException(Kind.UNAVAILABLE, code, message, null);
    }
}
