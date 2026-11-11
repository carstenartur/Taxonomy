package com.taxonomy.architecture.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchitectureRecommendationSnapshotTest {
    @Test void existingModelTargetCanFillObligationWithoutInventingElements() throws Exception {
        var gaps = mock(ArchitectureGapService.class);
        var nodes = mock(TaxonomyNodeRepository.class);
        var dsl = mock(WorkspaceDslReadPort.class);
        var read = mock(WorkspaceDslReadPort.RepositoryRead.class);
        var context = RepositoryContext.centralRead("repo", "main", "user");
        String commit = "a".repeat(40);
        var assessment = ArchitectureGapService.compatibilityAssessment("Fixture");
        assessment.setCommitId(commit);
        assessment.setJudgments(List.of(new ObligationJudgment("support", "service", "CR", "SUPPORTS", Set.of("BP", "BR"),
                1, null, 0, ObligationProfile.Severity.WARNING, ObligationJudgment.Status.VIOLATED, "Missing accepted support")));
        when(gaps.analyze(context, commit, "service-support", "1.0.0", EvidenceCompleteness.COMPLETE, "Fixture")).thenReturn(assessment);
        when(dsl.openRead(context)).thenReturn(read);
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                element service type CoreService {
                  title: "Service";
                }
                element role type BusinessRole {
                  title: "Role";
                }
                """));
        var service = new ArchitectureRecommendationService(gaps, nodes, mock(com.taxonomy.catalog.service.LocalEmbeddingService.class), dsl);
        var extra = new com.taxonomy.catalog.model.TaxonomyNode();
        extra.setCode("fixture-catalogue-process"); extra.setTaxonomyRoot("BP"); extra.setNameEn("Extra process");
        when(nodes.findByTaxonomyRootOrderByLevelAscNameEnAsc("BP")).thenReturn(List.of(extra));
        var result = service.recommend(context, commit, "service-support", "1.0.0", EvidenceCompleteness.COMPLETE, Map.of(), "Fixture", 50);
        assertThat(result.getAcceptedElements()).extracting(RecommendedElement::getNodeCode).containsExactly("service", "role");
        assertThat(result.getMatchedElements()).isEmpty();
        assertThat(result.getMeanRelevance()).isNull();
        assertThat(result.getProposedElements()).isEmpty();
        assertThat(result.getSuggestedRelations()).singleElement().extracting(SuggestedRelation::getTargetCode).isEqualTo("role");
        verify(read).verifyExpectedHead(commit);
    }

    @Test void catalogueIdentityCannotOverrideAChangedCanonicalElementType() throws Exception {
        var gaps = mock(ArchitectureGapService.class);
        var nodes = mock(TaxonomyNodeRepository.class);
        var dsl = mock(WorkspaceDslReadPort.class);
        var read = mock(WorkspaceDslReadPort.RepositoryRead.class);
        var context = RepositoryContext.centralRead("repo", "main", "user");
        String commit = "b".repeat(40);
        var assessment = ArchitectureGapService.compatibilityAssessment("Fixture");
        assessment.setCommitId(commit);
        assessment.setJudgments(List.of(new ObligationJudgment("support", "service", "CR", "SUPPORTS", Set.of("BP"),
                1, null, 0, ObligationProfile.Severity.WARNING, ObligationJudgment.Status.VIOLATED, "Missing accepted support")));
        when(gaps.analyze(context, commit, "service-support", "1.0.0", EvidenceCompleteness.COMPLETE, "Fixture")).thenReturn(assessment);
        when(dsl.openRead(context)).thenReturn(read);
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                element service type CoreService {
                  title: "Service";
                }
                element BP-fixture type CoreService {
                  title: "Changed canonical type";
                }
                """));
        var changed = new com.taxonomy.catalog.model.TaxonomyNode();
        changed.setCode("BP-fixture"); changed.setTaxonomyRoot("BP"); changed.setNameEn("Original catalogue process");
        when(nodes.findByTaxonomyRootOrderByLevelAscNameEnAsc("BP")).thenReturn(List.of(changed));
        var service = new ArchitectureRecommendationService(gaps, nodes, mock(com.taxonomy.catalog.service.LocalEmbeddingService.class), dsl);
        var result = service.recommend(context, commit, "service-support", "1.0.0", EvidenceCompleteness.COMPLETE, Map.of(), "Fixture", 50);
        assertThat(result.getAcceptedElements()).filteredOn(e -> e.getNodeCode().equals("BP-fixture"))
                .singleElement().extracting(RecommendedElement::getTaxonomyRoot).isEqualTo("CR");
        assertThat(result.getSuggestedRelations()).isEmpty();
        assertThat(result.getProposedElements()).isEmpty();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 3})
    void acceptedTargetsAreNotNewCandidatesAndCatalogueOnlyFillsRemainingShortfall(int minimum) throws Exception {
        var gaps = mock(ArchitectureGapService.class);
        var nodes = mock(TaxonomyNodeRepository.class);
        var dsl = mock(WorkspaceDslReadPort.class);
        var read = mock(WorkspaceDslReadPort.RepositoryRead.class);
        var context = RepositoryContext.centralRead("repo", "main", "user");
        String commit = "c".repeat(40);
        var assessment = ArchitectureGapService.compatibilityAssessment("Fixture");
        assessment.setCommitId(commit);
        assessment.setJudgments(List.of(new ObligationJudgment("future-support", "service", "CR", "SUPPORTS", Set.of("BR"),
                minimum, null, 1, ObligationProfile.Severity.WARNING, ObligationJudgment.Status.VIOLATED, "Additional accepted support required")));
        when(gaps.analyze(context, commit, "future-profile", "1", EvidenceCompleteness.COMPLETE, "Fixture")).thenReturn(assessment);
        when(dsl.openRead(context)).thenReturn(read);
        when(read.dslAtCommit(commit)).thenReturn(Optional.of("""
                element service type CoreService {
                  title: "Service";
                }
                element role-linked type BusinessRole {
                  title: "Already linked role";
                }
                element role-new type BusinessRole {
                  title: "Available existing role";
                }
                relation service SUPPORTS role-linked {
                  status: accepted;
                }
                """));
        var first = new com.taxonomy.catalog.model.TaxonomyNode();
        first.setCode("catalogue-role-a"); first.setTaxonomyRoot("BR"); first.setNameEn("First extra role");
        var second = new com.taxonomy.catalog.model.TaxonomyNode();
        second.setCode("catalogue-role-b"); second.setTaxonomyRoot("BR"); second.setNameEn("Second extra role");
        when(nodes.findByTaxonomyRootOrderByLevelAscNameEnAsc("BR")).thenReturn(List.of(first, second));
        var service = new ArchitectureRecommendationService(gaps, nodes, mock(com.taxonomy.catalog.service.LocalEmbeddingService.class), dsl);
        var result = service.recommend(context, commit, "future-profile", "1", EvidenceCompleteness.COMPLETE, Map.of(), "Fixture", 50);
        assertThat(result.getSuggestedRelations()).extracting(SuggestedRelation::getTargetCode)
                .contains("role-new").doesNotContain("role-linked").hasSize(minimum - 1);
        assertThat(result.getProposedElements()).hasSize(minimum - 2);
        if (minimum == 2) verify(nodes, never()).findByTaxonomyRootOrderByLevelAscNameEnAsc(anyString());
        else assertThat(result.getProposedElements()).extracting(RecommendedElement::getNodeCode).containsExactly("catalogue-role-a");
    }
}
