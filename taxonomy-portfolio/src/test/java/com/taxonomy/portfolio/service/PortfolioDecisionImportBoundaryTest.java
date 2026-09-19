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
}
