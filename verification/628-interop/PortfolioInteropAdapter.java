package com.taxonomy.composition.interop;

import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.extension.api.integration.IntegrationContracts.Artifact;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.InteropPortfolioPort;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.interop.persistence.IntegrationStore.Identity;
import com.taxonomy.portfolio.dto.PortfolioDtos;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.UnaryOperator;

import static com.taxonomy.interop.IntegrationDomainAdapter.stableId;
import static com.taxonomy.interop.IntegrationDomainAdapter.workspace;

/** Connects the exchange consumer port to the existing scoped portfolio authority. */
@Service
public class PortfolioInteropAdapter implements InteropPortfolioPort {
    private final ProjectPortfolioService projects;
    private final PortfolioGitService portfolio;

    public PortfolioInteropAdapter(ProjectPortfolioService projects, PortfolioGitService portfolio) {
        this.projects = projects;
        this.portfolio = portfolio;
    }

    @Override
    public List<ProjectView> listProjects(RepositoryContext context) {
        return projects.listProjects(context.username(), workspace(context)).stream()
                .map(project -> new ProjectView(project.id(), project.title())).toList();
    }

    @Override
    public ProjectView getProject(RepositoryContext context, Long projectId) {
        var project = projects.getProject(projectId, context.username(), workspace(context));
        return new ProjectView(project.id(), project.title());
    }

    @Override
    public List<RequirementView> listRequirements(RepositoryContext context, Long projectId) {
        return projects.listRequirements(projectId, context.username(), workspace(context)).stream()
                .map(PortfolioInteropAdapter::view).toList();
    }

    @Override
    public RequirementView getRequirement(RepositoryContext context, Long projectId, Long requirementId) {
        return view(projects.getRequirement(projectId, requirementId, context.username(), workspace(context)));
    }

    @Override
    public RequirementPage listApprovedRequirements(RepositoryContext context, Long projectId, int page, int pageSize) {
        var result = projects.listApprovedRequirements(projectId, context.username(), workspace(context), page, pageSize);
        return new RequirementPage(result.requirements().stream().map(PortfolioInteropAdapter::view).toList(), result.hasNext());
    }

    @Override
    public void requireProject(RepositoryContext context, Long projectId) {
        projects.requireProject(projectId, context.username(), workspace(context));
    }

    @Override
    public void lockProject(RepositoryContext context, Long projectId) {
        if (projectId != null) {
            projects.requireProjectForUpdate(projectId, context.username(), workspace(context));
        }
    }

    // @REQUIREMENT_MUTATION@

    @Override
    public UnaryOperator<String> portfolioContribution(RepositoryContext context) {
        return source -> ArchitectureSemanticPatch.applyProjection(source,
                portfolio.contributeTo(source, context.username(), workspace(context)));
    }

    private static RequirementView view(PortfolioDtos.RequirementView requirement) {
        var version = requirement.currentVersion();
        return new RequirementView(requirement.id(), requirement.requirementKey(), requirement.title(),
                requirement.status() == null ? null : requirement.status().name(),
                requirement.currentVersionId(), requirement.updatedAt(),
                version == null ? null : new RequirementVersionView(version.text(), version.createdAt()));
    }
}
