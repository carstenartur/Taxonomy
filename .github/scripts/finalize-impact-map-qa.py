#!/usr/bin/env python3
"""Apply the final impact-map usability and browser-acceptance refinements."""

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
    """    function shouldMuteEdge(options) {
        if (options.edgeId === options.selectedEdgeId) return false;
        var mutedBySelection = Boolean(options.selectedNodeId) && !options.connected;
        var mutedBySearch = options.hasSearch && !options.searchRelevant;
        return mutedBySelection || mutedBySearch;
    }

    function shouldClearSelectionFromClick(event) {""",
    """    function shouldMuteEdge(options) {
        if (options.edgeId === options.selectedEdgeId) return false;
        var mutedBySelection = Boolean(options.selectedNodeId) && !options.connected;
        var mutedBySearch = options.hasSearch && !options.searchRelevant;
        return mutedBySelection || mutedBySearch;
    }

    function shouldMuteNode(options) {
        if (options.nodeId === options.selectedNodeId) return false;
        var mutedBySelection = Boolean(options.selectedNodeId) && !options.connected;
        var mutedBySearch = options.hasSearch && !options.searchMatch;
        return mutedBySelection || mutedBySearch;
    }

    function shouldClearSelectionFromClick(event) {""",
)
replace_once(
    graph,
    "    function limitNodes(nodes) {",
    "    function limitNodes(nodes, preferredNodeIds) {",
)
replace_once(
    graph,
    """        var sorted = nodes.slice().sort(function (left, right) {
            if (left.anchor !== right.anchor) return left.anchor ? -1 : 1;""",
    """        var preferred = preferredNodeIds instanceof Set
            ? preferredNodeIds
            : new Set(preferredNodeIds || []);
        var sorted = nodes.slice().sort(function (left, right) {
            var leftPreferred = preferred.has(left.id);
            var rightPreferred = preferred.has(right.id);
            if (leftPreferred !== rightPreferred) return leftPreferred ? -1 : 1;
            if (left.anchor !== right.anchor) return left.anchor ? -1 : 1;""",
)
replace_once(
    graph,
    """        var limited = limitNodes(candidates);
        var ids = new Set(limited.nodes.map(function (node) { return node.id; }));""",
    """        var preferredNodeIds = new Set();
        if (state.selectedNodeId) preferredNodeIds.add(state.selectedNodeId);
        var limited = limitNodes(candidates, preferredNodeIds);
        var ids = new Set(limited.nodes.map(function (node) { return node.id; }));""",
)
replace_once(
    graph,
    """        function setSelection(nodeId, edgeId, center) {
            state.selectedNodeId = nodeId || null;
            state.selectedEdgeId = edgeId || null;
            updateInteractionStyles();
            renderDetails();
            if (center && nodeId) centerOnNode(nodeId);
        }

        function matchingNodeIds() {""",
    """        function setSelection(nodeId, edgeId, center) {
            state.selectedNodeId = nodeId || null;
            state.selectedEdgeId = edgeId || null;
            updateInteractionStyles();
            renderDetails();
            if (center && nodeId) centerOnNode(nodeId);
        }

        function renderAndCenterNode(nodeId) {
            renderDiagram(false);
            window.requestAnimationFrame(function () {
                centerOnNode(nodeId);
            });
        }

        function matchingNodeIds() {""",
)
replace_once(
    graph,
    """            state.nodeElements.forEach(function (element, id) {
                element.classList.toggle('is-selected', id === selectedNode);
                element.classList.toggle('is-search-match', hasSearch && matches.has(id));
                var mutedBySelection = Boolean(selectedNode) && !connectedNodeIds.has(id);
                var mutedBySearch = hasSearch && !matches.has(id);
                element.classList.toggle('is-muted', mutedBySelection || mutedBySearch);
            });""",
    """            state.nodeElements.forEach(function (element, id) {
                var searchMatch = matches.has(id);
                var muted = shouldMuteNode({
                    nodeId: id,
                    selectedNodeId: selectedNode,
                    connected: connectedNodeIds.has(id),
                    hasSearch: hasSearch,
                    searchMatch: searchMatch
                });
                element.classList.toggle('is-selected', id === selectedNode);
                element.classList.toggle('is-search-match', hasSearch && searchMatch);
                element.classList.toggle('is-muted', muted);
            });""",
)
replace_once(
    graph,
    """                state.selectedNodeId = nodeId;
                state.selectedEdgeId = null;
                renderDiagram(false);
            } else {""",
    """                state.selectedNodeId = nodeId;
                state.selectedEdgeId = null;
                renderAndCenterNode(nodeId);
            } else {""",
)
replace_once(
    graph,
    """                state.selectedNodeId = first;
                state.selectedEdgeId = null;
                renderDiagram(false);
                status.textContent = t('impactmap.search.hidden');""",
    """                state.selectedNodeId = first;
                state.selectedEdgeId = null;
                renderAndCenterNode(first);
                status.textContent = t('impactmap.search.hidden');""",
)
replace_once(
    graph,
    """            window.setTimeout(function () {
                updateViewBox();
                fitView();
            }, 0);""",
    """            window.setTimeout(function () {
                updateViewBox();
                if (full) {
                    fitView();
                } else {
                    showReadableInitialView();
                }
            }, 0);""",
)
replace_once(
    graph,
    """            visibleModel: visibleModel,
            shouldMuteEdge: shouldMuteEdge,
            shouldClearSelectionFromClick: shouldClearSelectionFromClick""",
    """            visibleModel: visibleModel,
            shouldMuteEdge: shouldMuteEdge,
            shouldMuteNode: shouldMuteNode,
            shouldClearSelectionFromClick: shouldClearSelectionFromClick""",
)

scoring = "taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js"
replace_once(
    scoring,
    """                    relationType: r.relationType,
                    propagatedRelevance: r.propagatedRelevance
                };""",
    """                    relationType: r.relationType,
                    propagatedRelevance: r.propagatedRelevance,
                    includedBecause: r.derivationReason || r.includedBecause || ''
                };""",
)

tests = ".github/scripts/taxonomy-impact-map.test.mjs"
replace_once(
    tests,
    """const indexPath = new URL(
  '../../taxonomy-app/src/main/resources/templates/index.html',
  import.meta.url
);""",
    """const indexPath = new URL(
  '../../taxonomy-app/src/main/resources/templates/index.html',
  import.meta.url
);
const scoringSourcePath = new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js',
  import.meta.url
);""",
)
replace_once(
    tests,
    "const graphSource = readFileSync(graphSourcePath, 'utf8');",
    """const graphSource = readFileSync(graphSourcePath, 'utf8');
const scoringSource = readFileSync(scoringSourcePath, 'utf8');""",
)
replace_once(
    tests,
    """test('combines selection and search edge muting without overriding either state', () => {""",
    """test('keeps the selected result visible when a large graph is capped', () => {
  const { api } = loadApi();
  const allNodes = Array.from({ length: 81 }, (_, index) => ({
    id: `N${index}`,
    anchor: index < 2,
    hotspot: false,
    context: false,
    relevance: index === 80 ? 0 : 1 - index / 100,
  }));

  const visible = api.visibleModel({
    allNodes,
    allEdges: [],
    showContext: true,
    mode: 'overview',
    selectedNodeId: 'N80',
  });

  assert.equal(visible.nodes.length, 80);
  assert.ok(visible.nodes.some((node) => node.id === 'N80'));
  assert.equal(visible.omitted, 1);
});

test('combines selection and search edge muting without overriding either state', () => {""",
)
replace_once(
    tests,
    """test('clears selection from non-interactive diagram background clicks', () => {""",
    """test('keeps a selected node visible while applying selection and search filters', () => {
  const { api } = loadApi();

  assert.equal(api.shouldMuteNode({
    nodeId: 'A', selectedNodeId: 'A', connected: true,
    hasSearch: true, searchMatch: false,
  }), false);
  assert.equal(api.shouldMuteNode({
    nodeId: 'B', selectedNodeId: 'A', connected: false,
    hasSearch: false, searchMatch: true,
  }), true);
  assert.equal(api.shouldMuteNode({
    nodeId: 'B', selectedNodeId: 'A', connected: true,
    hasSearch: true, searchMatch: false,
  }), true);
  assert.equal(api.shouldMuteNode({
    nodeId: 'B', selectedNodeId: 'A', connected: true,
    hasSearch: true, searchMatch: true,
  }), false);
});

test('clears selection from non-interactive diagram background clicks', () => {""",
)
replace_once(
    tests,
    """test('registers the stylesheet and renderer in the main page', () => {""",
    """test('passes relationship derivation reasons into the impact-map detail model', () => {
  assert.match(
    scoringSource,
    /includedBecause:\s*r\.derivationReason \|\| r\.includedBecause \|\| ''/
  );
});

test('registers the stylesheet and renderer in the main page', () => {""",
)
replace_once(
    tests,
    """    'showReadableInitialView',
    'fullFitScale >= 0.62',""",
    """    'showReadableInitialView',
    'renderAndCenterNode',
    'fullFitScale >= 0.62',""",
)

ui_acceptance = ".github/scripts/ui-acceptance.mjs"
replace_once(
    ui_acceptance,
    """    const interactive = page.locator('#interactiveMode');
    if (await interactive.isChecked()) await interactive.uncheck();
    await page.locator('#businessText').fill(""",
    """    const interactive = page.locator('#interactiveMode');
    if (await interactive.isChecked()) await interactive.uncheck();
    const architectureView = page.locator('#includeArchitectureView');
    if (!(await architectureView.isChecked())) await architectureView.check();
    await page.locator('#businessText').fill(""",
)
replace_once(
    ui_acceptance,
    """    assert(await page.locator('[role=\"treeitem\"][aria-label*=\"Relevance\"]').count() > 0,
      'Dynamic scores were not synchronized to accessible tree-item names');
    await page.locator('#businessText').fill(""",
    """    assert(await page.locator('[role=\"treeitem\"][aria-label*=\"Relevance\"]').count() > 0,
      'Dynamic scores were not synchronized to accessible tree-item names');

    await navigateTo('architecture');
    const impactMap = page.locator('.impact-map-workbench');
    await impactMap.waitFor({ state: 'visible', timeout: 30_000 });
    assert(await impactMap.locator('.impact-map-kpi').count() >= 4,
      'Impact map did not render its orientation KPIs');
    const impactNodes = impactMap.locator('.impact-map-node');
    assert(await impactNodes.count() > 0,
      'Impact map did not render readable element cards');
    const firstImpactNode = impactNodes.first();
    const impactCode = await firstImpactNode.getAttribute('data-node-id');
    assert(Boolean(impactCode), 'Impact-map element lacks its stable node code');
    await firstImpactNode.click();
    assert(!(await impactMap.locator('.impact-map-details').getAttribute('class')).includes('is-empty'),
      'Selecting an impact-map element did not expose its details');
    const impactSearch = impactMap.locator('.impact-map-search');
    await impactSearch.fill(impactCode);
    await impactSearch.press('Enter');
    assert(await impactMap.locator('.impact-map-node.is-search-match').count() > 0,
      'Impact-map search did not highlight the matching element');
    await impactMap.locator('.impact-map-layer-column').first().evaluate(element => {
      element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    assert((await impactMap.locator('.impact-map-details').getAttribute('class')).includes('is-empty'),
      'Clicking empty impact-map space did not clear the selection');
    await impactSearch.fill('');
    passed('impact map layout, search, details, and background clearing');

    await navigateTo('analyze');
    await page.locator('#businessText').fill(""",
)
