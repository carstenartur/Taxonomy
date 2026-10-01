package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.portfolio.reformulation.ReformulationEvidenceCodec;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioDocumentPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual owner serializer with scoped persistence boundaries; history reads are observable. */
class PortfolioCurrentStateReadTest {
    private final WorkspaceContext context = new WorkspaceContext("alice", "private", "draft", "repo-a");
    private final String scope = PortfolioScope.key("alice", context);
    private final ArchitectureProjectRepository projects = mock(ArchitectureProjectRepository.class);
    private final ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
    private final ProjectRequirementVersionRepository versions = mock(ProjectRequirementVersionRepository.class);
    private final RequirementElementMappingRepository mappings = mock(RequirementElementMappingRepository.class);
    private final PortfolioDecisionGitContributor decisions = mock(PortfolioDecisionGitContributor.class);
    private final ReformulationEvidenceCodec evidence = mock(ReformulationEvidenceCodec.class);
    private final ProjectPortfolioService writes = mock(ProjectPortfolioService.class);
    private final WorkspacePortfolioDocumentPort git = mock(WorkspacePortfolioDocumentPort.class);
    private final ArchitectureProject project = new ArchitectureProject(scope, "private", "alice", "P", "Project", null, null, Instant.EPOCH);
    private final ProjectRequirement requirement;
    private final ProjectRequirementVersion current;
    private final ProjectRequirementVersion later;
    private final PortablePortfolioGitService service;

    PortfolioCurrentStateReadTest() {
        ReflectionTestUtils.setField(project, "id", 10L);
        requirement = new ProjectRequirement(project, "R", "Requirement", null, 50, null, null, null, "alice", Instant.EPOCH);
        ReflectionTestUtils.setField(requirement, "id", 20L);
        current = version(100L, 1, "Chosen saved text");
        later = version(101L, 2, "Later unselected private text");
        requirement.pointToVersion(current.getId(), Instant.EPOCH);
        when(projects.findByScopeKeyOrderByUpdatedAtDesc(scope)).thenReturn(List.of(project));
        when(requirements.findByProjectIdAndScopeKeyOrderByRequirementKeyAsc(10L, scope)).thenReturn(List.of(requirement));
        when(versions.findByRequirementIdAndScopeKeyOrderByVersionNumberDesc(20L, scope)).thenReturn(List.of(later, current));
        when(versions.findByIdAndRequirementIdAndScopeKey(100L, 20L, scope)).thenReturn(Optional.of(current));
        when(decisions.contributeTo(anyString(), eq("alice"), eq(context))).thenAnswer(call -> call.getArgument(0, String.class)
                + "solutionDefinition DECISION {\n  title: \"Current decision\";\n}\n");
        when(evidence.contributeTo(anyString(), eq(scope))).thenAnswer(call -> call.getArgument(0, String.class)
                + "reformulationEvidence OLD {\n  payload: \"Private adoption ancestry\";\n}\n");
        service = new PortablePortfolioGitService(writes, projects, requirements, versions, mappings, git, decisions, evidence);
    }

    @Test void currentReadsOnlyTheSavedVersionAndPreservesDecisionsWithoutLoadingAncestry() {
        String exported = service.exportCurrentState("alice", context);
        assertThat(exported).contains("currentVersionNumber: 1", "requirementVersion P R 1", "requirement P__R",
                        "Chosen saved text", "solutionDefinition DECISION", "Current decision")
                .doesNotContain("Later unselected", "Private adoption", "Private original", "Private reason",
                        "currentVersionId:", "originalText:", "changeReason:", "requirementVersion P R 2");
        verify(versions, never()).findByRequirementIdAndScopeKeyOrderByVersionNumberDesc(anyLong(), anyString());
        verify(versions).findByIdAndRequirementIdAndScopeKey(100L, 20L, scope);
        verifyNoMoreInteractions(versions);
        verifyNoInteractions(evidence, writes, git);
        assertThat(requirement.getCurrentVersionId()).isEqualTo(100L);
    }

    @Test void noSavedVersionDoesNotInventACurrentBodyFromTheLastHistoricalVersion() {
        requirement.pointToVersion(null, Instant.EPOCH);
        when(versions.findByRequirementIdAndScopeKeyOrderByVersionNumberDesc(20L, scope)).thenReturn(List.of(later));
        String exported = service.exportCurrentState("alice", context);
        assertThat(exported).contains("projectRequirement P R", "solutionDefinition DECISION")
                .doesNotContain("requirementVersion", "requirement P__R", "currentVersionNumber:", "Later unselected");
        verifyNoInteractions(versions, evidence, writes, git);
    }

    @Test void unresolvedExactTenantPointerFailsInsteadOfSubstitutingAnyOtherVersion() {
        requirement.pointToVersion(999L, Instant.EPOCH);
        when(versions.findByRequirementIdAndScopeKeyOrderByVersionNumberDesc(20L, scope)).thenReturn(List.of(later));
        assertThatThrownBy(() -> service.exportCurrentState("alice", context))
                .isInstanceOf(PortfolioException.class).hasMessage("Current requirement version is unavailable in its tenant");
        verify(versions).findByIdAndRequirementIdAndScopeKey(999L, 20L, scope);
        verifyNoMoreInteractions(versions);
        verifyNoInteractions(decisions, evidence, writes, git);
    }

    @Test void ordinaryCollaborationExportStillIncludesDeclaredHistory() {
        assertThat(service.exportPortfolio("alice", context)).contains("requirementVersion P R 1", "requirementVersion P R 2",
                "Chosen saved text", "Later unselected private text", "Private adoption ancestry", "solutionDefinition DECISION");
        verify(versions).findByRequirementIdAndScopeKeyOrderByVersionNumberDesc(20L, scope);
        verify(evidence).contributeTo(anyString(), eq(scope));
        verifyNoInteractions(writes, git);
    }

    private ProjectRequirementVersion version(long id, int number, String text) {
        var version = new ProjectRequirementVersion(requirement, number, text, "a".repeat(64), "Private reason", "alice",
                Instant.EPOCH, null, null, null, null, null, "Private original");
        ReflectionTestUtils.setField(version, "id", id); return version;
    }
}
