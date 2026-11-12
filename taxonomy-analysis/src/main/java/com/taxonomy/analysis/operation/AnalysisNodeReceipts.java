package com.taxonomy.analysis.operation;

import com.taxonomy.analysis.service.AnalysisFollowUpProviders.Binding;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenResult;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** The first durable node can be the parent of later requests from another replica. */
public interface AnalysisNodeReceipts {
    record BoundNode(AnalyzeNodeChildrenResult result, Binding parentBinding) { }

    AnalyzeNodeChildrenResult executeNode(String fingerprint, boolean dispatchAvailable,
            Function<String, BoundNode> work, Supplier<AnalyzeNodeChildrenResult> local, BooleanSupplier current);
    Binding readParent(String operationId);
}
