/* Renderer adapter: accepts immutable DiagramScene values and emits selection intents only. */
window.ArchitectureEditorRenderer = function (element, onSelect, onRelation, onSummary, onRegion, onSuggestion) {
    'use strict';
    var d3 = window.d3;
    var svg = d3.select(element);
    var markerSequence = (window.ArchitectureEditorRenderer.markerSequence || 0) + 1;
    window.ArchitectureEditorRenderer.markerSequence = markerSequence;
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
        edges.selectAll('path').data(scene.edges.filter(function (edge) {
            return ids.has(edge.sourceId) && ids.has(edge.targetId);
        }).slice(0, MAX_EDGES - proposals.length), function (edge) { return edge.id; }).join('path')
            .attr('class', 'editor-edge').attr('tabindex', 0).attr('role', 'button')
            .attr('marker-end', 'url(#' + markerId + ')')
            .attr('aria-label', function (edge) { return edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId; })
            .attr('d', function (edge) { return 'M' + edge.sourceX + ',' + edge.sourceY + ' L' + edge.targetX + ',' + edge.targetY; })
            .on('click', function (event, edge) { onRelation(edge); })
            .on('keydown', function (event, edge) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onRelation(edge); } });
        ghosts.selectAll('path').data(proposals, function (edge) { return edge.id; }).join('path')
            .attr('class', function (edge) { return 'editor-ghost' + (edge.id === lens.reviewed ? ' selected' : ''); })
            .attr('tabindex', 0).attr('role', 'button').attr('marker-end', 'url(#' + markerId + ')')
            .attr('aria-label', function (edge) { return edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId; })
            .attr('d', function (edge) {
                var source = visible.find(function (node) { return node.id === edge.sourceId; });
                var target = visible.find(function (node) { return node.id === edge.targetId; });
                var x = source.x + source.width / 2, y = source.y + source.height / 2;
                var dx = target.x + target.width / 2 - x, dy = target.y + target.height / 2 - y;
                var start = Math.min(source.width / (2 * Math.abs(dx || 0.001)), source.height / (2 * Math.abs(dy || 0.001)));
                var end = Math.min(target.width / (2 * Math.abs(dx || 0.001)), target.height / (2 * Math.abs(dy || 0.001)));
                return 'M' + (x + dx * start) + ',' + (y + dy * start) + ' L' + (x + dx * (1 - end)) + ',' + (y + dy * (1 - end));
            }).on('click', function (event, edge) { if (onSuggestion) onSuggestion(edge.id); })
            .on('keydown', function (event, edge) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); if (onSuggestion) onSuggestion(edge.id); } });
        var groups = nodes.selectAll('g').data(visible, function (node) { return node.id; }).join(function (enter) {
            var group = enter.append('g'); group.append('rect'); group.append('text'); return group;
        }).attr('class', function (node) { return 'editor-node' + (node.id === selected ? ' selected' : '')
            + (lens.enabled && lens.states[node.id] ? ' lens-' + lens.states[node.id] : ''); })
            .attr('transform', function (node) { return 'translate(' + node.x + ',' + node.y + ')'; })
            .attr('tabindex', 0).attr('role', 'button').attr('aria-pressed', function (node) { return node.id === selected; })
            .attr('aria-label', function (node) { return node.label + ' · ' + node.id
                + (lens.enabled && lens.states[node.id] ? ' · ' + lens.label(lens.states[node.id]) : ''); })
            .on('click', function (event, node) { onSelect(node.id); })
            .on('keydown', function (event, node) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onSelect(node.id); } });
        groups.select('rect').attr('width', function (node) { return node.width; }).attr('height', function (node) { return node.height; }).attr('rx', 6);
        groups.select('text').attr('x', 12).attr('y', 30).text(function (node) {
            var label = node.label || node.id; return label.length > 28 ? label.substring(0, 27) + '…' : label;
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
        if (options && options.initial === true && scene.nodes.length) { frameInitial(); return; }
        var scale = Math.min(1, 980 / Math.max(1, scene.width), 400 / Math.max(1, scene.height));
        svg.call(zoom.transform, d3.zoomIdentity.translate(10, 10).scale(scale));
    }
    function focus() {
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
        onRegion(drawnNodes.filter(function (node) {
            var dx = center[0] - Math.max(node.x, Math.min(center[0], node.x + node.width));
            var dy = center[1] - Math.max(node.y, Math.min(center[1], node.y + node.height));
            return dx * dx + dy * dy <= distance * distance;
        }).map(function (node) { return node.id; }));
    }
    function leaveRegion() { ring.attr('display', 'none'); if (onRegion) onRegion([]); }
    element.addEventListener('keydown', onKeyDown);
    element.addEventListener('pointermove', onPointer);
    element.addEventListener('pointerdown', onPointer);
    element.addEventListener('pointerleave', leaveRegion);
    element.addEventListener('pointercancel', leaveRegion);
    return {
        render: function (value, selection) { scene = value; selected = selection; draw(); },
        select: function (id) { selected = id; draw(); },
        presentFocus: function (value) { lens = value; if (!lens.enabled) ring.attr('display', 'none'); draw(); },
        fit: fit, focus: focus,
        zoom: function (factor) { svg.call(zoom.scaleBy, factor); },
        destroy: function () {
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
