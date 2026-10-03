package com.taxonomy.analysis.dag;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Completion of one {@link SubtaxonomyAnalysisTask}. Carries counts and the root
 * relevance only; the scored result itself stays in the authoritative result store.
 *
 * @param rootScore   root relevance (0–100) when assessed, otherwise {@code null}
 * @param scoredNodes number of nodes assessed by this task
 * @param stopReason  typed cooperative stop reason for {@link AnalysisTaskOutcome#STOPPED}
 */
public record SubtaxonomyAnalysisCompleted(AnalysisEnvelope envelope, TaxonomyShardRoot root,
                                           AnalysisTaskOutcome outcome, Integer rootScore,
                                           int scoredNodes, String stopReason)
        implements AnalysisCompletionMessage {

    // The shared wire vocabulary is finite; a syntactically valid unknown reason
    // must not reach a worker that can only handle these cooperative-stop outcomes.
    static final Pattern REASON = Pattern.compile(
            "CANCELLED|MEMORY_PRESSURE|TIME_LIMIT|AWAITING_DECISION");

    public SubtaxonomyAnalysisCompleted {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(outcome, "outcome");
        envelope.requireType(AnalysisMessageType.SUBTAXONOMY_ANALYSIS_COMPLETED);
        if (!AnalysisTaskId.subtaxonomy(envelope.operationId(), root).equals(envelope.taskId())) {
            throw new IllegalArgumentException("Completion does not belong to the root task");
        }
        if (rootScore != null && (rootScore < 0 || rootScore > 100)) {
            throw new IllegalArgumentException("rootScore must be within 0..100");
        }
        if (scoredNodes < 0) throw new IllegalArgumentException("scoredNodes must be >= 0");
        if ((outcome == AnalysisTaskOutcome.STOPPED) != (stopReason != null)) {
            throw new IllegalArgumentException("stopReason is required exactly for STOPPED outcomes");
        }
        if (stopReason != null && !REASON.matcher(stopReason).matches()) {
            throw new IllegalArgumentException("Invalid stopReason");
        }
    }

    @Override
    public boolean stopsOperation() {
        return outcome == AnalysisTaskOutcome.STOPPED;
    }
}
