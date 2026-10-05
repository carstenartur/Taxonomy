package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisSourceAuthority;
import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.dto.ViewContext;

/** Captures one request's already-authorized workspace and requirement identity. */
public final class AnalysisOperationContexts {
    private AnalysisOperationContexts() { }

    public static AnalysisOperationContext create(String operationId, AnalyzeRequirementCommand command, ViewContext view) {
        var workspace = command.workspaceContext();
        String branch = view != null && view.basedOnBranch() != null ? view.basedOnBranch() : workspace.currentBranch();
        var authority = new AnalysisSourceAuthority(workspace.repositoryId(), workspace.workspaceId(), branch,
                view == null ? null : view.basedOnCommit());
        var provenance = command.provenance();
        var requirement = provenance == null ? RequirementReference.adHoc(command.businessText())
                : RequirementReference.of(provenance.projectId(), provenance.requirementId(),
                        provenance.snapshotId(), command.businessText());
        return new AnalysisOperationContext(operationId, authority, requirement, operationId);
    }
}
