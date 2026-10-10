package com.taxonomy.interop.config;

import com.taxonomy.interop.IntegrationPortfolioPort;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.workspace.service.WorkspaceContext;
import java.util.List;

/** Unavailable operations fail closed; absent portfolio data is never reported as an empty/deleted project. */
final class UnavailablePortfolioPort implements IntegrationPortfolioPort {
    private IntegrationProblem unavailable() { return new IntegrationProblem("PORTFOLIO_FEATURE_UNAVAILABLE", 503,
            "This integration operation requires the portfolio startup feature"); }
    @Override public RequirementApplyPlan planRequirementApply(Long id, String key, String dsl, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public List<ProjectData> listProjects(String user, WorkspaceContext context) { throw unavailable(); }
    @Override public ProjectData getProject(Long id, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public void requireProject(Long id, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public void requireProjectForUpdate(Long id, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public void refreshIntegrationRequirements(Long id, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public List<RequirementData> listRequirements(Long id, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public RequirementsPage listApprovedRequirements(Long id, String user, WorkspaceContext context, int page, int pageSize) { throw unavailable(); }
    @Override public RequirementData getRequirement(Long id, Long requirement, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public RequirementData createRequirement(Long id, ImportedRequirement request, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public void archiveRequirement(Long id, Long requirement, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public void updateRequirement(Long id, Long requirement, String title, boolean review, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public void addRequirementVersion(Long id, Long requirement, String text, String rationale, ImportProvenance source, String user, WorkspaceContext context) { throw unavailable(); }
    @Override public String contributeTo(String source, String user, WorkspaceContext context) { throw unavailable(); }
}
