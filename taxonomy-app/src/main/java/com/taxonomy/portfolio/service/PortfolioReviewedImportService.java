package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ImportDecision;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.PersistedReviewImport;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportItem;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applies all reviewed import decisions in one transaction. */
@Service
public class PortfolioReviewedImportService {

    private final ProjectPortfolioService projectService;

    public PortfolioReviewedImportService(ProjectPortfolioService projectService) {
        this.projectService = projectService;
    }

    @Transactional
    public PersistedReviewImport persist(Long projectId,
                                         List<ReviewedImportItem> items,
                                         String username,
                                         WorkspaceContext context) {
        if (items == null || items.isEmpty()) {
            throw PortfolioException.validation("At least one reviewed import item is required");
        }
        projectService.requireProject(projectId, username, context);
        List<RequirementView> created = new ArrayList<>();
        List<RequirementView> versioned = new ArrayList<>();
        Set<String> newKeys = new HashSet<>();
        Set<Long> versionTargets = new HashSet<>();

        for (ReviewedImportItem item : items) {
            if (item == null || item.decision() == null) {
                throw PortfolioException.validation("Every import item requires a decision");
            }
            if (item.decision() == ImportDecision.NEW_REQUIREMENT) {
                String normalizedKey = ProjectPortfolioService.requireText(
                        item.requirementKey(), "requirementKey", 64).toUpperCase(java.util.Locale.ROOT);
                if (!newKeys.add(normalizedKey)) {
                    throw PortfolioException.conflict(
                            "The reviewed import contains duplicate requirement key " + normalizedKey);
                }
                created.add(projectService.createRequirement(
                        projectId,
                        new CreateRequirementRequest(
                                normalizedKey,
                                item.title(),
                                item.text(),
                                RequirementStatus.DRAFT,
                                item.priority(),
                                item.criticality() != null ? item.criticality() : Criticality.MEDIUM,
                                item.requirementType() != null
                                        ? item.requirementType() : RequirementType.FUNCTIONAL,
                                ReviewStatus.PROPOSED,
                                username,
                                "Created from a human-reviewed document candidate",
                                item.source()),
                        username,
                        context));
            } else {
                if (item.targetRequirementId() == null) {
                    throw PortfolioException.validation(
                            "A NEW_VERSION decision requires targetRequirementId");
                }
                if (!versionTargets.add(item.targetRequirementId())) {
                    throw PortfolioException.validation(
                            "Only one reviewed new version per target requirement is allowed in one import");
                }
                projectService.addRequirementVersion(
                        projectId,
                        item.targetRequirementId(),
                        new CreateRequirementVersionRequest(
                                item.text(),
                                "Created from a human-reviewed document candidate",
                                item.source()),
                        username,
                        context);
                versioned.add(projectService.getRequirement(
                        projectId, item.targetRequirementId(), username, context));
            }
        }
        return new PersistedReviewImport(List.copyOf(created), List.copyOf(versioned));
    }
}
