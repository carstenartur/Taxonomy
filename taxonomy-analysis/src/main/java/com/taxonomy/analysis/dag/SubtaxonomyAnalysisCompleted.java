package com.taxonomy.analysis.dag;

import java.util.Objects;
import java.util.regex.Pattern;
import java.math.BigDecimal;

/**
 * Completion of one {@link SubtaxonomyAnalysisTask}. Carries counts and the root
 * relevance only; the scored result itself stays in the authoritative result store.
 *
 * @param rootScore   root relevance (0–100) when assessed, otherwise {@code null}
 * @param scoredNodes number of nodes assessed by this task
 * @param stopReason  typed cooperative stop reason for {@link AnalysisTaskOutcome#STOPPED}
 */
public record SubtaxonomyAnalysisCompleted(AnalysisEnvelope envelope, TaxonomyShardRoot root,
                                           AnalysisTaskOutcome outcome, Double rootScore,
                                           int scoredNodes, String stopReason)
        implements AnalysisCompletionMessage {

    // The shared wire vocabulary is finite; a syntactically valid unknown reason
    // must not reach a worker that can only handle these cooperative-stop outcomes.
    static final Pattern REASON = Pattern.compile(
            "CANCELLED|MEMORY_PRESSURE|TIME_LIMIT|AWAITING_DECISION|PROVIDER_PLUGIN_UNAVAILABLE|PROVIDER_CONFIGURATION_CHANGED|PROVIDER_BINDING_REQUIRED|PROVIDER_CONFIGURATION_REVISION_REQUIRED");

    public SubtaxonomyAnalysisCompleted(AnalysisEnvelope envelope, TaxonomyShardRoot root,
            AnalysisTaskOutcome outcome, Number rootScore, int scoredNodes, String stopReason) {
        this(envelope, root, outcome, rootScore == null ? null :
                Double.valueOf(requirePercentage(rootScore)), scoredNodes, stopReason);
    }

    public SubtaxonomyAnalysisCompleted {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(outcome, "outcome");
        envelope.requireType(AnalysisMessageType.SUBTAXONOMY_ANALYSIS_COMPLETED);
        if (!AnalysisTaskId.subtaxonomy(envelope.operationId(), root).equals(envelope.taskId())) {
            throw new IllegalArgumentException("Completion does not belong to the root task");
        }
        if (rootScore != null && (!Double.isFinite(rootScore) || rootScore < 0 || rootScore > 100)) {
            throw new IllegalArgumentException("rootScore must be within 0..100");
        }
        if (envelope.schemaVersion() == 1 && rootScore != null && rootScore != Math.rint(rootScore)) {
            throw new IllegalArgumentException("Version1 completion requires an integer percentage");
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

    private static double requirePercentage(Number number) {
        final BigDecimal value;
        try {
            value = number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("rootScore must be a finite percentage within 0..100", invalid);
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("rootScore must be within 0..100");
        }
        double result = value.doubleValue();
        if (!Double.isFinite(result) || result == 0d && value.signum() != 0) {
            throw new IllegalArgumentException("rootScore is outside the supported numeric precision");
        }
        return result == 0d ? 0d : result;
    }
}
