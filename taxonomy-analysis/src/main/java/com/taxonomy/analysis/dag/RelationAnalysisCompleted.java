package com.taxonomy.analysis.dag;

import java.util.Objects;

/**
 * Completion of one {@link RelationAnalysisTask}.
 *
 * @param relationCount number of relation decisions/hypotheses recorded
 * @param stopReason    typed cooperative stop reason for {@link AnalysisTaskOutcome#STOPPED}
 */
public record RelationAnalysisCompleted(AnalysisEnvelope envelope, AnalysisTaskOutcome outcome,
                                        int relationCount, String stopReason)
        implements AnalysisCompletionMessage {

    public RelationAnalysisCompleted {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(outcome, "outcome");
        envelope.requireType(AnalysisMessageType.RELATION_ANALYSIS_COMPLETED);
        if (envelope.taskType() != AnalysisTaskType.RELATION_ANALYSIS) {
            throw new IllegalArgumentException("Completion does not belong to a relation task");
        }
        if (relationCount < 0) throw new IllegalArgumentException("relationCount must be >= 0");
        if ((outcome == AnalysisTaskOutcome.STOPPED) != (stopReason != null)) {
            throw new IllegalArgumentException("stopReason is required exactly for STOPPED outcomes");
        }
        if (stopReason != null && !SubtaxonomyAnalysisCompleted.REASON.matcher(stopReason).matches()) {
            throw new IllegalArgumentException("Invalid stopReason");
        }
    }

    @Override
    public boolean stopsOperation() {
        return outcome == AnalysisTaskOutcome.STOPPED;
    }
}
