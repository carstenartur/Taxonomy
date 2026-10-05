package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.RelationAnalysisTask;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.dto.RelationHypothesisDto;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Provider computation outside the completion transaction, with typed phase results. */
@FunctionalInterface
public interface ClusterRelationComputation {
    Result compute(ClusterAnalysisStore.Input input, RelationAnalysisTask task, BooleanSupplier cancelled);

    sealed interface Result permits Preparation, Evaluation, ScoreOnly { }
    record Preparation(RelationSearchDistribution.Plan plan) implements Result {
        public Preparation { Objects.requireNonNull(plan); }
    }
    /** Provisional score-derived evidence; no hierarchical plan or verification was performed. */
    record ScoreOnly(List<RelationHypothesisDto> hypotheses) implements Result {
        public ScoreOnly { hypotheses = List.copyOf(hypotheses); }
    }
    record Evaluation(RelationSearchDistribution.WorkResult result) implements Result {
        public Evaluation { Objects.requireNonNull(result); }
    }
}
