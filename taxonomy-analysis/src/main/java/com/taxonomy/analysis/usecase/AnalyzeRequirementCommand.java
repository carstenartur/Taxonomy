package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.workspace.service.WorkspaceContext;

public record AnalyzeRequirementCommand(
        String businessText,
        boolean includeArchitectureView,
        int maxArchitectureNodes,
        String provider,
        String username,
        WorkspaceContext workspaceContext,
        AnalysisProvenance provenance) {

    /** Backward-compatible constructor for ad-hoc analyses. */
    public AnalyzeRequirementCommand(String businessText,
                                     boolean includeArchitectureView,
                                     int maxArchitectureNodes,
                                     String provider,
                                     String username,
                                     WorkspaceContext workspaceContext) {
        this(businessText, includeArchitectureView, maxArchitectureNodes,
                provider, username, workspaceContext, null);
    }
}
