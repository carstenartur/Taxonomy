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
        AnalysisScope analysisScope,
        com.taxonomy.extension.api.plugin.PluginInvocation providerBinding) {

    public AnalyzeRequirementCommand {
        analysisScope = AnalysisScope.orDefault(analysisScope);
        // A product-demo replay exists only for the isolated, project-bound demo requirement.
        if (com.taxonomy.analysis.demo.DemoProviderIds.isDemo(provider) && provenance == null) {
            throw new UnknownAnalysisProviderException(provider);
        }
    }

    /** Old persisted commands have no plugin binding and remain readable for built-in providers only. */
    public AnalyzeRequirementCommand(String businessText, boolean includeArchitectureView,
            int maxArchitectureNodes, String provider, String username, WorkspaceContext workspaceContext,
            AnalysisProvenance provenance, AnalysisScope analysisScope) {
        this(businessText, includeArchitectureView, maxArchitectureNodes, provider, username,
                workspaceContext, provenance, analysisScope, null);
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
