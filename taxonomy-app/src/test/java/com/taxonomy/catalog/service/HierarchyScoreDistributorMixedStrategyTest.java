package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Focused contract tests for mixed hierarchical and independent-leaf scoring.
 */
@ExtendWith(MockitoExtension.class)
class HierarchyScoreDistributorMixedStrategyTest {

    @Mock
    private TaxonomyService taxonomyService;

    @InjectMocks
    private HierarchyScoreDistributor distributor;

    @Test
    void independentLeavesDoNotConsumeOrDiluteTheHierarchicalBudget() {
        TaxonomyNode root = node("RT", null, 0);
        TaxonomyNode category = node("RT-CATEGORY", "RT", 1);
        TaxonomyNode product = node("RT-PRODUCT", "RT", 1);
        TaxonomyNode categoryLeaf = node("RT-CATEGORY-LEAF", "RT-CATEGORY", 2);

        when(taxonomyService.getRootNodes()).thenReturn(List.of(root));
        when(taxonomyService.getChildrenMap()).thenReturn(Map.of(
                "RT", List.of(product, category),
                "RT-CATEGORY", List.of(categoryLeaf)));

        NodeScorer categoryScorer = (requirement, nodes, parentScore) -> {
            Map<String, Integer> scores = new LinkedHashMap<>();
            for (TaxonomyNode node : nodes) {
                scores.put(node.getCode(), 1);
            }
            return scores;
        };
        NodeScorer productScorer = (requirement, nodes, parentScore) ->
                Map.of("RT-PRODUCT", 90);

        HierarchyScoreDistributor.DistributionResult result = distributor.distribute(
                Map.of("RT", 40),
                Map.of("RT", "root reason"),
                "requirement",
                categoryScorer,
                BudgetDistribution.INSTANCE,
                node -> "RT-PRODUCT".equals(node.getCode()),
                productScorer,
                IndependentScoring.INSTANCE);

        assertThat(result.scores())
                .containsEntry("RT", 40)
                .containsEntry("RT-CATEGORY", 40)
                .containsEntry("RT-CATEGORY-LEAF", 40)
                .containsEntry("RT-PRODUCT", 90);
        assertThat(result.scores().get("RT-CATEGORY"))
                .as("the only hierarchical child receives the complete parent budget")
                .isEqualTo(result.scores().get("RT"));
    }

    @Test
    void zeroParentShortCircuitsBothHierarchicalAndIndependentChildren() {
        TaxonomyNode root = node("RT", null, 0);
        TaxonomyNode category = node("RT-CATEGORY", "RT", 1);
        TaxonomyNode product = node("RT-PRODUCT", "RT", 1);

        when(taxonomyService.getRootNodes()).thenReturn(List.of(root));
        when(taxonomyService.getChildrenMap()).thenReturn(Map.of(
                "RT", List.of(category, product)));

        NodeScorer mustNotRun = (requirement, nodes, parentScore) -> {
            throw new AssertionError("a zero-scored parent must short-circuit child scoring");
        };

        HierarchyScoreDistributor.DistributionResult result = distributor.distribute(
                Map.of("RT", 0),
                Map.of("RT", "not relevant"),
                "requirement",
                mustNotRun,
                BudgetDistribution.INSTANCE,
                node -> "RT-PRODUCT".equals(node.getCode()),
                mustNotRun,
                IndependentScoring.INSTANCE);

        assertThat(result.scores())
                .containsEntry("RT", 0)
                .containsEntry("RT-CATEGORY", 0)
                .containsEntry("RT-PRODUCT", 0);
    }

    private TaxonomyNode node(String code, String parentCode, int level) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setName(code);
        node.setParentCode(parentCode);
        node.setTaxonomyRoot("RT");
        node.setLevel(level);
        return node;
    }
}
