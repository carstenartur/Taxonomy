package com.taxonomy.dto;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated history information for a single architecture element,
 * computed from the {@code ArchitectureCommitIndex}.
 *
 * <p>Answers questions like: "When was this element first introduced?",
 * "How often has it been modified?", "Is it volatile or stable?"
 *
 * @param elementId             the architecture element ID
 * @param firstSeen             timestamp of the earliest commit affecting this element
 * @param lastSeen              timestamp of the most recent commit affecting this element
 * @param occurrenceCount       number of commits that reference this element
 * @param volatility            a 0.0–1.0 measure of how frequently the element changes
 * @param recentCommitMessages  messages from the most recent commits affecting this element
 */
public record ElementHistoryAggregation(
        String elementId,
        Instant firstSeen,
        Instant lastSeen,
        int occurrenceCount,
        double volatility,
        List<String> recentCommitMessages
) {
    /**
     * Compute volatility as a ratio: occurrenceCount / totalCommitCount.
     * Clamped to [0.0, 1.0].
     */
    public static double computeVolatility(int occurrenceCount, int totalCommitCount) {
        if (totalCommitCount <= 0) return 0.0;
        return Math.min(1.0, (double) occurrenceCount / totalCommitCount);
    }
}
