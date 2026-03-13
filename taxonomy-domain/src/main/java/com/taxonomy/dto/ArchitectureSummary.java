package com.taxonomy.dto;

import java.time.Instant;
import java.util.List;

/**
 * A compact, actionable architecture summary designed for immediate
 * display after analysis completes.
 *
 * <p>Includes key findings, statistics, and recommended next steps
 * to guide the user through the workflow.
 *
 * @param generatedAt              when this summary was produced
 * @param totalNodes               total taxonomy nodes loaded
 * @param totalRelations           total confirmed relations
 * @param totalProposals           pending relation proposals
 * @param totalHypotheses          active hypotheses
 * @param totalCoveredNodes        nodes with at least one requirement coverage entry
 * @param topCapabilities          highest-scoring capabilities (by relation + coverage count)
 * @param topProcesses             highest-scoring processes
 * @param topServices              highest-scoring services
 * @param gaps                     identified coverage gaps (nodes with zero requirement coverage)
 * @param hubNodes                 nodes classified as "hub" (high relation count)
 * @param nextSteps                ordered list of recommended next actions
 */
public record ArchitectureSummary(
        Instant generatedAt,
        int totalNodes,
        int totalRelations,
        int totalProposals,
        int totalHypotheses,
        int totalCoveredNodes,
        List<SummaryEntry> topCapabilities,
        List<SummaryEntry> topProcesses,
        List<SummaryEntry> topServices,
        List<String> gaps,
        List<SummaryEntry> hubNodes,
        List<NextStep> nextSteps
) {

    /**
     * A named entry with a relevance score, used for top-N lists.
     *
     * @param code  the taxonomy node code
     * @param name  the display name
     * @param score a relevance/importance score
     */
    public record SummaryEntry(String code, String name, int score) {}

    /**
     * A recommended next action in the workflow.
     *
     * @param action       short action label (e.g. "Review Relations")
     * @param description  human-readable explanation
     * @param endpoint     optional API endpoint or UI path
     */
    public record NextStep(String action, String description, String endpoint) {}
}
