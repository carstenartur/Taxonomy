package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast source contract for the bounded search-result workspace. */
class TaxonomySearchLargeResultContractTest {

    @Test
    void largeResultsUseAWindowAndExposeOrientationControls() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");

        assertThat(search)
                .contains("const RESULT_WINDOW_SIZE = 50")
                .contains("function renderResultWindow()")
                .contains("navigationButton('previous'")
                .contains("navigationButton('next'")
                .contains("navigationButton('summary'")
                .contains("data-search-result-nav=\"")
                .contains("id=\"searchResultSummary\"")
                .contains("id=\"searchActiveFilters\"")
                .contains("id=\"searchCurrentPath\"")
                .contains("orientationText('navigation')")
                .contains("orientationText('results')")
                .contains("aria-posinset")
                .contains("aria-setsize")
                .contains("search-result-name text-truncate flex-grow-1")
                .contains("area.dataset.renderedResults")
                .contains("resultDiagnostics")
                .doesNotContain("nodes.forEach(function (node)");
    }

    @Test
    void everySearchOpensTheCompleteNestedWorkspace() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");

        assertThat(search)
                .contains("function openSearchWorkspace()")
                .contains("document.getElementById('analysisSecondaryTools')")
                .contains("document.getElementById('searchPanel')")
                .contains("secondaryTools.open = true")
                .contains("panel.open = true")
                .contains("var panel = openSearchWorkspace()");
        assertThat(search.lines()
                .map(String::strip)
                .filter("openSearchWorkspace();"::equals)
                .count())
                .as("ordinary search opens the outer and inner disclosures")
                .isEqualTo(1);
        assertThat(count(search, "openSearchWorkspace()"))
                .as("definition, ordinary search and Find Similar use one authority")
                .isEqualTo(3);
    }

    @Test
    void resultPositionsDoNotOverrideTheActionableLinkRole() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");

        assertThat(search)
                .contains("class=\"search-result-position\" role=\"listitem\"")
                .contains("<a href=\"#\" class=\"list-group-item list-group-item-action")
                .contains("html += '</a></div>'")
                .doesNotContain("search-result-item\" data-code=\"'\n"
                        + "                + escapeHtml(node.code) + '\" data-result-index=\"' + index + '\" '\n"
                        + "                + 'role=\"listitem\"");
    }

    @Test
    void resultNavigationPublishesOnlyConfirmedStableFocus() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");
        Path root = findRepositoryRoot();
        String integration = Files.readString(
                root.resolve("taxonomy-app/src/test/java/com/taxonomy/"
                        + "TaxonomyLargeResultBudgetIT.java"),
                StandardCharsets.UTF_8);

        assertThat(search)
                .contains("const NAVIGATION_COMPLETE_EVENT = '")
                .contains("taxonomy:search-navigation-complete")
                .contains("const MAX_FOCUS_ATTEMPTS = 4")
                .contains("function completeNavigationFocus(options)")
                .contains("resultState.focusRequestId !== options.requestId")
                .contains("document.activeElement === stableTarget")
                .contains("area.dataset.navigationFocusConfirmed = String(confirmed)")
                .contains("document.dispatchEvent(new CustomEvent(")
                .contains("var focusTarget = restoreResultFocus ? 'result' : 'tree'")
                .contains("focusTarget: focusTarget")
                .contains("focusTarget: 'summary'")
                .contains("'taxonomy:view-rendered', onViewRendered")
                .contains("tree.dataset.viewRendered === 'list'")
                .contains("previous.disabled = total === 0 || resultState.currentIndex <= 0")
                .contains("next.disabled = total === 0 || resultState.currentIndex >= total - 1")
                .doesNotContain("if (item && restoreResultFocus)");
        assertThat(integration)
                .contains("NAVIGATION_COMPLETE_EVENT")
                .contains("document.addEventListener(eventName, onComplete)")
                .contains("event.detail.focusConfirmed === true")
                .contains("interactionFocusConfirmed")
                .contains("returnFocusConfirmed")
                .doesNotContain(
                        "requestAnimationFrame(() => requestAnimationFrame(() =>");
    }

    @Test
    void concurrentSearchResponsesCannotOverwriteNewerState() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");
        Path root = findRepositoryRoot();
        String integration = Files.readString(
                root.resolve("taxonomy-app/src/test/java/com/taxonomy/"
                        + "TaxonomyLargeResultBudgetIT.java"),
                StandardCharsets.UTF_8);

        assertThat(search)
                .contains("let searchGeneration = 0")
                .contains("activeSearchController.abort()")
                .contains("function beginSearchRequest()")
                .contains("function isCurrentSearch(request)")
                .contains("function shouldIgnoreSearchError(error, request)")
                .contains("error.name === 'AbortError'")
                .contains("fetch(url, searchFetchOptions(request))")
                .contains("if (!isCurrentSearch(request)) return;");
        assertThat(count(search, "var request = beginSearchRequest();"))
                .as("full-text/semantic search and Find Similar share one authority")
                .isEqualTo(2);
        assertThat(integration)
                .contains("newestSearchOwnsResultsAcrossStaleSuccessAndFailure")
                .contains("race-slow-success")
                .contains("race-slow-failure")
                .contains("race-fast");
    }

    @Test
    void newSearchClearsStaleHighlightAndKeepsOneTreeTabStop() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");

        assertThat(search)
                .contains("function resetResultState()")
                .contains("document.querySelectorAll('.search-highlight')")
                .contains("#taxonomyTree [role=\"treeitem\"][tabindex=\"0\"]")
                .contains("item.setAttribute('tabindex', '-1')")
                .contains("node.setAttribute('tabindex', '0')");
    }

    @Test
    void browserEvidenceUsesRealNodesGeometryAndBrowserMetrics() throws Exception {
        Path root = findRepositoryRoot();
        String integration = Files.readString(
                root.resolve("taxonomy-app/src/test/java/com/taxonomy/"
                        + "TaxonomyLargeResultBudgetIT.java"),
                StandardCharsets.UTF_8);
        String budget = Files.readString(
                root.resolve(".github/large-result-budget.json"),
                StandardCharsets.UTF_8);

        assertThat(integration)
                .contains("window.__taxonomyBudgetRealCode")
                .contains("realTaxonomyCode")
                .contains("selectedCode")
                .contains("highlightedCode")
                .contains(".isEqualTo(realTaxonomyCode)")
                .contains("name.scrollWidth > name.clientWidth")
                .contains("clippedNames")
                .contains("maxNameOverflowPx")
                .contains("Emulation.setDeviceMetricsOverride")
                .contains("measuredDevicePixelRatio")
                .contains("horizontalOverflowPx")
                .doesNotContain("document.documentElement.style.fontSize")
                .doesNotContain(".contains(\"BUDGET-0001\")");
        assertThat(budget)
                .contains("\"responsiveProfiles\"")
                .contains("\"mobile-portrait\"")
                .contains("\"mobile-landscape\"")
                .contains("\"zoom-200\"")
                .contains("\"zoom-400\"")
                .contains("\"deviceScaleFactor\": 4.0");
    }

    @Test
    void searchPanelGuttersStayInsideTheResponsiveContainer() throws Exception {
        Path root = findRepositoryRoot();
        String index = Files.readString(
                root.resolve("taxonomy-app/src/main/resources/templates/index.html"),
                StandardCharsets.UTF_8);
        int panelStart = index.indexOf("<details id=\"searchPanel\"");
        int panelEnd = index.indexOf("</details>", panelStart);

        assertThat(panelStart).isGreaterThanOrEqualTo(0);
        assertThat(panelEnd).isGreaterThan(panelStart);
        assertThat(index.substring(panelStart, panelEnd))
                .contains("<div class=\"row g-2 mx-0 mb-2\">")
                .doesNotContain("<div class=\"row g-2 mb-2\">");
    }

    private static int count(String value, String needle) {
        return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = TaxonomySearchLargeResultContractTest.class
                .getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-app/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
