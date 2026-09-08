/* Renderer adapter: accepts immutable DiagramScene values and emits selection intents only. */
window.ArchitectureEditorRenderer = function (element, onSelect, onRelation, onSummary) {
    'use strict';
    var d3 = window.d3;
    var svg = d3.select(element);
    var markerSequence = (window.ArchitectureEditorRenderer.markerSequence || 0) + 1;
    window.ArchitectureEditorRenderer.markerSequence = markerSequence;
    var markerId = 'editorArrow-' + markerSequence;
    svg.append('defs').append('marker').attr('id', markerId).attr('viewBox', '0 0 10 10')
        .attr('refX', 9).attr('refY', 5).attr('markerWidth', 7).attr('markerHeight', 7).attr('orient', 'auto')
        .append('path').attr('d', 'M0,0 L10,5 L0,10 Z').attr('fill', 'context-stroke');
    var edges = svg.append('g');
    var nodes = svg.append('g');
    var scene = { nodes: [], edges: [], width: 1000, height: 420 };
    var selected = null;
    var transform = d3.zoomIdentity;
    var frame = null;
    var MAX_NODES = 200;
    var MAX_EDGES = 400;
    var zoom = d3.zoom().scaleExtent([0.01, 4]).on('zoom', function (event) {
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
        var ids = new Set(visible.map(function (node) { return node.id; }));
        edges.attr('transform', transform.toString());
        nodes.attr('transform', transform.toString());
        edges.selectAll('path').data(scene.edges.filter(function (edge) {
            return ids.has(edge.sourceId) && ids.has(edge.targetId);
        }).slice(0, MAX_EDGES), function (edge) { return edge.id; }).join('path')
            .attr('class', 'editor-edge').attr('tabindex', 0).attr('role', 'button')
            .attr('marker-end', 'url(#' + markerId + ')')
            .attr('aria-label', function (edge) { return edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId; })
            .attr('d', function (edge) { return 'M' + edge.sourceX + ',' + edge.sourceY + ' L' + edge.targetX + ',' + edge.targetY; })
            .on('click', function (event, edge) { onRelation(edge); })
            .on('keydown', function (event, edge) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onRelation(edge); } });
        var groups = nodes.selectAll('g').data(visible, function (node) { return node.id; }).join(function (enter) {
            var group = enter.append('g'); group.append('rect'); group.append('text'); return group;
        }).attr('class', function (node) { return 'editor-node' + (node.id === selected ? ' selected' : ''); })
            .attr('transform', function (node) { return 'translate(' + node.x + ',' + node.y + ')'; })
            .attr('tabindex', 0).attr('role', 'button').attr('aria-pressed', function (node) { return node.id === selected; })
            .attr('aria-label', function (node) { return node.label + ' · ' + node.id; })
            .on('click', function (event, node) { onSelect(node.id); })
            .on('keydown', function (event, node) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onSelect(node.id); } });
        groups.select('rect').attr('width', function (node) { return node.width; }).attr('height', function (node) { return node.height; }).attr('rx', 6);
        groups.select('text').attr('x', 12).attr('y', 30).text(function (node) {
            var label = node.label || node.id; return label.length > 28 ? label.substring(0, 27) + '…' : label;
        });
        onSummary(visible.length, scene.nodes.length);
    }

    function fit() {
        var scale = Math.min(1, 980 / Math.max(1, scene.width), 400 / Math.max(1, scene.height));
        svg.call(zoom.transform, d3.zoomIdentity.translate(10, 10).scale(scale));
    }
    function focus() {
        var node = scene.nodes.find(function (candidate) { return candidate.id === selected; });
        if (node) svg.call(zoom.transform, d3.zoomIdentity.translate(500 - node.x - node.width / 2, 210 - node.y - node.height / 2));
    }
    element.addEventListener('keydown', function (event) {
        var moves = { ArrowLeft: [60, 0], ArrowRight: [-60, 0], ArrowUp: [0, 60], ArrowDown: [0, -60] };
        if (event.target !== element) return;
        if (moves[event.key]) { event.preventDefault(); svg.call(zoom.translateBy, moves[event.key][0] / transform.k, moves[event.key][1] / transform.k); }
        if (event.key === '+' || event.key === '-') { event.preventDefault(); svg.call(zoom.scaleBy, event.key === '+' ? 1.4 : 1 / 1.4); }
    });
    return {
        render: function (value, selection) { scene = value; selected = selection; draw(); },
        select: function (id) { selected = id; draw(); },
        fit: fit, focus: focus,
        zoom: function (factor) { svg.call(zoom.scaleBy, factor); },
        destroy: function () { if (frame !== null) cancelAnimationFrame(frame); svg.on('.zoom', null); }
    };
};
