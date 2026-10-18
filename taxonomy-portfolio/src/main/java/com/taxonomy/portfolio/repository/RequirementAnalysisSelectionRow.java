package com.taxonomy.portfolio.repository;

/** Scalar requirement state read under the same row lock as an analysis-pointer mutation. */
public record RequirementAnalysisSelectionRow(Long id, Long currentVersionId, String currentAnalysisSnapshotId) {
}
