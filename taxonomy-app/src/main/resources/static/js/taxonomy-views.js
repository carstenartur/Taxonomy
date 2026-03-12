/* taxonomy-views.js – D3.js visualization views for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    // Sizing constants
    var MAX_SUNBURST_SIZE = 600; // max px for sunburst diameter
    var TREE_INITIAL_DEPTH = 3;  // collapse nodes at depth >= this on initial render

    // Root taxonomy colour palette (C1–C8)
    var ROOT_COLORS = [
        '#4e79a7', // C1 blue
        '#59a14f', // C2 green
        '#f28e2b', // C3 orange
        '#e15759', // C4 red
        '#76b7b2', // C5 teal
        '#edc948', // C6 yellow
        '#b07aa1', // C7 purple
        '#ff9da7'  // C8 pink
    ];

    // ── Tooltip ────────────────────────────────────────────────────────────────
    function ensureTooltip() {
        var tip = document.getElementById('tax-d3-tooltip');
        if (!tip) {
            tip = document.createElement('div');
            tip.id = 'tax-d3-tooltip';
            tip.className = 'tax-tooltip';
            document.body.appendChild(tip);
        }
        return tip;
    }

    function showTooltip(event, nodeData, scores) {
        var tip = ensureTooltip();
        var pct = scores ? scores[nodeData.code] : undefined;
        var html = '<strong>' + esc(nodeData.code) + '</strong>';
        if (nodeData.name) { html += ' &ndash; ' + esc(nodeData.name); }
        var showDescChk = document.getElementById('showDescriptions');
        var showDesc = !showDescChk || showDescChk.checked;
        if (showDesc && nodeData.description) { html += '<br><small>' + esc(nodeData.description).replace(/\n/g, '<br>') + '</small>'; }
        if (pct !== undefined && pct > 0) {
            html += '<br><span class="tax-tooltip-pct">Match: ' + pct + '%</span>';
        }
        tip.innerHTML = html;
        tip.style.display = 'block';
        tip.style.left = Math.min(event.pageX + 14, window.innerWidth - 220) + 'px';
        tip.style.top = (event.pageY - 20) + 'px';
    }

    function hideTooltip() {
        var tip = document.getElementById('tax-d3-tooltip');
        if (tip) { tip.style.display = 'none'; }
    }

    function esc(s) {
        if (!s) { return ''; }
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    // Build a map from node code → root colour
    function buildColorMap(data) {
        var map = {};
        data.forEach(function (root, i) {
            var color = ROOT_COLORS[i % ROOT_COLORS.length];
            function walk(node) {
                map[node.code] = color;
                if (node.children) { node.children.forEach(walk); }
            }
            walk(root);
        });
        return map;
    }

    function nodeColor(code, colorMap, scores) {
        if (scores && scores[code] !== undefined && scores[code] > 0) {
            var alpha = Math.min(scores[code] / 100, 1).toFixed(2);
            return 'rgba(0,128,0,' + alpha + ')';
        }
        return colorMap[code] || '#aaa';
    }

    // ── Sunburst ────────────────────────────────────────────────────────────────
    /**
     * Render an interactive zoomable sunburst chart into `container`.
     * @param {HTMLElement} container - The DOM element to render into (cleared first).
     * @param {Array}       data      - Array of root taxonomy nodes (each with `children`).
     * @param {Object|null} scores    - Map of node code → match percentage, or null.
     */
    function renderSunburst(container, data, scores) {
        if (typeof d3 === 'undefined') {
            container.innerHTML = '<div class="alert alert-warning mt-2">D3.js is required for this view. Please check your internet connection.</div>';
            return;
        }
        if (container._taxObserver) {
            container._taxObserver.disconnect();
            container._taxObserver = null;
        }
        container.innerHTML = '';

        var colorMap = buildColorMap(data);
        var W = container.clientWidth || 500;
        var size = Math.min(W, MAX_SUNBURST_SIZE);
        var radius = size / 2;

        // Synthetic root to unify the 8 root taxonomies
        var rootData = { code: '__root__', name: 'Taxonomy', children: data };
        var hierarchy = d3.hierarchy(rootData)
            .sum(function (d) { return (!d.children || !d.children.length) ? 1 : 0; });
        d3.partition().size([2 * Math.PI, radius])(hierarchy);

        // Store initial arc positions for transitions
        hierarchy.each(function (d) {
            d.current = { x0: d.x0, x1: d.x1, y0: d.y0, y1: d.y1 };
        });

        var arc = d3.arc()
            .startAngle(function (d) { return d.x0; })
            .endAngle(function (d) { return d.x1; })
            .padAngle(function (d) { return Math.min((d.x1 - d.x0) / 2, 0.005); })
            .padRadius(radius / 2)
            .innerRadius(function (d) { return d.y0; })
            .outerRadius(function (d) { return Math.max(d.y0, d.y1 - 1); });

        var svg = d3.select(container)
            .append('svg')
            .attr('viewBox', [-size / 2, -size / 2, size, size].join(' '))
            .attr('width', size)
            .attr('height', size)
            .style('display', 'block')
            .style('margin', '0 auto');

        var g = svg.append('g');

        // Draw arcs (skip synthetic root at index 0)
        var paths = g.selectAll('path')
            .data(hierarchy.descendants().slice(1))
            .join('path')
            .attr('fill', function (d) { return nodeColor(d.data.code, colorMap, scores); })
            .attr('fill-opacity', function (d) { return arcVisible(d.current) ? (d.children ? 0.85 : 0.7) : 0; })
            .attr('pointer-events', function (d) { return arcVisible(d.current) ? 'auto' : 'none'; })
            .attr('d', function (d) { return arc(d.current); })
            .style('cursor', function (d) { return d.children ? 'pointer' : 'default'; });

        paths.filter(function (d) { return d.children; }).on('click', clicked);

        paths
            .on('mousemove', function (event, d) { showTooltip(event, d.data, scores); })
            .on('mouseleave', hideTooltip);

        // Labels
        var labels = g.selectAll('text')
            .data(hierarchy.descendants().slice(1))
            .join('text')
            .attr('pointer-events', 'none')
            .attr('text-anchor', 'middle')
            .attr('dy', '0.35em')
            .style('font-size', '9px')
            .style('fill', '#fff')
            .style('font-weight', '600')
            .attr('fill-opacity', function (d) { return +labelVisible(d.current); })
            .attr('transform', function (d) { return labelTransform(d.current); })
            .text(function (d) { return d.data.code; });

        // Centre circle – click to zoom back out
        var centerGroup = svg.append('g').style('cursor', 'pointer');
        centerGroup.append('circle')
            .attr('r', Math.max(radius * 0.15, 20))
            .attr('fill', 'rgba(240,244,250,0.9)')
            .attr('stroke', '#d0d8e8')
            .attr('stroke-width', 1);

        var centerText = centerGroup.append('text')
            .attr('text-anchor', 'middle')
            .attr('dy', '0.35em')
            .style('font-size', '11px')
            .style('fill', '#003580')
            .style('pointer-events', 'none')
            .text('↩ back');

        centerGroup.on('click', function () {
            if (currentZoom && currentZoom.parent) {
                clicked(null, currentZoom.parent);
            }
        });

        var currentZoom = hierarchy;

        function clicked(event, p) {
            currentZoom = p;

            hierarchy.each(function (d) {
                d.target = {
                    x0: Math.max(0, Math.min(1, (d.x0 - p.x0) / (p.x1 - p.x0))) * 2 * Math.PI,
                    x1: Math.max(0, Math.min(1, (d.x1 - p.x0) / (p.x1 - p.x0))) * 2 * Math.PI,
                    y0: Math.max(0, d.y0 - p.y0),
                    y1: Math.max(0, d.y1 - p.y0)
                };
            });

            var t = svg.transition().duration(750);

            paths.transition(t)
                .tween('data', function (d) {
                    var i = d3.interpolate(d.current, d.target);
                    return function (tt) { d.current = i(tt); };
                })
                .filter(function (d) {
                    return +this.getAttribute('fill-opacity') || arcVisible(d.target);
                })
                .attr('fill-opacity', function (d) { return arcVisible(d.target) ? (d.children ? 0.85 : 0.7) : 0; })
                .attr('pointer-events', function (d) { return arcVisible(d.target) ? 'auto' : 'none'; })
                .attrTween('d', function (d) { return function () { return arc(d.current); }; });

            labels.filter(function (d) {
                return +this.getAttribute('fill-opacity') || labelVisible(d.target);
            }).transition(t)
                .attr('fill-opacity', function (d) { return +labelVisible(d.target); })
                .attrTween('transform', function (d) { return function () { return labelTransform(d.current); }; });

            // Show/hide back button
            centerText.text(p === hierarchy ? '' : '↩ back');
        }

        function arcVisible(d) {
            return d.y1 > d.y0 && d.x1 > d.x0;
        }

        function labelVisible(d) {
            return arcVisible(d) && (d.y1 - d.y0) * (d.x1 - d.x0) > 0.04;
        }

        function labelTransform(d) {
            var x = (d.x0 + d.x1) / 2 * 180 / Math.PI;
            var y = (d.y0 + d.y1) / 2;
            return 'rotate(' + (x - 90) + ') translate(' + y + ',0) rotate(' + (x < 180 ? 0 : 180) + ')';
        }

        // Responsive resize
        var obs = new ResizeObserver(function () {
            var newW = Math.min(container.clientWidth || 500, MAX_SUNBURST_SIZE);
            svg.attr('width', newW).attr('height', newW);
        });
        obs.observe(container);
        container._taxObserver = obs;
    }

    // ── Top-down Tree Diagram ──────────────────────────────────────────────────
    /**
     * Render a collapsible top-down node-link tree diagram into `container`.
     * Root nodes are at the top; children grow downward. Supports pan and zoom.
     * @param {HTMLElement} container - The DOM element to render into (cleared first).
     * @param {Array}       data      - Array of root taxonomy nodes (each with `children`).
     * @param {Object|null} scores    - Map of node code → match percentage, or null.
     */
    function renderTreeDiagram(container, data, scores) {
        if (typeof d3 === 'undefined') {
            container.innerHTML = '<div class="alert alert-warning mt-2">D3.js is required for this view. Please check your internet connection.</div>';
            return;
        }
        if (container._taxObserver) {
            container._taxObserver.disconnect();
            container._taxObserver = null;
        }
        container.innerHTML = '';

        var colorMap = buildColorMap(data);
        var marginLeft = 40;
        var marginRight = 300;
        var marginTop = 20;
        var marginBottom = 20;

        // Synthetic root
        var rootData = { code: '__root__', name: '', children: data };
        var root = d3.hierarchy(rootData);

        // Initially collapse depth >= TREE_INITIAL_DEPTH to keep the initial view compact
        root.each(function (d) {
            if (d.depth >= TREE_INITIAL_DEPTH && d.children) {
                d._children = d.children;
                d.children = null;
            }
        });

        // nodeSize([dx, dy]): dx = px vertical between sibling nodes, dy = px horizontal between levels
        var treeLayout = d3.tree().nodeSize([28, 220]);

        var containerWidth = container.clientWidth || 800;
        var svg = d3.select(container)
            .append('svg')
            .attr('width', containerWidth)
            .attr('height', 400)
            .style('display', 'block');

        // Zoom/pan layer
        var zoomLayer = svg.append('g');
        svg.call(
            d3.zoom()
                .scaleExtent([0.1, 20])
                .on('zoom', function (event) { zoomLayer.attr('transform', event.transform); })
        );

        // Content group – repositioned inside update() to keep all nodes visible
        var g = zoomLayer.append('g');

        var nodeSeq = 0;

        function update(source) {
            treeLayout(root);
            var nodes = root.descendants();
            var links = root.links().filter(function (l) { return l.source.data.code !== '__root__'; });

            // Horizontal layout: d.y is the horizontal position (depth), d.x is vertical position
            // Compute bounding box: xMin/xMax are vertical extents, yMax is maximum horizontal depth
            var xMin = Infinity, xMax = -Infinity, yMax = 0;
            nodes.forEach(function (d) {
                if (d.x < xMin) { xMin = d.x; }
                if (d.x > xMax) { xMax = d.x; }
                if (d.y > yMax) { yMax = d.y; }
            });

            // Resize SVG to fit horizontal layout
            var svgW = Math.max(containerWidth, yMax + marginLeft + marginRight);
            var svgH = Math.max(400, xMax - xMin + marginTop + marginBottom);
            svg.attr('width', svgW).attr('height', svgH);

            // Translate so the topmost node is at marginTop, root is inset by marginLeft
            g.attr('transform', 'translate(' + marginLeft + ',' + (marginTop - xMin) + ')');

            var sourceX = source.x0 !== undefined ? source.x0 : (source.x != null ? source.x : 0);
            var sourceY = source.y0 !== undefined ? source.y0 : (source.y != null ? source.y : 0);

            // ── Nodes ──────────────────────────────────
            var node = g.selectAll('g.tv-node')
                .data(nodes, function (d) { return d.id || (d.id = ++nodeSeq); });

            var nodeEnter = node.enter().append('g')
                .attr('class', 'tv-node')
                .attr('transform', 'translate(' + sourceY + ',' + sourceX + ')')
                .style('opacity', 0);

            nodeEnter.append('circle').attr('r', 7).attr('stroke-width', 1.5);

            nodeEnter.append('text')
                .attr('dy', '0.31em')
                .style('font-size', '12px')
                .style('user-select', 'none');

            // Interactivity for non-root nodes
            nodeEnter.filter(function (d) { return d.data.code !== '__root__'; })
                .style('cursor', 'pointer')
                .on('click', function (event, d) {
                    event.stopPropagation();
                    if (d.children) {
                        d._children = d.children;
                        d.children = null;
                    } else {
                        d.children = d._children;
                        d._children = null;
                    }
                    update(d);
                })
                .on('mousemove', function (event, d) { showTooltip(event, d.data, scores); })
                .on('mouseleave', hideTooltip);

            // Merge enter + update
            var nodeUpdate = nodeEnter.merge(node);

            // Horizontal layout: translate(y, x) — y is horizontal position, x is vertical
            nodeUpdate.transition().duration(300)
                .attr('transform', function (d) { return 'translate(' + d.y + ',' + d.x + ')'; })
                .style('opacity', function (d) { return d.data.code === '__root__' ? 0 : 1; });

            nodeUpdate.select('circle')
                .attr('fill', function (d) {
                    if (d.data.code === '__root__') { return 'none'; }
                    return nodeColor(d.data.code, colorMap, scores);
                })
                .attr('stroke', function (d) { return d.data.code === '__root__' ? 'none' : '#555'; });

            nodeUpdate.select('text')
                .text(function (d) {
                    if (d.data.code === '__root__') { return ''; }
                    return d.data.name ? d.data.code + ' \u2013 ' + d.data.name : d.data.code;
                })
                .attr('x', function (d) { return d.children || d._children ? -10 : 10; })
                .attr('text-anchor', function (d) { return d.children || d._children ? 'end' : 'start'; });

            // Exit: animate back to source position (using horizontal layout coordinates)
            node.exit().transition().duration(300)
                .attr('transform', 'translate(' + source.y + ',' + source.x + ')')
                .style('opacity', 0)
                .remove();

            // ── Links ──────────────────────────────────
            // Horizontal link: x accessor uses d.y (horizontal), y accessor uses d.x (vertical)
            var diag = d3.linkHorizontal()
                .x(function (d) { return d.y; })
                .y(function (d) { return d.x; });

            var link = g.selectAll('path.tv-link')
                .data(links, function (d) { return d.target.id; });

            var linkEnter = link.enter().insert('path', 'g')
                .attr('class', 'tv-link')
                .attr('fill', 'none')
                .attr('stroke', '#ccc')
                .attr('stroke-width', 1.5)
                .attr('d', function () {
                    var o = { x: sourceX, y: sourceY };
                    return diag({ source: o, target: o });
                });

            linkEnter.merge(link).transition().duration(300).attr('d', diag);

            link.exit().transition().duration(300)
                .attr('d', function () {
                    var o = { x: source.x, y: source.y };
                    return diag({ source: o, target: o });
                })
                .remove();

            // Save positions for transitions
            nodes.forEach(function (d) { d.x0 = d.x; d.y0 = d.y; });
        }

        // Set initial positions on the synthetic root
        root.x0 = 0;
        root.y0 = 0;
        update(root);

        // Responsive resize – widen SVG if container grows, but never shrink below content width
        var obs = new ResizeObserver(function () {
            var newW = container.clientWidth || 800;
            var curW = parseFloat(svg.attr('width')) || 0;
            if (newW > curW) { svg.attr('width', newW); }
        });
        obs.observe(container);
        container._taxObserver = obs;
    }

    // ── Decision Map ──────────────────────────────────────────────────────────
    /**
     * Render a Decision Map into `container`.
     * Shows only the "hot" paths (nodes with score > 0 and their ancestors),
     * with top-3 nodes highlighted with rank badges.
     * Includes a stats summary and a sortable/filterable results table.
     * @param {HTMLElement} container - The DOM element to render into (cleared first).
     * @param {Array}       data      - Array of root taxonomy nodes (each with `children`).
     * @param {Object|null} scores    - Map of node code → match percentage, or null.
     */
    function renderDecisionMap(container, data, scores) {
        if (typeof d3 === 'undefined') {
            container.innerHTML = '<div class="alert alert-warning mt-2">D3.js is required for this view. Please check your internet connection.</div>';
            return;
        }
        if (container._taxObserver) {
            container._taxObserver.disconnect();
            container._taxObserver = null;
        }
        container.innerHTML = '';

        scores = scores || {};

        // ── Build helper maps ────────────────────────────────────────────────
        var nameMap = {}, pathMap = {}, levelMap = {}, isLeafSet = new Set();

        function walkNodes(node, ancestors, level) {
            nameMap[node.code] = node.name || '';
            levelMap[node.code] = level;
            var displayName = node.name ? (node.code + ' ' + node.name) : node.code;
            pathMap[node.code] = ancestors.concat(displayName);
            if (!node.children || !node.children.length) {
                isLeafSet.add(node.code);
            } else {
                node.children.forEach(function (child) {
                    walkNodes(child, pathMap[node.code], level + 1);
                });
            }
        }
        data.forEach(function (root) { walkNodes(root, [], 0); });

        // ── Compute hot set (ancestors of scored nodes) ──────────────────────
        var hotSet = new Set();
        function computeHot(node) {
            var selfHot = (scores[node.code] > 0);
            var childHot = false;
            if (node.children) {
                node.children.forEach(function (child) {
                    if (computeHot(child)) { childHot = true; }
                });
            }
            if (selfHot || childHot) { hotSet.add(node.code); return true; }
            return false;
        }
        data.forEach(computeHot);

        // ── Scored nodes sorted by score desc ────────────────────────────────
        var allScored = Object.entries(scores)
            .filter(function (e) { return e[1] > 0; })
            .sort(function (a, b) { return b[1] - a[1]; });

        var top3Codes = allScored.slice(0, 3).map(function (e) { return e[0]; });
        var rankMap = {};
        top3Codes.forEach(function (code, i) { rankMap[code] = i + 1; });

        // ── Stats summary ────────────────────────────────────────────────────
        var totalNodes = Object.keys(nameMap).length;
        var scoredCount = allScored.length;
        var affectedRootsSet = new Set(
            allScored.map(function (e) { return pathMap[e[0]] && pathMap[e[0]][0]; }).filter(Boolean)
        );
        var statsDiv = document.createElement('div');
        statsDiv.className = 'decision-stats';
        if (scoredCount === 0) {
            statsDiv.innerHTML = '<span class="decision-stats-item">No scored nodes yet. Run an analysis to see the Decision Map.</span>';
        } else {
            var best = allScored[0];
            var bestName = nameMap[best[0]] || '';
            var bestDisplay = bestName ? (esc(best[0]) + ' \u2013 ' + esc(bestName)) : esc(best[0]);
            statsDiv.innerHTML =
                '<span class="decision-stats-item">&#128202; <strong>' + scoredCount + '</strong> of <strong>' + totalNodes + '</strong> nodes scored</span>' +
                '<span class="decision-stats-item">&#127942; Best: <strong>' + bestDisplay + '</strong> (' + best[1] + '%)</span>' +
                '<span class="decision-stats-item">&#127968; ' + affectedRootsSet.size + ' root categor' + (affectedRootsSet.size === 1 ? 'y' : 'ies') + ' affected</span>';
        }
        container.appendChild(statsDiv);

        // ── Filter data to hot paths only ────────────────────────────────────
        function filterToHot(node) {
            if (!hotSet.has(node.code)) { return null; }
            var result = { code: node.code, name: node.name, description: node.description, level: node.level };
            if (node.children) {
                var fc = node.children.map(filterToHot).filter(Boolean);
                if (fc.length) { result.children = fc; }
            }
            return result;
        }
        var filteredData = data.map(filterToHot).filter(Boolean);

        if (filteredData.length === 0) {
            var noDataMsg = document.createElement('div');
            noDataMsg.className = 'alert alert-info mt-2';
            noDataMsg.textContent = 'No scored nodes yet. Run an analysis to see the Decision Map.';
            container.appendChild(noDataMsg);
            renderDecisionTable(container, allScored, nameMap, pathMap, levelMap, isLeafSet, rankMap);
            return;
        }

        // ── D3 Tree ──────────────────────────────────────────────────────────
        var marginLeft = 40;
        var marginRight = 300;
        var marginTop = 20;
        var marginBottom = 20;

        // Synthetic root to hold all filtered roots
        var rootData = { code: '__root__', name: '', children: filteredData };
        var root = d3.hierarchy(rootData);

        var treeLayout = d3.tree().nodeSize([28, 220]);
        var containerWidth = container.clientWidth || 800;

        var svgWrapper = document.createElement('div');
        svgWrapper.className = 'decision-tree-wrapper';
        container.appendChild(svgWrapper);

        var svg = d3.select(svgWrapper)
            .append('svg')
            .attr('id', 'decision-map-svg')
            .attr('width', containerWidth)
            .attr('height', 400)
            .style('display', 'block');

        var zoomLayer = svg.append('g');
        svg.call(
            d3.zoom()
                .scaleExtent([0.1, 20])
                .on('zoom', function (event) { zoomLayer.attr('transform', event.transform); })
        );

        var g = zoomLayer.append('g');
        var nodeSeq = 0;

        var RANK_EMOJIS = ['', '\uD83E\uDD47', '\uD83E\uDD48', '\uD83E\uDD49']; // 🥇🥈🥉

        function dmNodeFill(d) {
            var code = d.data.code;
            if (code === '__root__') { return 'none'; }
            var rank = rankMap[code];
            if (rank === 1) { return '#FFD700'; }
            if (rank === 2) { return '#C0C0C0'; }
            if (rank === 3) { return '#CD7F32'; }
            var pct = scores[code];
            if (pct > 0) {
                var alpha = Math.min(pct / 100, 1).toFixed(2);
                return 'rgba(0,128,0,' + alpha + ')';
            }
            return '#ddd'; // hot-path ancestor with no score
        }

        function dmNodeStroke(d) {
            var code = d.data.code;
            if (code === '__root__') { return 'none'; }
            if (rankMap[code]) { return '#888'; }
            if (scores[code] > 0) { return '#555'; }
            return '#bbb';
        }

        function dmLinkStrokeWidth(d) {
            var code = d.target.data.code;
            if (rankMap[code]) { return 4; }
            var pct = scores[code] || 0;
            if (pct > 0) { return Math.max(2, Math.min(4, 2 + pct / 50)); }
            return 1;
        }

        function dmLinkStroke(d) {
            var code = d.target.data.code;
            if (rankMap[code] === 1) { return '#FFD700'; }
            if (rankMap[code] === 2) { return '#C0C0C0'; }
            if (rankMap[code] === 3) { return '#CD7F32'; }
            var pct = scores[code] || 0;
            if (pct > 0) { return 'rgba(0,128,0,0.6)'; }
            return '#ccc';
        }

        function dmUpdate(source) {
            treeLayout(root);
            var nodes = root.descendants();
            var links = root.links().filter(function (l) { return l.source.data.code !== '__root__'; });

            var xMin = Infinity, xMax = -Infinity, yMax = 0;
            nodes.forEach(function (d) {
                if (d.x < xMin) { xMin = d.x; }
                if (d.x > xMax) { xMax = d.x; }
                if (d.y > yMax) { yMax = d.y; }
            });

            var svgW = Math.max(containerWidth, yMax + marginLeft + marginRight);
            var svgH = Math.max(400, xMax - xMin + marginTop + marginBottom);
            svg.attr('width', svgW).attr('height', svgH);
            g.attr('transform', 'translate(' + marginLeft + ',' + (marginTop - xMin) + ')');

            var sourceX = source.x0 !== undefined ? source.x0 : (source.x || 0);
            var sourceY = source.y0 !== undefined ? source.y0 : (source.y || 0);

            // ── Nodes ──────────────────────────────────
            var node = g.selectAll('g.dm-node')
                .data(nodes, function (d) { return d.id || (d.id = ++nodeSeq); });

            var nodeEnter = node.enter().append('g')
                .attr('class', 'dm-node')
                .attr('transform', 'translate(' + sourceY + ',' + sourceX + ')')
                .style('opacity', 0);

            nodeEnter.append('circle').attr('r', 7).attr('stroke-width', 1.5);

            nodeEnter.append('text')
                .attr('class', 'dm-label')
                .attr('dy', '0.31em')
                .style('font-size', '12px')
                .style('user-select', 'none');

            nodeEnter.append('text')
                .attr('class', 'dm-rank')
                .attr('dy', '-0.7em')
                .attr('text-anchor', 'middle')
                .style('font-size', '13px')
                .style('pointer-events', 'none');

            nodeEnter.filter(function (d) { return d.data.code !== '__root__'; })
                .style('cursor', 'pointer')
                .on('click', function (event, d) {
                    event.stopPropagation();
                    if (d.children) { d._children = d.children; d.children = null; }
                    else { d.children = d._children; d._children = null; }
                    dmUpdate(d);
                })
                .on('mousemove', function (event, d) { showTooltip(event, d.data, scores); })
                .on('mouseleave', hideTooltip);

            var nodeUpdate = nodeEnter.merge(node);

            nodeUpdate.transition().duration(300)
                .attr('transform', function (d) { return 'translate(' + d.y + ',' + d.x + ')'; })
                .style('opacity', function (d) { return d.data.code === '__root__' ? 0 : 1; });

            nodeUpdate.select('circle')
                .attr('fill', dmNodeFill)
                .attr('stroke', dmNodeStroke);

            nodeUpdate.select('.dm-label')
                .text(function (d) {
                    if (d.data.code === '__root__') { return ''; }
                    var label = d.data.code;
                    var pct = scores[d.data.code];
                    if (pct > 0) { label += ' ' + pct + '%'; }
                    return label;
                })
                .attr('x', function (d) { return (d.children || d._children) ? -10 : 10; })
                .attr('text-anchor', function (d) { return (d.children || d._children) ? 'end' : 'start'; })
                .style('fill', function (d) { return (scores[d.data.code] || 0) >= 60 ? '#fff' : '#333'; })
                .style('font-weight', function (d) { return rankMap[d.data.code] ? '700' : '400'; });

            nodeUpdate.select('.dm-rank')
                .text(function (d) {
                    var rank = rankMap[d.data.code];
                    return rank ? RANK_EMOJIS[rank] : '';
                });

            node.exit().transition().duration(300)
                .attr('transform', 'translate(' + source.y + ',' + source.x + ')')
                .style('opacity', 0)
                .remove();

            // ── Links ──────────────────────────────────
            var diag = d3.linkHorizontal()
                .x(function (d) { return d.y; })
                .y(function (d) { return d.x; });

            var link = g.selectAll('path.dm-link')
                .data(links, function (d) { return d.target.id; });

            var linkEnter = link.enter().insert('path', 'g')
                .attr('class', 'dm-link')
                .attr('fill', 'none')
                .attr('d', function () {
                    var o = { x: sourceX, y: sourceY };
                    return diag({ source: o, target: o });
                });

            var linkUpdate = linkEnter.merge(link);
            linkUpdate.transition().duration(300).attr('d', diag);
            linkUpdate
                .attr('stroke', dmLinkStroke)
                .attr('stroke-width', dmLinkStrokeWidth);

            link.exit().transition().duration(300)
                .attr('d', function () {
                    var o = { x: source.x, y: source.y };
                    return diag({ source: o, target: o });
                })
                .remove();

            nodes.forEach(function (d) { d.x0 = d.x; d.y0 = d.y; });
        }

        root.x0 = 0;
        root.y0 = 0;
        dmUpdate(root);

        var obs = new ResizeObserver(function () {
            var newW = container.clientWidth || 800;
            var curW = parseFloat(svg.attr('width')) || 0;
            if (newW > curW) { svg.attr('width', newW); }
        });
        obs.observe(container);
        container._taxObserver = obs;

        // ── Results table ────────────────────────────────────────────────────
        renderDecisionTable(container, allScored, nameMap, pathMap, levelMap, isLeafSet, rankMap);
    }

    /**
     * Render the Decision Map results table (Top-N scored nodes with filters).
     */
    function renderDecisionTable(container, allScored, nameMap, pathMap, levelMap, isLeafSet, rankMap) {
        if (!allScored || allScored.length === 0) { return; }

        var RANK_EMOJIS = ['', '\uD83E\uDD47', '\uD83E\uDD48', '\uD83E\uDD49'];

        var tableWrapper = document.createElement('div');
        tableWrapper.className = 'decision-table-wrapper mt-3';

        var filterDiv = document.createElement('div');
        filterDiv.className = 'decision-filter mb-2 d-flex align-items-center gap-2 flex-wrap';
        filterDiv.innerHTML =
            '<strong>Top Matches</strong>' +
            '<div class="form-check form-check-inline ms-2 mb-0">' +
                '<input class="form-check-input" type="checkbox" id="dmFilterLeaves">' +
                '<label class="form-check-label small" for="dmFilterLeaves">Leaves only</label>' +
            '</div>' +
            '<label class="small ms-2 mb-0">Min score:&nbsp;' +
                '<input id="dmMinScore" type="number" class="form-control form-control-sm d-inline-block" ' +
                'style="width:70px" min="0" max="100" value="0">&nbsp;%' +
            '</label>';
        tableWrapper.appendChild(filterDiv);

        var table = document.createElement('table');
        table.className = 'table table-sm table-hover decision-table';
        table.innerHTML =
            '<thead><tr>' +
            '<th>#</th><th>Code</th><th>Name</th><th>Score</th><th>Path</th><th>Level</th>' +
            '</tr></thead>';
        var tbody = document.createElement('tbody');
        tbody.id = 'decision-table-body';
        table.appendChild(tbody);
        tableWrapper.appendChild(table);
        container.appendChild(tableWrapper);

        function renderRows(leavesOnly, minScore) {
            tbody.innerHTML = '';
            var displayRank = 0;
            var rows = allScored.filter(function (e) {
                if (e[1] < minScore) { return false; }
                if (leavesOnly && !isLeafSet.has(e[0])) { return false; }
                return true;
            }).slice(0, 20);

            rows.forEach(function (e, i) {
                var code = e[0];
                var pct = e[1];
                displayRank = i + 1;
                var rankEmoji = displayRank <= 3 ? RANK_EMOJIS[displayRank] : displayRank;
                var nodeName = nameMap[code] || '';
                var path = (pathMap[code] || []).join(' \u2192 ');
                var level = levelMap[code] || 0;
                var alpha = Math.min(pct / 100, 1).toFixed(2);
                var textColor = pct >= 60 ? '#fff' : '#000';
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td>' + rankEmoji + '</td>' +
                    '<td><strong>' + esc(code) + '</strong></td>' +
                    '<td>' + esc(nodeName) + '</td>' +
                    '<td><span class="decision-score-badge" style="background:rgba(0,128,0,' + alpha + ');color:' + textColor + '">' + pct + '%</span></td>' +
                    '<td class="small text-muted">' + esc(path) + '</td>' +
                    '<td class="text-center">' + level + '</td>';
                tbody.appendChild(tr);
            });

            if (rows.length === 0) {
                var tr = document.createElement('tr');
                tr.innerHTML = '<td colspan="6" class="text-muted text-center">No nodes match the filter criteria.</td>';
                tbody.appendChild(tr);
            }
        }

        renderRows(false, 0);

        var leavesChk = document.getElementById('dmFilterLeaves');
        var minScoreInput = document.getElementById('dmMinScore');
        function onFilterChange() {
            renderRows(
                leavesChk ? leavesChk.checked : false,
                parseInt(minScoreInput ? minScoreInput.value : '0', 10) || 0
            );
        }
        if (leavesChk) { leavesChk.addEventListener('change', onFilterChange); }
        if (minScoreInput) { minScoreInput.addEventListener('input', onFilterChange); }
    }

    // ── Export ─────────────────────────────────────────────────────────────────
    window.TaxonomyViews = {
        renderSunburst: renderSunburst,
        renderTreeDiagram: renderTreeDiagram,
        renderDecisionMap: renderDecisionMap
    };

})();
