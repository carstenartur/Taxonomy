package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.AnalysisReasonLength;
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
        com.taxonomy.extension.api.plugin.PluginInvocation providerBinding,
        com.taxonomy.analysis.recovery.AnalysisContinuationBinding continuationBinding,
        AnalysisReasonLength analysisReasonLength,
        com.taxonomy.dto.AnalysisResult retainedAnalysis) {

    public AnalyzeRequirementCommand {
        analysisScope = AnalysisScope.orDefault(analysisScope);
    }

    /** Admission fills a missing mode; older persisted commands retain their absent provenance. */
    public AnalyzeRequirementCommand withAnalysisReasonLength(AnalysisReasonLength value) {
        return new AnalyzeRequirementCommand(businessText, includeArchitectureView, maxArchitectureNodes, provider,
                username, workspaceContext, provenance, analysisScope, providerBinding, continuationBinding, value, retainedAnalysis);
    }

    public AnalyzeRequirementCommand withRetainedAnalysis(com.taxonomy.dto.AnalysisResult retained) {
        return new AnalyzeRequirementCommand(businessText, includeArchitectureView, maxArchitectureNodes, provider,
                username, workspaceContext, provenance, analysisScope, providerBinding, continuationBinding, analysisReasonLength, retained);
    }

    public AnalyzeRequirementCommand(String businessText, boolean includeArchitectureView, int maxArchitectureNodes,
            String provider, String username, WorkspaceContext workspaceContext, AnalysisProvenance provenance,
            AnalysisScope analysisScope, com.taxonomy.extension.api.plugin.PluginInvocation providerBinding,
            com.taxonomy.analysis.recovery.AnalysisContinuationBinding continuationBinding, AnalysisReasonLength reasonLength) {
        this(businessText, includeArchitectureView, maxArchitectureNodes, provider, username, workspaceContext,
                provenance, analysisScope, providerBinding, continuationBinding, reasonLength, null);
    }

    public AnalyzeRequirementCommand(String businessText, boolean includeArchitectureView, int maxArchitectureNodes,
            String provider, String username, WorkspaceContext workspaceContext, AnalysisProvenance provenance,
            AnalysisScope analysisScope, com.taxonomy.extension.api.plugin.PluginInvocation providerBinding,
            com.taxonomy.analysis.recovery.AnalysisContinuationBinding continuationBinding) {
        this(businessText, includeArchitectureView, maxArchitectureNodes, provider, username, workspaceContext,
                provenance, analysisScope, providerBinding, continuationBinding, null);
    }

    public AnalyzeRequirementCommand(String businessText, boolean includeArchitectureView, int maxArchitectureNodes,
            String provider, String username, WorkspaceContext workspaceContext, AnalysisProvenance provenance,
            AnalysisScope analysisScope, com.taxonomy.extension.api.plugin.PluginInvocation providerBinding) {
        this(businessText, includeArchitectureView, maxArchitectureNodes, provider, username, workspaceContext,
                provenance, analysisScope, providerBinding, null);
    }

    public AnalyzeRequirementCommand withContinuationBinding(com.taxonomy.analysis.recovery.AnalysisContinuationBinding binding) {
        return new AnalyzeRequirementCommand(businessText, includeArchitectureView, maxArchitectureNodes, provider,
                username, workspaceContext, provenance, analysisScope, providerBinding, binding, analysisReasonLength, retainedAnalysis);
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
