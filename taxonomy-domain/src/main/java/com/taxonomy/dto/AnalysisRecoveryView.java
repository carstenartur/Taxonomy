package com.taxonomy.dto;

import java.util.List;

/** Durable operation evidence. Counts are completed questions, not a guessed completion percentage. */
public record AnalysisRecoveryView(String id, long version, String state, int completedCalls,
                                   int attemptedCalls, String currentNode, List<OpenQuestion> openQuestions) {
    public AnalysisRecoveryView { openQuestions = List.copyOf(openQuestions); }
    public record OpenQuestion(String key, List<String> nodes, String error, int attempts,
                               boolean skipped, boolean outcomeUncertain) {
        public OpenQuestion { nodes = List.copyOf(nodes); }
    }
}
