/* taxonomy-views.js – D3.js visualization views for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    var t = TaxonomyI18n.t;

    var diagramHintSequence = 0;

    function rememberDiagramFocus(container) {
        var focused = document.activeElement;
        container._taxRestoreNodeFocus = !!(focused && container.contains(focused)
            && focused.getAttribute('data-diagram-node'));
    }

    function diagramNodeLabel(node, scores, zoomable) {
        var data = node.data;
        var label = data.code + (data.name ? ' – ' + data.name : '');
        if (hasScoreLabel(data.code, scores)) { label += ' · ' + scoreText(data.code, scores[data.code]); }
        if (!zoomable) {
            label += ' · ' + t(node.children ? 'views.diagram.expanded'
                : node._children ? 'views.diagram.collapsed' : 'views.diagram.leaf');
        }
        var showDescriptions = document.getElementById && document.getElementById('showDescriptions');
        if ((!showDescriptions || showDescriptions.checked) && data.description) { label += ' · ' + data.description; }
        return label;
    }

    /** One tab stop per diagram; arrows traverse only currently visible nodes. */
    function createDiagramNavigation(container, surface, options) {
        var entries = [];
        var active = null;
        surface.setAttribute('role', 'group');
        surface.setAttribute('aria-label', container.getAttribute('aria-label') || t('views.diagram.controls'));
        if (options.canvas) { surface.setAttribute('tabindex', '0'); }

        function select(entry, focus) {
            if (!entry) { return; }
            active = entry;
            container.setAttribute('data-active-node', entry.node.data.code);
            entries.forEach(function (candidate) {
                if (!options.canvas) { candidate.element.setAttribute('tabindex', candidate === active ? '0' : '-1'); }
            });
            if (options.canvas) {
                surface.setAttribute('data-diagram-node', entry.node.data.code);
                surface.setAttribute('aria-label', diagramNodeLabel(entry.node, options.scores, false));
                surface.setAttribute('role', entry.node.children || entry.node._children ? 'button' : 'group');
                surface.removeAttribute('aria-expanded');
                if (entry.node.children || entry.node._children) {
                    surface.setAttribute('aria-expanded', !!entry.node.children);
                }
            }
            if (options.change) { options.change(entry.node); }
            if (focus) { entry.element.focus(); }
        }

        surface.addEventListener('focusin', function (event) {
            var entry = options.canvas ? active : entries.find(function (candidate) { return candidate.element === event.target; });
            if (entry) { select(entry, false); }
        });
        surface.addEventListener('keydown', function (event) {
            var entry = options.canvas ? active : entries.find(function (candidate) { return candidate.element === event.target; });
            if (!entry) { return; }
            var index = entries.indexOf(entry);
            var next;
            if (event.key === 'ArrowDown' || event.key === 'ArrowRight') { next = Math.min(entries.length - 1, index + 1); }
            else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') { next = Math.max(0, index - 1); }
            else if (event.key === 'Home') { next = 0; }
            else if (event.key === 'End') { next = entries.length - 1; }
            else if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault(); event.stopPropagation();
                if (!event.repeat) { options.activate(entry.node); }
                return;
            } else { return; }
            event.preventDefault(); event.stopPropagation();
            select(entries[next], true);
        });

        return {
            update: function (nextEntries) {
                var previous = active;
                var hadFocus = container._taxRestoreNodeFocus || (options.canvas ? document.activeElement === surface
                    : entries.some(function (entry) { return entry.element === document.activeElement; }));
                container._taxRestoreNodeFocus = false;
                entries.forEach(function (entry) {
                    if (!options.canvas) {
                        entry.element.setAttribute('tabindex', '-1');
                        entry.element.setAttribute('aria-hidden', 'true');
                    }
                });
                entries = nextEntries;
                entries.forEach(function (entry) {
                    if (options.canvas) { return; }
                    entry.element.classList.add('tax-diagram-node');
                    entry.element.setAttribute('data-diagram-node', entry.node.data.code);
                    entry.element.removeAttribute('aria-hidden');
                    entry.element.setAttribute('role', entry.node.children || entry.node._children ? 'button' : 'group');
                    entry.element.setAttribute('aria-label', diagramNodeLabel(entry.node, options.scores, options.zoomable));
                    entry.element.removeAttribute('aria-expanded');
                    if (!options.zoomable && (entry.node.children || entry.node._children)) {
                        entry.element.setAttribute('aria-expanded', !!entry.node.children);
                    }
                });
                var code = container.getAttribute('data-active-node');
                var chosen = entries.find(function (entry) { return entry.node.data.code === code; });
                var ancestor = previous && previous.node.parent;
                while (!chosen && ancestor) {
                    chosen = entries.find(function (entry) { return entry.node === ancestor; });
                    ancestor = ancestor.parent;
                }
                active = null;
                select(chosen || entries[0], hadFocus);
                if (options.canvas && !active) { surface.setAttribute('aria-label', t('views.no.scored.nodes')); }
            },
            selectNode: function (node) {
                select(entries.find(function (entry) { return entry.node === node; }), false);
            },
            current: function () { return active && active.node; }
        };
    }

    function createDiagramControls(container, surface, actions, hintKey) {
        var controls = document.createElement('div');
        controls.className = 'tax-diagram-controls';
        controls.setAttribute('role', 'group');
        controls.setAttribute('aria-label', t('views.diagram.controls'));
        actions.forEach(function (action) {
            var button = document.createElement('button');
            button.setAttribute('type', 'button');
            button.className = 'btn btn-sm btn-outline-secondary';
            button.textContent = t(action.key);
            button.addEventListener('click', action.run);
            controls.appendChild(button);
        });
        var hint = document.createElement('p');
        hint.id = 'tax-diagram-hint-' + (++diagramHintSequence);
        hint.className = 'tax-diagram-hint small text-muted';
        hint.textContent = t(hintKey);
        controls.appendChild(hint);
        surface.setAttribute('aria-describedby', hint.id);
        container.insertBefore(controls, container.children[0]);
        return controls;
    }

    function installDiagramZoomControls(container, surface, selection, zoom, bounds, canvas) {
        function scale(factor) {
            selection.call(zoom.scaleBy, factor, [(container.clientWidth || 800) / 2, 200]);
        }
        createDiagramControls(container, surface, [
            {key: 'views.diagram.zoomIn', run: function () { scale(1.25); }},
            {key: 'views.diagram.zoomOut', run: function () { scale(0.8); }},
            {key: 'views.diagram.fit', run: function () {
                var box = bounds();
                if (!box || !box.width || !box.height) { return; }
                var k = Math.max(0.001, Math.min(1, ((container.clientWidth || 800) - 32) / box.width, 368 / box.height));
                var fitted = d3.zoomIdentity.translate(16 - box.x * k, 16 - box.y * k).scale(k);
                selection.call(zoom.transform, fitted);
            }},
            {key: 'views.diagram.reset', run: function () { selection.call(zoom.transform, d3.zoomIdentity); }}
        ], canvas ? 'views.diagram.canvasKeyboard' : 'views.diagram.keyboard');
    }

    function diagramEntries(selection, visible) {
        var entries = [];
        selection.each(function (node) {
            if (node.data.code !== '__root__' && (!visible || visible(node))) { entries.push({node: node, element: this}); }
        });
        return entries;
    }

    function toggleDiagramBranch(node, update) {
        if (node.children) { node._children = node.children; node.children = null; }
        else if (node._children) { node.children = node._children; node._children = null; }
        else { return; }
        hideTooltip();
        update(node);
    }

    function scoreText(code, value) {
        var scoring = window.TaxonomyScoring;
        return scoring && scoring.describeScore
            ? scoring.describeScore(code, value).label : value + '%';
    }

    function hasScoreLabel(code, scores) {
        if (!scores || !Object.prototype.hasOwnProperty.call(scores, code)) { return false; }
        var scoring = window.TaxonomyScoring;
        return scoring && scoring.describeScore
            ? scoring.describeScore(code, scores[code]).visible : scores[code] > 0;
    }

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
        if (pct !== undefined) {
            html += '<br><span class="tax-tooltip-pct">' + esc(scoreText(nodeData.code, pct)) + '</span>';
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

    /** A display-only projection. Missing/pending measurements are not negative evidence. */
    function filterZeroRelevance(data, scores, details) {
        function project(node) {
            var children = (node.children || []).map(project).filter(Boolean);
            var detail = details && details[node.code];
            var pendingProduct = detail && detail.kind === 'PRODUCT_SUITABILITY'
                && !Number.isFinite(detail.parentScore);
            var zero = scores && Object.prototype.hasOwnProperty.call(scores, node.code)
                && scores[node.code] === 0 && !pendingProduct;
            if (zero && children.length === 0) return null;
            return Object.assign({}, node, { children: children });
        }
        return (data || []).map(project).filter(Boolean);
    }

    // ── Sunburst ────────────────────────────────────────────────────────────────
    /**
     * Render an interactive zoomable sunburst chart into `container`.
     * @param {HTMLElement} container - The DOM element to render into (cleared first).
     * @param {Array}       data      - Array of root taxonomy nodes (each with `children`).
     * @param {Object|null} scores    - Map of node code → match percentage, or null.
     */
    function renderSunburst(container, data, scores) {
        rememberDiagramFocus(container);
        container.removeAttribute('data-view-rendered');
        if (typeof d3 === 'undefined') {
            container.innerHTML = '<div class="alert alert-warning mt-2">' + t('views.d3.required') + '</div>';
            return;
        }
        if (container._taxObserver) {
            container._taxObserver.disconnect();
            container._taxObserver = null;
        }
        container.innerHTML = '';

        try {
        var originalData = data;
        var colorMap = buildColorMap(originalData);
        var filter = document.getElementById('sunburstHideZero');
        if (filter) {
            if (filter._taxonomyZeroFilter) filter.removeEventListener('change', filter._taxonomyZeroFilter);
            filter._taxonomyZeroFilter = function () {
                if (container.getAttribute('data-view-rendered') === 'sunburst') {
                    renderSunburst(container, originalData, scores);
                }
            };
            filter.addEventListener('change', filter._taxonomyZeroFilter);
            if (filter.checked) data = filterZeroRelevance(originalData, scores,
                window.TaxonomyState && window.TaxonomyState.currentScoreDetails);
        }
        if (!data.length) {
            var empty = document.createElement('p');
            empty.className = 'text-muted p-3';
            empty.textContent = t('views.sunburst.empty');
            container.appendChild(empty);
            container.setAttribute('data-view-rendered', 'sunburst');
            return;
        }
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
            .text(t('views.back'));

        centerGroup.on('click', function () {
            if (currentZoom && currentZoom.parent) {
                clicked(null, currentZoom.parent);
            }
        });

        var currentZoom = hierarchy;
        var sunNavigation = createDiagramNavigation(container, svg.node(), {
            scores: scores, zoomable: true,
            activate: function (node) { if (node.children) { clicked(null, node); } }
        });
        sunNavigation.update(diagramEntries(paths, function (node) { return arcVisible(node.current); }));
        var sunControls = createDiagramControls(container, svg.node(), [
            {key: 'views.back', run: function () { if (currentZoom.parent) { clicked(null, currentZoom.parent); } }},
            {key: 'views.diagram.overview', run: function () { clicked(null, hierarchy); }}
        ], 'views.diagram.keyboard');
        var backControl = sunControls.querySelector('button');
        backControl.disabled = true;
        centerGroup.attr('aria-hidden', 'true'); // The equivalent native Back button is in the local controls.

        function clicked(event, p) {
            currentZoom = p;
            hideTooltip();

            hierarchy.each(function (d) {
                d.target = {
                    x0: Math.max(0, Math.min(1, (d.x0 - p.x0) / (p.x1 - p.x0))) * 2 * Math.PI,
                    x1: Math.max(0, Math.min(1, (d.x1 - p.x0) / (p.x1 - p.x0))) * 2 * Math.PI,
                    y0: Math.max(0, d.y0 - p.y0),
                    y1: Math.max(0, d.y1 - p.y0)
                };
            });

            var transition = svg.transition().duration(750);

            paths.transition(transition)
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
            }).transition(transition)
                .attr('fill-opacity', function (d) { return +labelVisible(d.target); })
                .attrTween('transform', function (d) { return function () { return labelTransform(d.current); }; });

            // Show/hide back button
            centerText.text(p === hierarchy ? '' : t('views.back'));
            backControl.disabled = p === hierarchy;
            sunNavigation.update(diagramEntries(paths, function (node) { return arcVisible(node.target); }));
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
        } catch (e) {
            console.error('Sunburst rendering failed:', e);
        }
        container.setAttribute('data-view-rendered', 'sunburst');
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
        rememberDiagramFocus(container);
        container.removeAttribute('data-view-rendered');
        if (typeof d3 === 'undefined') {
            container.innerHTML = '<div class="alert alert-warning mt-2">' + t('views.d3.required') + '</div>';
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
        var treeZoom = d3.zoom().scaleExtent([0.001, 20])
            .on('zoom', function (event) { zoomLayer.attr('transform', event.transform); });
        svg.call(treeZoom);
        installDiagramZoomControls(container, svg.node(), svg, treeZoom, function () { return zoomLayer.node().getBBox(); });
        var treeNavigation = createDiagramNavigation(container, svg.node(), {
            scores: scores, activate: function (node) { toggleDiagramBranch(node, update); }
        });

        // Content group – repositioned inside update() to keep all nodes visible
        var g = zoomLayer.append('g');

        var nodeSeq = 0;
        var updateToken = 0;

        function update(source) {
            container.removeAttribute('data-transitions-complete');
            var currentToken = ++updateToken;
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
                    treeNavigation.selectNode(d);
                    toggleDiagramBranch(d, update);
                })
                .on('mousemove', function (event, d) { showTooltip(event, d.data, scores); })
                .on('mouseleave', hideTooltip);

            // Merge enter + update
            var nodeUpdate = nodeEnter.merge(node);
            treeNavigation.update(diagramEntries(nodeUpdate));

            // Horizontal layout: translate(y, x) — y is horizontal position, x is vertical
            var nodeT = nodeUpdate.transition().duration(300)
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

            var linkT = linkEnter.merge(link).transition().duration(300).attr('d', diag);

            link.exit().transition().duration(300)
                .attr('d', function () {
                    var o = { x: source.x, y: source.y };
                    return diag({ source: o, target: o });
                })
                .remove();

            // Signal when ALL transitions (nodes + links) complete.
            // The version token prevents stale handlers from a prior update()
            // from racing with a newer call that already cleared the attribute.
            var settle = function () {
                if (currentToken === updateToken) {
                    container.setAttribute('data-transitions-complete', 'true');
                }
            };
            Promise.all([nodeT.end(), linkT.end()])
                .then(settle)
                .catch(settle);

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
        container.setAttribute('data-view-rendered', 'tree');
    }

    // ── Decision Map ──────────────────────────────────────────────────────────
    /**
     * Render a Decision Map into `container`.
     * Shows paths with positive relevance or product suitability evidence, plus their ancestors,
     * with top-3 nodes highlighted with rank badges.
     * Includes a stats summary and a sortable/filterable results table.
     * @param {HTMLElement} container - The DOM element to render into (cleared first).
     * @param {Array}       data      - Array of root taxonomy nodes (each with `children`).
     * @param {Object|null} scores    - Map of node code → match percentage, or null.
     */
    function renderDecisionMap(container, data, scores) {
        rememberDiagramFocus(container);
        container.removeAttribute('data-view-rendered');
        if (typeof d3 === 'undefined') {
            container.innerHTML = '<div class="alert alert-warning mt-2">' + t('views.d3.required') + '</div>';
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
            var selfHot = hasScoreLabel(node.code, scores);
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
            statsDiv.innerHTML = '<span class="decision-stats-item">' +
                t(hotSet.size > 0 ? 'views.product.evidence.only' : 'views.no.scored.nodes') + '</span>';
        } else {
            var best = allScored[0];
            var bestName = nameMap[best[0]] || '';
            var bestDisplay = bestName ? (esc(best[0]) + ' \u2013 ' + esc(bestName)) : esc(best[0]);
            statsDiv.innerHTML =
                '<span class="decision-stats-item">&#128202; ' + esc(t('views.ui.scored', scoredCount, totalNodes)) + '</span>' +
                '<span class="decision-stats-item">&#127942; ' + esc(t('views.ui.best')) + ' <strong>' + bestDisplay + '</strong> (' + best[1] + '%)</span>' +
                '<span class="decision-stats-item">&#127968; ' + esc(t('views.ui.roots', affectedRootsSet.size)) + '</span>';
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
            noDataMsg.textContent = t('views.no.scored.nodes');
            container.appendChild(noDataMsg);
            renderDecisionTable(container, allScored, nameMap, pathMap, levelMap, isLeafSet, rankMap);
            container.setAttribute('data-view-rendered', 'decision');
            return;
        }

        // ── D3 Tree ──────────────────────────────────────────────────────────
        try {
        var marginLeft = 40;
        var marginRight = 300;
        var marginTop = 20;
        var marginBottom = 20;

        // Synthetic root to hold all filtered roots
        var rootData = { code: '__root__', name: '', children: filteredData };
        var root = d3.hierarchy(rootData);

        var treeLayout = d3.tree().nodeSize([28, 280]);
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
        var decisionZoom = d3.zoom().scaleExtent([0.001, 20])
            .on('zoom', function (event) { zoomLayer.attr('transform', event.transform); });
        svg.call(decisionZoom);
        installDiagramZoomControls(container, svg.node(), svg, decisionZoom, function () { return zoomLayer.node().getBBox(); });
        var decisionNavigation = createDiagramNavigation(container, svg.node(), {
            scores: scores, activate: function (node) { toggleDiagramBranch(node, dmUpdate); }
        });

        var g = zoomLayer.append('g');
        var nodeSeq = 0;
        var dmUpdateToken = 0;

        var RANK_EMOJIS = ['', '\uD83E\uDD47', '\uD83E\uDD48', '\uD83E\uDD49']; // 🥇🥈🥉

        function dmTheme(token, fallback) {
            // Keep the semantic token live so theme changes update existing SVG nodes
            // without resetting zoom, collapsed branches or the reading position.
            return 'var(' + token + ', ' + fallback + ')';
        }

        function dmNodeFill(d) {
            var code = d.data.code;
            if (code === '__root__') { return 'none'; }
            var rank = rankMap[code];
            if (rank === 1) { return dmTheme('--decision-rank-1-surface', '#F6C344'); }
            if (rank === 2) { return dmTheme('--decision-rank-2-surface', '#CBD5E1'); }
            if (rank === 3) { return dmTheme('--decision-rank-3-surface', '#C98958'); }
            if ((scores[code] || 0) > 0) {
                // Magnitude is encoded by the numeric label, not hue/opacity.
                return dmTheme('--decision-score-surface', '#DBEAFE');
            }
            return dmTheme('--decision-ancestor-surface', '#F1F5F9');
        }

        function dmNodeStroke(d) {
            var code = d.data.code;
            if (code === '__root__') { return 'none'; }
            if (rankMap[code]) { return dmTheme('--decision-rank-stroke', '#475569'); }
            if ((scores[code] || 0) > 0) { return dmTheme('--decision-score-stroke', '#1D4ED8'); }
            return dmTheme('--decision-ancestor-stroke', '#64748B');
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
            if (rankMap[code] === 1) { return dmTheme('--decision-rank-1-line', '#9A6A00'); }
            if (rankMap[code] === 2) { return dmTheme('--decision-rank-2-line', '#64748B'); }
            if (rankMap[code] === 3) { return dmTheme('--decision-rank-3-line', '#8A4B22'); }
            if ((scores[code] || 0) > 0) { return dmTheme('--decision-score-line', '#2563EB'); }
            return dmTheme('--decision-link', '#64748B');
        }

        function dmUpdate(source) {
            container.removeAttribute('data-transitions-complete');
            var currentToken = ++dmUpdateToken;
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
                    decisionNavigation.selectNode(d);
                    toggleDiagramBranch(d, dmUpdate);
                })
                .on('mousemove', function (event, d) { showTooltip(event, d.data, scores); })
                .on('mouseleave', hideTooltip);

            var nodeUpdate = nodeEnter.merge(node);
            decisionNavigation.update(diagramEntries(nodeUpdate));

            var nodeT = nodeUpdate.transition().duration(300)
                .attr('transform', function (d) { return 'translate(' + d.y + ',' + d.x + ')'; })
                .style('opacity', function (d) { return d.data.code === '__root__' ? 0 : 1; });

            nodeUpdate.select('circle')
                .attr('fill', dmNodeFill)
                .attr('stroke', dmNodeStroke);

            nodeUpdate.select('.dm-label')
                .text(function (d) {
                    if (d.data.code === '__root__') { return ''; }
                    var name = nameMap[d.data.code];
                    var label = name ? (d.data.code + ' \u2013 ' + name) : d.data.code;
                    var pct = scores[d.data.code];
                    if (hasScoreLabel(d.data.code, scores)) { label += ' ' + scoreText(d.data.code, pct); }
                    return label;
                })
                .attr('x', function (d) { return (d.children || d._children) ? -10 : 10; })
                .attr('text-anchor', function (d) { return (d.children || d._children) ? 'end' : 'start'; })
                .style('fill', function () { return dmTheme('--decision-map-text', '#1F2937'); })
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
            var linkT = linkUpdate.transition().duration(300).attr('d', diag);
            linkUpdate
                .attr('stroke', dmLinkStroke)
                .attr('stroke-width', dmLinkStrokeWidth);

            link.exit().transition().duration(300)
                .attr('d', function () {
                    var o = { x: source.x, y: source.y };
                    return diag({ source: o, target: o });
                })
                .remove();

            // Signal when ALL transitions (nodes + links) complete.
            // The version token prevents stale handlers from a prior dmUpdate()
            // from racing with a newer call that already cleared the attribute.
            var settle = function () {
                if (currentToken === dmUpdateToken) {
                    container.setAttribute('data-transitions-complete', 'true');
                }
            };
            Promise.all([nodeT.end(), linkT.end()])
                .then(settle)
                .catch(settle);

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
        } catch (e) {
            console.error('Decision map rendering failed:', e);
        }

        // ── Results table ────────────────────────────────────────────────────
        renderDecisionTable(container, allScored, nameMap, pathMap, levelMap, isLeafSet, rankMap);
        container.setAttribute('data-view-rendered', 'decision');
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
            '<strong>' + esc(t('views.ui.matches')) + '</strong>' +
            '<div class="form-check form-check-inline ms-2 mb-0">' +
                '<input class="form-check-input" type="checkbox" id="dmFilterLeaves">' +
                '<label class="form-check-label small" for="dmFilterLeaves">' + esc(t('views.ui.leaves')) + '</label>' +
            '</div>' +
            '<label class="small ms-2 mb-0">' + esc(t('views.ui.min')) + '&nbsp;' +
                '<input id="dmMinScore" type="number" class="form-control form-control-sm d-inline-block" ' +
                'style="width:70px" min="0" max="100" value="0">&nbsp;%' +
            '</label>';
        tableWrapper.appendChild(filterDiv);

        var table = document.createElement('table');
        table.className = 'table table-sm table-hover decision-table';
        table.innerHTML =
            '<thead><tr>' +
            '<th>#</th><th>' + esc(t('views.ui.code')) + '</th><th>' + esc(t('views.ui.name')) + '</th><th>' + esc(t('views.ui.score')) + '</th><th>' + esc(t('views.ui.path')) + '</th><th>' + esc(t('views.ui.level')) + '</th>' +
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
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td>' + rankEmoji + '</td>' +
                    '<td><strong>' + esc(code) + '</strong></td>' +
                    '<td>' + esc(nodeName) + '</td>' +
                    '<td><span class="decision-score-badge">' + esc(scoreText(code, pct)) + '</span></td>' +
                    '<td class="small text-muted">' + esc(path) + '</td>' +
                    '<td class="text-center">' + level + '</td>';
                tbody.appendChild(tr);
            });

            if (rows.length === 0) {
                var tr = document.createElement('tr');
                tr.innerHTML = '<td colspan="6" class="text-muted text-center">' + t('views.no.filter.match') + '</td>';
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

    // ── Canvas Tree Renderer (Phase 1) ────────────────────────────────────────
    /**
     * Count all descendant nodes in a taxonomy data tree.
     * @param {Object} node - A taxonomy node with optional `children` array.
     * @returns {number} Total node count including the node itself.
     */
    function countNodes(node) {
        if (!node) { return 0; }
        var count = 1;
        if (node.children) {
            for (var i = 0; i < node.children.length; i++) {
                count += countNodes(node.children[i]);
            }
        }
        return count;
    }

    /**
     * Render a collapsible tree diagram using Canvas 2D for high-performance
     * rendering with large node counts (> 500). Uses the same D3 layout logic
     * as renderTreeDiagram but draws to a <canvas> element instead of SVG DOM.
     *
     * @param {HTMLElement} container - The DOM element to render into (cleared first).
     * @param {Array}       data      - Array of root taxonomy nodes (each with `children`).
     * @param {Object|null} scores    - Map of node code → match percentage, or null.
     */
    function renderTreeCanvas(container, data, scores) {
        rememberDiagramFocus(container);
        container.removeAttribute('data-view-rendered');
        if (typeof d3 === 'undefined') {
            container.innerHTML = '<div class="alert alert-warning mt-2">' + t('views.d3.required') + '</div>';
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
        var NODE_RADIUS = 7;

        // Synthetic root
        var rootData = { code: '__root__', name: '', children: data };
        var root = d3.hierarchy(rootData);

        // Collapse depth >= TREE_INITIAL_DEPTH
        root.each(function (d) {
            if (d.depth >= TREE_INITIAL_DEPTH && d.children) {
                d._children = d.children;
                d.children = null;
            }
        });

        var treeLayout = d3.tree().nodeSize([28, 220]);
        var containerWidth = container.clientWidth || 800;

        // Create canvas element
        var canvas = document.createElement('canvas');
        canvas.width = containerWidth;
        canvas.height = 400;
        canvas.style.display = 'block';
        canvas.style.width = '100%';
        container.appendChild(canvas);
        // Mark container as using canvas renderer for export detection
        container._canvasRenderer = true;
        container._canvasData = data;
        container._canvasScores = scores;

        var ctx = canvas.getContext('2d');

        // Transform state for zoom/pan
        var transform = { x: 0, y: 0, k: 1 };

        // Store laid-out nodes for hit testing
        var layoutNodes = [];
        var layoutLinks = [];
        var canvasStatus = document.createElement('p');
        canvasStatus.className = 'tax-diagram-selection small';
        canvasStatus.setAttribute('role', 'status');
        canvasStatus.setAttribute('aria-live', 'polite');
        container.appendChild(canvasStatus);
        var canvasNavigation = createDiagramNavigation(container, canvas, {
            scores: scores, canvas: true,
            activate: function (node) { toggleDiagramBranch(node, performLayout); },
            change: function (node) {
                canvasStatus.textContent = diagramNodeLabel(node, scores, false);
                if (document.activeElement === canvas && canvasZoom) {
                    d3.select(canvas).call(canvasZoom.translateTo,
                        node.y + canvas._offsetX, node.x + canvas._offsetY,
                        [(container.clientWidth || 800) / 2, 200]);
                }
                draw();
            }
        });
        canvas.addEventListener('blur', draw);

        function performLayout() {
            treeLayout(root);
            layoutNodes = root.descendants();
            layoutLinks = root.links().filter(function (l) { return l.source.data.code !== '__root__'; });

            var xMin = Infinity, xMax = -Infinity, yMax = 0;
            layoutNodes.forEach(function (d) {
                if (d.x < xMin) { xMin = d.x; }
                if (d.x > xMax) { xMax = d.x; }
                if (d.y > yMax) { yMax = d.y; }
            });

            var svgW = Math.max(containerWidth, yMax + marginLeft + marginRight);
            var svgH = Math.max(400, xMax - xMin + marginTop + marginBottom);

            canvas.width = svgW * (window.devicePixelRatio || 1);
            canvas.height = svgH * (window.devicePixelRatio || 1);
            canvas.style.width = svgW + 'px';
            canvas.style.height = svgH + 'px';

            // Store offset for translating coordinates
            canvas._offsetX = marginLeft;
            canvas._offsetY = marginTop - xMin;

            canvasNavigation.update(layoutNodes.filter(function (node) { return node.data.code !== '__root__'; })
                .map(function (node) { return {node: node, element: canvas}; }));
            draw();
        }

        function draw() {
            var dpr = window.devicePixelRatio || 1;
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            ctx.clearRect(0, 0, canvas.width / dpr, canvas.height / dpr);

            ctx.save();
            ctx.translate(transform.x, transform.y);
            ctx.scale(transform.k, transform.k);

            var ox = canvas._offsetX || 0;
            var oy = canvas._offsetY || 0;

            // Draw links
            ctx.strokeStyle = '#ccc';
            ctx.lineWidth = 1.5;
            layoutLinks.forEach(function (l) {
                var sx = l.source.y + ox;
                var sy = l.source.x + oy;
                var tx = l.target.y + ox;
                var ty = l.target.x + oy;
                ctx.beginPath();
                ctx.moveTo(sx, sy);
                ctx.bezierCurveTo((sx + tx) / 2, sy, (sx + tx) / 2, ty, tx, ty);
                ctx.stroke();
            });

            // Draw nodes
            ctx.font = '12px Arial, Helvetica, sans-serif';
            layoutNodes.forEach(function (d) {
                if (d.data.code === '__root__') { return; }
                var nx = d.y + ox;
                var ny = d.x + oy;

                // Circle
                var fill = nodeColor(d.data.code, colorMap, scores);
                ctx.beginPath();
                ctx.arc(nx, ny, NODE_RADIUS, 0, 2 * Math.PI);
                ctx.fillStyle = fill;
                ctx.fill();
                ctx.strokeStyle = '#555';
                ctx.lineWidth = 1.5;
                ctx.stroke();

                if (canvasNavigation && canvasNavigation.current() === d && document.activeElement === canvas) {
                    ctx.beginPath();
                    ctx.arc(nx, ny, NODE_RADIUS + 5, 0, 2 * Math.PI);
                    ctx.strokeStyle = '#0d6efd';
                    ctx.lineWidth = 3;
                    ctx.stroke();
                }

                // Collapse indicator (+ inside circle for collapsed nodes)
                if (d._children && d._children.length) {
                    ctx.fillStyle = '#fff';
                    ctx.font = 'bold 10px Arial';
                    ctx.textAlign = 'center';
                    ctx.textBaseline = 'middle';
                    ctx.fillText('+', nx, ny);
                    ctx.font = '12px Arial, Helvetica, sans-serif';
                }

                // Label
                var label = d.data.name ? d.data.code + ' \u2013 ' + d.data.name : d.data.code;
                ctx.fillStyle = '#333';
                if (d.children || d._children) {
                    ctx.textAlign = 'right';
                    ctx.textBaseline = 'middle';
                    ctx.fillText(label, nx - 10, ny);
                } else {
                    ctx.textAlign = 'left';
                    ctx.textBaseline = 'middle';
                    ctx.fillText(label, nx + 10, ny);
                }

                // Score badge
                if (hasScoreLabel(d.data.code, scores)) {
                    var pct = scores[d.data.code];
                    var badgeText = scoreText(d.data.code, pct);
                    var badgeX = (d.children || d._children) ? nx - 10 - ctx.measureText(label).width - 4 : nx + 10 + ctx.measureText(label).width + 4;
                    ctx.fillStyle = 'rgba(0,128,0,0.75)';
                    var bw = ctx.measureText(badgeText).width + 6;
                    ctx.fillRect(badgeX - 1, ny - 7, bw, 14);
                    ctx.fillStyle = '#fff';
                    ctx.font = '10px Arial';
                    ctx.textAlign = 'left';
                    ctx.fillText(badgeText, badgeX + 2, ny + 3);
                    ctx.font = '12px Arial, Helvetica, sans-serif';
                }
            });

            ctx.restore();
        }

        // Hit testing: find the node under mouse coordinates
        function hitTest(mx, my) {
            var ox = canvas._offsetX || 0;
            var oy = canvas._offsetY || 0;
            // Invert the transform
            var ix = (mx - transform.x) / transform.k;
            var iy = (my - transform.y) / transform.k;

            for (var i = 0; i < layoutNodes.length; i++) {
                var d = layoutNodes[i];
                if (d.data.code === '__root__') { continue; }
                var nx = d.y + ox;
                var ny = d.x + oy;
                var dx = ix - nx;
                var dy = iy - ny;
                if (dx * dx + dy * dy <= (NODE_RADIUS + 4) * (NODE_RADIUS + 4)) {
                    return d;
                }
            }
            return null;
        }

        // Click handler: toggle expand/collapse
        canvas.addEventListener('click', function (event) {
            var rect = canvas.getBoundingClientRect();
            var mx = event.clientX - rect.left;
            var my = event.clientY - rect.top;
            var hit = hitTest(mx, my);
            if (hit) {
                canvasNavigation.selectNode(hit);
                toggleDiagramBranch(hit, performLayout);
            }
        });

        // Mousemove handler: show tooltip
        canvas.addEventListener('mousemove', function (event) {
            var rect = canvas.getBoundingClientRect();
            var mx = event.clientX - rect.left;
            var my = event.clientY - rect.top;
            var hit = hitTest(mx, my);
            if (hit) {
                canvas.style.cursor = (hit.children || hit._children) ? 'pointer' : 'default';
                showTooltip(event, hit.data, scores);
            } else {
                canvas.style.cursor = 'default';
                hideTooltip();
            }
        });

        canvas.addEventListener('mouseleave', hideTooltip);

        // d3.zoom() on canvas for pan/zoom
        var canvasZoom = d3.zoom().scaleExtent([0.001, 20])
            .on('zoom', function (event) {
                transform = { x: event.transform.x, y: event.transform.y, k: event.transform.k };
                draw();
            });
        var canvasSelection = d3.select(canvas).call(canvasZoom);
        installDiagramZoomControls(container, canvas, canvasSelection, canvasZoom, function () {
            var dpr = window.devicePixelRatio || 1;
            return {x: 0, y: 0, width: canvas.width / dpr, height: canvas.height / dpr};
        }, true);

        // Initial layout and draw
        root.x0 = 0;
        root.y0 = 0;
        performLayout();

        // Responsive resize
        var obs = new ResizeObserver(function () {
            var newW = container.clientWidth || 800;
            if (newW > containerWidth) {
                performLayout();
            }
        });
        obs.observe(container);
        container._taxObserver = obs;
        container.setAttribute('data-view-rendered', 'tree-canvas');
    }

    // ── SVG-on-Demand Export (Phase 2) ─────────────────────────────────────────
    /**
     * Build an off-screen SVG DOM element from taxonomy data using the same
     * D3 tree layout. Used for clean vector export when the Canvas renderer
     * is active. Also works as a standalone export generator.
     *
     * @param {Array}       data    - Array of root taxonomy nodes.
     * @param {Object|null} scores  - Map of node code → match percentage, or null.
     * @param {Object}      options - { expandAll: boolean }
     * @returns {SVGSVGElement} The generated SVG element (not attached to DOM).
     */
    function buildExportSVG(data, scores, options) {
        options = options || {};
        var colorMap = buildColorMap(data);
        var marginLeft = 40;
        var marginRight = 300;
        var marginTop = 20;
        var marginBottom = 20;
        var CHAR_WIDTH_ESTIMATE = 7;  // approx px per character for 12px Arial
        var BADGE_OFFSET = 20;        // extra px gap between label end and badge

        var rootData = { code: '__root__', name: '', children: data };
        var root = d3.hierarchy(rootData);

        // If expandAll is not set, apply the same initial collapse
        if (!options.expandAll) {
            root.each(function (d) {
                if (d.depth >= TREE_INITIAL_DEPTH && d.children) {
                    d._children = d.children;
                    d.children = null;
                }
            });
        }

        var treeLayout = d3.tree().nodeSize([28, 220]);
        treeLayout(root);

        var nodes = root.descendants();
        var links = root.links().filter(function (l) { return l.source.data.code !== '__root__'; });

        var xMin = Infinity, xMax = -Infinity, yMax = 0;
        nodes.forEach(function (d) {
            if (d.x < xMin) { xMin = d.x; }
            if (d.x > xMax) { xMax = d.x; }
            if (d.y > yMax) { yMax = d.y; }
        });

        var svgW = Math.max(800, yMax + marginLeft + marginRight);
        var svgH = Math.max(400, xMax - xMin + marginTop + marginBottom);

        // Create off-screen SVG
        var svgNS = 'http://www.w3.org/2000/svg';
        var svg = document.createElementNS(svgNS, 'svg');
        svg.setAttribute('xmlns', svgNS);
        svg.setAttribute('width', svgW);
        svg.setAttribute('height', svgH);
        svg.setAttribute('viewBox', '0 0 ' + svgW + ' ' + svgH);

        // Background
        var bg = document.createElementNS(svgNS, 'rect');
        bg.setAttribute('width', svgW);
        bg.setAttribute('height', svgH);
        bg.setAttribute('fill', '#ffffff');
        svg.appendChild(bg);

        // Style
        var style = document.createElementNS(svgNS, 'style');
        style.textContent = [
            'text { font-family: Arial, Helvetica, sans-serif; font-size: 12px; }',
            '.tv-link { fill: none; stroke: #ccc; stroke-width: 1.5; }'
        ].join('\n');
        svg.appendChild(style);

        var g = document.createElementNS(svgNS, 'g');
        g.setAttribute('transform', 'translate(' + marginLeft + ',' + (marginTop - xMin) + ')');
        svg.appendChild(g);

        // Draw links using cubic Bézier curves (same as d3.linkHorizontal)
        links.forEach(function (l) {
            var path = document.createElementNS(svgNS, 'path');
            var sx = l.source.y, sy = l.source.x;
            var tx = l.target.y, ty = l.target.x;
            var mx = (sx + tx) / 2;
            path.setAttribute('d', 'M' + sx + ',' + sy + ' C' + mx + ',' + sy + ' ' + mx + ',' + ty + ' ' + tx + ',' + ty);
            path.setAttribute('class', 'tv-link');
            g.appendChild(path);
        });

        // Draw nodes
        nodes.forEach(function (d) {
            if (d.data.code === '__root__') { return; }
            var nodeG = document.createElementNS(svgNS, 'g');
            nodeG.setAttribute('transform', 'translate(' + d.y + ',' + d.x + ')');

            var circle = document.createElementNS(svgNS, 'circle');
            circle.setAttribute('r', '7');
            circle.setAttribute('fill', nodeColor(d.data.code, colorMap, scores));
            circle.setAttribute('stroke', '#555');
            circle.setAttribute('stroke-width', '1.5');
            nodeG.appendChild(circle);

            var text = document.createElementNS(svgNS, 'text');
            text.setAttribute('dy', '0.31em');
            var label = d.data.name ? d.data.code + ' \u2013 ' + d.data.name : d.data.code;
            text.textContent = label;
            if (d.children || d._children) {
                text.setAttribute('x', '-10');
                text.setAttribute('text-anchor', 'end');
            } else {
                text.setAttribute('x', '10');
                text.setAttribute('text-anchor', 'start');
            }
            nodeG.appendChild(text);

            // Score badge
            if (hasScoreLabel(d.data.code, scores)) {
                var badge = document.createElementNS(svgNS, 'text');
                badge.setAttribute('dy', '0.31em');
                badge.setAttribute('x', (d.children || d._children) ? '-10' : '10');
                badge.setAttribute('dx', (d.children || d._children) ? ('-' + (label.length * CHAR_WIDTH_ESTIMATE + BADGE_OFFSET)) : '' + (label.length * CHAR_WIDTH_ESTIMATE + BADGE_OFFSET));
                badge.setAttribute('text-anchor', 'start');
                badge.setAttribute('fill', 'green');
                badge.setAttribute('font-size', '10px');
                badge.setAttribute('font-weight', 'bold');
                badge.textContent = scoreText(d.data.code, scores[d.data.code]);
                nodeG.appendChild(badge);
            }

            g.appendChild(nodeG);
        });

        return svg;
    }

    // ── DOT (Graphviz) Export (Phase 4) ────────────────────────────────────────
    /**
     * Generate a DOT (Graphviz) representation of the taxonomy tree.
     * @param {Array}       data   - Array of root taxonomy nodes.
     * @param {Object|null} scores - Map of node code → match percentage, or null.
     * @returns {string} DOT source code.
     */
    function buildDotExport(data, scores) {
        var lines = ['digraph Taxonomy {'];
        lines.push('    rankdir=LR;');
        lines.push('    node [shape=box, style="rounded,filled", fontname="Arial", fontsize=11];');
        lines.push('    edge [color="#999999"];');
        lines.push('');

        function dotId(code) {
            return '"' + code.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
        }

        function dotEscape(text) {
            return text.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
        }

        function walk(node) {
            var label = node.name ? dotEscape(node.code) + '\\n' + dotEscape(node.name) : dotEscape(node.code);
            var fillColor = '#e8e8e8';
            if (scores && scores[node.code] > 0) {
                var pct = Math.min(scores[node.code] / 100, 1);
                var r = Math.round(255 * (1 - pct));
                var g = Math.round(200 + 55 * pct);
                var b = Math.round(255 * (1 - pct));
                fillColor = '#' + ('0' + r.toString(16)).slice(-2) + ('0' + g.toString(16)).slice(-2) + ('0' + b.toString(16)).slice(-2);
            }
            lines.push('    ' + dotId(node.code) + ' [label="' + label + '", fillcolor="' + fillColor + '"];');
            if (node.children) {
                node.children.forEach(function (child) {
                    lines.push('    ' + dotId(node.code) + ' -> ' + dotId(child.code) + ';');
                    walk(child);
                });
            }
        }

        data.forEach(function (root) { walk(root); });
        lines.push('}');
        return lines.join('\n');
    }

    /**
     * Generate a Mermaid tree diagram of the taxonomy hierarchy.
     * This generates the full parent-child tree (not the architecture view).
     * @param {Array}       data   - Array of root taxonomy nodes.
     * @param {Object|null} scores - Map of node code → match percentage, or null.
     * @returns {string} Mermaid diagram source.
     */
    function buildMermaidTreeExport(data, scores) {
        var lines = ['graph LR'];

        function mermaidId(code) {
            return code.replace(/[^a-zA-Z0-9_]/g, '_');
        }

        function mermaidLabel(node) {
            var label = node.name ? node.code + ' ' + node.name : node.code;
            if (hasScoreLabel(node.code, scores)) {
                label += ' [' + scoreText(node.code, scores[node.code]) + ']';
            }
            return label.replace(/"/g, '&quot;');
        }

        function walk(node) {
            if (node.children) {
                node.children.forEach(function (child) {
                    lines.push('    ' + mermaidId(node.code) + '["' + mermaidLabel(node) + '"] --> ' + mermaidId(child.code) + '["' + mermaidLabel(child) + '"]');
                    walk(child);
                });
            }
        }

        data.forEach(function (root) {
            // If root has no children, render it as a standalone node
            if (!root.children || root.children.length === 0) {
                lines.push('    ' + mermaidId(root.code) + '["' + mermaidLabel(root) + '"]');
            }
            walk(root);
        });

        return lines.join('\n');
    }

    // ── Export ─────────────────────────────────────────────────────────────────
    window.TaxonomyViews = {
        renderSunburst: renderSunburst,
        filterZeroRelevance: filterZeroRelevance,
        renderTreeDiagram: renderTreeDiagram,
        renderTreeCanvas: renderTreeCanvas,
        renderDecisionMap: renderDecisionMap,
        countNodes: countNodes,
        buildExportSVG: buildExportSVG,
        buildDotExport: buildDotExport,
        buildMermaidTreeExport: buildMermaidTreeExport
    };

})();
