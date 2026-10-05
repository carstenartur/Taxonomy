package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.dto.AnalysisResult;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Production worker preparation: no JMS classes, provider credentials or durable side effects. */
public final class ClusterAnalysisService {
    private final ClusterAnalysisStore store;
    private final ClusterAnalysisComputation computation;
    private final ClusterAnalysisSignals signals;

    public ClusterAnalysisService(ClusterAnalysisStore store, ClusterAnalysisComputation computation,
                                  ClusterAnalysisSignals signals) {
        this.store = Objects.requireNonNull(store); this.computation = Objects.requireNonNull(computation);
        this.signals = Objects.requireNonNull(signals);
    }

    public PreparedAnalysisCompletion<SubtaxonomyAnalysisCompleted> prepare(SubtaxonomyAnalysisTask task) {
        var context = ClusterAnalysisStore.context(task.envelope());
        var messages = new AnalysisMessageFactory(context, Clock.systemUTC());
        // Register before reading durable state: cancellation cannot fall into the registration gap.
        try (var worker = signals.worker(context)) {
            var input = store.start(task);
            if (!input.executable()) return PreparedAnalysisCompletion.withoutEffects(
                    messages.completed(task, AnalysisTaskOutcome.STOPPED, null, 0, "CANCELLED"));
            AnalysisResult result;
            AnalysisTaskOutcome outcome;
            String reason = null;
            try {
                if (worker.cancelled()) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                result = Objects.requireNonNull(computation.score(input, task, worker::cancelled), "computed root result");
                outcome = "SUCCESS".equals(result.getStatus()) ? AnalysisTaskOutcome.COMPLETED
                        : "ERROR".equals(result.getStatus()) ? AnalysisTaskOutcome.FAILED : AnalysisTaskOutcome.PARTIAL;
            } catch (AnalysisStoppedException stopped) {
                result = new AnalysisResult(stopped.partialScores(), List.of());
                result.setReasons(stopped.partialReasons()); result.setDiscrepancies(stopped.partialDiscrepancies());
                result.setStatus("PARTIAL"); result.setErrorMessage(stopped.reason().name());
                result.setWarnings(new java.util.ArrayList<>(List.of(stopped.reason().name())));
                outcome = AnalysisTaskOutcome.STOPPED; reason = stopped.reason().name();
            } catch (RuntimeException failure) {
                // Preserve failure explicitly without retaining arbitrary provider/credential-bearing exception text.
                result = failed("ROOT_EVALUATION_FAILED"); outcome = AnalysisTaskOutcome.FAILED;
            }
            var completion = messages.completed(task, outcome, result.getRawScores().get(task.root().code()),
                    result.getRawScores().size(), reason);
            var prepared = result;
            return new PreparedAnalysisCompletion<>(completion, () -> store.persistResult(task, prepared));
        }
    }

    private static AnalysisResult failed(String reason) {
        var result = new AnalysisResult(Map.of(), List.of()); result.setStatus("ERROR");
        result.setErrorMessage(reason); result.setWarnings(new java.util.ArrayList<>(List.of(reason))); return result;
    }
}
