package com.taxonomy.architecture.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LayerRepresentativeSelectorTest {

    private final TaxonomyNodeRepository nodeRepository = mock(TaxonomyNodeRepository.class);
    private final LayerRepresentativeSelector selector = new LayerRepresentativeSelector(nodeRepository);

    @Test
    void deepLeafAlwaysIncluded() {
        TaxonomyNode node = createNode("CP-1023", 3);
        assertThat(selector.shouldInclude(node, 80, true)).isTrue();
    }

    @Test
    void deepLeafIncludedEvenWithLowRootScore() {
        TaxonomyNode node = createNode("CP-1023", 3);
        assertThat(selector.shouldInclude(node, 10, false)).isTrue();
    }

    @Test
    void level1SkippedWhenDeepLeavesExist() {
        TaxonomyNode node = createNode("CI-1000", 1);
        assertThat(selector.shouldInclude(node, 80, true)).isFalse();
    }

    @Test
    void level1AcceptedAsFallbackWhenNoDeepLeaves() {
        TaxonomyNode node = createNode("CI-1000", 1);
        assertThat(selector.shouldInclude(node, 60, false)).isTrue();
    }

    @Test
    void level1RejectedWhenRootScoreTooLow() {
        TaxonomyNode node = createNode("CI-1000", 1);
        assertThat(selector.shouldInclude(node, 30, false)).isFalse();
    }

    @Test
    void level0AlwaysRejected() {
        TaxonomyNode node = createNode("CI", 0);
        assertThat(selector.shouldInclude(node, 80, false)).isFalse();
    }

    private static TaxonomyNode createNode(String code, int level) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setLevel(level);
        return node;
    }
}
