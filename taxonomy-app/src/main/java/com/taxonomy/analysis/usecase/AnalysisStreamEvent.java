package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyDiscrepancy;

import java.util.List;
import java.util.Map;

public sealed interface AnalysisStreamEvent
        permits AnalysisStreamEvent.Phase,
        AnalysisStreamEvent.Scores,
        AnalysisStreamEvent.Expanding,
        AnalysisStreamEvent.Complete,
        AnalysisStreamEvent.Error {

    record Phase(String message, int progressPercent) implements AnalysisStreamEvent {
    }

    record Scores(Map<String, Integer> newScores,
                  Map<String, String> reasons,
                  String description,
                  LlmCallDetail detail) implements AnalysisStreamEvent {
    }

    record Expanding(String parentCode, List<String> childCodes) implements AnalysisStreamEvent {
    }

    record Complete(String status,
                    Map<String, Integer> allScores,
                    List<String> warnings,
                    List<TaxonomyDiscrepancy> discrepancies) implements AnalysisStreamEvent {
    }

    record Error(String status,
                 String errorMessage,
                 Map<String, Integer> partialScores,
                 List<String> warnings,
                 List<TaxonomyDiscrepancy> discrepancies) implements AnalysisStreamEvent {
    }
}
