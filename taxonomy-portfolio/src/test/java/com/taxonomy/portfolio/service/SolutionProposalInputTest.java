package com.taxonomy.portfolio.service;

import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SolutionProposalInputTest {
    @Test
    void newProposalKeepsCoverageSourceAndHumanReviewDefaults() {
        var f = new Fixture();
        when(f.projectSolutions.findByProjectIdAndSolutionId(41L, 101L)).thenReturn(Optional.empty());
        when(f.projectSolutions.save(any())).thenReturn(f.projectSolution);
        when(f.links.findByProjectSolutionIdAndRequirementId(11L, 7L)).thenReturn(Optional.empty());
        var requirement = mock(ProjectRequirement.class);
        when(f.projects.requireRequirement(41L, 7L, "owner", f.scope)).thenReturn(requirement);

        assertThat(f.service.proposeFromCurrentMappings(41L, "owner", f.scope)).hasSize(1);

        var solution = ArgumentCaptor.forClass(ProjectSolution.class);
        verify(f.projectSolutions).save(solution.capture());
        assertThat(solution.getValue().getStatus()).isEqualTo(ProjectSolutionStatus.PROPOSED);
        assertThat(solution.getValue().getActionStatus()).isEqualTo(ActionStatus.UNDECIDED);
        assertThat(solution.getValue().getPriority()).isEqualTo(62);
        var link = ArgumentCaptor.forClass(RequirementSolutionLink.class);
        verify(f.links).save(link.capture());
        assertThat(link.getValue().getRequirement()).isSameAs(requirement);
        assertThat(link.getValue().getSnapshotId()).isEqualTo("snapshot-current");
        assertThat(link.getValue().getCoveragePercent()).isEqualTo(49d);
        assertThat(link.getValue().getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(link.getValue().getRole()).isEqualTo(RequirementSolutionRole.USES);
        assertThat(link.getValue().getEvidence()).contains("BP-1000", "70%");
        verify(f.mappings).findCurrentProposalInputsForProject(41L, PortfolioScope.key("owner", f.scope));
    }

    @Test
    void repeatedProposalKeepsAnExistingDecisionAndDoesNotHydrateTheRequirementAgain() {
        var f = new Fixture();
        when(f.projectSolutions.findByProjectIdAndSolutionId(41L, 101L)).thenReturn(Optional.of(f.projectSolution));
        var existing = mock(RequirementSolutionLink.class);
        when(existing.getId()).thenReturn(123L);
        when(f.links.findByProjectSolutionIdAndRequirementId(11L, 7L)).thenReturn(Optional.of(existing));

        assertThat(f.service.proposeFromCurrentMappings(41L, "owner", f.scope)).hasSize(1);

        verify(f.projects, never()).requireRequirement(any(), any(), any(), any());
        verify(f.links, never()).save(any());
        verify(f.projectSolutions, never()).save(any());
        verify(existing, never()).update(any(), anyDouble(), any(), any(), any(), any(), any());
    }

    @Test
    void unconfirmedAndForeignWorkspaceCoverageCannotCreateProposals() {
        var f = new Fixture();
        var foreignSolution = mock(SolutionDefinition.class);
        when(foreignSolution.getScopeKey()).thenReturn("foreign-workspace");
        var foreign = mock(SolutionTaxonomyCoverage.class);
        when(foreign.getSolution()).thenReturn(foreignSolution);
        when(foreign.getReviewStatus()).thenReturn(ReviewStatus.CONFIRMED);
        when(f.coverageRow.getReviewStatus()).thenReturn(ReviewStatus.PROPOSED);
        when(f.coverage.findByNodeCode("BP-1000")).thenReturn(List.of(f.coverageRow, foreign));

        assertThat(f.service.proposeFromCurrentMappings(41L, "owner", f.scope)).isEmpty();

        verifyNoInteractions(f.projectSolutions, f.links);
        verify(f.projects, never()).requireRequirement(any(), any(), any(), any());
    }

    private static final class Fixture {
        final WorkspaceContext scope = new WorkspaceContext("owner", "owned-workspace", "draft", "repository");
        final ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
        final RequirementElementMappingRepository mappings = mock(RequirementElementMappingRepository.class);
        final SolutionTaxonomyCoverageRepository coverage = mock(SolutionTaxonomyCoverageRepository.class);
        final ProjectSolutionRepository projectSolutions = mock(ProjectSolutionRepository.class);
        final RequirementSolutionLinkRepository links = mock(RequirementSolutionLinkRepository.class);
        final SolutionTaxonomyCoverage coverageRow = mock(SolutionTaxonomyCoverage.class);
        final ProjectSolution projectSolution = mock(ProjectSolution.class);
        final SolutionPortfolioService service = new SolutionPortfolioService(mock(SolutionDefinitionRepository.class), coverage,
                projectSolutions, links, mappings, mock(RequirementAnalysisSnapshotRepository.class),
                mock(SolutionProductCandidateRepository.class), mock(TaxonomyNodeLookup.class), projects,
                mock(ProductCatalogService.class), new PortfolioJsonCodec(JsonMapper.builder().build()));

        Fixture() {
            var project = mock(ArchitectureProject.class);
            when(project.getId()).thenReturn(41L);
            when(projects.requireProject(41L, "owner", scope)).thenReturn(project);
            when(mappings.findCurrentProposalInputsForProject(41L, PortfolioScope.key("owner", scope)))
                    .thenReturn(List.of(new CurrentElementMappingReadRow("snapshot-current", 7L, "BP-1000", 61.2, .7)));
            var solution = mock(SolutionDefinition.class);
            when(solution.getId()).thenReturn(101L);
            when(solution.getScopeKey()).thenReturn(PortfolioScope.key("owner", scope));
            when(coverageRow.getSolution()).thenReturn(solution);
            when(coverageRow.getReviewStatus()).thenReturn(ReviewStatus.CONFIRMED);
            when(coverageRow.getCoveragePercent()).thenReturn(70);
            when(coverage.findByNodeCode("BP-1000")).thenReturn(List.of(coverageRow));
            when(projectSolution.getId()).thenReturn(11L);
            when(projectSolution.getProject()).thenReturn(project);
            when(projectSolution.getSolution()).thenReturn(solution);
        }
    }
}
