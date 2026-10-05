package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.SubtaxonomyAnalysisTask;
import com.taxonomy.dto.AnalysisResult;
import java.util.function.BooleanSupplier;

/** Effect-free root scoring against the admitted frozen shard, outside a database transaction. */
@FunctionalInterface
public interface ClusterAnalysisComputation {
    AnalysisResult score(ClusterAnalysisStore.Input input, SubtaxonomyAnalysisTask task, BooleanSupplier cancelled);
}
