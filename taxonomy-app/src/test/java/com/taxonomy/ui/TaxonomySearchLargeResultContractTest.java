package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast source contract for the bounded search-result workspace. */
class TaxonomySearchLargeResultContractTest {

    @Test
    void largeResultsUseAWindowAndExposeOrientationControls() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");

        assertThat(search)
                .contains("const RESULT_WINDOW_SIZE = 50")
                .contains("function renderResultWindow()")
                .contains("data-search-result-nav=\"previous\"")
                .contains("data-search-result-nav=\"next\"")
                .contains("data-search-result-nav=\"summary\"")
                .contains("id=\"searchResultSummary\"")
                .contains("id=\"searchActiveFilters\"")
                .contains("id=\"searchCurrentPath\"")
                .contains("aria-posinset")
                .contains("aria-setsize")
                .contains("area.dataset.renderedResults")
                .contains("resultDiagnostics")
                .doesNotContain("nodes.forEach(function (node)");
    }

    @Test
    void resultNavigationPreservesFocusAndReturnContext() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");

        assertThat(search)
                .contains("focus({ preventScroll: true })")
                .contains("function returnToSummary()")
                .contains("summary.scrollIntoView({ block: 'nearest' })")
                .contains("window.scrollTo({ top: resultState.originWindowY")
                .contains("announceResultPosition(index)")
                .contains("updateCurrentPath(treeNode, node.code)");
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = TaxonomySearchLargeResultContractTest.class
                .getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
