package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that architecture layers use one contrast-safe semantic palette. */
class TaxonomyDesignTokenContractTest {

    private static final Pattern TOKEN = Pattern.compile("(--taxonomy-layer-[a-z-]+):\\s*(#[0-9a-fA-F]{6})");

    @Test
    void everyLayerSurfacePassesNormalTextContrastAgainstItsForeground() throws Exception {
        String css = resource("/static/css/taxonomy.css");
        Map<String, String> tokens = tokens(css);
        String foreground = tokens.get("--taxonomy-layer-on-surface");

        assertThat(foreground).isEqualToIgnoringCase("#ffffff");
        for (String name : new String[]{
                "--taxonomy-layer-cap-surface",
                "--taxonomy-layer-proc-surface",
                "--taxonomy-layer-svc-surface",
                "--taxonomy-layer-app-surface",
                "--taxonomy-layer-info-surface",
                "--taxonomy-layer-comm-surface",
                "--taxonomy-layer-system-surface",
                "--taxonomy-layer-component-surface",
                "--taxonomy-layer-default-surface"
        }) {
            assertThat(tokens).as("semantic token %s", name).containsKey(name);
            assertThat(contrast(tokens.get(name), foreground))
                    .as("contrast for %s", name)
                    .isGreaterThanOrEqualTo(4.5);
        }
    }

    @Test
    void cssAndGraphRenderingConsumeSemanticTokensInsteadOfDuplicatingLayerHexValues() throws Exception {
        String css = resource("/static/css/taxonomy.css");
        String graph = resource("/static/js/shared/taxonomy-graph.js");
        String scoring = resource("/static/js/core/taxonomy-scoring.js");

        assertThat(css)
                .contains("background: var(--taxonomy-layer-cap-surface)")
                .contains("background: var(--taxonomy-layer-proc-surface)")
                .contains("background: var(--taxonomy-layer-svc-surface)")
                .contains("background: var(--taxonomy-layer-app-surface)")
                .contains("background: var(--taxonomy-layer-info-surface)")
                .contains("background: var(--taxonomy-layer-comm-surface)")
                .contains("--taxonomy-font-size-essential: 0.8125rem")
                .contains("@media (forced-colors: active)")
                .contains("@media print");

        assertThat(graph)
                .contains("GRAPH_NODE_COLOR_TOKENS")
                .contains("getPropertyValue(name)")
                .doesNotContain("var GRAPH_NODE_COLORS =")
                .doesNotContain("'Capabilities': '#4A90D9'");

        assertThat(scoring)
                .doesNotContain("icon: '🔵'")
                .doesNotContain("icon: '🟢'")
                .doesNotContain("icon: '🟠'")
                .doesNotContain("icon: '🟣'")
                .doesNotContain("icon: '🔷'")
                .doesNotContain("icon: '🔴'");
    }

    private static Map<String, String> tokens(String css) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(css);
        while (matcher.find()) result.putIfAbsent(matcher.group(1), matcher.group(2));
        return result;
    }

    private static double contrast(String left, String right) {
        double l1 = luminance(left);
        double l2 = luminance(right);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static double luminance(String hex) {
        int red = Integer.parseInt(hex.substring(1, 3), 16);
        int green = Integer.parseInt(hex.substring(3, 5), 16);
        int blue = Integer.parseInt(hex.substring(5, 7), 16);
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue);
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.04045
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = TaxonomyDesignTokenContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
