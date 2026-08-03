package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectConflictRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.repository.ProjectSolutionRepository;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectPortfolioViewCountTest {

    @Mock
    private ArchitectureProjectRepository projectRepository;
    @Mock
    private ProjectRequirementRepository requirementRepository;
    @Mock
    private ProjectRequirementVersionRepository versionRepository;
    @Mock
    private ProjectSolutionRepository solutionRepository;
    @Mock
    private ProjectConflictRepository conflictRepository;
    @Mock
    private PortfolioFingerprintService fingerprintService;
    @Mock
    private PortfolioJsonCodec jsonCodec;

    private ProjectPortfolioService service;

    @BeforeEach
    void setUp() {
        service = new ProjectPortfolioService(
                projectRepository,
                requirementRepository,
                versionRepository,
                solutionRepository,
                conflictRepository,
                fingerprintService,
                jsonCodec);
    }

    @Test
    void createsProjectSummaryFromAggregateCountsWithoutLoadingChildEntities() {
        ArchitectureProject project = org.mockito.Mockito.mock(ArchitectureProject.class);
        when(project.getId()).thenReturn(42L);
        when(project.getProjectKey()).thenReturn("LOAD-TEST");
        when(project.getTitle()).thenReturn("Large portfolio");
        when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
        when(project.getOwnerUsername()).thenReturn("load-user");
        when(project.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(project.getUpdatedAt()).thenReturn(Instant.EPOCH);
        when(requirementRepository.countByProjectId(42L)).thenReturn(10_000L);
        when(solutionRepository.countByProjectId(42L)).thenReturn(1_000L);
        when(conflictRepository.countByProjectIdAndStatusNotIn(eq(42L), argThat(statuses ->
                statuses.size() == 2
                        && statuses.contains(ConflictStatus.REJECTED)
                        && statuses.contains(ConflictStatus.RESOLVED))))
                .thenReturn(250L);

        var view = service.toProjectView(project);

        assertThat(view.requirementCount()).isEqualTo(10_000);
        assertThat(view.solutionCount()).isEqualTo(1_000);
        assertThat(view.openConflictCount()).isEqualTo(250);
        verify(solutionRepository).countByProjectId(42L);
        verify(conflictRepository).countByProjectIdAndStatusNotIn(eq(42L), argThat(statuses ->
                statuses.contains(ConflictStatus.REJECTED)
                        && statuses.contains(ConflictStatus.RESOLVED)));
        verify(solutionRepository, never())
                .findByProjectIdOrderByPriorityDescSolutionTitleAsc(42L);
        verify(conflictRepository, never())
                .findByProjectIdOrderByConfidenceDescDetectedAtDesc(42L);
    }
}
