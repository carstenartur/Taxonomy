#!/usr/bin/env python3
"""Apply confirmed interaction fixes from PR review."""

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


def replace_expected(path: str, old: str, new: str, expected: int) -> None:
    file = Path(path)
    content = file.read_text(encoding="utf-8")
    occurrences = content.count(old)
    if occurrences != expected:
        raise SystemExit(
            f"{path}: expected {expected} occurrences of {old!r}, found {occurrences}"
        )
    file.write_text(content.replace(old, new), encoding="utf-8")


graph = "taxonomy-app/src/main/resources/static/js/shared/taxonomy-impact-map.js"
replace_expected(graph, ".toLocaleLowerCase()", ".toLowerCase()", 5)

replace_once(
    graph,
    """    function safeNumber(value, fallback) {
        var number = Number(value);
        return Number.isFinite(number) ? number : fallback;
    }

    function createElement(tagName, className, text) {""",
    """    function safeNumber(value, fallback) {
        var number = Number(value);
        return Number.isFinite(number) ? number : fallback;
    }

    function shouldMuteEdge(options) {
        if (options.edgeId === options.selectedEdgeId) return false;
        var mutedBySelection = Boolean(options.selectedNodeId) && !options.connected;
        var mutedBySearch = options.hasSearch && !options.searchRelevant;
        return mutedBySelection || mutedBySearch;
    }

    function shouldClearSelectionFromClick(event) {
        if (!event || event.defaultPrevented) return false;
        var target = event.target;
        if (!target || typeof target.closest !== 'function') return true;
        return !target.closest('.impact-map-node, .impact-map-edge-hit');
    }

    function createElement(tagName, className, text) {""",
)

replace_once(
    graph,
    """            state.edgeElements.forEach(function (element, id) {
                var edge = state.visible.edges.find(function (candidate) { return candidate.id === id; });
                var connected = connectedEdgeIds.has(id);
                element.classList.toggle('is-selected', id === selectedEdge);
                element.classList.toggle('is-connected', connected);
                element.classList.toggle('is-muted', Boolean(selectedNode) && !connected);
                if (element._label) {
                    element._label.style.display = state.visible.edges.length <= 14 || connected || id === selectedEdge
                        ? '' : 'none';
                }
                if (hasSearch && edge) {
                    var searchRelevant = matches.has(edge.source) || matches.has(edge.target);
                    element.classList.toggle('is-muted', !searchRelevant);
                }
            });""",
    """            state.edgeElements.forEach(function (element, id) {
                var connected = connectedEdgeIds.has(id);
                var source = element.getAttribute('data-source');
                var target = element.getAttribute('data-target');
                var searchRelevant = !hasSearch || matches.has(source) || matches.has(target);
                var muted = shouldMuteEdge({
                    edgeId: id,
                    selectedEdgeId: selectedEdge,
                    selectedNodeId: selectedNode,
                    connected: connected,
                    hasSearch: hasSearch,
                    searchRelevant: searchRelevant
                });
                element.classList.toggle('is-selected', id === selectedEdge);
                element.classList.toggle('is-connected', connected);
                element.classList.toggle('is-muted', muted);
                if (element._label) {
                    element._label.style.display = state.visible.edges.length <= 14 ||
                        connected || id === selectedEdge || (hasSearch && searchRelevant)
                        ? '' : 'none';
                }
            });""",
)

replace_once(
    graph,
    """        svg.addEventListener('click', function (event) {
            if (event.target === svg) {
                setSelection(null, null, false);
            }
        });""",
    """        svg.addEventListener('click', function (event) {
            if (shouldClearSelectionFromClick(event)) {
                setSelection(null, null, false);
            }
        });""",
)

replace_once(
    graph,
    """            normalizeEdges: normalizeEdges,
            buildLayout: buildLayout,
            visibleModel: visibleModel
        };""",
    """            normalizeEdges: normalizeEdges,
            buildLayout: buildLayout,
            visibleModel: visibleModel,
            shouldMuteEdge: shouldMuteEdge,
            shouldClearSelectionFromClick: shouldClearSelectionFromClick
        };""",
)

tests = ".github/scripts/taxonomy-impact-map.test.mjs"
replace_once(
    tests,
    """test('provides orientation, search, focus, detail and accessibility controls', () => {""",
    """test('combines selection and search edge muting without overriding either state', () => {
  const { api } = loadApi();

  assert.equal(api.shouldMuteEdge({
    edgeId: 'AB', selectedEdgeId: null, selectedNodeId: 'A',
    connected: false, hasSearch: false, searchRelevant: true,
  }), true);
  assert.equal(api.shouldMuteEdge({
    edgeId: 'AB', selectedEdgeId: null, selectedNodeId: 'A',
    connected: true, hasSearch: true, searchRelevant: false,
  }), true);
  assert.equal(api.shouldMuteEdge({
    edgeId: 'AB', selectedEdgeId: null, selectedNodeId: 'A',
    connected: true, hasSearch: true, searchRelevant: true,
  }), false);
  assert.equal(api.shouldMuteEdge({
    edgeId: 'AB', selectedEdgeId: 'AB', selectedNodeId: 'A',
    connected: false, hasSearch: true, searchRelevant: false,
  }), false);
});

test('clears selection from non-interactive diagram background clicks', () => {
  const { api } = loadApi();
  const background = { closest() { return null; } };
  const node = { closest() { return {}; } };

  assert.equal(api.shouldClearSelectionFromClick({ defaultPrevented: false, target: background }), true);
  assert.equal(api.shouldClearSelectionFromClick({ defaultPrevented: false, target: node }), false);
  assert.equal(api.shouldClearSelectionFromClick({ defaultPrevented: true, target: background }), false);
});

test('uses locale-independent matching and avoids per-edge graph scans', () => {
  assert.doesNotMatch(graphSource, /toLocaleLowerCase/);
  assert.doesNotMatch(graphSource, /state\.visible\.edges\.find/);
  assert.match(graphSource, /\.toLowerCase\(\)/);
  assert.match(graphSource, /mutedBySelection \|\| mutedBySearch/);
});

test('provides orientation, search, focus, detail and accessibility controls', () => {""",
)
