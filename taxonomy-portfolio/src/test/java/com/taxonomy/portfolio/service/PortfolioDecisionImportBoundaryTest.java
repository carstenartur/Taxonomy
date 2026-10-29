package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.repository.*;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortfolioDecisionImportBoundaryTest {
    final ArchitectureProjectRepository projects=mock(ArchitectureProjectRepository.class);
    final ProjectRequirementRepository requirements=mock(ProjectRequirementRepository.class);
    final SolutionDefinitionRepository solutions=mock(SolutionDefinitionRepository.class);
    final SolutionTaxonomyCoverageRepository solutionCoverage=mock(SolutionTaxonomyCoverageRepository.class);
    final ProjectSolutionRepository decisions=mock(ProjectSolutionRepository.class);
    final RequirementSolutionLinkRepository links=mock(RequirementSolutionLinkRepository.class);
    final ProductCatalogEntryRepository products=mock(ProductCatalogEntryRepository.class);
    final ProductTaxonomyCoverageRepository productCoverage=mock(ProductTaxonomyCoverageRepository.class);
    final SolutionProductCandidateRepository candidates=mock(SolutionProductCandidateRepository.class);
    final PortfolioDecisionGitContributor contributor=new PortfolioDecisionGitContributor(projects,requirements,solutions,solutionCoverage,decisions,links,products,productCoverage,candidates);
    final WorkspaceContext context=new WorkspaceContext("alice","ws-a","draft","repo-a");

    @Test void incompleteReferencesAreWarningsNotFabricatedDecisions() {
        String dsl="""
                productDefinition UNSOURCED {
                }
                productDefinition INVALID_DATE {
                  sourceReference: "reference";
                  verifiedAt: "invalid";
                }
                solutionTaxonomyCoverage MISSING CP-1 {
                }
                productTaxonomyCoverage MISSING CP-1 {
                }
                projectSolutionDecision UNKNOWN MISSING {
                }
                requirementSolutionDecision UNKNOWN REQ MISSING {
                }
                solutionProductDecision UNKNOWN MISSING PRODUCT {
                }
                """;
        var result=contributor.materialize(dsl,"alice",context);
        assertThat(result.warnings()).hasSize(7);
        assertThat(result.warnings()).anyMatch(w -> w.contains("requires sourceReference and verifiedAt"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("references unavailable"));
        verify(products,never()).save(any());verify(decisions,never()).save(any());
        verify(links,never()).save(any());verify(candidates,never()).save(any());
    }

    @Test void invalidOptionalNumbersAndEnumsUseTheDocumentedFallbacks() {
        when(solutions.save(any())).thenAnswer(call->call.getArgument(0));
        String dsl="""
                solutionDefinition SOL {
                  title: " ";
                  solutionType: "unknown";
                  operatingModel: "unknown";
                  lifecycleStatus: "unknown";
                  maturityLevel: "not-a-number";
                  leadTimeDays: "not-a-number";
                  costAmount: "invalid";
                  costCurrency: "invalid";
                }
                """;
        var result=contributor.materialize(dsl,"alice",context);assertThat(result.warnings()).isEmpty();
        var captured=org.mockito.ArgumentCaptor.forClass(SolutionDefinition.class);verify(solutions).save(captured.capture());
        var value=captured.getValue();assertThat(value.getTitle()).isEqualTo("SOL");assertThat(value.getMaturityLevel()).isZero();
        assertThat(value.getCostAmount()).isNull();assertThat(value.getCostCurrency()).isNull();assertThat(value.getLeadTimeDays()).isNull();
        assertThat(value.getSolutionType()).isEqualTo(com.taxonomy.portfolio.model.PortfolioTypes.SolutionType.OTHER);
    }

    @Test void persistenceFailuresRemainVisibleInImportEvidence() {
        when(solutions.save(any())).thenThrow(new IllegalStateException("fixture persistence failure"));
        var result=contributor.materialize("""
                solutionDefinition S {
                  title: "Example";
                }
                ""","alice",context);
        assertThat(result.warnings()).singleElement().asString().contains("solutionDefinition S", "fixture persistence failure");
    }

    @Test void blankProjectionAndUnownedBlocksHaveStableSemantics() {
        assertThat(contributor.contributeTo(null,"alice",context)).contains("meta");
        assertThat(contributor.contributeTo(" ","alice",context)).contains("meta");
        assertThat(contributor.contributeTo("""
                requirement R {
                  title: "Kept";
                }
                ""","alice",context)).contains("requirement R", "Kept");
    }
    @Test void concurrentProductInputFencePreventsGitProductAndCoverageMutation() {
        var product=new ProductCatalogEntry(PortfolioScope.key("alice",context),"ws-a","PRODUCT","Manufacturer",null,"Original product",null,com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus.ACTIVE,null,null,com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel.UNSPECIFIED,null,null,null,null,null,null,"Original source",java.time.Instant.EPOCH,"alice",java.time.Instant.EPOCH);
        org.springframework.test.util.ReflectionTestUtils.setField(product,"id",9L);
        when(products.findByScopeKeyAndProductKeyIgnoreCase(anyString(),eq("PRODUCT"))).thenReturn(java.util.Optional.of(product));
        when(products.save(any())).thenAnswer(call->call.getArgument(0));
        when(products.findByIdAndScopeKeyForUpdate(eq(9L),anyString())).thenThrow(PortfolioException.conflict("Concurrent product inputs"));
        var result=contributor.materialize("""
                productDefinition PRODUCT {
                  productName: "Changed product";
                  sourceReference: "Changed source";
                  verifiedAt: "2026-01-01T00:00:00Z";
                }
                productTaxonomyCoverage PRODUCT BP {
                  coveragePercent: "100";
                  reviewStatus: "CONFIRMED";
                }
                ""","alice",context);
        assertThat(product.getProductName()).isEqualTo("Original product");
        assertThat(result.warnings()).anyMatch(w->w.contains("Concurrent product inputs"));
        verify(productCoverage,never()).save(any());
    }

    @Test void gitCannotSelectExistingCandidateWithRetainedInventoryExclusion() {
        var product=new ProductCatalogEntry(PortfolioScope.key("alice",context),"ws-a","PRODUCT","Manufacturer",null,"Product",null,com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus.ACTIVE,null,null,com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel.UNSPECIFIED,null,null,null,null,null,null,"Source",java.time.Instant.EPOCH,"alice",java.time.Instant.EPOCH);
        org.springframework.test.util.ReflectionTestUtils.setField(product,"id",9L);
        var solution=mock(SolutionDefinition.class);when(solution.getId()).thenReturn(5L);
        var project=mock(ArchitectureProject.class);when(project.getId()).thenReturn(1L);when(project.getProjectKey()).thenReturn("P");
        var decision=mock(ProjectSolution.class);when(decision.getId()).thenReturn(7L);
        var candidate=new SolutionProductCandidate(decision,product,100,"AVAILABILITY:CONTRADICTED",null,null,null,1,com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus.CONFIRMED,com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus.REJECTED,"alice",java.time.Instant.EPOCH);
        candidate.bindInventoryEvidence("retained-comparison","frozen-fingerprint","AVAILABILITY:CONTRADICTED");
        when(solutions.findByScopeKeyAndSolutionKeyIgnoreCase(anyString(),eq("SOL"))).thenReturn(java.util.Optional.of(solution));when(solutions.save(any())).thenReturn(solution);
        when(products.findByScopeKeyAndProductKeyIgnoreCase(anyString(),eq("PRODUCT"))).thenReturn(java.util.Optional.of(product));when(products.findByIdAndScopeKeyForUpdate(eq(9L),anyString())).thenReturn(java.util.Optional.of(product));when(products.save(any())).thenReturn(product);
        when(projects.findByScopeKeyOrderByUpdatedAtDesc(anyString())).thenReturn(java.util.List.of(project));
        when(decisions.findByProjectIdAndSolutionId(1L,5L)).thenReturn(java.util.Optional.of(decision));when(decisions.save(any())).thenReturn(decision);
        when(candidates.findByProjectSolutionIdAndProductId(7L,9L)).thenReturn(java.util.Optional.of(candidate));
        var result=contributor.materialize("""
                solutionDefinition SOL {
                  title: "Existing solution";
                }
                productDefinition PRODUCT {
                  sourceReference: "Source";
                  verifiedAt: "2026-01-01T00:00:00Z";
                }
                projectSolutionDecision P SOL {
                }
                solutionProductDecision P SOL PRODUCT {
                  selectionStatus: "SELECTED";
                  reviewStatus: "CONFIRMED";
                }
                ""","alice",context);
        assertThat(candidate.getSelectionStatus()).isNotEqualTo(com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus.SELECTED);
        assertThat(candidate.getHardExclusions()).isEqualTo("AVAILABILITY:CONTRADICTED");
        assertThat(candidate.getInventoryHardExclusions()).isEqualTo("AVAILABILITY:CONTRADICTED");
        assertThat(result.warnings()).anyMatch(w->w.contains("cannot remain SELECTED"));
    }

}
