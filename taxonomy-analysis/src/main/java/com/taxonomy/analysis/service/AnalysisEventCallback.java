package com.taxonomy.analysis.service;

import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.dto.TaxonomyDiscrepancy;

import java.util.List;
import java.util.Map;

/**
 * Callback interface for receiving streaming analysis events during
 * {@link LlmService#analyzeStreaming(String, AnalysisEventCallback)}.
 */
public interface AnalysisEventCallback {

    /** A phase or progress update message. */
    void onPhase(String message, int progressPercent);

    /** Partial scores have arrived; merge these into the UI immediately. */
    void onScores(Map<String, Double> newScores, Map<String, String> reasons, String description,
                  LlmCallDetail detail);

    /** A parent node is about to be expanded; its children are next to be evaluated. */
    void onExpanding(String parentCode, List<String> childCodes);

    /** Analysis completed successfully. */
    void onComplete(String status, Map<String, Double> allScores, List<String> warnings,
                    List<TaxonomyDiscrepancy> discrepancies,
                    List<ProductCoverageGap> productCoverageGaps);

    /** Terminal assessment coverage; older callbacks keep their existing completion contract. */
    default void onComplete(String status, Map<String, Double> allScores, List<String> warnings,
                            List<TaxonomyDiscrepancy> discrepancies,
                            List<ProductCoverageGap> productCoverageGaps, AnalysisCoverage coverage) {
        onComplete(status, allScores, warnings, discrepancies, productCoverageGaps);
    }

    /** Complete explanations and assessment coverage; coverage-aware observers remain compatible. */
    default void onComplete(String status, Map<String, Double> allScores, Map<String, String> allReasons,
                            List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                            List<ProductCoverageGap> productCoverageGaps, AnalysisCoverage coverage) {
        onComplete(status, allScores, warnings, discrepancies, productCoverageGaps, coverage);
    }

    /** Analysis stopped due to an error (possibly with partial scores). */
    void onError(String status, String errorMessage,
                 Map<String, Double> partialScores, List<String> warnings,
                 List<TaxonomyDiscrepancy> discrepancies,
                 List<ProductCoverageGap> productCoverageGaps);

    /** Evidence-complete stop; legacy callback implementations retain their error contract. */
    default void onError(String status, String errorMessage,
                         Map<String, Double> partialScores, Map<String, String> partialReasons,
                         List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                         List<ProductCoverageGap> productCoverageGaps) {
        onError(status, errorMessage, partialScores, warnings, discrepancies, productCoverageGaps);
    }

    /** A stop preserves open assessments as well as the accepted numeric evidence. */
    default void onError(String status, String errorMessage,
                         Map<String, Double> partialScores, Map<String, String> partialReasons,
                         List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                         List<ProductCoverageGap> productCoverageGaps, AnalysisCoverage coverage) {
        onError(status, errorMessage, partialScores, partialReasons, warnings, discrepancies, productCoverageGaps);
    }
}
