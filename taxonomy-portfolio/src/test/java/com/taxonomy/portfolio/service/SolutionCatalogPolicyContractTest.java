package com.taxonomy.portfolio.service;

import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SolutionCatalogPolicyContractTest {
    final SolutionDefinitionRepository solutions=mock(SolutionDefinitionRepository.class);
    final SolutionTaxonomyCoverageRepository coverage=mock(SolutionTaxonomyCoverageRepository.class);
    final ProjectSolutionRepository decisions=mock(ProjectSolutionRepository.class);
    final RequirementSolutionLinkRepository links=mock(RequirementSolutionLinkRepository.class);
    final RequirementElementMappingRepository mappings=mock(RequirementElementMappingRepository.class);
    final RequirementAnalysisSnapshotRepository snapshots=mock(RequirementAnalysisSnapshotRepository.class);
    final SolutionProductCandidateRepository candidates=mock(SolutionProductCandidateRepository.class);
    final TaxonomyNodeLookup nodes=mock(TaxonomyNodeLookup.class);
    final ProjectPortfolioService projects=mock(ProjectPortfolioService.class);
    final WorkspaceContext context=new WorkspaceContext("alice","ws-a","draft","repo-a");
    final SolutionPortfolioService service=new SolutionPortfolioService(solutions,coverage,decisions,links,mappings,snapshots,candidates,nodes,projects,mock(ProductCatalogService.class),new PortfolioJsonCodec(new ObjectMapper()));
    SolutionDefinition solution;
    ProjectSolution decision;
    ProjectRequirement requirement;
    @BeforeEach void setUp() {
        when(solutions.save(any())).thenAnswer(call->{solution=call.getArgument(0);ReflectionTestUtils.setField(solution,"id",7L);return solution;});
        var request=mock(CreateSolutionRequest.class);when(request.solutionKey()).thenReturn("sol-one");when(request.title()).thenReturn("Solution");
        when(request.extensionAttributes()).thenReturn(null);when(request.maturityLevel()).thenReturn(null);service.createSolution(request,"alice",context);
        when(solutions.findByIdAndScopeKey(7L,PortfolioScope.key("alice",context))).thenReturn(Optional.of(solution));
        var project=mock(ArchitectureProject.class);when(project.getId()).thenReturn(1L);when(projects.requireProject(1L,"alice",context)).thenReturn(project);
        decision=new ProjectSolution(project,solution,null,null,50,"initial","alice",Instant.EPOCH);ReflectionTestUtils.setField(decision,"id",3L);
        when(decisions.findByIdAndProjectId(3L,1L)).thenReturn(Optional.of(decision));
        requirement=mock(ProjectRequirement.class);when(requirement.getId()).thenReturn(2L);
        when(projects.requireRequirement(1L,2L,"alice",context)).thenReturn(requirement);
        when(nodes.findByCode("CP-1")).thenReturn(Optional.of(mock(TaxonomyNode.class)));
        clearInvocations(solutions);
    }
    @Test void explicitAndOmittedUpdateValuesPreserveReviewedMetadata() {
        assertThat(solution.getMaturityLevel()).isZero();assertThat(solution.getLifecycleStatus()).isEqualTo(LifecycleStatus.PLANNED);
        var update=mock(UpdateSolutionRequest.class);when(update.title()).thenReturn("Updated");when(update.ownerUsername()).thenReturn("bob");
        when(update.maturityLevel()).thenReturn(4);when(update.costAmount()).thenReturn(new BigDecimal("25"));when(update.costCurrency()).thenReturn("eur");
        when(update.leadTimeDays()).thenReturn(3);when(update.extensionAttributes()).thenReturn(Map.of("evidence","reviewed"));
        service.updateSolution(7L,update,"alice",context);
        var empty=mock(UpdateSolutionRequest.class);when(empty.extensionAttributes()).thenReturn(null);when(empty.maturityLevel()).thenReturn(null);when(empty.leadTimeDays()).thenReturn(null);
        service.updateSolution(7L,empty,"alice",context);when(empty.ownerUsername()).thenReturn(" ");service.updateSolution(7L,empty,"alice",context);
        assertThat(solution.getTitle()).isEqualTo("Updated");assertThat(solution.getOwnerUsername()).isEqualTo("bob");
        assertThat(solution.getMaturityLevel()).isEqualTo(4);assertThat(solution.getCostCurrency()).isEqualTo("EUR");
        assertThat(solution.getCostAmount()).isEqualByComparingTo("25");assertThat(solution.getLeadTimeDays()).isEqualTo(3);
        assertThat(solution.getExtensionAttributesJson()).contains("reviewed");
    }
    @Test void allNullRequestsFailBeforeMutation() {
        assertThatThrownBy(()->service.createSolution(null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.updateSolution(7L,null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.upsertTaxonomyCoverage(7L,null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.addProjectSolution(1L,null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.updateProjectSolution(1L,3L,null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.linkRequirement(1L,3L,null,"alice",context)).isInstanceOf(PortfolioException.class);
        verify(solutions,never()).save(any());verify(decisions,never()).save(any());verify(links,never()).save(any());
    }
    @Test void invalidKeysAndScopedMissingRecordsAreNotAccepted() {
        var request=mock(CreateSolutionRequest.class);when(request.solutionKey()).thenReturn("bad/key");
        assertThatThrownBy(()->service.createSolution(request,"alice",context)).hasMessageContaining("unsupported characters");
        when(request.solutionKey()).thenReturn("sol-one");when(solutions.findByScopeKeyAndSolutionKeyIgnoreCase(anyString(),eq("SOL-ONE"))).thenReturn(Optional.of(solution));
        assertThatThrownBy(()->service.createSolution(request,"alice",context)).hasMessageContaining("already exists");
        assertThatThrownBy(()->service.requireSolution(null,"alice",context)).hasMessageContaining("solutionId is required");
        assertThatThrownBy(()->service.requireSolution(999L,"alice",context)).hasMessageContaining("Solution not found");
        var patch=mock(UpdateProjectSolutionRequest.class);
        assertThatThrownBy(()->service.updateProjectSolution(1L,null,patch,"alice",context)).hasMessageContaining("projectSolutionId is required");
        assertThatThrownBy(()->service.updateProjectSolution(1L,999L,patch,"alice",context)).hasMessageContaining("Project solution not found");
    }
    @ParameterizedTest @ValueSource(ints={-1,6})
    void maturityBoundsAreEnforced(int value) {
        var request=mock(UpdateSolutionRequest.class);when(request.maturityLevel()).thenReturn(value);
        assertThatThrownBy(()->service.updateSolution(7L,request,"alice",context)).hasMessageContaining("maturityLevel");
    }
    @ParameterizedTest @ValueSource(ints={-1,101})
    void priorityAndCoverageBoundsAreEnforced(int value) {
        var request=mock(UpdateProjectSolutionRequest.class);when(request.priority()).thenReturn(value);
        assertThatThrownBy(()->service.updateProjectSolution(1L,3L,request,"alice",context)).hasMessageContaining("priority");
        var tax=mock(UpsertTaxonomyCoverageRequest.class);when(tax.nodeCode()).thenReturn("cp-1");when(tax.coveragePercent()).thenReturn(value);
        assertThatThrownBy(()->service.upsertTaxonomyCoverage(7L,tax,"alice",context)).hasMessageContaining("coveragePercent");
    }
    @Test void missingAndForeignSnapshotsCannotBeLinked() {
        var request=mock(LinkRequirementSolutionRequest.class);when(request.requirementId()).thenReturn(2L);
        assertThatThrownBy(()->service.linkRequirement(1L,3L,request,"alice",context)).hasMessageContaining("requires an analysis snapshot");
        when(request.snapshotId()).thenReturn(" ");when(requirement.getCurrentAnalysisSnapshotId()).thenReturn("missing");
        assertThatThrownBy(()->service.linkRequirement(1L,3L,request,"alice",context)).hasMessageContaining("snapshot not found");
        var foreign=mock(RequirementAnalysisSnapshot.class);var other=mock(ProjectRequirement.class);when(other.getId()).thenReturn(8L);when(foreign.getRequirement()).thenReturn(other);
        when(request.snapshotId()).thenReturn("foreign");when(snapshots.findByIdAndProjectId("foreign",1L)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(()->service.linkRequirement(1L,3L,request,"alice",context)).hasMessageContaining("does not belong");
        verify(links,never()).save(any());
    }
    @Test void partialProjectDecisionAndCoverageUpdatesKeepIdentity() {
        var patch=mock(UpdateProjectSolutionRequest.class);service.updateProjectSolution(1L,3L,patch,"alice",context);
        when(patch.priority()).thenReturn(70);service.updateProjectSolution(1L,3L,patch,"alice",context);
        assertThat(decision.getPriority()).isEqualTo(70);
        var existing=new SolutionTaxonomyCoverage(solution,"CP-1",20,"old",ReviewStatus.CONFIRMED,"alice",Instant.EPOCH);
        ReflectionTestUtils.setField(existing,"id",4L);when(coverage.findBySolutionIdAndNodeCode(7L,"CP-1")).thenReturn(Optional.of(existing));
        var request=mock(UpsertTaxonomyCoverageRequest.class);when(request.nodeCode()).thenReturn("cp-1");when(request.coveragePercent()).thenReturn(80);
        service.upsertTaxonomyCoverage(7L,request,"alice",context);assertThat(existing.getCoveragePercent()).isEqualTo(80);
        assertThat(existing.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);verify(coverage).save(existing);
    }
}
