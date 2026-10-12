package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks CSP-safe visibility and labelled-region behaviour for dynamic UI states. */
class UiSemanticsBridgeContractTest {

    @Test
    void apiClientLoadsTheSemanticsBridgeFromSameOrigin() throws Exception {
        String source = resource("/static/js/api/taxonomy-api-client.js");

        assertThat(source)
                .contains("loadSurface('TaxonomyUiSemantics'")
                .contains("data-taxonomy-ui-semantics")
                .contains("/js/security/taxonomy-ui-semantics.js");
    }

    @Test
    void resultPanelsUseBootstrapClassesInsteadOfInlineDisplayForRendering() throws Exception {
        String source = resource("/static/js/security/taxonomy-ui-semantics.js");

        assertThat(source)
                .contains("attributeFilter: ['style']")
                .contains("panel.classList.toggle('d-none', hidden)")
                .contains("panel.classList.toggle('d-block', !hidden)")
                .contains("panel.setAttribute('aria-hidden', hidden ? 'true' : 'false')")
                .contains("docCandidateReviewPanel")
                .contains("docAiResultPanel")
                .contains("docRegMapResultPanel");
    }

    @Test
    void taxonomyContainerRemainsALabelledRegionOutsideTreeRendering() throws Exception {
        String source = resource("/static/js/security/taxonomy-ui-semantics.js");

        assertThat(source)
                .contains("[data-taxonomy-container], [role=\"tree\"], [role=\"tablist\"]")
                .contains("var expectedRole = containsTreeItems && !containsInnerWidgets ? 'tree' : 'region'")
                .doesNotContain("tree.removeAttribute('role')");

        String bridge = source.substring(source.indexOf("function installTaxonomyContainerBridge()"));
        var filter = Pattern.compile("attributeFilter\\s*:\\s*\\[([^]]*)]").matcher(bridge);
        assertThat(filter.find()).as("taxonomy observer attribute filter").isTrue();
        var observedAttributes = Pattern.compile("['\"]([^'\"]+)['\"]")
                .matcher(filter.group(1)).results().map(match -> match.group(1)).toList();
        assertThat(observedAttributes).contains("role", "tabindex", "data-view-rendered");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = UiSemanticsBridgeContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
