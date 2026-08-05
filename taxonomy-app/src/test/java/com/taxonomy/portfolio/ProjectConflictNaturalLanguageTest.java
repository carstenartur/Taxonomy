package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictType;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.ProjectConflict;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.repository.ProjectConflictRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.portfolio.service.ProjectConflictService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectConflictNaturalLanguageTest {

    @Test
    void detectsNaturalLanguageExternalHostingProhibition() {
        ProjectConflictRepository conflicts = mock(ProjectConflictRepository.class);
        ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
        ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
        PortfolioFingerprintService fingerprints = mock(PortfolioFingerprintService.class);
        ProjectConflictService service = new ProjectConflictService(
                conflicts, requirements, projects, fingerprints);
        WorkspaceContext context = new WorkspaceContext("architect", "ws-1", "draft");
        Instant now = Instant.parse("2026-08-04T00:00:00Z");

        ArchitectureProject project = new ArchitectureProject(
                "ws-1", "ws-1", "architect", "P-1", "Hosting", null,
                ProjectStatus.ACTIVE, now);
        ProjectRequirement cloud = requirement(
                project, "REQ-CLOUD", "Mandatory public cloud", now);
        ProjectRequirement local = requirement(
                project, "REQ-LOCAL", "External hosting excluded", now);
        ProjectRequirementVersion cloudVersion = version(
                cloud, "The solution must use public cloud hosting for all runtime services.",
                "cloud-hash", now);
        ProjectRequirementVersion localVersion = version(
                local, "The solution must not use external hosting and must remain on premises.",
                "local-hash", now);

        when(projects.requireProject(1L, "architect", context)).thenReturn(project);
        when(requirements.findByProjectIdOrderByRequirementKeyAsc(1L))
                .thenReturn(List.of(cloud, local));
        when(projects.currentVersion(cloud)).thenReturn(cloudVersion);
        when(projects.currentVersion(local)).thenReturn(localVersion);
        when(fingerprints.contentFingerprint(anyString())).thenReturn("hosting-fingerprint");
        when(conflicts.findByProjectIdAndFingerprint(1L, "hosting-fingerprint"))
                .thenReturn(Optional.empty());
        when(conflicts.findByProjectIdOrderByConfidenceDescDetectedAtDesc(1L))
                .thenReturn(List.of());

        service.detect(1L, "architect", context);

        ArgumentCaptor<ProjectConflict> saved = ArgumentCaptor.forClass(ProjectConflict.class);
        verify(conflicts).save(saved.capture());
        assertThat(saved.getValue().getConflictType()).isEqualTo(ConflictType.HOSTING);
        assertThat(saved.getValue().getEvidence())
                .contains("REQ-CLOUD")
                .contains("REQ-LOCAL")
                .contains("Human review is required");
    }

    private static ProjectRequirement requirement(ArchitectureProject project,
                                                  String key,
                                                  String title,
                                                  Instant now) {
        return new ProjectRequirement(
                project,
                key,
                title,
                RequirementStatus.DRAFT,
                50,
                Criticality.MEDIUM,
                RequirementType.SECURITY,
                ReviewStatus.PROPOSED,
                "architect",
                now);
    }

    private static ProjectRequirementVersion version(ProjectRequirement requirement,
                                                     String text,
                                                     String hash,
                                                     Instant now) {
        return new ProjectRequirementVersion(
                requirement,
                1,
                text,
                hash,
                "Initial version",
                "architect",
                now,
                null,
                null,
                "[]",
                null,
                null,
                text);
    }
}
