package com.taxonomy;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the two distinct mock-scoring contracts: hierarchical category
 * children consume their parent's budget, while concrete product leaves retain
 * independent suitability scores and are excluded from parent-budget checks.
 */
@SpringBootTest
@TestPropertySource(properties = {"llm.mock=true"})
class LlmMockScoringTests {

    @Autowired
    private LlmService llmService;

    @Autowired
    private TaxonomyService taxonomyService;

    @Autowired
    private CatalogueOverlayService catalogueOverlayService;

    @Test
    void mockModeHierarchicalChildrenSumToParentScore() {
        AnalysisResult result = llmService.analyzeWithBudget(
                "Provide secure voice communications between HQ and deployed forces");

        assertThat(result).isNotNull();
        assertThat(result.getWarnings())
                .as("Mock analysis warnings; status=%s error=%s",
                        result.getStatus(), result.getErrorMessage())
                .isEmpty();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");

        Map<String, Integer> scores = result.getScores();
        assertThat(scores).isNotEmpty();

        verifyHierarchicalChildrenSumToParent(taxonomyService.getRootNodes(), scores);
    }

    @Test
    void mockModeNoHierarchicalChildExceedsParent() {
        AnalysisResult result = llmService.analyzeWithBudget(
                "Provide secure voice communications between HQ and deployed forces");

        verifyNoHierarchicalChildExceedsParent(
                taxonomyService.getRootNodes(), result.getScores());
    }

    private void verifyHierarchicalChildrenSumToParent(
            List<TaxonomyNode> nodes,
            Map<String, Integer> scores) {
        for (TaxonomyNode parent : nodes) {
            int parentScore = scores.getOrDefault(parent.getCode(), 0);
            List<TaxonomyNode> children = taxonomyService.getChildrenOf(parent.getCode());
            List<TaxonomyNode> hierarchicalChildren = hierarchicalChildren(children);

            if (!hierarchicalChildren.isEmpty()) {
                int childSum = hierarchicalChildren.stream()
                        .mapToInt(child -> scores.getOrDefault(child.getCode(), 0))
                        .sum();
                assertThat(childSum)
                        .as("Hierarchical children of %s (score=%d) must sum to the parent score, but summed to %d",
                                parent.getCode(), parentScore, childSum)
                        .isEqualTo(parentScore);
            }
            if (!children.isEmpty()) {
                verifyHierarchicalChildrenSumToParent(children, scores);
            }
        }
    }

    private void verifyNoHierarchicalChildExceedsParent(
            List<TaxonomyNode> nodes,
            Map<String, Integer> scores) {
        for (TaxonomyNode parent : nodes) {
            int parentScore = scores.getOrDefault(parent.getCode(), 0);
            List<TaxonomyNode> children = taxonomyService.getChildrenOf(parent.getCode());
            for (TaxonomyNode child : hierarchicalChildren(children)) {
                int childScore = scores.getOrDefault(child.getCode(), 0);
                assertThat(childScore)
                        .as("Hierarchical child %s (score=%d) must not exceed parent %s (score=%d)",
                                child.getCode(), childScore,
                                parent.getCode(), parentScore)
                        .isLessThanOrEqualTo(parentScore);
            }
            if (!children.isEmpty()) {
                verifyNoHierarchicalChildExceedsParent(children, scores);
            }
        }
    }

    private List<TaxonomyNode> hierarchicalChildren(List<TaxonomyNode> children) {
        return children.stream()
                .filter(child -> !catalogueOverlayService.isProduct(child.getCode()))
                .toList();
    }
}
