package com.nato.taxonomy.service;

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
    void onScores(Map<String, Integer> newScores, String description);

    /** A parent node is about to be expanded; its children are next to be evaluated. */
    void onExpanding(String parentCode, List<String> childCodes);

    /** Analysis completed successfully. */
    void onComplete(String status, Map<String, Integer> allScores, List<String> warnings);

    /** Analysis stopped due to an error (possibly with partial scores). */
    void onError(String status, String errorMessage,
                 Map<String, Integer> partialScores, List<String> warnings);
}
