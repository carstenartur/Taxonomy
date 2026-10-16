package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.dto.TaxonomyDiscrepancy;

import java.util.List;
import java.util.Map;

public sealed interface AnalysisStreamEvent
        permits AnalysisStreamEvent.Phase,
        AnalysisStreamEvent.Scores,
        AnalysisStreamEvent.Expanding,
        AnalysisStreamEvent.Complete,
        AnalysisStreamEvent.Error,
        AnalysisStreamEvent.DurableSnapshot {

    /** Already persisted evidence; adapters must not re-read current catalogue semantics. */
    record DurableSnapshot(com.taxonomy.analysis.cluster.ClusterAnalysisStore.Snapshot snapshot) implements AnalysisStreamEvent { }

    record Phase(String message, int progressPercent) implements AnalysisStreamEvent { }

    record Scores(Map<String, Double> newScores,
                  Map<String, String> reasons,
                  String description,
                  LlmCallDetail detail) implements AnalysisStreamEvent { }

    record Expanding(String parentCode, List<String> childCodes) implements AnalysisStreamEvent { }

    record Complete(String status,
                    Map<String, Double> allScores,
                    List<String> warnings,
                    List<TaxonomyDiscrepancy> discrepancies,
                    List<ProductCoverageGap> productCoverageGaps,
                    Long analysisDurationMillis) implements AnalysisStreamEvent {
        public Complete(String status, Map<String, Double> allScores, List<String> warnings,
                        List<TaxonomyDiscrepancy> discrepancies, List<ProductCoverageGap> productCoverageGaps) {
            this(status, allScores, warnings, discrepancies, productCoverageGaps, null);
        }
    }

    record Error(String status,
                 String errorMessage,
                 Map<String, Double> partialScores,
                 List<String> warnings,
                 List<TaxonomyDiscrepancy> discrepancies,
                 List<ProductCoverageGap> productCoverageGaps,
                 Map<String, String> partialReasons,
                 Long analysisDurationMillis) implements AnalysisStreamEvent {
        public Error(String status, String errorMessage, Map<String, Double> partialScores,
                     List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                     List<ProductCoverageGap> productCoverageGaps, Map<String, String> partialReasons) {
            this(status, errorMessage, partialScores, warnings, discrepancies, productCoverageGaps, partialReasons, null);
        }
        public Error(String status, String errorMessage, Map<String, Double> partialScores,
                     List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                     List<ProductCoverageGap> productCoverageGaps) {
            this(status, errorMessage, partialScores, warnings, discrepancies, productCoverageGaps, Map.of(), null);
        }
    }
}
