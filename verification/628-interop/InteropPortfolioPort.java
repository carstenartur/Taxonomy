package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationContracts.Artifact;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.interop.persistence.IntegrationStore.Identity;
import com.taxonomy.workspace.service.RepositoryContext;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Portfolio operations needed by reviewed exchange and the read-only OSLC provider.
 * Implementations preserve the supplied repository, workspace, branch and actor;
 * neither entity repositories nor portfolio implementation DTOs cross this boundary.
 */
public interface InteropPortfolioPort {
    record ProjectView(Long id, String title) { }

    record RequirementVersionView(String text, Instant createdAt) { }

    /** The original status name is retained, including in the exchange state fingerprint. */
    record RequirementView(Long id, String requirementKey, String title, String status,
                           Long currentVersionId, Instant updatedAt, RequirementVersionView currentVersion) {
        public boolean archived() { return "ARCHIVED".equals(status); }
        public boolean approved() { return "APPROVED".equals(status); }
    }

    record RequirementPage(List<RequirementView> requirements, boolean hasNext) {
        public RequirementPage { requirements = List.copyOf(requirements); }
    }

    record AppliedRequirement(String businessIdentity, Long requirementId) { }

    List<ProjectView> listProjects(RepositoryContext context);
    ProjectView getProject(RepositoryContext context, Long projectId);
    List<RequirementView> listRequirements(RepositoryContext context, Long projectId);
    RequirementView getRequirement(RepositoryContext context, Long projectId, Long requirementId);
    RequirementPage listApprovedRequirements(RepositoryContext context, Long projectId, int page, int pageSize);
    void requireProject(RepositoryContext context, Long projectId);
    void lockProject(RepositoryContext context, Long projectId);
    AppliedRequirement applyRequirement(RepositoryContext context, Connection connection,
                                        Artifact value, Identity previous, String rationale);
    UnaryOperator<String> portfolioContribution(RepositoryContext context);
}
