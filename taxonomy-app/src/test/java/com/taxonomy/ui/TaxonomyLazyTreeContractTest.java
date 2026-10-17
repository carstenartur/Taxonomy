package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the incremental taxonomy-tree rendering contract. Large taxonomies must not
 * recursively materialize every collapsed descendant before the user or a score needs it.
 */
class TaxonomyLazyTreeContractTest {

    @Test
    void collapsedSubtreesAreDeferredUntilNeeded() throws Exception {
        String browse = resource("/static/js/core/taxonomy-browse.js");

        assertThat(browse)
                .contains("function materializeChildren(wrapper, scores)")
                .contains("function ensureNodeRendered(code, scores, respectClosedBranches)")
                .contains("respectClosedBranches && path.slice(0, -1).some(node => expansionPreference(node.code) === false)")
                .contains("function materializeScoredPaths(scores)")
                .contains("ensureNodeRendered(entry[0], scores, true)")
                .contains("function materializeAllTaxonomyNodes(scores)")
                .contains("childContainer.dataset.renderState = 'deferred'")
                .contains("wrapper._taxonomyNode = node")
                .contains("materializeScoredPaths(scores)")
                .contains("path.push(node)")
                .contains("found = path.slice()")
                .contains("path.pop()")
                .doesNotContain("path.concat(node)")
                .doesNotContain("node.children.forEach(child => childContainer.appendChild(buildNodeEl(child, scores)))");
    }

    @Test
    void streamingScoringMaterializesTargetPathsBeforeDomUpdates() throws Exception {
        String scoring = resource("/static/js/core/taxonomy-scoring.js");

        assertThat(scoring)
                .contains("B().ensureNodeRendered(code, scores, true)")
                .contains("const el = renderedScoreNode(code, S.currentScores)")
                .contains("const el = renderedScoreNode(code, scores)")
                .contains("B().materializeChildren(el, scores)");
    }

    @Test
    void searchNavigationMaterializesTheExactLazyPathBeforeHighlighting() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");

        assertThat(search)
                .contains("window.TaxonomyBrowse.ensureNodeRendered(")
                .contains("window.TaxonomyState ? window.TaxonomyState.currentScores : null);")
                .contains("window.TaxonomyBrowse.setNodeExpanded(parentNode, true);")
                .contains("header.classList.add('search-highlight')");
    }

    @Test
    void explicitExpandAllAndManualScoringMayMaterializeTheFullTree() throws Exception {
        String browse = resource("/static/js/core/taxonomy-browse.js");

        assertThat(browse)
                .contains("materializeAllTaxonomyNodes(S.currentScores)")
                .contains("materializeAllTaxonomyNodes(null)")
                .contains("ensureNodeRendered: ensureNodeRendered")
                .contains("materializeChildren: materializeChildren")
                .contains("materializeAllTaxonomyNodes: materializeAllTaxonomyNodes");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = TaxonomyLazyTreeContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
