#!/usr/bin/env python3
"""Apply the fail-closed graph token review fixes for PR #633."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRAPH = ROOT / "taxonomy-app/src/main/resources/static/js/shared/taxonomy-graph.js"
TEST = ROOT / "taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyDesignTokenContractTest.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch_graph(source: str) -> str:
    source = replace_once(
        source,
        """    function cssColorToken(name, fallbackToken) {
        var root = getComputedStyle(document.documentElement);
        return root.getPropertyValue(name).trim()
            || root.getPropertyValue(fallbackToken).trim()
            || 'rgb(75, 85, 99)';
    }

    function getNodeColor(taxonomySheet) {
        var token = GRAPH_NODE_COLOR_TOKENS[taxonomySheet]
            || '--taxonomy-layer-default-surface';
        return cssColorToken(token, '--taxonomy-layer-default-surface');
    }
""",
        """    function resolveGraphNodeColorPalette() {
        var styles = getComputedStyle(document.documentElement);
        var tokenNames = new Set(Object.values(GRAPH_NODE_COLOR_TOKENS));
        tokenNames.add('--taxonomy-layer-default-surface');
        var palette = {};
        tokenNames.forEach(function (token) {
            var value = styles.getPropertyValue(token).trim();
            if (!/^#[0-9a-fA-F]{6}$/.test(value)) {
                throw new Error('Missing or invalid required taxonomy color token: ' + token);
            }
            palette[token] = value;
        });
        return palette;
    }

    function getNodeColor(taxonomySheet, palette) {
        var token = GRAPH_NODE_COLOR_TOKENS[taxonomySheet]
            || '--taxonomy-layer-default-surface';
        var value = palette[token];
        if (!value) {
            throw new Error('Unresolved required taxonomy color token: ' + token);
        }
        return value;
    }
""",
        "replace repeated computed-style lookup and hidden fallback",
    )
    source = replace_once(
        source,
        """    function renderForceGraph(container, nodes, edges, originCode) {
        if (typeof d3 === 'undefined' || !nodes || nodes.length === 0) return;

        var width = container.clientWidth || 500;
""",
        """    function renderForceGraph(container, nodes, edges, originCode) {
        if (typeof d3 === 'undefined' || !nodes || nodes.length === 0) return;

        var nodeColorPalette = resolveGraphNodeColorPalette();
        var width = container.clientWidth || 500;
""",
        "snapshot force-graph palette",
    )
    source = replace_once(
        source,
        """    function renderImpactForceGraph(container, nodes, edges, options) {
        if (typeof d3 === 'undefined' || !nodes || nodes.length === 0) return;

        var anchorCodes = options.anchorCodes || new Set();
""",
        """    function renderImpactForceGraph(container, nodes, edges, options) {
        if (typeof d3 === 'undefined' || !nodes || nodes.length === 0) return;

        var nodeColorPalette = resolveGraphNodeColorPalette();
        var anchorCodes = options.anchorCodes || new Set();
""",
        "snapshot impact-graph palette",
    )
    count = source.count("getNodeColor(d.sheet)")
    if count != 2:
        raise RuntimeError(f"node color calls: expected 2 d.sheet calls, found {count}")
    source = source.replace("getNodeColor(d.sheet)", "getNodeColor(d.sheet, nodeColorPalette)")
    count = source.count("getNodeColor(sheet)")
    if count != 2:
        raise RuntimeError(f"legend color calls: expected 2 sheet calls, found {count}")
    source = source.replace("getNodeColor(sheet)", "getNodeColor(sheet, nodeColorPalette)")
    return source


def patch_test(source: str) -> str:
    source = replace_once(
        source,
        """                .contains("--taxonomy-font-size-essential: 0.8125rem")
                .contains("@media (forced-colors: active)")
                .contains("@media print");
""",
        """                .contains("--taxonomy-font-size-essential: 0.8125rem")
                .contains("[data-bs-theme=\\\"dark\\\"]")
                .contains("--taxonomy-layer-cap-accent: #8ec5f5")
                .contains("@media (forced-colors: active)")
                .contains("@media print");
""",
        "lock dark-theme token variant",
    )
    source = replace_once(
        source,
        """        assertThat(graph)
                .contains("GRAPH_NODE_COLOR_TOKENS")
                .contains("getPropertyValue(name)")
                .doesNotContain("var GRAPH_NODE_COLORS =")
                .doesNotContain("'Capabilities': '#4A90D9'");
""",
        """        assertThat(graph)
                .contains("GRAPH_NODE_COLOR_TOKENS")
                .contains("function resolveGraphNodeColorPalette()")
                .contains("var styles = getComputedStyle(document.documentElement)")
                .contains("Missing or invalid required taxonomy color token")
                .contains("getNodeColor(d.sheet, nodeColorPalette)")
                .doesNotContain("var GRAPH_NODE_COLORS =")
                .doesNotContain("'Capabilities': '#4A90D9'")
                .doesNotContain("rgb(75, 85, 99)");
""",
        "lock fail-closed cached graph palette",
    )
    return source


def main() -> None:
    graph = GRAPH.read_text(encoding="utf-8")
    if "function resolveGraphNodeColorPalette()" in graph:
        print("Design-token review fixes already applied.")
        return
    GRAPH.write_text(patch_graph(graph), encoding="utf-8")
    TEST.write_text(patch_test(TEST.read_text(encoding="utf-8")), encoding="utf-8")
    print("Applied cached, fail-closed graph token resolution and dark-theme contract.")


if __name__ == "__main__":
    main()
