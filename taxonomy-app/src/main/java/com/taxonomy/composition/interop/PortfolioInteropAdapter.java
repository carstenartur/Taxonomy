package com.taxonomy.composition.interop;

import com.taxonomy.interop.IntegrationPortfolioPort;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.function.Supplier;

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
        return portfolio(() -> projects.listProjects(username, context)).stream()
                .map(p -> new ProjectData(p.id(), p.title(), p.projectKey())).toList();
    }

    @Override
    public ProjectData getProject(Long projectId, String username, WorkspaceContext context) {
        var project = portfolio(() -> projects.getProject(projectId, username, context));
        return new ProjectData(project.id(), project.title(), project.projectKey());
    }

    @Override
    public void requireProject(Long projectId, String username, WorkspaceContext context) {
        portfolio(() -> { projects.requireProject(projectId, username, context); return null; });
    }

    @Override
    public void requireProjectForUpdate(Long projectId, String username, WorkspaceContext context) {
        portfolio(() -> { projects.requireProjectForUpdate(projectId, username, context); return null; });
    }

    @Override
    public List<RequirementData> listRequirements(Long projectId, String username, WorkspaceContext context) {
        return portfolio(() -> projects.listRequirements(projectId, username, context)).stream()
                .map(PortfolioInteropAdapter::requirement).toList();
    }

    @Override
    public RequirementsPage listApprovedRequirements(Long projectId, String username, WorkspaceContext context,
                                                     int page, int pageSize) {
        var result = portfolio(
                () -> projects.listApprovedRequirements(projectId, username, context, page, pageSize));
        return new RequirementsPage(result.requirements().stream()
                .map(PortfolioInteropAdapter::requirement).toList(), result.hasNext());
    }

    @Override
    public RequirementData getRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context) {
        return requirement(portfolio(
                () -> projects.getRequirement(projectId, requirementId, username, context)));
    }

    @Override
    public RequirementData createRequirement(Long projectId, ImportedRequirement request,
                                             String username, WorkspaceContext context) {
        return requirement(portfolio(() -> projects.createRequirement(projectId,
                new CreateRequirementRequest(request.key(), request.title(), request.text(),
                        RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL,
                        ReviewStatus.PROPOSED, username, request.rationale(), provenance(request.source())),
                username, context)));
    }

    @Override
    public void archiveRequirement(Long projectId, Long requirementId, String username, WorkspaceContext context) {
        portfolio(() -> projects.updateRequirement(projectId, requirementId,
                new UpdateRequirementRequest(null, RequirementStatus.ARCHIVED, null, null, null, null, null),
                username, context));
    }

    @Override
    public void updateRequirement(Long projectId, Long requirementId, String title, boolean requiresReview,
                                  String username, WorkspaceContext context) {
        portfolio(() -> projects.updateRequirement(projectId, requirementId,
                new UpdateRequirementRequest(title, requiresReview ? RequirementStatus.DRAFT : null,
                        null, null, null, requiresReview ? ReviewStatus.PROPOSED : null, null), username, context));
    }

    @Override
    public void addRequirementVersion(Long projectId, Long requirementId, String text, String rationale,
                                      ImportProvenance source, String username, WorkspaceContext context) {
        portfolio(() -> projects.addRequirementVersion(projectId, requirementId,
                new CreateRequirementVersionRequest(text, rationale, provenance(source)), username, context));
    }

    @Override
    public RequirementApplyPlan planRequirementApply(Long projectId, String requirementKey, String dsl,
                                                     String username, WorkspaceContext context) {
        var project = portfolio(() -> projects.getProject(projectId, username, context));
        String canonical = portfolio(() -> portfolio.planRequirementIdentity(project.projectKey(), requirementKey, dsl, username, context));
        return new RequirementApplyPlan(project.projectKey(), requirementKey, canonical);
    }

    @Override
    public String contributeTo(String source, String username, WorkspaceContext context) {
        return portfolio(() -> portfolio.contributeTo(source, username, context));
    }

    private static <T> T portfolio(Supplier<T> action) {
        try {
            return action.get();
        } catch (PortfolioException failure) {
            String message = failure.getMessage();
            if (failure.getKind() == PortfolioException.Kind.NOT_FOUND
                    && message != null
                    && (message.startsWith("Current requirement version not found:")
                        || message.startsWith("Requirement has no text version:"))) {
                throw new IntegrationProblem(
                        "REQUIREMENT_VERSION_UNAVAILABLE", 409,
                        "Requirement has no readable current version; repair it before exchanging content");
            }
            int status = switch (failure.getKind()) {
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
                case VALIDATION -> 400;
                case PAYLOAD_TOO_LARGE -> 413;
                case ANALYSIS_FAILED -> 422;
                case UNAVAILABLE -> 503;
            };
            String code = failure.getCode() == null
                    ? "PORTFOLIO_" + failure.getKind().name()
                    : failure.getCode();
            throw new IntegrationProblem(code, status, message);
        }
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
