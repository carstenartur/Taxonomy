package com.taxonomy.analysis.dag;

import java.util.Objects;

/**
 * Control event published after cancellation was persisted by the operation
 * authority. It accelerates cooperative stopping; it is not the authority itself,
 * so workers still check durable operation state before starting a task.
 *
 * @param reason typed stop reason, e.g. {@code CANCELLED}
 */
public record AnalysisCancellationEvent(AnalysisEnvelope envelope, String reason) implements AnalysisMessage {

    public AnalysisCancellationEvent {
        Objects.requireNonNull(envelope, "envelope");
        envelope.requireType(AnalysisMessageType.ANALYSIS_CANCELLATION);
        if (envelope.taskId() != null) {
            throw new IllegalArgumentException("Cancellation is operation-scoped");
        }
        if (reason == null || !SubtaxonomyAnalysisCompleted.REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("Invalid cancellation reason");
        }
    }
}
