package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisSourceAuthority;
import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.dto.ViewContext;

/** Captures one request's already-authorized workspace and requirement identity. */
public final class AnalysisOperationContexts {
    private AnalysisOperationContexts() { }

    public static ViewContext selectedView(AnalyzeRequirementCommand command, ViewContext current) {
        var model = command.modelBinding();
        if (model == null) return current;
        AdmittedCatalogue.requireWorkspace(model, command.workspaceContext());
        if (current != null && java.util.Objects.equals(model.commit(), current.basedOnCommit())
                && model.branch().equals(current.basedOnBranch())) return current;
        return new ViewContext(model.commit(), model.branch(), null, false, true, true);
    }

    public static AnalysisOperationContext create(String operationId, AnalyzeRequirementCommand command, ViewContext view) {
        var workspace = command.workspaceContext();
        view = selectedView(command, view);
        String branch = view != null && view.basedOnBranch() != null ? view.basedOnBranch() : workspace.currentBranch();
        var authority = new AnalysisSourceAuthority(workspace.repositoryId(), workspace.workspaceId(), branch,
                view == null ? null : view.basedOnCommit(),
                command.modelBinding() == null || command.modelBinding().commit() != null
                        ? null : command.modelBinding().modelSha256());
        var provenance = command.provenance();
        var requirement = provenance == null ? RequirementReference.adHoc(command.businessText())
                : new RequirementReference(provenance.projectId(), provenance.requirementId(), provenance.snapshotId(),
                        RequirementReference.sha256(command.businessText()), provenance.requirementVersionId());
        return new AnalysisOperationContext(operationId, authority, requirement, operationId);
    }
}
