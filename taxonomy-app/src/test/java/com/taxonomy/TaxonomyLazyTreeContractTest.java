package com.taxonomy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Source-level contract for the incremental taxonomy tree renderer.
 *
 * <p>The one-shot QA workflow transforms the JavaScript sources before Maven runs. The normal
 * resources phase then copies those transformed files onto the test classpath, so these assertions
 * fail closed if the transformation stops deferring collapsed descendants or if scoring can no
 * longer materialize a target path on demand.
 */
class TaxonomyLazyTreeContractTest {

    @Test
    void collapsedTaxonomyDescendantsAreDeferredUntilRequested() throws IOException {
        String browse = resource("/static/js/core/taxonomy-browse.js");

        assertContains(browse, "function materializeChildren(wrapper, scores)",
                "incremental child materializer must be present");
        assertContains(browse, "function ensureNodeRendered(code, scores, respectClosedBranches)",
                "path materializer must distinguish automatic updates from explicit navigation");
        assertContains(browse,
                "if (respectClosedBranches && path.slice(0, -1).some(node => expansionPreference(node.code) === false)) { return null; }",
                "automatic materialization must leave explicitly closed ancestor paths deferred");
        assertContains(browse, "function materializeAllTaxonomyNodes(scores)",
                "explicit full materialization must remain available");
        assertContains(browse, "wrapper._taxonomyNode = node;",
                "rendered wrappers must retain their source node");
        assertContains(browse, "childContainer.dataset.renderState = 'deferred';",
                "collapsed descendants must start deferred");
        assertContains(browse, "children = materializeChildren(wrapper, S.currentScores) || children;",
                "expanding a node must materialize only its immediate children first");
        assertContains(browse, "materializeAllTaxonomyNodes(S.currentScores);",
                "explicit expand-all must still materialize the complete taxonomy");

        assertFalse(
                normalize(browse).contains(normalize(
                        "node.children.forEach(child => childContainer.appendChild(buildNodeEl(child, scores)));")),
                "Collapsed nodes must not recursively create all descendant DOM elements");
    }

    @Test
    void scoredAndStreamingNodesMaterializeTheirPathsBeforeDomAccess() throws IOException {
        String browse = resource("/static/js/core/taxonomy-browse.js");
        String scoring = resource("/static/js/core/taxonomy-scoring.js");

        assertContains(browse, "materializeScoredPaths(scores);",
                "scored paths must be materialized before score expansion");
        assertContains(browse, "if (entry[1] > 0) { ensureNodeRendered(entry[0], scores, true); }",
                "automatic scored-path materialization must respect closed branches");
        assertContains(browse, "ensureNodeRendered: ensureNodeRendered",
                "path materialization API must be exposed to scoring");
        assertContains(browse, "materializeChildren: materializeChildren",
                "incremental child materialization API must be exposed to scoring");
        assertContains(browse, "materializeAllTaxonomyNodes: materializeAllTaxonomyNodes",
                "explicit full materialization API must remain exposed");

        assertContains(scoring,
                "function renderedScoreNode(code, scores) { return B().ensureNodeRendered(code, scores, true)",
                "score updates must materialize paths through the API that respects closed branches");
        assertContains(scoring, "const el = renderedScoreNode(code, S.currentScores);",
                "streaming score updates must resolve their target before DOM access");
        assertContains(scoring, "const el = renderedScoreNode(code, scores);",
                "matched scores must use the same guarded target resolution");
        assertContains(scoring,
                "const el = userInitiated ? B().ensureNodeRendered(code, S.currentScores) : renderedScoreNode(code, S.currentScores);",
                "explicit navigation must be able to reveal a path that automatic updates leave closed");
        assertContains(scoring, "B().setNodeExpanded(el, true, !userInitiated);",
                "expansion must preserve whether the request was automatic or user initiated");
        assertContains(scoring, "B().materializeChildren(el, scores);",
                "matched nodes must materialize the immediate child level before expansion");
        assertContains(scoring, "el.setAttribute('aria-expanded', 'true');",
                "programmatic expansion must keep the ARIA state in sync");
    }

    private static void assertContains(String source, String fragment, String message) {
        assertTrue(normalize(source).contains(normalize(fragment)), message + ": " + fragment);
    }

    private static String normalize(String source) {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = TaxonomyLazyTreeContractTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, () -> "Missing test resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
