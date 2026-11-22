package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import java.util.Optional;
import java.util.function.Consumer;

/** Optional execution boundary. The application supplies this port only for durable clustered analysis. */
public interface ClusterAnalysisExecution {
    AnalysisResult execute(AnalysisOperationContext context, AnalyzeRequirementCommand command, ViewContext view,
                           Consumer<ClusterAnalysisStore.Snapshot> progress);

    /** Reattach to an existing unpublished analysis for the exact owner, scope and requirement. */
    Optional<AnalysisResult> resumeUnpublished(String operationId, WorkspaceContext scope, String requirementText);
}
