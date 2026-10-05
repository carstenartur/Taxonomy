package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RelationHypothesisDto;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Typed relation worker preparation; only the winning completion callback writes evidence. */
public final class ClusterRelationService {
    /** The implementation authorizes reads and joins the completion ledger's transaction for writes. */
    public interface Store {
        ClusterAnalysisStore.Input start(AnalysisTaskMessage task);
        String shard(AnalysisOperationContext context, TaxonomyShardRoot root);
        AnalysisResult rootResults(AnalysisOperationContext context);
        RelationSearchDistribution.Plan relationPlan(AnalysisOperationContext context);
        void persistRelationPlan(RelationAnalysisTask task, RelationSearchDistribution.Plan plan);
        void persistRelationResult(RelationAnalysisTask task, RelationSearchDistribution.WorkResult result);
        void persistScoreOnlyRelations(RelationAnalysisTask task, List<RelationHypothesisDto> hypotheses);
        void persistRelationFailure(RelationAnalysisTask task, String reason);
    }

    private final Store store;
    private final ClusterRelationComputation computation;
    private final ClusterAnalysisSignals signals;

    public ClusterRelationService(Store store, ClusterRelationComputation computation, ClusterAnalysisSignals signals) {
        this.store = Objects.requireNonNull(store); this.computation = Objects.requireNonNull(computation);
        this.signals = Objects.requireNonNull(signals);
    }

    public PreparedAnalysisCompletion<RelationAnalysisCompleted> prepare(RelationAnalysisTask task) {
        var context = ClusterAnalysisStore.context(task.envelope());
        var messages = new AnalysisMessageFactory(context, Clock.systemUTC());
        // Register before the authoritative read so cancellation cannot fall into a registration gap.
        try (var worker = signals.worker(context)) {
            var input = store.start(task);
            if (!input.executable()) return PreparedAnalysisCompletion.withoutEffects(
                    messages.completed(task, AnalysisTaskOutcome.STOPPED, 0, "CANCELLED"));
            try {
                if (worker.cancelled()) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                var result = Objects.requireNonNull(computation.compute(input, task, worker::cancelled), "relation result");
                if (worker.cancelled()) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                boolean evaluation = task.taskId().relationWorkOrdinal().isPresent();
                if (result instanceof ClusterRelationComputation.Preparation prepared && !evaluation) {
                    var plan = prepared.plan();
                    String stopped = stopped(plan.stopReason());
                    var outcome = outcome(stopped, plan.stopReason(), !plan.warnings().isEmpty(), !plan.sources().isEmpty());
                    return new PreparedAnalysisCompletion<>(messages.completed(task, outcome, 0, stopped),
                            () -> store.persistRelationPlan(task, plan));
                }
                if (result instanceof ClusterRelationComputation.ScoreOnly scoreOnly && !evaluation) {
                    return new PreparedAnalysisCompletion<>(messages.completed(task, AnalysisTaskOutcome.COMPLETED,
                            scoreOnly.hypotheses().size(), null),
                            () -> store.persistScoreOnlyRelations(task, scoreOnly.hypotheses()));
                }
                if (result instanceof ClusterRelationComputation.Evaluation evaluated && evaluation) {
                    var evidence = evaluated.result();
                    String stopped = stopped(evidence.stopReason());
                    var outcome = outcome(stopped, evidence.stopReason(), !evidence.result().searchExhausted(),
                            !evidence.result().edges().isEmpty());
                    return new PreparedAnalysisCompletion<>(messages.completed(task, outcome, evidence.result().edges().size(), stopped),
                            () -> store.persistRelationResult(task, evidence));
                }
                throw new IllegalArgumentException("Relation computation returned the wrong phase");
            } catch (AnalysisStoppedException stopped) {
                String reason = stopped.reason().name();
                return new PreparedAnalysisCompletion<>(messages.completed(task, AnalysisTaskOutcome.STOPPED, 0, reason),
                        () -> store.persistRelationFailure(task, reason));
            } catch (RuntimeException failed) {
                // A malformed input cannot be represented by an invented empty Plan or Work grant.
                String reason = task.taskId().relationWorkOrdinal().isPresent()
                        ? "RELATION_EVALUATION_FAILED" : "RELATION_PREPARATION_FAILED";
                return new PreparedAnalysisCompletion<>(messages.completed(task, AnalysisTaskOutcome.FAILED, 0, null),
                        () -> store.persistRelationFailure(task, reason));
            }
        }
    }

    private static AnalysisTaskOutcome outcome(String stopped, String failure, boolean partial, boolean evidence) {
        if (stopped != null) return AnalysisTaskOutcome.STOPPED;
        if (!failure.isEmpty()) return evidence ? AnalysisTaskOutcome.PARTIAL : AnalysisTaskOutcome.FAILED;
        return partial ? AnalysisTaskOutcome.PARTIAL : AnalysisTaskOutcome.COMPLETED;
    }
    private static String stopped(String reason) {
        for (var value : AnalysisStoppedException.Reason.values()) {
            if (reason.equals(value.name()) || reason.startsWith(value.name() + ":")) return value.name();
        }
        return null;
    }
}
