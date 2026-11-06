package com.taxonomy.portfolio.service;

/** Admission for optional model work before a portfolio import or automatic dispatch. */
@FunctionalInterface
public interface AnalysisExecutionPermission {
    boolean mayExecute(String actor);
}
