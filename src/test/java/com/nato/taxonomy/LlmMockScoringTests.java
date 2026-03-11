package com.nato.taxonomy;

import com.nato.taxonomy.dto.AnalysisResult;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.service.LlmService;
import com.nato.taxonomy.service.TaxonomyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that mock mode ({@code llm.mock=true}) respects the parent-budget constraint:
 * children scores must always sum to their parent's score, and no child may exceed its parent.
 */
@SpringBootTest
@TestPropertySource(properties = {"llm.mock=true"})
class LlmMockScoringTests {

    @Autowired
    private LlmService llmService;

    @Autowired
    private TaxonomyService taxonomyService;

    @Test
    void mockModeChildScoresSumToParentScore() {
        AnalysisResult result = llmService.analyzeWithBudget(
                "Provide secure voice communications between HQ and deployed forces");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");

        Map<String, Integer> scores = result.getScores();
        assertThat(scores).isNotEmpty();

        // Recursively verify that all non-leaf nodes with score > 0 have children summing to parent
        verifyChildrenSumToParent(taxonomyService.getRootNodes(), scores);
    }

    @Test
    void mockModeNoChildExceedsParent() {
        AnalysisResult result = llmService.analyzeWithBudget(
                "Provide secure voice communications between HQ and deployed forces");

        Map<String, Integer> scores = result.getScores();

        // Recursively verify no child exceeds its parent at any level
        verifyNoChildExceedsParent(taxonomyService.getRootNodes(), scores);
    }

    /**
     * Recursively checks that children scores sum to the parent's score, for all nodes at all levels.
     */
    private void verifyChildrenSumToParent(List<TaxonomyNode> nodes, Map<String, Integer> scores) {
        for (TaxonomyNode parent : nodes) {
            int parentScore = scores.getOrDefault(parent.getCode(), 0);
            if (parentScore > 0) {
                List<TaxonomyNode> children = taxonomyService.getChildrenOf(parent.getCode());
                if (!children.isEmpty()) {
                    int childSum = children.stream()
                            .mapToInt(child -> scores.getOrDefault(child.getCode(), 0))
                            .sum();
                    assertThat(childSum)
                            .as("Children of %s (score=%d) must sum to parent score, but summed to %d",
                                    parent.getCode(), parentScore, childSum)
                            .isEqualTo(parentScore);
                    // Recurse into children
                    verifyChildrenSumToParent(children, scores);
                }
            }
        }
    }

    /**
     * Recursively checks that no child score exceeds its parent's score, at any level.
     */
    private void verifyNoChildExceedsParent(List<TaxonomyNode> nodes, Map<String, Integer> scores) {
        for (TaxonomyNode parent : nodes) {
            int parentScore = scores.getOrDefault(parent.getCode(), 0);
            List<TaxonomyNode> children = taxonomyService.getChildrenOf(parent.getCode());
            for (TaxonomyNode child : children) {
                int childScore = scores.getOrDefault(child.getCode(), 0);
                assertThat(childScore)
                        .as("Child %s (score=%d) must not exceed parent %s (score=%d)",
                                child.getCode(), childScore, parent.getCode(), parentScore)
                        .isLessThanOrEqualTo(parentScore);
            }
            // Recurse into children
            if (!children.isEmpty()) {
                verifyNoChildExceedsParent(children, scores);
            }
        }
    }
}
