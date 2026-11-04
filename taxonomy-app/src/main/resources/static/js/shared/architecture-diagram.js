/* One SVG diagram engine. Source adapters supply immutable data and pure layout/presentation. */
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
        result.textContent = value === undefined || value === null ? '' : value; parent.appendChild(result); return result;
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
        if (edge.compact) {
            var line = element('path', { 'class': edge.className || prefix + '-edge', 'data-edge-id': edge.id,
                'data-source': edge.source, 'data-target': edge.target, tabindex: 0, role: 'button',
                d: edge.path, stroke: edge.color, 'marker-end': 'url(#' + edge.markerId + ')', 'aria-label': edge.ariaLabel });
            activate(line, function () { if (callbacks.select) callbacks.select(edge.id); });
            parent.appendChild(line); return line;
        }
        var group = element('g', { 'class': edge.className || prefix + '-edge', 'data-edge-id': edge.id, 'data-source': edge.source, 'data-target': edge.target });
        var path = element('path', { 'class': edge.pathClass || prefix + '-edge-path', d: edge.path, stroke: edge.color, 'marker-end': 'url(#' + edge.markerId + ')' });
        var hit = element('path', { 'class': edge.hitClass || prefix + '-edge-hit', 'data-edge-id': edge.id, d: edge.path, tabindex: 0, role: 'button', 'aria-label': edge.ariaLabel });
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
    function isContext(node) { return node.context === undefined ? !node.anchor : Boolean(node.context); }
    function visibleModel(scene, state, options) {
        options = options || {};
        var focusId = state.focusedNodeId || state.selectedNodeId;
        var included = new Set([focusId]);
        if (state.mode === 'focus' && focusId) scene.edges.forEach(function (edge) {
            if (source(edge) === focusId || target(edge) === focusId) { included.add(source(edge)); included.add(target(edge)); }
        });
        var nodes = scene.nodes.filter(function (node) {
            return (state.showContext !== false || !isContext(node))
                && (state.mode !== 'focus' || !focusId || included.has(node.id));
        });
        var omitted = 0;
        if (options.modelNodes > 0 && nodes.length > options.modelNodes) {
            omitted = nodes.length - options.modelNodes;
            nodes = nodes.slice().sort(function (left, right) {
                if (left.id === state.selectedNodeId) return -1;
                if (right.id === state.selectedNodeId) return 1;
                return options.rankNodes ? options.rankNodes(left, right) : 0;
            }).slice(0, options.modelNodes);
        }
        var ids = new Set(nodes.map(function (node) { return node.id; }));
        return { nodes: nodes, edges: scene.edges.filter(function (edge) { return ids.has(source(edge)) && ids.has(target(edge)); }), nodeIds: ids, omitted: omitted };
    }
    function calculateFitTransform(bounds, viewport, options) {
        options = options || {}; bounds = bounds || {}; viewport = viewport || {};
        if (options.requireVisible && (!viewport.width || !viewport.height)) return null;
        function number(value, fallback) { var result = Number(value); return Number.isFinite(result) ? result : fallback; }
        var padding = Math.max(0, number(options.padding, 34));
        var minimum = Math.max(Number.EPSILON, number(options.minimumScale, 0.01));
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
    function wrapLabel(value, maximumCharacters, maximumLines) {
        var words = String(value || '').trim().split(/\s+/).filter(Boolean), lines = [], current = '';
        words.forEach(function (word) {
            var candidate = current ? current + ' ' + word : word;
            if (candidate.length <= maximumCharacters || !current) current = candidate;
            else { lines.push(current); current = word; }
        });
        if (current) lines.push(current);
        if (lines.length > maximumLines) {
            lines.splice(maximumLines);
            lines[maximumLines - 1] = lines[maximumLines - 1].substring(0, Math.max(1, maximumCharacters - 1)).replace(/[\s.,;:]+$/, '') + '…';
        }
        return lines.length ? lines : [''];
    }
    function parseColor(color) {
        var value = String(color || '').trim(), match = value.match(/^#([0-9a-f]{3})$/i);
        if (match) return match[1].split('').map(function (part) { return parseInt(part + part, 16); });
        match = value.match(/^#([0-9a-f]{6})$/i);
        if (match) return [0, 2, 4].map(function (offset) { return parseInt(match[1].substring(offset, offset + 2), 16); });
        match = value.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i);
        return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : [233, 236, 239];
    }
    function rgba(color, alpha) { return 'rgba(' + parseColor(color).join(',') + ',' + alpha + ')'; }
    function readableTextColor(color) {
        var c = parseColor(color).map(function (value) { value /= 255; return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4); });
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] > 0.47 ? '#17212b' : '#ffffff';
    }
    function shouldMuteNode(value) {
        return value.nodeId !== value.selectedNodeId && Boolean((value.selectedNodeId && !value.connected) || (value.hasSearch && !value.searchMatch));
    }
    function shouldMuteEdge(value) {
        return value.edgeId !== value.selectedEdgeId && Boolean((value.selectedNodeId && !value.connected) || (value.hasSearch && !value.searchRelevant));
    }
    function shouldClearSelectionFromClick(event) {
        return Boolean(event && !event.defaultPrevented && (!event.target || typeof event.target.closest !== 'function'
            || !event.target.closest('[data-node-id], [data-edge-id], [data-suggestion-id]')));
    }
    window.TaxonomyArchitectureDiagram = { element: element, text: text, activate: activate, renderNode: renderNode, renderEdge: renderEdge,
        visibleModel: visibleModel, calculateFitTransform: calculateFitTransform, relationshipLanes: relationshipLanes,
        edgePath: edgePath, regionNodeIds: regionNodeIds, suggestionPath: suggestionPath,
        sourceId: source, targetId: target, isContext: isContext,
        wrapLabel: wrapLabel, parseColor: parseColor, rgba: rgba, readableTextColor: readableTextColor,
        shouldMuteNode: shouldMuteNode, shouldMuteEdge: shouldMuteEdge, shouldClearSelectionFromClick: shouldClearSelectionFromClick };
}());

/* A controller owns its SVG, interaction state and lifecycle. Layout callbacks return data only. */
window.TaxonomyArchitectureDiagram.create = function (svg, options) {
    'use strict';
    options = options || {};
    if (svg.__taxonomyDiagramEngine) svg.__taxonomyDiagramEngine.destroy();
    var common = window.TaxonomyArchitectureDiagram, d3 = window.d3;
    var prefix = options.prefix || 'editor', compact = prefix === 'editor';
    var presentation = Object.assign({ viewportClass: prefix + '-viewport', selectedClass: compact ? 'selected' : 'is-selected',
        edgeLabelLimit: 12, highlightNeighbors: !compact }, options.presentation);
    var viewport = Object.assign({ minimumWidth: 320, minimumHeight: 300, fallbackWidth: 900, fallbackHeight: 540,
        minimumScale: 0.01, maximumScale: 4, fitPadding: compact ? 10 : 34, fitMaximumScale: compact ? 1 : 1.25,
        fixedWidth: compact ? 1000 : null, fixedHeight: compact ? 420 : null, fitAlignment: compact ? 'start' : 'center',
        extendMinimumForFit: compact
    }, options.viewport);
    var behavior = Object.assign({ focus: compact ? 'viewport' : 'neighbors', clearOnBackground: !compact, lensLocksPan: compact }, options.behavior);
    var limits = Object.assign({ cull: compact, visibleNodes: compact ? 200 : Infinity, edges: compact ? 400 : Infinity, suggestions: 32 }, options.limits);
    var container = options.container || svg, selection = d3.select(svg);
    var sequence = common.markerSequence = (common.markerSequence || 0) + 1;
    var markerPrefix = 'architecture-diagram-' + sequence + '-';
    var definitions = common.element('defs');
    var canvas = common.element('g', { 'class': presentation.viewportClass });
    var relationships = common.element('g'), overlay = common.element('g', { 'class': 'architecture-lens-suggestions' });
    var cards = common.element('g');
    var ring = common.element('circle', { 'class': 'editor-lens-ring architecture-lens-ring', 'aria-hidden': 'true', display: 'none',
        fill: 'rgba(113,66,160,0.05)', stroke: '#7142a0', 'stroke-width': 2, 'pointer-events': 'none' });
    svg.append(definitions, canvas, ring);
    var state = { selectedNodeId: null, selectedEdgeId: null, focusedNodeId: null, mode: 'overview', showContext: true, searchQuery: '' };
    var scene = { nodes: [], edges: [], width: 1000, height: 420 };
    var model = { nodes: [], edges: [], layers: [], bounds: null, nodeIds: new Set(), omitted: 0 };
    var nodeElements = new Map(), edgeElements = new Map(), drawnNodes = [];
    var transform = d3.zoomTransform(svg), focusViewport = null, lens = { enabled: false, states: {}, suggestions: [] };
    var onRegion = options.onRegion, onSuggestion = options.onSuggestion;
    var active = true, destroyed = false, hasScene = false, autoFit = false, pendingFit = null, lastFitSettings = {};
    var drawFrame = null, resizeFrame = null, fitFrame = null, fitGeneration = 0;
    var reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var source = common.sourceId, target = common.targetId;
    var stateKeys = ['selectedNodeId', 'selectedEdgeId', 'focusedNodeId', 'mode', 'showContext', 'searchQuery'];
    function normalizeState() {
        state.selectedNodeId = state.selectedNodeId || null; state.selectedEdgeId = state.selectedEdgeId || null;
        state.showContext = state.showContext !== false; state.searchQuery = String(state.searchQuery || '');
        if (!state.selectedNodeId || state.mode !== 'focus') { state.mode = 'overview'; state.focusedNodeId = null; }
        else if (!state.focusedNodeId) state.focusedNodeId = state.selectedNodeId;
    }
    function snapshot() { return Object.assign({}, state); }
    function captureViewport() { return { x: transform.x, y: transform.y, k: transform.k }; }
    function validViewport(value) { return value && Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.k) && value.k > 0; }
    function copyModel() {
        return Object.assign({}, model, { nodes: model.nodes.map(function (node) { return Object.assign({}, node); }),
            edges: model.edges.map(function (edge) { return Object.assign({}, edge); }), layers: model.layers.slice(),
            bounds: Object.assign({}, model.bounds), nodeIds: new Set(model.nodeIds) });
    }
    function matchingNodeIds() {
        var query = state.searchQuery.trim().toLowerCase();
        if (!query) return [];
        return scene.nodes.filter(function (node) {
            return String(node.searchText || [node.id, node.label, node.title, node.type, node.sheetLabel].filter(Boolean).join(' ')).toLowerCase().includes(query);
        }).map(function (node) { return node.id; });
    }
    function notify() {
        if (options.onChange && !destroyed) options.onChange({ state: snapshot(), model: copyModel(), matches: matchingNodeIds() });
    }
    function measuredSize() {
        var box = container.getBoundingClientRect();
        return { width: container.clientWidth || box.width || 0, height: container.clientHeight || box.height || 0 };
    }
    function viewportSize() {
        var size = measuredSize();
        return { width: viewport.fixedWidth || Math.max(viewport.minimumWidth, size.width || viewport.fallbackWidth),
            height: viewport.fixedHeight || Math.max(viewport.minimumHeight, size.height || viewport.fallbackHeight) };
    }
    function updateViewBox() {
        var size = viewportSize(), value = '0 0 ' + size.width + ' ' + size.height;
        if (svg.getAttribute('viewBox') !== value) svg.setAttribute('viewBox', value);
    }
    function reportFit(value, scale) { if (options.onFitState) options.onFitState(value, scale); }
    function cancelScheduledFit() {
        fitGeneration++;
        if (fitFrame !== null) cancelAnimationFrame(fitFrame);
        fitFrame = null;
    }
    function markUserControlled() {
        autoFit = false; pendingFit = null; cancelScheduledFit(); selection.interrupt();
        reportFit('user-controlled', transform.k);
    }
    var zoom = d3.zoom().scaleExtent([viewport.minimumScale, viewport.maximumScale])
        .filter(function (event) {
            return active && !destroyed && (!event.ctrlKey || event.type === 'wheel') && !event.button
                && (!behavior.lensLocksPan || !lens.enabled || event.type === 'wheel');
        }).on('zoom', function (event) {
            if (destroyed) return;
            if (event.sourceEvent) markUserControlled();
            transform = event.transform; canvas.setAttribute('transform', transform.toString());
            if (limits.cull && active && drawFrame === null) drawFrame = requestAnimationFrame(function () { drawFrame = null; draw(); });
        });
    selection.call(zoom).on('dblclick.zoom', null);
    canvas.setAttribute('transform', transform.toString());

    function applyTransform(value, animate, onEnd) {
        selection.interrupt();
        if (animate && !reducedMotion) selection.transition().duration(180).call(zoom.transform, value).on('end', function () { if (onEnd) onEnd(); });
        else { selection.call(zoom.transform, value); if (onEnd) onEnd(); }
    }
    function restoreViewport(value) {
        if (destroyed || !validViewport(value)) return;
        markUserControlled();
        if (viewport.extendMinimumForFit) zoom.scaleExtent([Math.min(zoom.scaleExtent()[0], value.k), viewport.maximumScale]);
        applyTransform(d3.zoomIdentity.translate(value.x, value.y).scale(value.k));
    }
    function defaultBounds(nodes) {
        if (compact) return { x: 0, y: 0, width: scene.width || 1000, height: scene.height || 420 };
        if (!nodes.length) return { x: 0, y: 0, width: scene.width || 760, height: scene.height || 420 };
        var x = Math.min.apply(null, nodes.map(function (node) { return node.x; }));
        var y = Math.min.apply(null, nodes.map(function (node) { return node.y; }));
        return { x: x, y: y, width: Math.max.apply(null, nodes.map(function (node) { return node.x + node.width; })) - x,
            height: Math.max.apply(null, nodes.map(function (node) { return node.y + node.height; })) - y };
    }
    function rebuild() {
        var selected = scene.nodes.find(function (node) { return node.id === state.selectedNodeId; });
        if (state.selectedNodeId && (!selected || (!state.showContext && common.isContext(selected)))) {
            state.selectedNodeId = null; state.mode = 'overview';
        }
        var focused = scene.nodes.find(function (node) { return node.id === state.focusedNodeId; });
        if (state.mode === 'focus' && (!focused || (!state.showContext && common.isContext(focused)))) state.mode = 'overview';
        normalizeState();
        var filter = behavior.focus === 'neighbors' ? state : Object.assign({}, state, { mode: 'overview' });
        var visible = common.visibleModel(scene, filter, { modelNodes: limits.modelNodes, rankNodes: options.rankNodes });
        var laidOut = options.layout ? options.layout(visible, snapshot()) : visible;
        model = Object.assign({}, laidOut, { nodes: laidOut.nodes || visible.nodes, edges: laidOut.edges || visible.edges,
            layers: laidOut.layers || [], omitted: visible.omitted || 0 });
        model.nodeIds = new Set(model.nodes.map(function (node) { return node.id; }));
        model.bounds = model.bounds || defaultBounds(model.nodes);
        if (state.selectedNodeId && !model.nodeIds.has(state.selectedNodeId)) {
            state.selectedNodeId = null; state.mode = 'overview'; focusViewport = null;
            rebuild(); return;
        }
        if (state.selectedEdgeId && !model.edges.some(function (edge) { return edge.id === state.selectedEdgeId; })) state.selectedEdgeId = null;
        updateViewBox(); draw(); notify();
    }
    function addMarker(id, color) {
        var marker = common.element('marker', { id: id, viewBox: '0 -5 10 10', refX: 9, refY: 0, markerWidth: 6, markerHeight: 6, orient: 'auto', markerUnits: 'strokeWidth' });
        marker.appendChild(common.element('path', { d: 'M0,-5L10,0L0,5Z', fill: color || 'context-stroke' }));
        definitions.appendChild(marker);
    }
    function drawLayers() {
        model.layers.forEach(function (layer) {
            var group = common.element('g', { 'class': prefix + '-layer' });
            group.appendChild(common.element('rect', { 'class': prefix + '-layer-column', x: layer.x - 10, y: layer.y,
                width: layer.width + 20, height: layer.height, rx: 12, fill: layer.backgroundFill || common.rgba(layer.color, 0.08) }));
            group.appendChild(common.element('rect', { 'class': prefix + '-layer-header', x: layer.x, y: layer.y + 7,
                width: layer.width, height: layer.headerHeight || 34, rx: 8, fill: layer.color, stroke: layer.headerStroke }));
            var color = layer.textColor || common.readableTextColor(layer.color), baseline = layer.y + (layer.labelOffsetY || 30);
            common.text(group, prefix + '-layer-title', layer.x + 12, baseline, layer.label, color);
            common.text(group, prefix + '-layer-count', layer.x + layer.width - 12, baseline, String(layer.count), color).setAttribute('text-anchor', 'end');
            canvas.appendChild(group);
        });
    }
    function requestSelection(id) {
        if (!active || destroyed) return false;
        if (options.onSelect && options.onSelect(id) === false) return false;
        select(id);
        return true;
    }
    function selectEdge(id) {
        if (!active || destroyed) return;
        var edge = scene.edges.find(function (candidate) { return candidate.id === id; });
        if (options.onRelation) { if (edge) options.onRelation(edge); }
        else select(null, id);
    }
    function interactionStyles() {
        var connectedNodes = new Set(), connectedEdges = new Set(), matches = new Set(matchingNodeIds());
        var selected = presentation.highlightNeighbors ? state.selectedNodeId : null, hasSearch = Boolean(state.searchQuery.trim());
        if (selected) {
            connectedNodes.add(selected);
            model.edges.forEach(function (edge) {
                if (source(edge) === selected || target(edge) === selected) {
                    connectedNodes.add(source(edge)); connectedNodes.add(target(edge)); connectedEdges.add(edge.id);
                }
            });
        }
        nodeElements.forEach(function (node, id) {
            node.classList.toggle(presentation.selectedClass, id === state.selectedNodeId);
            node.setAttribute('aria-pressed', String(id === state.selectedNodeId));
            node.classList.toggle('is-search-match', hasSearch && matches.has(id));
            node.classList.toggle('is-muted', common.shouldMuteNode({ nodeId: id, selectedNodeId: state.selectedNodeId,
                connected: !presentation.highlightNeighbors || connectedNodes.has(id), hasSearch: hasSearch, searchMatch: matches.has(id) }));
        });
        edgeElements.forEach(function (node, id) {
            var connected = connectedEdges.has(id), relevant = !hasSearch || matches.has(node.getAttribute('data-source')) || matches.has(node.getAttribute('data-target'));
            node.classList.toggle(presentation.selectedClass, id === state.selectedEdgeId);
            node.classList.toggle('is-connected', connected);
            node.classList.toggle('is-muted', common.shouldMuteEdge({ edgeId: id, selectedEdgeId: state.selectedEdgeId,
                selectedNodeId: selected, connected: connected, hasSearch: hasSearch, searchRelevant: relevant }));
            var hit = node.getAttribute('role') === 'button' ? node : node.querySelector('[role="button"]');
            if (hit) hit.setAttribute('aria-pressed', String(id === state.selectedEdgeId));
            if (node._label) node._label.style.display = model.edges.length <= presentation.edgeLabelLimit || connected
                || id === state.selectedEdgeId || (hasSearch && relevant) ? '' : 'none';
        });
    }
    function focusedTarget() {
        var focused = document.activeElement, result = {};
        if (focused && svg.contains(focused)) ['node', 'edge', 'suggestion'].some(function (kind) {
            var id = focused.getAttribute('data-' + kind + '-id');
            if (id !== null) { result.kind = kind; result.id = id; return true; } return false;
        });
        return result;
    }
    function drawRelationships(focused) {
        var ids = new Set(drawnNodes.map(function (node) { return node.id; }));
        var byId = new Map(drawnNodes.map(function (node) { return [node.id, node]; }));
        var proposals = lens.enabled ? (lens.suggestions || []).filter(function (edge) { return ids.has(edge.sourceId) && ids.has(edge.targetId); }).slice(0, Math.min(limits.suggestions, limits.edges)) : [];
        var availableEdges = model.edges.filter(function (edge) { return ids.has(source(edge)) && ids.has(target(edge)); });
        var edges = availableEdges.slice(0, Math.max(0, limits.edges - proposals.length));
        var selectedEdge = availableEdges.find(function (edge) { return edge.id === state.selectedEdgeId; });
        if (selectedEdge && !edges.includes(selectedEdge) && edges.length) edges[edges.length - 1] = selectedEdge;
        definitions.replaceChildren(); relationships.replaceChildren(); overlay.replaceChildren(); edgeElements.clear();
        var markers = new Map();
        edges.forEach(function (edge) {
            var color = edge.color || 'context-stroke';
            if (!markers.has(color)) { var id = markerPrefix + 'edge-' + markers.size; markers.set(color, id); addMarker(id, color); }
            var a = byId.get(source(edge)), b = byId.get(target(edge));
            var path = edge.path || (edge.sourceX !== undefined ? 'M' + edge.sourceX + ',' + edge.sourceY + ' L' + edge.targetX + ',' + edge.targetY
                : compact ? common.suggestionPath(a, b) : common.edgePath(a, b, 0));
            var spec = Object.assign({ prefix: prefix, compact: compact, ariaLabel: source(edge) + ' ' + (edge.relationType || edge.type || '') + ' ' + target(edge) }, edge,
                { source: source(edge), target: target(edge), path: path, markerId: markers.get(color) });
            var group = common.renderEdge(relationships, spec, { select: selectEdge }); edgeElements.set(edge.id, group);
            if (focused.kind === 'edge' && focused.id === edge.id) (group.getAttribute('role') === 'button' ? group : group.querySelector('[role="button"]')).focus({ preventScroll: true });
        });
        if (proposals.length) addMarker(markerPrefix + 'suggestion', '#7142a0');
        proposals.forEach(function (edge) {
            var path = common.element('path', { 'data-suggestion-id': edge.id, 'class': 'editor-ghost' + (edge.id === lens.reviewed ? ' selected' : ''),
                d: common.suggestionPath(byId.get(edge.sourceId), byId.get(edge.targetId)), fill: 'none', stroke: '#7142a0', 'stroke-width': 3,
                'stroke-dasharray': '8 5', 'marker-end': 'url(#' + markerPrefix + 'suggestion)', tabindex: 0, role: 'button',
                'aria-label': edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId });
            common.activate(path, function () { if (active && onSuggestion) onSuggestion(edge.id); }); overlay.appendChild(path);
            if (focused.kind === 'suggestion' && focused.id === edge.id) path.focus({ preventScroll: true });
        });
    }
    function lensStyles() {
        drawnNodes.forEach(function (node) {
            var group = nodeElements.get(node.id), previous = group.getAttribute('data-lens-state');
            var assessment = lens.enabled && (lens.states || {})[node.id];
            if (previous) group.classList.remove('lens-' + previous);
            if (assessment) { group.classList.add('lens-' + assessment); group.setAttribute('data-lens-state', assessment); }
            else group.removeAttribute('data-lens-state');
            var label = node.ariaLabel || (node.label || node.title || node.id) + ' · ' + node.id;
            group.setAttribute('aria-label', label + (assessment ? ' · ' + (lens.label ? lens.label(assessment) : assessment) : ''));
        });
        if (!lens.enabled) ring.setAttribute('display', 'none');
    }
    function draw() {
        if (destroyed || !hasScene) return;
        var focused = focusedTarget(), size = viewportSize(), left = -transform.x / transform.k, top = -transform.y / transform.k;
        var candidates = limits.cull ? model.nodes.filter(function (node) {
            return node.x + node.width >= left && node.x <= left + size.width / transform.k
                && node.y + node.height >= top && node.y <= top + size.height / transform.k;
        }) : model.nodes;
        drawnNodes = candidates.slice(0, limits.visibleNodes);
        var selectedNode = candidates.find(function (node) { return node.id === state.selectedNodeId; });
        if (selectedNode && !drawnNodes.includes(selectedNode) && drawnNodes.length) drawnNodes[drawnNodes.length - 1] = selectedNode;
        canvas.replaceChildren(); cards.replaceChildren(); nodeElements.clear();
        drawLayers(); canvas.append(relationships, overlay, cards); drawRelationships(focused);
        drawnNodes.forEach(function (node) {
            var label = node.label || node.title || node.id;
            var spec = Object.assign({ prefix: prefix, compact: compact, titleLines: common.wrapLabel(label, 32, 2), score: '', meta: '' }, node);
            spec.label = compact && label.length > 28 ? label.substring(0, 27) + '…' : label;
            spec.selected = node.id === state.selectedNodeId;
            var group = common.renderNode(cards, spec, { select: requestSelection, focus: focus }); nodeElements.set(node.id, group);
            if (focused.kind === 'node' && focused.id === node.id) group.focus({ preventScroll: true });
        });
        lensStyles(); interactionStyles();
        if (options.onSummary) options.onSummary(drawnNodes.length, scene.nodes.length);
    }
    function readableFrame(node) {
        var matrix = svg.getScreenCTM(), size = viewportSize();
        var screenScale = matrix ? Math.min(Math.hypot(matrix.a, matrix.b), Math.hypot(matrix.c, matrix.d)) || 1 : 1;
        var padding = Math.min(10 / screenScale, size.height / 4);
        return { padding: padding, scale: Math.max(zoom.scaleExtent()[0], Math.min(viewport.maximumScale, 1 / screenScale,
            (size.width - 2 * padding) / node.width, (size.height - 2 * padding) / node.height)) };
    }
    function fitTransform(settings) {
        var size = viewportSize();
        if (settings.initial && compact && model.nodes.length) {
            var first = model.nodes[0], frame = readableFrame(first);
            var x = Math.min.apply(null, model.nodes.map(function (node) { return node.x; }));
            var y = Math.min.apply(null, model.nodes.map(function (node) { return node.y; }));
            var right = Math.max.apply(null, model.nodes.map(function (node) { return node.x + node.width; }));
            var bottom = Math.max.apply(null, model.nodes.map(function (node) { return node.y + node.height; }));
            var together = (right - x) * frame.scale <= size.width - 2 * frame.padding && (bottom - y) * frame.scale <= size.height - 2 * frame.padding;
            return { scale: frame.scale, x: together ? size.width / 2 - (x + right) / 2 * frame.scale : frame.padding - first.x * frame.scale,
                y: together ? size.height / 2 - (y + bottom) / 2 * frame.scale : frame.padding - first.y * frame.scale };
        }
        var fit = common.calculateFitTransform(model.bounds, size, { padding: viewport.fitPadding,
            minimumScale: viewport.extendMinimumForFit ? Number.EPSILON : viewport.minimumScale, maximumScale: viewport.fitMaximumScale });
        if (viewport.fitAlignment === 'start') { fit.x = viewport.fitPadding - model.bounds.x * fit.scale; fit.y = viewport.fitPadding - model.bounds.y * fit.scale; }
        return fit;
    }
    function fit(settings) {
        if (destroyed || !hasScene || !active) return;
        lastFitSettings = Object.assign({}, settings); pendingFit = lastFitSettings; autoFit = true; cancelScheduledFit(); reportFit('pending');
        if (!active) return;
        var generation = fitGeneration, previous = null, attempts = 0;
        function measureAndFit() {
            fitFrame = null;
            if (destroyed || !active || !autoFit || generation !== fitGeneration) return;
            var measured = measuredSize();
            if (measured.width > 0 && measured.height > 0) {
                if (previous && Math.abs(previous.width - measured.width) <= 1 && Math.abs(previous.height - measured.height) <= 1) {
                    updateViewBox(); var result = fitTransform(pendingFit || {}); pendingFit = null;
                    if (viewport.extendMinimumForFit) zoom.scaleExtent([Math.min(viewport.minimumScale, result.scale), viewport.maximumScale]);
                    applyTransform(d3.zoomIdentity.translate(result.x, result.y).scale(result.scale), false, function () {
                        if (autoFit && generation === fitGeneration) reportFit('complete', result.scale);
                    });
                    return;
                }
                previous = measured;
            }
            if (++attempts < 12) fitFrame = requestAnimationFrame(measureAndFit);
        }
        fitFrame = requestAnimationFrame(measureAndFit);
    }
    function centerOnNode(id) {
        var node = model.nodes.find(function (value) { return value.id === id; });
        if (!node) return;
        var size = viewportSize(), scale = compact ? readableFrame(node).scale : Math.max(0.9, Math.min(1.55, Math.max(transform.k, 1.05)));
        markUserControlled();
        applyTransform(d3.zoomIdentity.translate(size.width / 2 - (node.x + node.width / 2) * scale,
            size.height / 2 - (node.y + node.height / 2) * scale).scale(scale), !compact);
    }
    function setState(patch, settings) {
        if (destroyed) return;
        var previous = snapshot(), previousViewport = focusViewport;
        stateKeys.forEach(function (key) { if (Object.prototype.hasOwnProperty.call(patch, key)) state[key] = patch[key]; });
        normalizeState();
        if (hasScene && stateKeys.some(function (key) { return previous[key] !== state[key]; })) {
            var layoutChanged = previous.mode !== state.mode || previous.showContext !== state.showContext
                || previous.focusedNodeId !== state.focusedNodeId || (state.selectedNodeId && !model.nodeIds.has(state.selectedNodeId));
            if (layoutChanged) rebuild();
            else {
                // Keep the actual mouse/keyboard target attached until its gesture completes.
                if (limits.cull && state.selectedNodeId && !nodeElements.has(state.selectedNodeId)) draw();
                else interactionStyles();
                notify();
            }
        }
        if (previous.mode === 'focus' && state.mode !== 'focus') {
            focusViewport = null;
            if (previousViewport) restoreViewport(previousViewport);
        }
        if (settings && settings.fit) fit();
    }
    function select(id, edgeId, settings) {
        if (destroyed) return;
        var patch = { selectedNodeId: id || null, selectedEdgeId: edgeId || null };
        if (settings && settings.reveal && id && !model.nodeIds.has(id)) { patch.showContext = true; patch.mode = 'overview'; }
        setState(patch);
        if (settings && settings.center && id) centerOnNode(id);
    }
    function focus(id) {
        if (!active || destroyed) return;
        if (id && !requestSelection(id)) return;
        if (!state.selectedNodeId) return;
        if (!focusViewport) focusViewport = captureViewport();
        state.mode = 'focus'; state.focusedNodeId = state.selectedNodeId; rebuild();
        if (behavior.focus === 'neighbors') fit(); else centerOnNode(state.selectedNodeId);
    }
    function unfocus() {
        if (destroyed) return;
        var prior = focusViewport;
        state.mode = 'overview'; state.focusedNodeId = null; focusViewport = null; rebuild();
        if (prior) restoreViewport(prior); else fit();
    }
    function onKeyDown(event) {
        if (!active || destroyed || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
        var moves = { ArrowLeft: [60, 0], ArrowRight: [-60, 0], ArrowUp: [0, 60], ArrowDown: [0, -60] };
        if (moves[event.key] || event.key === '+' || event.key === '-') {
            event.preventDefault(); if (event.target !== svg) svg.focus({ preventScroll: true }); markUserControlled();
            if (moves[event.key]) selection.call(zoom.translateBy, moves[event.key][0] / transform.k, moves[event.key][1] / transform.k);
            else selection.call(zoom.scaleBy, event.key === '+' ? 1.4 : 1 / 1.4);
        } else if (event.key === 'Escape') { event.preventDefault(); unfocus(); }
    }
    function onPointer(event) {
        if (!active || destroyed || !lens.enabled || !onRegion) return;
        var point = d3.pointer(event, svg), matrix = svg.getScreenCTM();
        var radius = 56 / (matrix ? Math.hypot(matrix.a, matrix.b) || 1 : 1);
        ring.setAttribute('cx', point[0]); ring.setAttribute('cy', point[1]); ring.setAttribute('r', radius); ring.removeAttribute('display');
        onRegion(common.regionNodeIds(drawnNodes, transform.invert(point), radius / transform.k));
    }
    function leaveRegion() { ring.setAttribute('display', 'none'); if (onRegion) onRegion([]); }
    function onBackground(event) { if (behavior.clearOnBackground && common.shouldClearSelectionFromClick(event)) requestSelection(null); }
    var observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(function () {
        if (!active || destroyed || resizeFrame !== null) return;
        // Preserve a pending fit's two-frame readiness, but retain the resize if user control cancels it.
        var fitting = autoFit && fitFrame !== null;
        // Never change the observed SVG or surrounding layout during resize delivery.
        resizeFrame = requestAnimationFrame(function () {
            resizeFrame = null;
            if (!active || destroyed) return;
            if (autoFit) { if (fitFrame === null && (!fitting || pendingFit)) fit(lastFitSettings); }
            else { updateViewBox(); if (limits.cull) draw(); }
        });
    }) : null;
    if (observer) observer.observe(container);
    var listeners = { keydown: onKeyDown, pointermove: onPointer, pointerdown: onPointer, pointerleave: leaveRegion, pointercancel: leaveRegion, click: onBackground };
    Object.keys(listeners).forEach(function (name) { svg.addEventListener(name, listeners[name]); });
    var api = {
        render: function (value, initial) {
            if (destroyed) return;
            scene = Object.assign({}, value, { nodes: (value.nodes || []).map(function (node) { return Object.assign({}, node); }),
                edges: (value.edges || []).map(function (edge) { return Object.assign({}, edge); }) });
            hasScene = true;
            if (initial !== undefined) {
                if (initial !== null && typeof initial === 'object') stateKeys.forEach(function (key) {
                    if (Object.prototype.hasOwnProperty.call(initial, key)) state[key] = initial[key];
                });
                else { state.selectedNodeId = initial || null; state.selectedEdgeId = null; }
            }
            normalizeState();
            if (state.selectedNodeId && !scene.nodes.some(function (node) { return node.id === state.selectedNodeId; })) { state.selectedNodeId = null; state.mode = 'overview'; focusViewport = null; }
            rebuild();
        },
        select: select, setState: setState,
        setSearch: function (value) { if (!destroyed) { state.searchQuery = String(value || ''); interactionStyles(); notify(); } },
        setContext: function (value) { setState({ showContext: Boolean(value) }, { fit: true }); },
        getState: snapshot, getModel: copyModel, matchingNodeIds: matchingNodeIds,
        focus: focus, unfocus: unfocus, fit: fit,
        zoom: function (factor) { if (!destroyed && Number.isFinite(factor) && factor > 0) { markUserControlled(); selection.call(zoom.scaleBy, factor); } },
        captureViewport: captureViewport, restoreViewport: restoreViewport,
        captureSession: function () {
            return { state: snapshot(), viewport: captureViewport(), focusViewport: focusViewport ? Object.assign({}, focusViewport) : null };
        },
        restoreSession: function (value) {
            if (destroyed || !value || !value.state || !validViewport(value.viewport)) return;
            setState(value.state);
            focusViewport = state.mode === 'focus' && validViewport(value.focusViewport) ? Object.assign({}, value.focusViewport) : null;
            restoreViewport(value.viewport);
        },
        setLensCallbacks: function (region, suggestion) { onRegion = region; onSuggestion = suggestion; },
        presentFocus: function (value) {
            if (destroyed) return;
            lens = value || { enabled: false };
            // Focus-panel feedback can reenter during a click; keep cards and their children attached.
            if (hasScene) { drawRelationships(focusedTarget()); lensStyles(); interactionStyles(); }
        },
        setActive: function (value) {
            if (destroyed) return;
            active = Boolean(value); cancelScheduledFit(); selection.interrupt();
            if (drawFrame !== null) cancelAnimationFrame(drawFrame); drawFrame = null;
            if (resizeFrame !== null) cancelAnimationFrame(resizeFrame); resizeFrame = null;
            if (observer) { observer.disconnect(); if (active) observer.observe(container); }
            if (active) { updateViewBox(); draw(); if (autoFit) fit(lastFitSettings); }
            else leaveRegion();
        },
        destroy: function () {
            if (destroyed) return;
            destroyed = true; active = false; cancelScheduledFit(); selection.interrupt();
            if (drawFrame !== null) cancelAnimationFrame(drawFrame);
            if (resizeFrame !== null) cancelAnimationFrame(resizeFrame);
            if (observer) observer.disconnect();
            Object.keys(listeners).forEach(function (name) { svg.removeEventListener(name, listeners[name]); });
            selection.on('.zoom', null); definitions.remove(); canvas.remove(); ring.remove();
            nodeElements.clear(); edgeElements.clear(); drawnNodes = [];
            if (svg.__taxonomyDiagramEngine === api) delete svg.__taxonomyDiagramEngine;
        }
    };
    svg.__taxonomyDiagramEngine = api;
    return api;
};
