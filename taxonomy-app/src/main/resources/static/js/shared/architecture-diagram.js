/* Shared SVG scene primitives. Source adapters own metadata and layout, never activation or geometry mutation. */
(function () {
    'use strict';
    var NS = 'http://www.w3.org/2000/svg';
    function element(tag, attributes) {
        var result = document.createElementNS(NS, tag);
        Object.keys(attributes || {}).forEach(function (key) {
            if (attributes[key] !== undefined && attributes[key] !== null) result.setAttribute(key, String(attributes[key]));
        });
        return result;
    }
    function text(parent, className, x, y, value, color) {
        var result = element('text', { 'class': className, x: x, y: y, fill: color });
        result.textContent = value; parent.appendChild(result); return result;
    }
    function activate(target, select, focus) {
        function click(event) { if (event.defaultPrevented) return; event.stopPropagation(); select(); }
        function doubleClick(event) { event.preventDefault(); event.stopPropagation(); if (focus) focus(); }
        function keydown(event) {
            if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation(); select(); }
            else if (focus && event.key.toLowerCase() === 'f') { event.preventDefault(); event.stopPropagation(); focus(); }
        }
        target.addEventListener('click', click);
        target.addEventListener('keydown', keydown);
        if (focus) target.addEventListener('dblclick', doubleClick);
        return function () {
            target.removeEventListener('click', click); target.removeEventListener('keydown', keydown);
            if (focus) target.removeEventListener('dblclick', doubleClick);
        };
    }
    function renderNode(parent, node, callbacks) {
        callbacks = callbacks || {};
        var prefix = node.prefix || 'architecture';
        var group = element('g', { 'class': node.className || prefix + '-node', transform: 'translate(' + node.x + ',' + node.y + ')',
            'data-node-id': node.id, tabindex: 0, role: 'button', 'aria-label': node.ariaLabel || node.label || node.id,
            'aria-pressed': Boolean(node.selected) });
        group.appendChild(element('rect', { 'class': prefix + '-node-card', x: 0, y: 0, width: node.width, height: node.height, rx: node.compact ? 6 : 9, fill: node.color }));
        if (node.compact) text(group, prefix + '-node-title', 12, 30, node.label || node.id, node.textColor);
        else {
            text(group, prefix + '-node-code', 12, 19, node.id, node.textColor);
            var scoreWidth = node.scoreWidth || 58;
            group.appendChild(element('rect', { 'class': prefix + '-node-score-pill', x: node.width - scoreWidth - 8, y: 7, width: scoreWidth, height: 22, rx: 11, fill: node.scoreFill }));
            text(group, prefix + '-node-score', node.width - 8 - scoreWidth / 2, 22, node.score, node.textColor).setAttribute('text-anchor', 'middle');
            (node.titleLines || []).forEach(function (line, index) { text(group, prefix + '-node-title', 12, 43 + index * 15, line, node.textColor); });
            text(group, prefix + '-node-meta', 12, node.metaY === undefined ? node.height - 8 : node.metaY, node.meta, node.textColor);
            if (node.hotspot) text(group, prefix + '-hotspot-mark', node.width - 13, 80, '⚠', '#b42318').setAttribute('text-anchor', 'end');
        }
        if (node.tooltip) { var title = element('title'); title.textContent = node.tooltip; group.appendChild(title); }
        activate(group, function () { if (callbacks.select) callbacks.select(node.id); }, callbacks.focus && function () { callbacks.focus(node.id); });
        parent.appendChild(group); return group;
    }
    function renderEdge(parent, edge, callbacks) {
        callbacks = callbacks || {};
        var prefix = edge.prefix || 'architecture';
        var group = element('g', { 'class': edge.className || prefix + '-edge', 'data-edge-id': edge.id, 'data-source': edge.source, 'data-target': edge.target });
        var path = element('path', { 'class': edge.pathClass || prefix + '-edge-path', d: edge.path, stroke: edge.color, 'marker-end': 'url(#' + edge.markerId + ')' });
        var hit = element('path', { 'class': edge.hitClass || prefix + '-edge-hit', d: edge.path, tabindex: 0, role: 'button', 'aria-label': edge.ariaLabel });
        var title = element('title'); title.textContent = edge.tooltip || ''; hit.appendChild(title);
        activate(hit, function () { if (callbacks.select) callbacks.select(edge.id); });
        group.appendChild(path); group.appendChild(hit);
        if (edge.label !== undefined) {
            var label = text(group, prefix + '-edge-label', edge.labelX, edge.labelY, edge.label, edge.color);
            label.setAttribute('text-anchor', 'middle'); label.style.display = edge.showLabel === false ? 'none' : ''; group._label = label;
        }
        parent.appendChild(group); return group;
    }
    function source(edge) { return edge.sourceId === undefined ? edge.source : edge.sourceId; }
    function target(edge) { return edge.targetId === undefined ? edge.target : edge.targetId; }
    function visibleModel(scene, state) {
        var included = new Set([state.selectedNodeId]);
        if (state.mode === 'focus' && state.selectedNodeId) scene.edges.forEach(function (edge) {
            if (source(edge) === state.selectedNodeId || target(edge) === state.selectedNodeId) { included.add(source(edge)); included.add(target(edge)); }
        });
        var nodes = scene.nodes.filter(function (node) {
            return (state.showContext !== false || (node.context === undefined ? node.anchor : !node.context))
                && (state.mode !== 'focus' || !state.selectedNodeId || included.has(node.id));
        });
        var ids = new Set(nodes.map(function (node) { return node.id; }));
        return { nodes: nodes, edges: scene.edges.filter(function (edge) { return ids.has(source(edge)) && ids.has(target(edge)); }), nodeIds: ids };
    }
    function createState() {
        var state = { selectedNodeId: null, selectedEdgeId: null, mode: 'overview', showContext: true }, viewport = { x: 0, y: 0, k: 1 };
        return {
            select: function (id, edgeId) { state.selectedNodeId = id || null; state.selectedEdgeId = edgeId || null; if (!id) state.mode = 'overview'; },
            focus: function () { if (state.selectedNodeId) state.mode = 'focus'; },
            unfocus: function () { state.mode = 'overview'; },
            setContext: function (value) { state.showContext = Boolean(value); },
            snapshot: function () { return Object.assign({}, state); },
            captureViewport: function () { return Object.assign({}, viewport); },
            restoreViewport: function (value) { if (value && Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.k) && value.k > 0) viewport = { x: value.x, y: value.y, k: value.k }; },
            refresh: function (scene) { if (state.selectedNodeId && !scene.nodes.some(function (node) { return node.id === state.selectedNodeId; })) { state.selectedNodeId = null; state.mode = 'overview'; } }
        };
    }
    function calculateFitTransform(bounds, viewport, options) {
        options = options || {}; bounds = bounds || {}; viewport = viewport || {};
        if (options.requireVisible && (!viewport.width || !viewport.height)) return null;
        function number(value, fallback) { var result = Number(value); return Number.isFinite(result) ? result : fallback; }
        var padding = Math.max(0, number(options.padding, 34));
        var minimum = Math.max(0.001, number(options.minimumScale, 0.01));
        var maximum = Math.max(minimum, number(options.maximumScale, 1.25));
        var width = Math.max(1, number(bounds.width, 1)), height = Math.max(1, number(bounds.height, 1));
        var vw = Math.max(padding * 2 + 1, number(viewport.width, 320)), vh = Math.max(padding * 2 + 1, number(viewport.height, 300));
        var scale = Math.max(minimum, Math.min(maximum, (vw - 2 * padding) / width, (vh - 2 * padding) / height));
        var left = (vw - width * scale) / 2, top = (vh - height * scale) / 2;
        return { scale: scale, x: left - number(bounds.x, 0) * scale, y: top - number(bounds.y, 0) * scale, left: left, top: top, right: left + width * scale, bottom: top + height * scale };
    }
    function relationshipLanes(edges) {
        var groups = new Map(), lanes = new Map();
        edges.forEach(function (edge) { var key = source(edge) + '\u0000' + target(edge); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(edge); });
        groups.forEach(function (items) { items.forEach(function (edge, index) { lanes.set(edge.id, index - (items.length - 1) / 2); }); }); return lanes;
    }
    function edgePath(source, target, lane, endpoints) {
        var sourceLayer = source.layerIndex === undefined ? source.layer : source.layerIndex;
        var targetLayer = target.layerIndex === undefined ? target.layer : target.layerIndex;
        var forward = sourceLayer < targetLayer, sameLayer = sourceLayer === targetLayer;
        var sx = endpoints ? endpoints.sourceX : source.x + (forward || sameLayer ? source.width : 0);
        var tx = endpoints ? endpoints.targetX : target.x + (forward ? 0 : target.width);
        var sy = endpoints ? endpoints.sourceY : source.y + source.height / 2;
        var ty = endpoints ? endpoints.targetY : target.y + target.height / 2;
        var offset = (lane || 0) * 12, cx1, cx2;
        if (sameLayer) {
            if (endpoints) cx1 = cx2 = Math.max(source.x + source.width, target.x + target.width) + 42 + Math.abs(offset);
            else { var spread = 42 + Math.min(80, Math.abs(ty - sy) * 0.22) + Math.abs(offset); cx1 = sx + spread; cx2 = tx + spread; }
        } else {
            var distance = Math.max(42, Math.abs(tx - sx) * 0.43), direction = tx >= sx ? 1 : -1;
            cx1 = sx + direction * distance; cx2 = tx - direction * distance;
        }
        return 'M' + sx + ',' + sy + ' C' + cx1 + ',' + (sy + offset) + ' ' + cx2 + ',' + (ty + offset) + ' ' + tx + ',' + ty;
    }
    function regionNodeIds(nodes, center, radius) {
        return nodes.filter(function (node) {
            var dx = center[0] - Math.max(node.x, Math.min(center[0], node.x + node.width));
            var dy = center[1] - Math.max(node.y, Math.min(center[1], node.y + node.height));
            return dx * dx + dy * dy <= radius * radius;
        }).map(function (node) { return node.id; });
    }
    function suggestionPath(source, target) {
        var x = source.x + source.width / 2, y = source.y + source.height / 2;
        var dx = target.x + target.width / 2 - x, dy = target.y + target.height / 2 - y;
        var start = Math.min(source.width / (2 * Math.abs(dx || 0.001)), source.height / (2 * Math.abs(dy || 0.001)));
        var end = Math.min(target.width / (2 * Math.abs(dx || 0.001)), target.height / (2 * Math.abs(dy || 0.001)));
        return 'M' + (x + dx * start) + ',' + (y + dy * start) + ' L' + (x + dx * (1 - end)) + ',' + (y + dy * (1 - end));
    }
    window.TaxonomyArchitectureDiagram = { element: element, text: text, activate: activate, renderNode: renderNode, renderEdge: renderEdge,
        visibleModel: visibleModel, createState: createState, calculateFitTransform: calculateFitTransform, relationshipLanes: relationshipLanes,
        edgePath: edgePath, regionNodeIds: regionNodeIds, suggestionPath: suggestionPath };
}());

/* Renderer adapter: accepts immutable DiagramScene values and emits selection intents only. */
window.TaxonomyArchitectureDiagram.create = function (element, options) {
    var onSelect = options.onSelect || function () {}, onRelation = options.onRelation || function () {};
    var onSummary = options.onSummary || function () {}, onRegion = options.onRegion, onSuggestion = options.onSuggestion;
    'use strict';
    var d3 = window.d3;
    var svg = d3.select(element);
    var markerSequence = (window.TaxonomyArchitectureDiagram.markerSequence || 0) + 1;
    window.TaxonomyArchitectureDiagram.markerSequence = markerSequence;
    var markerId = 'editorArrow-' + markerSequence;
    var definitions = svg.append('defs');
    definitions.append('marker').attr('id', markerId).attr('viewBox', '0 0 10 10')
        .attr('refX', 9).attr('refY', 5).attr('markerWidth', 7).attr('markerHeight', 7).attr('orient', 'auto')
        .append('path').attr('d', 'M0,0 L10,5 L0,10 Z').attr('fill', 'context-stroke');
    var edges = svg.append('g');
    var ghosts = svg.append('g');
    var nodes = svg.append('g');
    var lens = { enabled: false, states: {}, suggestions: [] };
    var ring = svg.append('circle').attr('class', 'editor-lens-ring').attr('aria-hidden', 'true').attr('display', 'none');
    var drawnNodes = [];
    var scene = { nodes: [], edges: [], width: 1000, height: 420 };
    var selected = null;
    var transform = d3.zoomIdentity;
    var frame = null;
    var focusViewport = null;
    var pendingFit = null;
    var destroyed = false;
    var MAX_NODES = 200;
    var MAX_EDGES = 400;
    var zoom = d3.zoom().scaleExtent([0.01, 4]).filter(function (event) {
        return (!event.ctrlKey || event.type === 'wheel') && !event.button && (!lens.enabled || event.type === 'wheel');
    }).on('zoom', function (event) {
        transform = event.transform;
        if (frame === null) frame = requestAnimationFrame(draw);
    });
    svg.call(zoom).on('dblclick.zoom', null);

    function draw() {
        frame = null;
        if (destroyed) return;
        var left = -transform.x / transform.k;
        var top = -transform.y / transform.k;
        var right = left + 1000 / transform.k;
        var bottom = top + 420 / transform.k;
        var visible = scene.nodes.filter(function (node) {
            return node.x + node.width >= left && node.x <= right && node.y + node.height >= top && node.y <= bottom;
        });
        var selectedNode = visible.find(function (node) { return node.id === selected; });
        visible = visible.slice(0, MAX_NODES);
        if (selectedNode && !visible.includes(selectedNode)) visible[visible.length - 1] = selectedNode;
        drawnNodes = visible;
        var ids = new Set(visible.map(function (node) { return node.id; }));
        var proposals = lens.enabled ? lens.suggestions.filter(function (edge) {
            return ids.has(edge.sourceId) && ids.has(edge.targetId);
        }).slice(0, 32) : [];
        edges.attr('transform', transform.toString());
        ghosts.attr('transform', transform.toString());
        nodes.attr('transform', transform.toString());
        // Preserve keyboard focus across a bounded redraw (including zoom culling).
        var active = document.activeElement;
        var activeId = active && active.getAttribute && active.getAttribute('data-node-id');
        var activeEdge = active && active.getAttribute && active.getAttribute('data-edge-id');
        var activeSuggestion = active && active.getAttribute && active.getAttribute('data-suggestion-id');
        edges.node().replaceChildren(); ghosts.node().replaceChildren(); nodes.node().replaceChildren();
        var common = window.TaxonomyArchitectureDiagram;
        scene.edges.filter(function (edge) { return ids.has(edge.sourceId) && ids.has(edge.targetId); })
            .slice(0, MAX_EDGES - proposals.length).forEach(function (edge) {
                var path = common.element('path', { 'class': 'editor-edge', 'data-edge-id': edge.id, tabindex: 0, role: 'button',
                    'marker-end': 'url(#' + markerId + ')', 'aria-label': edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId,
                    d: 'M' + edge.sourceX + ',' + edge.sourceY + ' L' + edge.targetX + ',' + edge.targetY });
                common.activate(path, function () { onRelation(edge); }); edges.node().appendChild(path);
                if (activeEdge === edge.id && path.focus) path.focus({ preventScroll: true });
            });
        proposals.forEach(function (edge) {
            var source = visible.find(function (node) { return node.id === edge.sourceId; });
            var target = visible.find(function (node) { return node.id === edge.targetId; });
            var path = common.element('path', { 'data-suggestion-id': edge.id, 'class': 'editor-ghost' + (edge.id === lens.reviewed ? ' selected' : ''),
                tabindex: 0, role: 'button', 'marker-end': 'url(#' + markerId + ')',
                'aria-label': edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId, d: common.suggestionPath(source, target) });
            common.activate(path, function () { if (onSuggestion) onSuggestion(edge.id); }); ghosts.node().appendChild(path);
            if (activeSuggestion === edge.id && path.focus) path.focus({ preventScroll: true });
        });
        visible.forEach(function (node) {
            var label = node.label || node.id;
            var group = common.renderNode(nodes.node(), {
                id: node.id, x: node.x, y: node.y, width: node.width, height: node.height, compact: true, prefix: 'editor',
                label: label.length > 28 ? label.substring(0, 27) + '…' : label, selected: node.id === selected,
                className: 'editor-node' + (node.id === selected ? ' selected' : '')
                    + (lens.enabled && lens.states[node.id] ? ' lens-' + lens.states[node.id] : ''),
                ariaLabel: label + ' · ' + node.id + (lens.enabled && lens.states[node.id] ? ' · '
                    + (lens.label ? lens.label(lens.states[node.id]) : lens.states[node.id]) : '')
            }, { select: function (id) { selected = id; onSelect(id); }, focus: function (id) { selected = id; onSelect(id); focus(); } });
            if (activeId === node.id && group.focus) group.focus({ preventScroll: true });
        });
        onSummary(visible.length, scene.nodes.length);
    }

    function readableFrame(node) {
        var matrix = element.getScreenCTM();
        var screenScale = matrix ? Math.min(Math.hypot(matrix.a, matrix.b), Math.hypot(matrix.c, matrix.d)) : 1;
        screenScale = screenScale || 1;
        var padding = Math.min(10 / screenScale, 420 / 4);
        var extent = zoom.scaleExtent();
        return { padding: padding, scale: Math.max(extent[0], Math.min(extent[1], 1 / screenScale,
            (1000 - 2 * padding) / node.width, (420 - 2 * padding) / node.height)) };
    }
    function frameInitial() {
        var first = scene.nodes[0];
        var frame = readableFrame(first);
        var left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
        scene.nodes.forEach(function (node) {
            left = Math.min(left, node.x); top = Math.min(top, node.y);
            right = Math.max(right, node.x + node.width); bottom = Math.max(bottom, node.y + node.height);
        });
        var x = frame.padding - first.x * frame.scale;
        var y = frame.padding - first.y * frame.scale;
        // Keep compact models together; large models open on a readable neighborhood.
        if ((right - left) * frame.scale <= 1000 - 2 * frame.padding
                && (bottom - top) * frame.scale <= 420 - 2 * frame.padding) {
            x = 500 - (left + right) / 2 * frame.scale;
            y = 210 - (top + bottom) / 2 * frame.scale;
        }
        svg.call(zoom.transform, d3.zoomIdentity.translate(x, y).scale(frame.scale));
    }
    function fit(options) {
        var size = element.getBoundingClientRect();
        if (!size.width || !size.height) { pendingFit = options || {}; return; }
        pendingFit = null;
        if (options && options.initial === true && scene.nodes.length) { frameInitial(); return; }
        var scale = Math.min(1, 980 / Math.max(1, scene.width), 400 / Math.max(1, scene.height));
        svg.call(zoom.transform, d3.zoomIdentity.translate(10, 10).scale(scale));
    }
    function focus() {
        if (!focusViewport) focusViewport = { x: transform.x, y: transform.y, k: transform.k };
        var node = scene.nodes.find(function (candidate) { return candidate.id === selected; });
        if (node) {
            var scale = readableFrame(node).scale;
            svg.call(zoom.transform, d3.zoomIdentity.translate(500 - (node.x + node.width / 2) * scale,
                210 - (node.y + node.height / 2) * scale).scale(scale));
        }
    }
    function onKeyDown(event) {
        var moves = { ArrowLeft: [60, 0], ArrowRight: [-60, 0], ArrowUp: [0, 60], ArrowDown: [0, -60] };
        if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
        // Navigation may remove a focused child from the visible scene; keep keyboard control on the SVG.
        if ((moves[event.key] || event.key === '+' || event.key === '-') && event.target !== element) element.focus({ preventScroll: true });
        if (moves[event.key]) { event.preventDefault(); svg.call(zoom.translateBy, moves[event.key][0] / transform.k, moves[event.key][1] / transform.k); }
        if (event.key === '+' || event.key === '-') { event.preventDefault(); svg.call(zoom.scaleBy, event.key === '+' ? 1.4 : 1 / 1.4); }
    }
    function onPointer(event) {
        if (!lens.enabled || !onRegion) return;
        var point = d3.pointer(event, element), matrix = element.getScreenCTM();
        var radius = 56 / (matrix ? Math.hypot(matrix.a, matrix.b) || 1 : 1);
        ring.attr('cx', point[0]).attr('cy', point[1]).attr('r', radius).attr('display', null);
        var center = transform.invert(point), distance = radius / transform.k;
        onRegion(window.TaxonomyArchitectureDiagram.regionNodeIds(drawnNodes, center, distance));
    }
    function leaveRegion() { ring.attr('display', 'none'); if (onRegion) onRegion([]); }
    var resizeObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(function () {
        if (pendingFit && !destroyed) fit(pendingFit);
    }) : null;
    if (resizeObserver) resizeObserver.observe(element);
    element.addEventListener('keydown', onKeyDown);
    element.addEventListener('pointermove', onPointer);
    element.addEventListener('pointerdown', onPointer);
    element.addEventListener('pointerleave', leaveRegion);
    element.addEventListener('pointercancel', leaveRegion);
    return {
        render: function (value, selection) { scene = value; selected = selection; draw(); },
        select: function (id) { selected = id; draw(); },
        setLensCallbacks: function (region, suggestion) { onRegion = region; onSuggestion = suggestion; },
        presentFocus: function (value) { lens = value; if (!lens.enabled) ring.attr('display', 'none'); draw(); },
        fit: fit, focus: focus,
        unfocus: function () { if (focusViewport) { svg.call(zoom.transform, d3.zoomIdentity.translate(focusViewport.x, focusViewport.y).scale(focusViewport.k)); focusViewport = null; } },
        captureViewport: function () { return { x: transform.x, y: transform.y, k: transform.k }; },
        restoreViewport: function (value) { if (value && Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.k) && value.k > 0) svg.call(zoom.transform, d3.zoomIdentity.translate(value.x, value.y).scale(value.k)); },
        zoom: function (factor) { svg.call(zoom.scaleBy, factor); },
        destroy: function () {
            destroyed = true;
            if (resizeObserver) resizeObserver.disconnect();
            if (frame !== null) cancelAnimationFrame(frame);
            element.removeEventListener('keydown', onKeyDown);
            element.removeEventListener('pointermove', onPointer);
            element.removeEventListener('pointerdown', onPointer);
            element.removeEventListener('pointerleave', leaveRegion);
            element.removeEventListener('pointercancel', leaveRegion);
            svg.on('.zoom', null);
            nodes.remove(); edges.remove(); ghosts.remove(); ring.remove(); definitions.remove();
        }
    };
};

/* Controls for externally laid-out scenes. Both read-only adapters use this same
 * selection/viewport/lens interface; DOM ownership stays with their existing UI. */
window.TaxonomyArchitectureDiagram.connect = function (svg, options) {
    'use strict';
    var common = window.TaxonomyArchitectureDiagram, d3 = window.d3;
    var lens = { enabled: false, states: {}, suggestions: [] }, regionCallback = options.onRegion;
    var suggestionCallback = options.onSuggestion, disposed = false;
    var markerId = 'architecture-lens-arrow-' + (common.markerSequence = (common.markerSequence || 0) + 1);
    var definitions = common.element('defs');
    var marker = common.element('marker', { id: markerId, viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto' });
    marker.appendChild(common.element('path', { d: 'M0,0 L10,5 L0,10 Z', fill: '#7142a0' })); definitions.appendChild(marker);
    var ring = common.element('circle', { 'class': 'editor-lens-ring architecture-lens-ring', 'aria-hidden': 'true', display: 'none',
        fill: 'rgba(113,66,160,0.05)', stroke: '#7142a0', 'stroke-width': 2, 'pointer-events': 'none' });
    var overlay = common.element('g', { 'class': 'architecture-lens-suggestions' });
    function transform() { return d3.zoomTransform(svg); }
    function restore(value) {
        if (!value || !Number.isFinite(value.x) || !Number.isFinite(value.y) || !Number.isFinite(value.k) || value.k <= 0) return;
        options.applyTransform(d3.zoomIdentity.translate(value.x, value.y).scale(value.k));
    }
    function keydown(event) {
        if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
        var moves = { ArrowLeft: [60, 0], ArrowRight: [-60, 0], ArrowUp: [0, 60], ArrowDown: [0, -60] };
        if (moves[event.key]) {
            event.preventDefault(); var current = transform();
            if (event.target !== svg && svg.focus) svg.focus({ preventScroll: true });
            restore({ x: current.x + moves[event.key][0], y: current.y + moves[event.key][1], k: current.k });
        } else if (event.key === '+' || event.key === '-') { event.preventDefault(); options.zoom(event.key === '+' ? 1.4 : 1 / 1.4); }
        else if (event.key === 'Escape') { event.preventDefault(); options.unfocus(); }
    }
    function onPointer(event) {
        if (!lens.enabled || !regionCallback) return;
        var point = d3.pointer(event, svg), matrix = svg.getScreenCTM(), current = transform();
        var radius = 56 / (matrix ? Math.hypot(matrix.a, matrix.b) || 1 : 1);
        ring.setAttribute('cx', point[0]); ring.setAttribute('cy', point[1]); ring.setAttribute('r', radius); ring.removeAttribute('display');
        regionCallback(common.regionNodeIds(options.getNodes(), current.invert(point), radius / current.k));
    }
    function leave() { ring.setAttribute('display', 'none'); if (regionCallback) regionCallback([]); }
    function refresh() {
        if (disposed) return;
        var activeSuggestion = document.activeElement && document.activeElement.getAttribute('data-suggestion-id');
        var nodes = options.getNodes(), byId = new Map(nodes.map(function (node) { return [node.id, node]; }));
        svg.appendChild(definitions); svg.appendChild(overlay); svg.appendChild(ring); overlay.replaceChildren();
        overlay.setAttribute('transform', transform().toString());
        var states = lens.states || {};
        options.getNodeElements().forEach(function (element, id) {
            ['unassessed', 'assessing', 'supported', 'weak', 'gap', 'unexplored', 'pending', 'covered', 'partial'].forEach(function (state) { element.classList.remove('lens-' + state); });
            var baseLabel = element.getAttribute('data-lens-base-label');
            if (baseLabel === null) { baseLabel = element.getAttribute('aria-label') || id; element.setAttribute('data-lens-base-label', baseLabel); }
            element.setAttribute('aria-label', baseLabel + (lens.enabled && states[id] ? ' · ' + (lens.label ? lens.label(states[id]) : states[id]) : ''));
            var prior = element.getAttribute('data-lens-state');
            if (prior) element.classList.remove('lens-' + prior);
            element.removeAttribute('data-lens-state');
            if (lens.enabled && states[id]) { element.classList.add('lens-' + states[id]); element.setAttribute('data-lens-state', states[id]); }
        });
        if (!lens.enabled) { ring.setAttribute('display', 'none'); return; }
        (lens.suggestions || []).filter(function (edge) { return byId.has(edge.sourceId) && byId.has(edge.targetId); }).slice(0, 32).forEach(function (edge) {
            var path = common.element('path', { 'data-suggestion-id': edge.id, 'class': 'editor-ghost' + (edge.id === lens.reviewed ? ' selected' : ''), d: common.suggestionPath(byId.get(edge.sourceId), byId.get(edge.targetId)),
                fill: 'none', stroke: '#7142a0', 'stroke-width': 3, 'stroke-dasharray': '8 5', 'marker-end': 'url(#' + markerId + ')', tabindex: 0, role: 'button',
                'aria-label': edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId });
            common.activate(path, function () { if (suggestionCallback) suggestionCallback(edge.id); }); overlay.appendChild(path);
            if (activeSuggestion === edge.id && path.focus) path.focus({ preventScroll: true });
        });
    }
    svg.addEventListener('keydown', keydown);
    svg.addEventListener('pointermove', onPointer); svg.addEventListener('pointerdown', onPointer);
    svg.addEventListener('pointerleave', leave); svg.addEventListener('pointercancel', leave);
    return {
        render: refresh, select: options.select, focus: options.focus, unfocus: options.unfocus,
        fit: options.fit, zoom: options.zoom,
        captureViewport: function () { var value = transform(); return { x: value.x, y: value.y, k: value.k }; },
        restoreViewport: restore,
        presentFocus: function (value) { lens = value || { enabled: false }; refresh(); },
        setLensCallbacks: function (region, suggestion) { regionCallback = region; suggestionCallback = suggestion; },
        viewportChanged: function () { overlay.setAttribute('transform', transform().toString()); },
        destroy: function () {
            disposed = true; svg.removeEventListener('keydown', keydown);
            svg.removeEventListener('pointermove', onPointer); svg.removeEventListener('pointerdown', onPointer);
            svg.removeEventListener('pointerleave', leave); svg.removeEventListener('pointercancel', leave);
            ring.remove(); overlay.remove(); definitions.remove();
        }
    };
};
