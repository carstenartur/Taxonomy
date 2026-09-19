package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationValidationServiceRepositoryContextTest {

    private RelationCompatibilityMatrix compatibilityMatrix;
    private RelationQualityService qualityService;
    private RelationValidationService validationService;

    @BeforeEach
    void setUp() {
        compatibilityMatrix = mock(RelationCompatibilityMatrix.class);
        qualityService = mock(RelationQualityService.class);
        validationService = new RelationValidationService(
                compatibilityMatrix, qualityService);
    }

    @Test
    void confidenceFeedbackReceivesTheExactProposalGenerationContext() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        TaxonomyNode source = node("BP-1000", "BP");
        TaxonomyNodeDto target = target("CP-1000", "CP");
        when(compatibilityMatrix.isCompatible(
                "BP", "CP", RelationType.RELATED_TO)).thenReturn(true);
        when(qualityService.acceptanceHistoryWeight(
                "BP", "CP", RelationType.RELATED_TO, context)).thenReturn(0.25);

        var result = validationService.validate(
                source,
                target,
                RelationType.RELATED_TO,
                0,
                2,
                context);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getConfidence()).isCloseTo(0.81, within(1.0e-12));
        verify(qualityService).acceptanceHistoryWeight(
                "BP", "CP", RelationType.RELATED_TO, context);
    }

    @Test
    void invalidCompatibilityDoesNotReadAnyReviewHistory() {
        RepositoryContext context = RepositoryContext.centralWrite(
                "repo-a", "main", "architect");
        TaxonomyNode source = node("BP-1000", "BP");
        TaxonomyNodeDto target = target("IP-1000", "IP");
        when(compatibilityMatrix.isCompatible(
                "BP", "IP", RelationType.RELATED_TO)).thenReturn(false);

        var result = validationService.validate(
                source,
                target,
                RelationType.RELATED_TO,
                0,
                1,
                context);

        assertThat(result.isValid()).isFalse();
        verify(qualityService, never()).acceptanceHistoryWeight(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static TaxonomyNode node(String code, String root) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setTaxonomyRoot(root);
        return node;
    }

    private static TaxonomyNodeDto target(String code, String root) {
        TaxonomyNodeDto target = new TaxonomyNodeDto();
        target.setCode(code);
        target.setTaxonomyRoot(root);
        return target;
    }
}
