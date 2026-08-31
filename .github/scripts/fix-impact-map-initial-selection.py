#!/usr/bin/env python3
"""Separate initial centring from explicit user selection in the impact map."""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    content = file.read_text(encoding="utf-8")
    occurrences = content.count(old)
    if occurrences != 1:
        raise SystemExit(
            f"{path}: expected one replacement target, found {occurrences}"
        )
    file.write_text(content.replace(old, new, 1), encoding="utf-8")


graph = "taxonomy-app/src/main/resources/static/js/shared/taxonomy-impact-map.js"
replace_once(
    graph,
    """            mode: 'overview',
            selectedNodeId: defaultSelection ? defaultSelection.id : null,
            selectedEdgeId: null,""",
    """            mode: 'overview',
            initialNodeId: defaultSelection ? defaultSelection.id : null,
            selectedNodeId: null,
            selectedEdgeId: null,""",
)
replace_once(
    graph,
    """            if (fullFitScale >= 0.62 || !state.selectedNodeId) {
                fitView();
            } else {
                centerOnNode(state.selectedNodeId, 0.82);
            }""",
    """            var focusNodeId = state.selectedNodeId || state.initialNodeId;
            if (fullFitScale >= 0.62 || !focusNodeId) {
                fitView();
            } else {
                centerOnNode(focusNodeId, 0.82);
            }""",
)

tests = ".github/scripts/taxonomy-impact-map.test.mjs"
replace_once(
    tests,
    """    'showReadableInitialView',
    'renderAndCenterNode',
    'fullFitScale >= 0.62',""",
    """    'showReadableInitialView',
    'renderAndCenterNode',
    "initialNodeId: defaultSelection ? defaultSelection.id : null",
    'selectedNodeId: null',
    'state.selectedNodeId || state.initialNodeId',
    'fullFitScale >= 0.62',""",
)

ui_acceptance = ".github/scripts/ui-acceptance.mjs"
replace_once(
    ui_acceptance,
    """    const impactNodes = impactMap.locator('.impact-map-node');
    assert(await impactNodes.count() > 0,
      'Impact map did not render readable element cards');
    const firstImpactNode = impactNodes.first();""",
    """    const impactNodes = impactMap.locator('.impact-map-node');
    assert(await impactNodes.count() > 0,
      'Impact map did not render readable element cards');
    assert(await impactMap.locator('.impact-map-node.is-muted').count() === 0,
      'Impact map starts with unrelated elements muted before the user selects anything');
    assert((await impactMap.locator('.impact-map-details').getAttribute('class')).includes('is-empty'),
      'Impact map starts with an implicit selection instead of the overview hint');
    const firstImpactNode = impactNodes.first();""",
)
