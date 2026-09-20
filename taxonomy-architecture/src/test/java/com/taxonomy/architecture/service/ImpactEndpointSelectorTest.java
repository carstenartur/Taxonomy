package com.taxonomy.architecture.service;

import com.taxonomy.dto.RequirementElementView;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactEndpointSelectorTest {

    private final ImpactEndpointSelector selector = new ImpactEndpointSelector();

    @Test
    void concreteDescendantSuppressesItsScoredAncestorsButKeepsOtherBranches() {
        var parent = createLeaf("CP-1059", 1.0, "CP > CP-1000 > CP-1059");
        parent.setAnchor(true);
        var leaf = createLeaf("CP-1041", 1.0, "CP > CP-1000 > CP-1059 > CP-1041");
        var sibling = createLeaf("CP-1020", .8, "CP > CP-1000 > CP-1059 > CP-1020");
        assertThat(selector.selectEndpoints(List.of(parent, leaf, sibling)))
                .extracting(RequirementElementView::getNodeCode)
                .containsExactly("CP-1041", "CP-1020");
    }

    @Test
    void lowScoringDescendantDoesNotDisplaceAQualifiedParent() {
        var parent = createLeaf("CP-1059", 1.0, "CP > CP-1000 > CP-1059");
        var weak = createLeaf("CP-1041", .01, "CP > CP-1000 > CP-1059 > CP-1041");
        assertThat(selector.selectEndpoints(List.of(parent, weak)))
                .extracting(RequirementElementView::getNodeCode).containsExactly("CP-1059");
    }

    @Test
    void emptyListReturnsEmpty() {
        assertThat(selector.selectEndpoints(List.of())).isEmpty();
    }

    @Test
    void nullListReturnsEmpty() {
        assertThat(selector.selectEndpoints(null)).isEmpty();
    }

    @Test
    void singleHighScoringLeafIsSelected() {
        RequirementElementView leaf = createLeaf("CO-1011", 0.80, "CO > CO-1000 > CO-1011");
        List<RequirementElementView> result = selector.selectEndpoints(List.of(leaf));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeCode()).isEqualTo("CO-1011");
    }

    @Test
    void multipleQualifiedLeavesAllSelected() {
        RequirementElementView leaf1 = createLeaf("CO-1011", 0.80, "CO > CO-1000 > CO-1011");
        RequirementElementView leaf2 = createLeaf("CO-1050", 0.55, "CO > CO-1000 > CO-1050 > CO-1051");
        leaf2.setHierarchyPath("CO > CO-1000 > CO-1050 > CO-1051");
        List<RequirementElementView> result = selector.selectEndpoints(List.of(leaf1, leaf2));
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void anchorAlwaysIncluded() {
        RequirementElementView anchor = createLeaf("CO-1011", 0.10, "CO > CO-1011");
        anchor.setAnchor(true);
        List<RequirementElementView> result = selector.selectEndpoints(List.of(anchor));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNodeCode()).isEqualTo("CO-1011");
    }

    @Test
    void impactSelectedAlwaysIncluded() {
        RequirementElementView leaf = createLeaf("CO-1011", 0.10, "CO > CO-1011");
        leaf.setSelectedForImpact(true);
        List<RequirementElementView> result = selector.selectEndpoints(List.of(leaf));
        assertThat(result).hasSize(1);
    }

    @Test
    void atLeastOneIsAlwaysReturned() {
        RequirementElementView lowLeaf = createLeaf("CO-1011", 0.05, "CO > CO-1011");
        List<RequirementElementView> result = selector.selectEndpoints(List.of(lowLeaf));
        assertThat(result).hasSize(1);
    }

    @Test
    void resultsOrderedByCompositeScoreDescending() {
        RequirementElementView high = createLeaf("CO-1011", 0.80, "CO > CO-1000 > CO-1011");
        RequirementElementView low = createLeaf("CO-1050", 0.55, "CO > CO-1000 > CO-1050");
        List<RequirementElementView> result = selector.selectEndpoints(List.of(low, high));
        assertThat(result.get(0).getNodeCode()).isEqualTo("CO-1011");
    }

    private static RequirementElementView createLeaf(String code, double relevance, String path) {
        RequirementElementView el = new RequirementElementView();
        el.setNodeCode(code);
        el.setRelevance(relevance);
        el.setHierarchyPath(path);
        return el;
    }
}
