package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Focused negative/default-path evidence for the exact requirement tenant boundary. */
class RequirementTenantCoverageTest {

    @Test
    void transientRequirementDerivesScopeAndAppliesStableDefaults() {
        Instant createdAt = Instant.parse("2026-08-16T00:00:00Z");
        String scopeKey = new PortfolioTenantIdentity(
                "repository-coverage",
                PortfolioTenantIdentity.CENTRAL_SCOPE,
                "main").scopeKey();
        ArchitectureProject project = new ArchitectureProject(
                scopeKey,
                null,
                "architect",
                "COVERAGE-PROJECT",
                "Coverage project",
                null,
                ProjectStatus.ACTIVE,
                createdAt);

        ProjectRequirement requirement = new ProjectRequirement(
                project,
                "REQ-COVERAGE",
                "Coverage requirement",
                null,
                50,
                null,
                null,
                null,
                "architect",
                createdAt);

        assertThat(requirement.getScopeKey()).isEqualTo(scopeKey);
        assertThat(requirement.getProjectId()).isNull();
        assertThat(requirement.getStatus()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(requirement.getCriticality()).isEqualTo(Criticality.MEDIUM);
        assertThat(requirement.getRequirementType()).isEqualTo(RequirementType.FUNCTIONAL);
        assertThat(requirement.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(requirement.getCurrentVersion()).isNull();
        assertThat(requirement.getRowVersion()).isZero();

        Instant updatedAt = createdAt.plusSeconds(1);
        requirement.updateMetadata(
                null, null, null, null, null, null, null, updatedAt);

        assertThat(requirement.getTitle()).isEqualTo("Coverage requirement");
        assertThat(requirement.getStatus()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(requirement.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @SuppressWarnings("deprecation")
    void repositoryIdentityGuardsRejectIncompleteKeysBeforeDatabaseLookup() {
        ProjectRequirementVersionRepository repository = mock(
                ProjectRequirementVersionRepository.class,
                Answers.CALLS_REAL_METHODS);

        assertThat(repository.findByIdAndRequirementIdAndScopeKey(
                null, 1L, "scope")).isEmpty();
        assertThat(repository.findByIdAndRequirementIdAndScopeKey(
                1L, null, "scope")).isEmpty();
        assertThat(repository.findByIdAndRequirementIdAndScopeKey(
                1L, 2L, null)).isEmpty();
        assertThat(repository.findByIdAndRequirementIdAndScopeKey(
                1L, 2L, "  ")).isEmpty();
        assertThat(repository.findByIdAndRequirementId(null, 1L)).isEmpty();
        assertThat(repository.findByIdAndRequirementId(1L, null)).isEmpty();

        verify(repository, never()).findById(anyLong());
    }
}
