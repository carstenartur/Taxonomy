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

    function isTaxonomyContainer(node) {
        return !!(window.TaxonomyUtils && window.TaxonomyUtils.isTaxonomyContainer
            && window.TaxonomyUtils.isTaxonomyContainer(node));
    }

    function contentRoots(data) {
        return window.TaxonomyUtils && window.TaxonomyUtils.contentRoots
            ? window.TaxonomyUtils.contentRoots(data) : (data || []);
    }

    function containerLabel(area, scores) {
        var label = area.code + (area.name ? ' – ' + area.name : '');
        if (scores && Object.prototype.hasOwnProperty.call(scores, area.code) && Number.isFinite(scores[area.code])) {
            label += ' · ' + t('browse.container.relevance', scores[area.code]);
        }
        return label;
    }

    /** Area headings are outside the node hierarchy and never receive node actions or ranks. */
    function appendContainerHeadings(container, data, scores) {
        var areas = (data || []).filter(isTaxonomyContainer);
        if (!areas.length) return;
        var labels = document.createElement('div');
        labels.className = 'tax-container-labels';
        areas.forEach(function (area) {
            var label = document.createElement('div');
            var heading = document.createElement('h3');
            heading.className = 'tax-container-heading h6 mt-3 mb-2';
            heading.setAttribute('data-taxonomy-container', area.code);
            heading.textContent = area.code + (area.name ? ' – ' + area.name : '');
            if (scores && Object.prototype.hasOwnProperty.call(scores, area.code) && Number.isFinite(scores[area.code])) {
                var score = document.createElement('span');
                score.className = 'tax-container-score small ms-2';
                score.textContent = t('browse.container.relevance', scores[area.code]);
                heading.appendChild(score);
            }
            label.appendChild(heading);
            if (!area.children || !area.children.length) {
                var empty = document.createElement('p');
                empty.className = 'text-muted small';
                empty.textContent = t('browse.container.empty');
                label.appendChild(empty);
            }
            labels.appendChild(label);
        });
        container.appendChild(labels);
    }

    function decisionMatches(data, scores) {
        var entries = new Set();
        function collect(node) {
            if (!isTaxonomyContainer(node)) { entries.add(node.code); }
            (node.children || []).forEach(collect);
        }
        (data || []).forEach(collect);
        return Object.entries(scores || {})
            .filter(function (entry) { return Number.isFinite(entry[1]) && entry[1] > 0 && entries.has(entry[0]); })
            .sort(function (a, b) { return b[1] - a[1]; });
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
        appendContainerHeadings(container, data, scores);
        var W = container.clientWidth || 500;
        var size = Math.min(W, MAX_SUNBURST_SIZE);
        var radius = size / 2;

        // Synthetic root to unify the 8 root taxonomies
        var rootData = { code: '__root__', name: 'Taxonomy', children: contentRoots(data) };
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

        appendContainerHeadings(container, data, scores);
        var colorMap = buildColorMap(data);
        var marginLeft = 40;
        var marginRight = 300;
        var marginTop = 20;
        var marginBottom = 20;

        // Synthetic root
        var rootData = { code: '__root__', name: '', children: contentRoots(data) };
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
    /** Wrap using the same measured font used by SVG. Even unbroken names remain complete. */
    function decisionLines(text, measure, width) {
        var lines = [], line = '';
        String(text || '').split(/\s+/).forEach(function (word) {
            var candidate = line ? line + ' ' + word : word;
            if (measure(candidate) <= width) { line = candidate; return; }
            if (line) { lines.push(line); line = ''; }
            if (measure(word) <= width) { line = word; return; }
            Array.from(word).forEach(function (character) {
                if (measure(line + character) > width && line) { lines.push(line); line = character; }
                else { line += character; }
            });
        });
        if (line || !lines.length) { lines.push(line); }
        return lines;
    }

    function decisionMeasure() {
        var canvas = document.createElement('canvas');
        var context = canvas.getContext('2d');
        context.font = 'bold 14px Arial, Helvetica, sans-serif';
        return function (value) { return context.measureText(value).width; };
    }

    /**
     * The hierarchy remains unchanged. Each subtree reserves all occupied node/label/action
     * bounds; area captions and their wrapped lines occupy their own band above the subtree.
     * Screen, fit and print all consume this one measured layout.
     */
    function layoutDecisionPanels(root, scores, areas, measure, captionAreas, metrics) {
        metrics = metrics || {};
        var textWidth = metrics.textWidth || 226;
        var cardWidth = metrics.cardWidth || 262;
        var columnPitch = metrics.columnPitch || 304;
        var panels = [], byCode = {}, nodes = [], cursor = 16, width = 320;
        (root.children || []).forEach(function (branch) {
            var area = areas[branch.data.code] || branch.data;
            var panel = byCode[area.code];
            if (!panel) { panel = {area: area, roots: [], nodes: []}; byCode[area.code] = panel; panels.push(panel); }
            panel.roots.push(branch);
        });
        (captionAreas || []).forEach(function (area) {
            if (!byCode[area.code]) {
                var panel = {area: area, roots: [], nodes: []};
                byCode[area.code] = panel; panels.push(panel);
            }
        });
        if (captionAreas) {
            var order = captionAreas.map(function (area) { return area.code; });
            panels.sort(function (a, b) { return order.indexOf(a.area.code) - order.indexOf(b.area.code); });
        }
        panels.forEach(function (panel) {
            panel.top = cursor;
            panel.lines = decisionLines(containerLabel(panel.area, scores), measure, 900);
            panel.captionHeight = panel.lines.length * 20 + 24;
            cursor += panel.captionHeight;
            function size(node) {
                var label = node.data.code + (node.data.name ? ' – ' + node.data.name : '');
                if (hasScoreLabel(node.data.code, scores)) { label += ' · ' + scoreText(node.data.code, scores[node.data.code]); }
                node.lines = decisionLines(label, measure, textWidth);
                // Bold measurement conservatively bounds regular labels; action and stroke get their own padding.
                node.contextHeight = node.parent && node.parent.data.code !== '__root__'
                    ? Math.max(36, decisionLines(t('views.diagram.printParent', node.parent.data.code, 999999), measure, textWidth).length * 20 + 16) : 36;
                node.nodeHeight = Math.max(48, node.lines.length * 20 + node.contextHeight);
                node.subtreeHeight = Math.max(node.nodeHeight, (node.children || []).reduce(function (sum, child) { return sum + size(child) + 16; }, 0) - (node.children ? 16 : 0));
                return node.subtreeHeight;
            }
            function place(node, top) {
                node.y = 32 + (node.depth - 1) * columnPitch;
                node.x = top + node.subtreeHeight / 2;
                node.box = {left: node.y - 10, right: node.y + cardWidth - 10, top: node.x - node.nodeHeight / 2, bottom: node.x + node.nodeHeight / 2};
                panel.nodes.push(node); nodes.push(node); width = Math.max(width, node.box.right + 16);
                var childTop = top;
                (node.children || []).forEach(function (child) { place(child, childTop); childTop += child.subtreeHeight + 16; });
            }
            panel.roots.forEach(function (branch) { size(branch); place(branch, cursor); cursor += branch.subtreeHeight + 16; });
            panel.bottom = cursor;
            panel.left = 16;
            panel.right = Math.max(288, panel.lines.reduce(function (max, line) { return Math.max(max, measure(line)); }, 0) + 32,
                panel.nodes.reduce(function (max, node) { return Math.max(max, node.box.right + 16); }, 0));
            width = Math.max(width, panel.right);
            cursor += 40;
        });
        var knownCodes = new Set(nodes.map(function (node) { return node.data.code; }));
        var rankMap = {};
        Object.keys(scores).filter(function (code) { return knownCodes.has(code) && Number.isFinite(scores[code]) && scores[code] > 0; })
            .sort(function (a, b) { return scores[b] - scores[a]; }).slice(0, 3).forEach(function (code, index) { rankMap[code] = index + 1; });
        return {panels: panels, nodes: nodes, width: width, height: cursor, scores: scores, rankMap: rankMap, measure: measure, textWidth: textWidth, columnsPerPage: metrics.columnsPerPage || 3};
    }

    function showDecisionReason(node, scores, trigger) {
        hideTooltip();
        var old = document.getElementById('decisionReasonDialog');
        if (old) { old.remove(); }
        var dialog = document.createElement('dialog');
        dialog.id = 'decisionReasonDialog'; dialog.className = 'tax-reason-dialog';
        dialog.setAttribute('aria-labelledby', 'decisionReasonTitle');
        var heading = document.createElement('div'); heading.className = 'tax-reason-heading';
        var title = document.createElement('h2'); title.id = 'decisionReasonTitle';
        title.textContent = node.code + (node.name ? ' – ' + node.name : '');
        var close = document.createElement('button'); close.type = 'button';
        close.className = 'btn btn-sm btn-outline-secondary'; close.textContent = t('views.diagram.close');
        close.addEventListener('click', function () { dialog.close(); });
        heading.append(title, close); dialog.appendChild(heading);
        if (hasScoreLabel(node.code, scores)) {
            var score = document.createElement('p'); score.textContent = scoreText(node.code, scores[node.code]); dialog.appendChild(score);
        }
        var reasons = window.TaxonomyState && window.TaxonomyState.currentReasons || {};
        [[t('views.diagram.catalogueDescription'), node.description || t('views.diagram.descriptionMissing')],
            [t('views.diagram.analysisReason'), reasons[node.code] || t('views.diagram.reasonMissing')]].forEach(function (section) {
            var label = document.createElement('h3'); label.className = 'h6'; label.textContent = section[0];
            var content = document.createElement('p'); content.className = 'tax-reason-text'; content.textContent = section[1];
            dialog.append(label, content);
        });
        dialog.addEventListener('close', function () { dialog.remove(); if (trigger && trigger.isConnected) { trigger.focus(); } }, {once: true});
        document.body.appendChild(dialog); dialog.showModal();
    }

    function decisionSvgElement(doc, parent, tag, attributes, value) {
        var element = doc.createElementNS('http://www.w3.org/2000/svg', tag);
        Object.keys(attributes || {}).forEach(function (key) { element.setAttribute(key, attributes[key]); });
        if (value !== undefined) { element.textContent = value; }
        parent.appendChild(element); return element;
    }

    /** Each node fits one landscape page at 14px type, with its parent code as continuation context. */
    function buildDecisionPrintPages(layout, doc) {
        var pages = [];
        layout.panels.forEach(function (panel) {
            var batches = [];
            var headerLines = panel.lines;
            var rootLines = [];
            panel.roots.forEach(function (root) {
                rootLines = rootLines.concat(decisionLines(t('views.diagram.printRoots', root.data.code) + ' · ' + t('views.diagram.printContinue', 999999, 999999), layout.measure, 900));
            });
            // A short overview belongs in the page header; only extensive root lists
            // need dedicated overview continuation sheets.
            var inlineOverview = rootLines.length > 0 && rootLines.length <= 3;
            var overviewHeight = inlineOverview ? (rootLines.length + 1) * 20 + 6 : 0;
            var bandHeight = Math.max(160, 640 - (headerLines.length > 9 ? 62 : headerLines.length * 20 + 42) - overviewHeight);
            var maxDepth = panel.nodes.reduce(function (max, node) { return Math.max(max, node.depth); }, 1);
            for (var depth = 1; depth <= maxDepth; depth += layout.columnsPerPage) {
                var candidates = panel.nodes.filter(function (node) { return node.depth >= depth && node.depth < depth + layout.columnsPerPage; })
                    .sort(function (a, b) { return a.box.top - b.box.top; });
                // A pathological long name can exceed a page by itself. Keep all its text
                // on continuation fragments instead of scaling the entire taxonomy down.
                candidates = candidates.reduce(function (result, node) {
                    var lineLimit = Math.max(1, Math.floor((bandHeight - node.contextHeight) / 20));
                    if (node.nodeHeight <= bandHeight) { result.push(node); return result; }
                    for (var line = 0; line < node.lines.length; line += lineLimit) {
                        var fragment = Object.assign({}, node);
                        fragment.lines = node.lines.slice(line, line + lineLimit);
                        fragment.nodeHeight = fragment.lines.length * 20 + node.contextHeight;
                        fragment.fragment = line > 0;
                        fragment.box = Object.assign({}, node.box, {top: node.box.top + line * 20, bottom: node.box.top + line * 20 + fragment.nodeHeight});
                        fragment.x = fragment.box.top + fragment.nodeHeight / 2;
                        result.push(fragment);
                    }
                    return result;
                }, []);
                // Fragment expansion changes the occupied ordering: a child's original
                // top may precede its parent's later fragments. Re-sort the full set.
                candidates.sort(function (a, b) { return a.box.top - b.box.top; });
                var batch = [], top = 0, bottom = 0;
                candidates.forEach(function (node) {
                    var nextTop = batch.length ? Math.min(top, node.box.top) : node.box.top;
                    if (batch.length && Math.max(bottom, node.box.bottom) - nextTop > bandHeight) { batches.push({nodes: batch, depth: depth, top: top, bottom: bottom}); batch = []; }
                    if (!batch.length) { top = node.box.top; bottom = node.box.bottom; }
                    batch.push(node); top = Math.min(top, node.box.top); bottom = Math.max(bottom, node.box.bottom);
                });
                if (batch.length) { batches.push({nodes: batch, depth: depth, top: top, bottom: bottom}); }
            }
            // Recorded area relevance is caption evidence, never a fabricated catalogue node.
            if (!panel.nodes.length) { batches.push({nodes: [], depth: 1, top: 0, bottom: 0}); }
            // Put root-bearing pages first; the overview and parent references identify
            // exact continuation destinations without modifying the measured screen layout.
            batches.sort(function (a, b) {
                var aRoot = a.nodes.some(function (node) { return panel.roots.indexOf(node) >= 0 || panel.roots.some(function (root) { return root.data.code === node.data.code; }); });
                var bRoot = b.nodes.some(function (node) { return panel.roots.indexOf(node) >= 0 || panel.roots.some(function (root) { return root.data.code === node.data.code; }); });
                return Number(bRoot) - Number(aRoot) || a.depth - b.depth || a.top - b.top;
            });
            var overviewCount = inlineOverview ? 0 : Math.ceil(rootLines.length / 28);
            var captionCount = headerLines.length > 9 ? Math.ceil(headerLines.length / 28) : 0;
            var prefixPages = overviewCount + captionCount;
            var totalPages = prefixPages + batches.length;
            var nodePage = {};
            batches.forEach(function (batch, index) { batch.nodes.forEach(function (node) { if (!nodePage[node.data.code]) { nodePage[node.data.code] = prefixPages + index + 1; } }); });
            rootLines = [];
            panel.roots.forEach(function (root) {
                rootLines = rootLines.concat(decisionLines(t('views.diagram.printRoots', root.data.code) + ' · ' + t('views.diagram.printContinue', nodePage[root.data.code], totalPages), layout.measure, 900));
            });
            for (var overview = 0; overview < overviewCount; overview++) {
                var overviewPage = doc.createElement('section'); overviewPage.className = 'decision-print-page';
                overviewPage.setAttribute('data-taxonomy-panel', panel.area.code);
                var overviewHeading = doc.createElement('h2');
                overviewHeading.textContent = panel.area.code + ' · ' + t('views.diagram.printOverview') + ' · ' + t('views.diagram.printContinue', overview + 1, totalPages);
                overviewPage.appendChild(overviewHeading);
                var overviewSvg = decisionSvgElement(doc, overviewPage, 'svg', {viewBox: '0 0 960 600', width: 960, height: 600});
                rootLines.slice(overview * 28, (overview + 1) * 28).forEach(function (line, index) {
                    decisionSvgElement(doc, overviewSvg, 'text', {x: 12, y: 20 + index * 20, fill: '#111827', 'font-size': 14, 'font-family': 'Arial, Helvetica, sans-serif'}, line);
                });
                doc.body.appendChild(overviewPage); pages.push(overviewPage);
            }
            if (headerLines.length > 9) {
                for (var captionLine = 0; captionLine < headerLines.length; captionLine += 28) {
                    var captionPage = doc.createElement('section'); captionPage.className = 'decision-print-page';
                    captionPage.setAttribute('data-taxonomy-panel', panel.area.code);
                    var captionTitle = doc.createElement('h2'); captionTitle.textContent = panel.area.code + ' · ' + t('views.diagram.printContinue', overviewCount + Math.floor(captionLine / 28) + 1, totalPages);
                    captionPage.appendChild(captionTitle);
                    var captionSvg = decisionSvgElement(doc, captionPage, 'svg', {viewBox: '0 0 960 600', width: 960, height: 600});
                    headerLines.slice(captionLine, captionLine + 28).forEach(function (line, index) {
                        decisionSvgElement(doc, captionSvg, 'text', {x: 12, y: 20 + index * 20, fill: '#111827', 'font-size': 14, 'font-family': 'Arial, Helvetica, sans-serif'}, line);
                    });
                    doc.body.appendChild(captionPage); pages.push(captionPage);
                }
            }
            batches.forEach(function (batch, index) {
                var page = doc.createElement('section'); page.className = 'decision-print-page';
                page.setAttribute('data-taxonomy-panel', panel.area.code);
                var heading = doc.createElement('h2'); heading.textContent = headerLines.length > 9 ? panel.area.code : containerLabel(panel.area, layout.scores);
                // Very long area headings wrap in a bounded header, rather than consuming the diagram page.
                heading.style.cssText = 'font-size:14px;line-height:20px;max-width:900px;margin:0 0 6px;overflow-wrap:anywhere';
                page.appendChild(heading);
                var continuation = doc.createElement('p'); continuation.textContent = t('views.diagram.printContinue', prefixPages + index + 1, totalPages) + (batch.nodes.length ? ' · ' + t('views.diagram.printContext') : '');
                continuation.style.cssText = 'font-size:14px;line-height:20px;margin:0 0 8px'; page.appendChild(continuation);
                if (inlineOverview) {
                    var rootOverview = doc.createElement('p');
                    rootOverview.style.cssText = 'font-size:14px;line-height:20px;white-space:pre-wrap;max-width:900px;margin:0 0 6px';
                    rootOverview.textContent = t('views.diagram.printOverview') + '\n' + rootLines.join('\n');
                    page.appendChild(rootOverview);
                }
                var offsetX = batch.nodes.length ? 16 - Math.min.apply(null, batch.nodes.map(function (node) { return node.box.left; })) : 16, offsetY = 12 - batch.top;
                var pageWidth = batch.nodes.length ? Math.max(280, Math.max.apply(null, batch.nodes.map(function (node) { return node.box.right + offsetX + 12; }))) : 960;
                var pageHeight = batch.bottom - batch.top + 24;
                var svg = decisionSvgElement(doc, page, 'svg', {xmlns: 'http://www.w3.org/2000/svg', viewBox: '0 0 ' + pageWidth + ' ' + pageHeight,
                    width: pageWidth, height: pageHeight, role: 'img', 'aria-label': heading.textContent});
                var included = new Set(batch.nodes);
                batch.nodes.forEach(function (node) {
                    if (included.has(node.parent)) {
                        decisionSvgElement(doc, svg, 'path', {d: 'M' + (node.parent.box.right + offsetX) + ',' + (node.parent.x + offsetY) + 'H' + (node.y - 10 + offsetX) + 'V' + (node.x + offsetY),
                            fill: 'none', stroke: '#64748b', 'stroke-width': decisionPrintLinkWidth(node, layout.scores, layout.rankMap)});
                    } else if (node.parent && node.parent.data.code !== '__root__') {
                        // A continued edge keeps its original weight beside the page reference.
                        decisionSvgElement(doc, svg, 'path', {d: 'M' + (node.y + offsetX - 20) + ',' + (node.x + offsetY) + 'H' + (node.y + offsetX - 10),
                            'data-parent-page': nodePage[node.parent.data.code], fill: 'none', stroke: '#64748b',
                            'stroke-width': decisionPrintLinkWidth(node, layout.scores, layout.rankMap)});
                    }
                });
                batch.nodes.forEach(function (node) {
                    var box = {left: node.box.left + offsetX, right: node.box.right + offsetX, top: node.box.top + offsetY, bottom: node.box.bottom + offsetY};
                    var group = decisionSvgElement(doc, svg, 'g', {'data-print-node': node.fragment ? '' : node.data.code, 'data-print-fragment': node.data.code, 'data-occupied-bounds': JSON.stringify(box)});
                    decisionSvgElement(doc, group, 'rect', {class: 'decision-print-card', x: box.left + 1, y: box.top + 1,
                        width: box.right - box.left - 2, height: box.bottom - box.top - 2, rx: 6, fill: '#FFFFFF', stroke: '#334155', 'stroke-width': 2});
                    decisionSvgElement(doc, group, 'circle', {cx: node.y + offsetX, cy: node.x + offsetY, r: 7, fill: '#fff', stroke: '#334155', 'stroke-width': 1.5});
                    node.lines.forEach(function (line, lineIndex) {
                        decisionSvgElement(doc, group, 'text', {x: node.y + offsetX + 12, y: box.top + 16 + lineIndex * 20, fill: '#111827', 'font-size': 14, 'font-family': 'Arial, Helvetica, sans-serif'}, line);
                    });
                    if (node.parent && node.parent.data.code !== '__root__') {
                        decisionLines(t('views.diagram.printParent', node.parent.data.code, nodePage[node.parent.data.code]), layout.measure, layout.textWidth).forEach(function (line, index) {
                            decisionSvgElement(doc, group, 'text', {x: node.y + offsetX + 12, y: box.bottom - node.contextHeight + 20 + index * 20, fill: '#334155', 'font-size': 14, 'font-family': 'Arial, Helvetica, sans-serif'}, line);
                        });
                    }
                });
                doc.body.appendChild(page); pages.push(page);
            });
        });
        return pages;
    }

    function decisionPrintLinkWidth(node, scores, rankMap) {
        if (rankMap[node.data.code]) { return 4; }
        var value = scores[node.data.code] || 0;
        return value > 0 ? Math.max(2, Math.min(4, 2 + value / 50)) : 1;
    }

    function printDecisionDiagram(layout, container) {
        // Open synchronously in the user gesture so normal popup policies can allow it.
        var popup = window.open('', '_blank');
        if (!popup) {
            var message = container.querySelector('.decision-print-status');
            if (!message) { message = document.createElement('p'); message.className = 'decision-print-status'; message.setAttribute('role', 'status'); container.appendChild(message); }
            message.textContent = t('views.diagram.printBlocked'); return;
        }
        var doc = popup.document;
        doc.documentElement.lang = document.documentElement.lang || 'en';
        var charset = doc.createElement('meta'); charset.setAttribute('charset', 'utf-8');
        doc.head.appendChild(charset);
        doc.title = t('views.diagram.printTitle');
        var style = doc.createElement('style');
        style.textContent = '@page{size:A4 landscape;margin:10mm}html,body{margin:0;background:#fff;color:#111827;font-family:Arial,Helvetica,sans-serif}.decision-print-page{box-sizing:border-box;width:1040px;break-after:page;page-break-after:always;padding:0}.decision-print-page:last-child{break-after:auto;page-break-after:auto}.decision-print-page svg{display:block;max-width:100%;height:auto}h2{font-weight:600}@media screen{body{padding:20px}.decision-print-page{border-bottom:1px solid #ccc;margin-bottom:24px}}';
        doc.head.appendChild(style);
        buildDecisionPrintPages(layout, doc);
        popup.addEventListener('afterprint', function () { popup.close(); }, {once: true});
        popup.focus();
        // Let the new DOM and fonts settle; cancellation affects only this disposable window.
        popup.requestAnimationFrame(function () { popup.requestAnimationFrame(function () { if (!popup.closed) { popup.print(); } }); });
    }

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
        var nameMap = {}, pathMap = {}, levelMap = {}, isLeafSet = new Set(), containerCodes = new Set();

        function walkNodes(node, ancestors, level) {
            if (isTaxonomyContainer(node)) { containerCodes.add(node.code); }
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
            var selfHot = !isTaxonomyContainer(node) && hasScoreLabel(node.code, scores);
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
        var allScored = decisionMatches(data, scores);

        var top3Codes = allScored.slice(0, 3).map(function (e) { return e[0]; });
        var rankMap = {};
        top3Codes.forEach(function (code, i) { rankMap[code] = i + 1; });

        // ── Stats summary ────────────────────────────────────────────────────
        var totalNodes = Object.keys(nameMap).length - containerCodes.size;
        var scoredCount = allScored.length;
        var affectedRootsSet = new Set(
            allScored.map(function (e) { return pathMap[e[0]] && pathMap[e[0]][0]; }).filter(Boolean)
        );
        var statsDiv = document.createElement('div');
        statsDiv.className = 'decision-stats';
        if (scoredCount === 0) {
            statsDiv.innerHTML = '<span class="decision-stats-item">' +
                t(hotSet.size > 0 ? 'views.product.evidence.only'
                    : Array.from(containerCodes).some(function (code) { return scores[code] > 0; })
                        ? 'views.container.evidence.only' : 'views.no.scored.nodes') + '</span>';
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
            var result = Object.assign({}, node, {children: []});
            if (node.children) {
                var fc = node.children.map(filterToHot).filter(Boolean);
                if (fc.length) { result.children = fc; }
            }
            return result;
        }
        var filteredData = data.map(filterToHot).filter(Boolean);
        var visibleAreas = data.filter(function (area) {
            return hotSet.has(area.code) || (isTaxonomyContainer(area) && Number.isFinite(scores[area.code]) && scores[area.code] > 0);
        });

        if (filteredData.length === 0 && visibleAreas.length === 0) {
            appendContainerHeadings(container, data, scores);
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
        var rootData = { code: '__root__', name: '', children: contentRoots(filteredData) };
        var root = d3.hierarchy(rootData);

        var panelAreas = {};
        filteredData.forEach(function (area) {
            if (isTaxonomyContainer(area)) {
                (area.children || []).forEach(function (branch) { panelAreas[branch.code] = area; });
            } else { panelAreas[area.code] = area; }
        });
        var measureDecisionText = decisionMeasure();
        var decisionLayout;
        // This full hierarchy is independent of screen collapse, node coordinates and zoom.
        var printRoot = d3.hierarchy(rootData);
        // Reflow print cards at the same measured 14px type: four ordinary levels
        // fit landscape paper without changing screen dimensions or scaling text.
        var printLayout = layoutDecisionPanels(printRoot, scores, panelAreas, measureDecisionText, visibleAreas,
            {textWidth: 174, cardWidth: 210, columnPitch: 240, columnsPerPage: 4});
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
        var printButton = document.createElement('button'); printButton.type = 'button';
        printButton.className = 'btn btn-sm btn-outline-secondary';
        printButton.textContent = t('views.diagram.print');
        printButton.addEventListener('click', function () { printDecisionDiagram(printLayout, container); });
        var toolbar = container.querySelector('.tax-diagram-controls');
        toolbar.insertBefore(printButton, toolbar.querySelector('.tax-diagram-hint'));
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
            decisionLayout = layoutDecisionPanels(root, scores, panelAreas, measureDecisionText, visibleAreas);
            var nodes = decisionLayout.nodes;
            var links = root.links().filter(function (l) { return l.source.data.code !== '__root__'; });
            svg.attr('width', Math.max(containerWidth, decisionLayout.width)).attr('height', Math.max(400, decisionLayout.height));
            g.attr('transform', 'translate(0,0)');
            var captions = g.selectAll('text.dm-area-caption').data(decisionLayout.panels, function (panel) { return panel.area.code; });
            captions.exit().remove();
            captions.enter().append('text').attr('class', 'dm-area-caption').merge(captions)
                .attr('data-taxonomy-container', function (panel) { return panel.area.code; })
                .attr('x', 16).attr('y', function (panel) { return panel.top + 16; })
                .style('font-family', 'Arial, Helvetica, sans-serif').style('font-size', '14px')
                .style('fill', dmTheme('--decision-map-text', '#1F2937'))
                .each(function (panel) {
                    var caption = d3.select(this); caption.selectAll('tspan').remove();
                    panel.lines.forEach(function (line, index) { caption.append('tspan').attr('x', 16).attr('dy', index ? 20 : 0).text(line); });
                });

            var sourceX = source.x0 !== undefined ? source.x0 : (source.x || 0);
            var sourceY = source.y0 !== undefined ? source.y0 : (source.y || 0);

            // ── Nodes ──────────────────────────────────
            var node = g.selectAll('g.dm-node')
                .data(nodes, function (d) { return d.id || (d.id = ++nodeSeq); });

            var nodeEnter = node.enter().append('g')
                .attr('class', 'dm-node')
                .attr('transform', 'translate(' + sourceY + ',' + sourceX + ')')
                .style('opacity', 0);

            // Expansion and saved rationale are independent controls. Keep their
            // common positioned group neutral so assistive technology exposes both.
            var nodeControl = nodeEnter.append('g').attr('class', 'dm-node-control');

            // A visible card occupies the measured boundary, so links clearly attach
            // to their parent while staying outside wrapped labels and action targets.
            nodeControl.append('rect').attr('class', 'dm-card').attr('rx', 6).attr('stroke-width', 2);
            nodeControl.append('circle').attr('r', 7).attr('stroke-width', 1.5);

            nodeControl.append('text')
                .attr('class', 'dm-label')
                .attr('dy', '0.31em')
                .style('font-size', '14px')
                .style('font-family', 'Arial, Helvetica, sans-serif')
                .style('user-select', 'none');

            nodeControl.append('text')
                .attr('class', 'dm-rank')
                .attr('dy', '-0.7em')
                .attr('text-anchor', 'middle')
                .style('font-size', '13px')
                .style('pointer-events', 'none');

            nodeEnter.filter(function (d) { return Number.isFinite(scores[d.data.code]); })
                .append('g').attr('class', 'dm-reason-action').attr('role', 'button').attr('tabindex', '0')
                .attr('aria-haspopup', 'dialog')
                .attr('aria-label', function (d) { return t('views.diagram.rationaleFor', d.data.code + ' ' + (d.data.name || '')); })
                .style('cursor', 'pointer')
                .on('focus', function () { d3.select(this).select('rect').attr('stroke', dmTheme('--decision-score-stroke', '#1D4ED8')).attr('stroke-width', 2); })
                .on('blur', function () { d3.select(this).select('rect').attr('stroke', 'none'); })
                .on('click', function (event, d) { event.stopPropagation(); showDecisionReason(d.data, scores, this); })
                .on('keydown', function (event, d) {
                    if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault(); event.stopPropagation();
                        if (!event.repeat) { showDecisionReason(d.data, scores, this); }
                    }
                })
                .each(function () {
                    var action = d3.select(this);
                    action.append('rect').attr('x', 8).attr('y', -20).attr('width', 238).attr('height', 26).attr('fill', 'transparent');
                    action.append('text').attr('x', 12).style('font-size', '14px').style('font-family', 'Arial, Helvetica, sans-serif')
                        .style('text-decoration', 'underline').style('fill', dmTheme('--decision-score-stroke', '#1D4ED8'))
                        .text(t('views.diagram.rationale'));
                });

            nodeControl.filter(function (d) { return d.data.code !== '__root__'; })
                .style('cursor', 'pointer')
                .on('click', function (event, d) {
                    event.stopPropagation();
                    decisionNavigation.selectNode(d);
                    toggleDiagramBranch(d, dmUpdate);
                })
                .on('mousemove', function (event, d) { showTooltip(event, d.data, scores); })
                .on('mouseleave', hideTooltip);

            var nodeUpdate = nodeEnter.merge(node);
            decisionNavigation.update(diagramEntries(nodeUpdate.select('.dm-node-control')));

            var nodeT = nodeUpdate.transition().duration(300)
                .attr('transform', function (d) { return 'translate(' + d.y + ',' + d.x + ')'; })
                .style('opacity', function (d) { return d.data.code === '__root__' ? 0 : 1; });

            nodeUpdate.select('.dm-card')
                .attr('x', -9).attr('y', function (d) { return -d.nodeHeight / 2 + 1; })
                .attr('width', 260).attr('height', function (d) { return d.nodeHeight - 2; })
                .attr('fill', 'var(--bs-body-bg, #FFFFFF)').attr('stroke', dmNodeStroke);

            nodeUpdate.select('circle')
                .attr('fill', dmNodeFill)
                .attr('stroke', dmNodeStroke);

            nodeUpdate.select('.dm-label')
                .attr('x', 12).attr('y', function (d) { return -d.nodeHeight / 2 + 18; }).attr('dy', 0)
                .attr('text-anchor', 'start')
                .style('fill', function () { return dmTheme('--decision-map-text', '#1F2937'); })
                .style('font-weight', '400')
                .each(function (d) {
                    var label = d3.select(this); label.selectAll('tspan').remove();
                    d.lines.forEach(function (line, index) { label.append('tspan').attr('x', 12).attr('dy', index ? 20 : 0).text(line); });
                });
            nodeUpdate.select('.dm-reason-action')
                .attr('transform', function (d) { return 'translate(0,' + (d.nodeHeight / 2 - 8) + ')'; });

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
            var curve = d3.linkHorizontal().x(function (d) { return d.y; }).y(function (d) { return d.x; });
            var diag = function (link) {
                return curve({source: {x: link.source.x, y: link.source.box ? link.source.box.right : link.source.y},
                    target: {x: link.target.x, y: link.target.box ? link.target.y - 10 : link.target.y}});
            };

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
                Number(minScoreInput ? minScoreInput.value : '0') || 0
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

        appendContainerHeadings(container, data, scores);
        var colorMap = buildColorMap(data);
        var marginLeft = 40;
        var marginRight = 300;
        var marginTop = 20;
        var marginBottom = 20;
        var NODE_RADIUS = 7;

        // Synthetic root
        var rootData = { code: '__root__', name: '', children: contentRoots(data) };
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
        var areas = (data || []).filter(isTaxonomyContainer);
        var colorMap = buildColorMap(data);
        var marginLeft = 40;
        var marginRight = 300;
        var marginTop = 20 + areas.length * 28;
        var marginBottom = 20;
        var CHAR_WIDTH_ESTIMATE = 7;  // approx px per character for 12px Arial
        var BADGE_OFFSET = 20;        // extra px gap between label end and badge

        var rootData = { code: '__root__', name: '', children: contentRoots(data) };
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

        areas.forEach(function (area, index) {
            var heading = document.createElementNS(svgNS, 'text');
            heading.setAttribute('data-taxonomy-container', area.code);
            heading.setAttribute('x', marginLeft);
            heading.setAttribute('y', 20 + index * 28);
            heading.setAttribute('font-weight', 'bold');
            heading.textContent = containerLabel(area, scores);
            svg.appendChild(heading);
        });

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

        data.forEach(function (root) {
            if (isTaxonomyContainer(root)) {
                lines.push('    subgraph ' + dotId('cluster_' + root.code) + ' {');
                lines.push('    label="' + dotEscape(containerLabel(root, scores)) + '";');
                (root.children || []).forEach(walk);
                lines.push('    }');
            } else {
                walk(root);
            }
        });
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

        function writeRoot(root) {
            // If root has no children, render it as a standalone node
            if (!root.children || root.children.length === 0) {
                lines.push('    ' + mermaidId(root.code) + '["' + mermaidLabel(root) + '"]');
            }
            walk(root);
        }

        data.forEach(function (root) {
            if (isTaxonomyContainer(root)) {
                lines.push('    subgraph cluster_' + mermaidId(root.code) + '["'
                    + containerLabel(root, scores).replace(/"/g, '&quot;') + '"]');
                (root.children || []).forEach(writeRoot);
                lines.push('    end');
            } else {
                writeRoot(root);
            }
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
