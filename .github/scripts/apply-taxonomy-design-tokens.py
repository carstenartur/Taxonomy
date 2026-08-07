#!/usr/bin/env python3
"""Apply the contrast-safe architecture design-token contract for issue #623."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CSS = ROOT / "taxonomy-app/src/main/resources/static/css/taxonomy.css"
GRAPH = ROOT / "taxonomy-app/src/main/resources/static/js/shared/taxonomy-graph.js"
SCORING = ROOT / "taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js"
ROLE_FLOW = ROOT / ".github/scripts/ui-role-state-flow.mjs"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


def patch_css(source: str) -> str:
    token_marker = "--taxonomy-layer-cap-surface"
    if token_marker not in source:
        tokens = r'''/* ── Semantic architecture design tokens ─────────────────────────────── */
:root {
    --taxonomy-layer-on-surface: #ffffff;
    --taxonomy-layer-cap-surface: #1d5f91;
    --taxonomy-layer-cap-accent: #1d5f91;
    --taxonomy-layer-proc-surface: #146c43;
    --taxonomy-layer-proc-accent: #146c43;
    --taxonomy-layer-svc-surface: #8a4b00;
    --taxonomy-layer-svc-accent: #8a4b00;
    --taxonomy-layer-app-surface: #6f2c7f;
    --taxonomy-layer-app-accent: #6f2c7f;
    --taxonomy-layer-info-surface: #075985;
    --taxonomy-layer-info-accent: #075985;
    --taxonomy-layer-comm-surface: #b02a37;
    --taxonomy-layer-comm-accent: #b02a37;
    --taxonomy-layer-system-surface: #4c3f91;
    --taxonomy-layer-component-surface: #6f2c7f;
    --taxonomy-layer-default-surface: #4b5563;
    --taxonomy-font-size-essential: 0.8125rem;
}

[data-bs-theme="dark"] {
    --taxonomy-layer-cap-accent: #8ec5f5;
    --taxonomy-layer-proc-accent: #75d6a2;
    --taxonomy-layer-svc-accent: #f5b970;
    --taxonomy-layer-app-accent: #d3a3e3;
    --taxonomy-layer-info-accent: #7dd3fc;
    --taxonomy-layer-comm-accent: #f3a6ae;
}

'''
        source = tokens + source

    old_summary = r'''/* Layer colours */
.layer-cap { border-color: #4A90D9; }
.layer-cap .summary-layer-title { color: #4A90D9; }
.layer-cap .summary-layer-element { background: #4A90D9; }

.layer-proc { border-color: #27AE60; }
.layer-proc .summary-layer-title { color: #27AE60; }
.layer-proc .summary-layer-element { background: #27AE60; }

.layer-svc { border-color: #F39C12; }
.layer-svc .summary-layer-title { color: #F39C12; }
.layer-svc .summary-layer-element { background: #F39C12; }

.layer-app { border-color: #8E44AD; }
.layer-app .summary-layer-title { color: #8E44AD; }
.layer-app .summary-layer-element { background: #8E44AD; }

.layer-info { border-color: #3498DB; }
.layer-info .summary-layer-title { color: #3498DB; }
.layer-info .summary-layer-element { background: #3498DB; }

.layer-comm { border-color: #E74C3C; }
.layer-comm .summary-layer-title { color: #E74C3C; }
.layer-comm .summary-layer-element { background: #E74C3C; }
'''
    new_summary = r'''/* Architecture-layer colours use semantic tokens with tested foreground contrast. */
.layer-cap { border-color: var(--taxonomy-layer-cap-accent); }
.layer-cap .summary-layer-title { color: var(--taxonomy-layer-cap-accent); }
.layer-cap .summary-layer-element { background: var(--taxonomy-layer-cap-surface); }

.layer-proc { border-color: var(--taxonomy-layer-proc-accent); }
.layer-proc .summary-layer-title { color: var(--taxonomy-layer-proc-accent); }
.layer-proc .summary-layer-element { background: var(--taxonomy-layer-proc-surface); }

.layer-svc { border-color: var(--taxonomy-layer-svc-accent); }
.layer-svc .summary-layer-title { color: var(--taxonomy-layer-svc-accent); }
.layer-svc .summary-layer-element { background: var(--taxonomy-layer-svc-surface); }

.layer-app { border-color: var(--taxonomy-layer-app-accent); }
.layer-app .summary-layer-title { color: var(--taxonomy-layer-app-accent); }
.layer-app .summary-layer-element { background: var(--taxonomy-layer-app-surface); }

.layer-info { border-color: var(--taxonomy-layer-info-accent); }
.layer-info .summary-layer-title { color: var(--taxonomy-layer-info-accent); }
.layer-info .summary-layer-element { background: var(--taxonomy-layer-info-surface); }

.layer-comm { border-color: var(--taxonomy-layer-comm-accent); }
.layer-comm .summary-layer-title { color: var(--taxonomy-layer-comm-accent); }
.layer-comm .summary-layer-element { background: var(--taxonomy-layer-comm-surface); }

.summary-layer-element {
    color: var(--taxonomy-layer-on-surface);
    font-size: var(--taxonomy-font-size-essential);
}
'''
    source = replace_once(source, old_summary, new_summary, "summary layer tokens")

    old_impact = r'''/* Layer colours for impact nodes */
.impact-swimlane.layer-cap { border-color: #4A90D9; }
.impact-swimlane.layer-cap .impact-swimlane-title { color: #4A90D9; }
.impact-swimlane.layer-cap .impact-node { background: #4A90D9; }

.impact-swimlane.layer-proc { border-color: #27AE60; }
.impact-swimlane.layer-proc .impact-swimlane-title { color: #27AE60; }
.impact-swimlane.layer-proc .impact-node { background: #27AE60; }

.impact-swimlane.layer-svc { border-color: #F39C12; }
.impact-swimlane.layer-svc .impact-swimlane-title { color: #F39C12; }
.impact-swimlane.layer-svc .impact-node { background: #F39C12; }

.impact-swimlane.layer-app { border-color: #8E44AD; }
.impact-swimlane.layer-app .impact-swimlane-title { color: #8E44AD; }
.impact-swimlane.layer-app .impact-node { background: #8E44AD; }

.impact-swimlane.layer-info { border-color: #3498DB; }
.impact-swimlane.layer-info .impact-swimlane-title { color: #3498DB; }
.impact-swimlane.layer-info .impact-node { background: #3498DB; }

.impact-swimlane.layer-comm { border-color: #E74C3C; }
.impact-swimlane.layer-comm .impact-swimlane-title { color: #E74C3C; }
.impact-swimlane.layer-comm .impact-node { background: #E74C3C; }
'''
    new_impact = r'''/* Layer colours for impact nodes */
.impact-swimlane.layer-cap { border-color: var(--taxonomy-layer-cap-accent); }
.impact-swimlane.layer-cap .impact-swimlane-title { color: var(--taxonomy-layer-cap-accent); }
.impact-swimlane.layer-cap .impact-node { background: var(--taxonomy-layer-cap-surface); }

.impact-swimlane.layer-proc { border-color: var(--taxonomy-layer-proc-accent); }
.impact-swimlane.layer-proc .impact-swimlane-title { color: var(--taxonomy-layer-proc-accent); }
.impact-swimlane.layer-proc .impact-node { background: var(--taxonomy-layer-proc-surface); }

.impact-swimlane.layer-svc { border-color: var(--taxonomy-layer-svc-accent); }
.impact-swimlane.layer-svc .impact-swimlane-title { color: var(--taxonomy-layer-svc-accent); }
.impact-swimlane.layer-svc .impact-node { background: var(--taxonomy-layer-svc-surface); }

.impact-swimlane.layer-app { border-color: var(--taxonomy-layer-app-accent); }
.impact-swimlane.layer-app .impact-swimlane-title { color: var(--taxonomy-layer-app-accent); }
.impact-swimlane.layer-app .impact-node { background: var(--taxonomy-layer-app-surface); }

.impact-swimlane.layer-info { border-color: var(--taxonomy-layer-info-accent); }
.impact-swimlane.layer-info .impact-swimlane-title { color: var(--taxonomy-layer-info-accent); }
.impact-swimlane.layer-info .impact-node { background: var(--taxonomy-layer-info-surface); }

.impact-swimlane.layer-comm { border-color: var(--taxonomy-layer-comm-accent); }
.impact-swimlane.layer-comm .impact-swimlane-title { color: var(--taxonomy-layer-comm-accent); }
.impact-swimlane.layer-comm .impact-node { background: var(--taxonomy-layer-comm-surface); }

.impact-node,
.impact-node .impact-badge,
.impact-node .impact-node-code,
.archview-legend,
.force-graph-legend {
    font-size: var(--taxonomy-font-size-essential);
}
'''
    source = replace_once(source, old_impact, new_impact, "impact layer tokens")

    if "@media (forced-colors: active) {\n    .summary-layer-element" not in source:
        source += r'''

@media (forced-colors: active) {
    .summary-layer,
    .impact-swimlane,
    .summary-layer-element,
    .impact-node,
    .force-graph-legend-dot {
        border: 2px solid CanvasText !important;
        color: CanvasText !important;
        background: Canvas !important;
        forced-color-adjust: auto;
    }
}

@media print {
    .summary-layer-element,
    .impact-node,
    .force-graph-legend-dot {
        border: 1px solid #000 !important;
        color: #000 !important;
        background: #fff !important;
    }
}
'''
    return source


def patch_graph(source: str) -> str:
    old = r'''    var GRAPH_NODE_COLORS = {
        'Capabilities': '#4A90D9',
        'Business Processes': '#27AE60',
        'Business Roles': '#27AE60',
        'Services': '#F39C12',
        'COI Services': '#F39C12',
        'Core Services': '#F39C12',
        'Applications': '#8E44AD',
        'User Applications': '#8E44AD',
        'Information Products': '#3498DB',
        'Communications Services': '#E74C3C',
        'Systems': '#6A5ACD',
        'Components': '#9B59B6'
    };
'''
    new = r'''    var GRAPH_NODE_COLOR_TOKENS = {
        'Capabilities': '--taxonomy-layer-cap-surface',
        'CP': '--taxonomy-layer-cap-surface',
        'Business Processes': '--taxonomy-layer-proc-surface',
        'Business Roles': '--taxonomy-layer-proc-surface',
        'BP': '--taxonomy-layer-proc-surface',
        'BR': '--taxonomy-layer-proc-surface',
        'Services': '--taxonomy-layer-svc-surface',
        'COI Services': '--taxonomy-layer-svc-surface',
        'Core Services': '--taxonomy-layer-svc-surface',
        'CI': '--taxonomy-layer-svc-surface',
        'CR': '--taxonomy-layer-svc-surface',
        'Applications': '--taxonomy-layer-app-surface',
        'User Applications': '--taxonomy-layer-app-surface',
        'UA': '--taxonomy-layer-app-surface',
        'Information Products': '--taxonomy-layer-info-surface',
        'IP': '--taxonomy-layer-info-surface',
        'Communications Services': '--taxonomy-layer-comm-surface',
        'CO': '--taxonomy-layer-comm-surface',
        'Systems': '--taxonomy-layer-system-surface',
        'Components': '--taxonomy-layer-component-surface'
    };
'''
    source = replace_once(source, old, new, "graph layer token map")
    source = replace_once(
        source,
        r'''    function getNodeColor(taxonomySheet) {
        return GRAPH_NODE_COLORS[taxonomySheet] || '#6c757d';
    }
''',
        r'''    function cssColorToken(name, fallbackToken) {
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
''',
        "graph computed design token",
    )
    return source


def patch_scoring(source: str) -> str:
    replacements = {
        "icon: '🔵'": "icon: '◆'",
        "icon: '🟢'": "icon: '▰'",
        "icon: '🟠'": "icon: '⬡'",
        "icon: '🟣'": "icon: '▣'",
        "icon: '🔷'": "icon: '◇'",
        "icon: '🔴'": "icon: '↔'",
    }
    for old, new in replacements.items():
        if old not in source:
            raise RuntimeError(f"scoring icon token missing: {old}")
        source = source.replace(old, new)
    return source


def patch_role_flow(source: str) -> str:
    old = """  passed('analysis loading, success and contextual next action');
  await runAxe('analysis-success');
"""
    new = r'''  passed('analysis loading, success and contextual next action');
  const architectureContrast = await page.evaluate(() => {
    function channel(value) {
      const normalized = value / 255;
      return normalized <= 0.04045
        ? normalized / 12.92
        : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
    function luminance(value) {
      const match = value.match(/rgba?\((\d+)[, ]+(\d+)[, ]+(\d+)/i);
      if (!match) return null;
      return 0.2126 * channel(Number(match[1]))
        + 0.7152 * channel(Number(match[2]))
        + 0.0722 * channel(Number(match[3]));
    }
    function ratio(foreground, background) {
      const left = luminance(foreground);
      const right = luminance(background);
      if (left === null || right === null) return null;
      return (Math.max(left, right) + 0.05) / (Math.min(left, right) + 0.05);
    }
    const elements = [...document.querySelectorAll('.summary-layer-element, .impact-node')]
      .filter(element => element.getClientRects().length > 0);
    const ratios = elements.map(element => {
      const style = getComputedStyle(element);
      return ratio(style.color, style.backgroundColor);
    }).filter(value => value !== null);
    return {
      sampleCount: ratios.length,
      minimumRatio: ratios.length ? Math.min(...ratios) : null
    };
  });
  assert(architectureContrast.sampleCount > 0
    && architectureContrast.minimumRatio >= 4.5,
  `Architecture layer contrast failed: ${JSON.stringify(architectureContrast)}`);
  passed('contrast-safe architecture layer tokens');
  await runAxe('analysis-success');
'''
    return replace_once(source, old, new, "browser contrast evidence")


def main() -> None:
    css = CSS.read_text(encoding="utf-8")
    if "--taxonomy-layer-cap-surface" in css:
        print("Architecture design tokens already applied.")
        return
    CSS.write_text(patch_css(css), encoding="utf-8")
    GRAPH.write_text(patch_graph(GRAPH.read_text(encoding="utf-8")), encoding="utf-8")
    SCORING.write_text(patch_scoring(SCORING.read_text(encoding="utf-8")), encoding="utf-8")
    ROLE_FLOW.write_text(patch_role_flow(ROLE_FLOW.read_text(encoding="utf-8")), encoding="utf-8")
    print("Applied contrast-safe semantic architecture design tokens.")


if __name__ == "__main__":
    main()
