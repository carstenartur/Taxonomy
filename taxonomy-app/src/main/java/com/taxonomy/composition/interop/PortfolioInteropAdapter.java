package com.taxonomy.composition.interop;

import com.taxonomy.interop.IntegrationPortfolioPort;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import java.util.List;

/** Maps the interoperability port to the unchanged portfolio authorities. */
@Service
public class PortfolioInteropAdapter implements IntegrationPortfolioPort {
    private final ProjectPortfolioService projects;
    private final PortfolioGitService portfolio;

    public PortfolioInteropAdapter(ProjectPortfolioService projects, PortfolioGitService portfolio) {
        this.projects = projects;
        this.portfolio = portfolio;
    }

    @Override
    public List<ProjectData> listProjects(String username, WorkspaceContext context) {
        return projects.listProjects(username, context).stream()
                .map(p -> new ProjectData(p.id(), p.title())).toList();
    }

    @Override
    public ProjectData getProject(Long projectId, String username, WorkspaceContext context) {
        var project = projects.getProject(projectId, username, context);
        return new ProjectData(project.id(), project.title());
    }

    @Override
    public void requireProject(Long projectId, String username, WorkspaceContext context) {
        projects.requireProject(projectId, username, context);
    }

    @Override
    public void requireProjectForUpdate(Long projectId, String username, WorkspaceContext context) {
        projects.requireProjectForUpdate(projectId, username, context);
    }

    @Override
    public List<RequirementData> listRequirements(Long projectId, String username, WorkspaceContext context) {
        return projects.listRequirements(projectId, username, context).stream()
                .map(PortfolioInteropAdapter::requirement).toList();
    }

    @Override
    public RequirementsPage listApprovedRequirements(Long projectId, String username, WorkspaceContext context,
                                                     int page, int pageSize) {
        var result = projects.listApprovedRequirements(projectId, username, context, page, pageSize);
        return new RequirementsPage(result.requirements().stream()
                .map(PortfolioInteropAdapter::requirement).toList(), result.hasNext());
    }

    @Override
    public RequirementData getRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context) {
        return requirement(projects.getRequirement(projectId, requirementId, username, context));
    }

    @Override
    public RequirementData createRequirement(Long projectId, ImportedRequirement request,
                                             String username, WorkspaceContext context) {
        return requirement(projects.createRequirement(projectId,
                new CreateRequirementRequest(request.key(), request.title(), request.text(),
                        RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL,
                        ReviewStatus.PROPOSED, username, request.rationale(), provenance(request.source())),
                username, context));
    }

    @Override
    public void archiveRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context) {
        projects.updateRequirement(projectId, requirementId,
                new UpdateRequirementRequest(null, RequirementStatus.ARCHIVED, null, null, null, null, null),
                username, context);
    }

    @Override
    public void updateRequirement(Long projectId, Long requirementId, String title, boolean requiresReview,
                                  String username, WorkspaceContext context) {
        projects.updateRequirement(projectId, requirementId,
                new UpdateRequirementRequest(title, requiresReview ? RequirementStatus.DRAFT : null,
                        null, null, null, requiresReview ? ReviewStatus.PROPOSED : null, null), username, context);
    }

    @Override
    public void addRequirementVersion(Long projectId, Long requirementId, String text, String rationale,
                                      ImportProvenance source, String username, WorkspaceContext context) {
        projects.addRequirementVersion(projectId, requirementId,
                new CreateRequirementVersionRequest(text, rationale, provenance(source)), username, context);
    }

    @Override
    public String contributeTo(String source, String username, WorkspaceContext context) {
        return portfolio.contributeTo(source, username, context);
    }

    private static SourceReference provenance(ImportProvenance source) {
        return new SourceReference(null, null, List.of(), source.sectionReference(), null, source.originalText());
    }

    private static RequirementData requirement(RequirementView value) {
        var version = value.currentVersion();
        return new RequirementData(value.id(), value.requirementKey(), value.title(),
                value.status() == null ? null : value.status().name(), value.currentVersionId(), value.updatedAt(),
                version == null ? null : new VersionData(version.text(), version.createdAt()));
    }
}
