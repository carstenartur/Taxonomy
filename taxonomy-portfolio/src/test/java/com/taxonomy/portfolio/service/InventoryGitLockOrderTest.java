package com.taxonomy.portfolio.service;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.portfolio.reformulation.ReformulationEvidenceCodec;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class InventoryGitLockOrderTest {
    @Test void outerImportLocksSortedAggregatesBeforeAnySuperclassProjectWrite() {
        var projects=mock(ArchitectureProjectRepository.class);var requirements=mock(ProjectRequirementRepository.class);
        var versions=mock(ProjectRequirementVersionRepository.class);var solutions=mock(SolutionDefinitionRepository.class);
        var projectService=mock(ProjectPortfolioService.class);var order=new ArrayList<String>();
        var p1=mock(ArchitectureProject.class);when(p1.getId()).thenReturn(1L);when(p1.getProjectKey()).thenReturn("P1");
        var p2=mock(ArchitectureProject.class);when(p2.getId()).thenReturn(2L);when(p2.getProjectKey()).thenReturn("P2");
        when(projects.findByScopeKeyOrderByUpdatedAtDesc(anyString())).thenReturn(List.of(p2,p1));
        when(projects.findByIdAndScopeKeyForUpdate(anyLong(),anyString())).thenAnswer(c->{Long id=c.getArgument(0);order.add("lock:"+id);return Optional.of(id==1?p1:p2);});
        when(projects.findByScopeKeyAndProjectKeyIgnoreCase(anyString(),eq("P2"))).thenReturn(Optional.of(p2));
        when(projectService.updateProject(eq(2L),any(),anyString(),any())).thenAnswer(c->{order.add("write:2");return mock(ProjectView.class);});
        var repositoryFence=mock(RepositoryMutationFence.class);doAnswer(c->{order.add("repository:"+c.getArgument(0));return null;}).when(repositoryFence).lock(anyString());
        var decisions=new PortfolioDecisionGitContributor(projects,requirements,solutions,mock(SolutionTaxonomyCoverageRepository.class),mock(ProjectSolutionRepository.class),mock(RequirementSolutionLinkRepository.class),mock(ProductCatalogEntryRepository.class),mock(ProductTaxonomyCoverageRepository.class),mock(SolutionProductCandidateRepository.class),repositoryFence);
        var service=new PortablePortfolioGitService(projectService,projects,requirements,versions,mock(RequirementElementMappingRepository.class),mock(WorkspacePortfolioDocumentPort.class),decisions,mock(ReformulationEvidenceCodec.class));
        service.materialize("""
                project P2 {
                  title: "Edited second project";
                }
                ""","alice",new WorkspaceContext("alice","ws","draft","repo"));
        assertEquals(List.of("repository:repo","lock:1","lock:2","write:2"),order);
    }
    @Test void repositoryFenceContentionIsAnExplicitConflictBeforeAnyProjectWrite() {
        var fence=mock(RepositoryMutationFence.class);doThrow(new org.springframework.dao.CannotAcquireLockException("Concurrent repository owner")).when(fence).lock("repo");
        var projects=mock(ArchitectureProjectRepository.class);
        var contributor=new PortfolioDecisionGitContributor(projects,mock(ProjectRequirementRepository.class),mock(SolutionDefinitionRepository.class),mock(SolutionTaxonomyCoverageRepository.class),mock(ProjectSolutionRepository.class),mock(RequirementSolutionLinkRepository.class),mock(ProductCatalogEntryRepository.class),mock(ProductTaxonomyCoverageRepository.class),mock(SolutionProductCandidateRepository.class),fence);
        var failure=assertThrows(PortfolioException.class,()->contributor.materialize("", "alice",new WorkspaceContext("alice","ws","draft","repo")));
        assertEquals(PortfolioException.Kind.CONFLICT,failure.getKind());verifyNoInteractions(projects);
    }

}
