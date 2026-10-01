package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.workspace.service.WorkspaceContext;

public record AnalyzeRequirementCommand(
        String businessText,
        boolean includeArchitectureView,
        int maxArchitectureNodes,
        String provider,
        String username,
        WorkspaceContext workspaceContext,
        AnalysisProvenance provenance,
        AnalysisScope analysisScope) {

    public AnalyzeRequirementCommand {
        analysisScope = AnalysisScope.orDefault(analysisScope);
    }

    public AnalyzeRequirementCommand(String businessText, boolean includeArchitectureView,
            int maxArchitectureNodes, String provider, String username,
            WorkspaceContext workspaceContext, AnalysisProvenance provenance) {
        this(businessText, includeArchitectureView, maxArchitectureNodes, provider, username,
                workspaceContext, provenance, AnalysisScope.full());
    }

    /** Backward-compatible constructor for ad-hoc analyses. */
    public AnalyzeRequirementCommand(String businessText,
                                     boolean includeArchitectureView,
                                     int maxArchitectureNodes,
                                     String provider,
                                     String username,
                                     WorkspaceContext workspaceContext) {
        this(businessText, includeArchitectureView, maxArchitectureNodes,
                provider, username, workspaceContext, null, AnalysisScope.full());
    }
}
