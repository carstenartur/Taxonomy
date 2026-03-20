/* taxonomy-graph.js – Graph Explorer UI for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    var t = TaxonomyI18n.t;

    // ── Helpers ────────────────────────────────────────────────────────────────

    var MAX_AUTOCOMPLETE_SUGGESTIONS = 200;

    var escapeHtml = TaxonomyUtils.escapeHtml;

    function safeLen(arr) {
        return Array.isArray(arr) ? arr.length : 0;
    }

    function getNodeCode() {
        var input = document.getElementById('graphNodeInput');
        return input ? input.value.trim() : '';
    }

    function getMaxHops() {
        var select = document.getElementById('graphMaxHops');
        return select ? parseInt(select.value, 10) : 2;
    }

    function showGraphLoading() {
        var area = document.getElementById('graphResultsArea');
        var content = document.getElementById('graphResultsContent');
        if (area) area.style.display = '';
        if (content) {
            content.innerHTML =
                '<div class="text-center text-muted py-2">' +
                '<div class="spinner-border spinner-border-sm me-1" role="status"></div> ' +
                escapeHtml(t('graph.loading')) + '</div>';
        }
    }

    function showGraphError(msg) {
        var content = document.getElementById('graphResultsContent');
        if (content) {
            content.innerHTML = '<div class="alert alert-warning py-1 px-2 small mb-0">' +
                escapeHtml(msg) + '</div>';
        }
    }

    // ── Render helpers ────────────────────────────────────────────────────────

    function relevanceBadge(relevance) {
        var pct = (relevance * 100).toFixed(0);
        var cls = 'bg-secondary';
        if (relevance >= 0.7) cls = 'bg-success';
        else if (relevance >= 0.4) cls = 'bg-primary';
        return '<span class="badge ' + cls + ' graph-relevance-badge">' + pct + '%</span>';
    }

    function hopBadge(hop) {
        if (hop === 0) return '<span class="badge bg-dark">' + escapeHtml(t('graph.badge.origin')) + '</span>';
        return '<span class="badge bg-light text-dark border">' + escapeHtml(t('graph.badge.hop', hop)) + '</span>';
    }

    function renderElementsTable(elements, title) {
        if (!elements || elements.length === 0) return '';
        var html = '<h6 class="graph-section-title">' + escapeHtml(title) +
            ' <span class="badge bg-secondary">' + elements.length + '</span></h6>';
        html += '<div class="table-responsive"><table class="table table-sm table-hover graph-table mb-2">';
        html += '<thead><tr><th>' + escapeHtml(t('graph.header.code')) + '</th><th>' + escapeHtml(t('graph.header.title')) + '</th><th>' + escapeHtml(t('graph.header.sheet')) + '</th><th>' + escapeHtml(t('graph.header.relevance')) + '</th><th>' + escapeHtml(t('graph.header.hop')) + '</th><th>' + escapeHtml(t('graph.header.reason')) + '</th></tr></thead><tbody>';
        elements.forEach(function (e) {
            html += '<tr class="graph-element-row" data-code="' + escapeHtml(e.nodeCode) + '">' +
                '<td><a href="#" class="graph-node-link" data-code="' + escapeHtml(e.nodeCode) + '">' +
                escapeHtml(e.nodeCode) + '</a></td>' +
                '<td>' + escapeHtml(e.title || '') + '</td>' +
                '<td><span class="badge bg-light text-dark border">' + escapeHtml(e.taxonomySheet || '') + '</span></td>' +
                '<td>' + relevanceBadge(e.relevance) + '</td>' +
                '<td>' + hopBadge(e.hopDistance) + '</td>' +
                '<td class="small text-muted">' + escapeHtml(e.includedBecause || '') + '</td>' +
                '</tr>';
        });
        html += '</tbody></table></div>';
        return html;
    }

    function renderRelationshipsTable(rels) {
        if (!rels || rels.length === 0) return '';
        var html = '<h6 class="graph-section-title">' + escapeHtml(t('graph.section.relationships')) +
            ' <span class="badge bg-secondary">' + rels.length + '</span></h6>';
        html += '<div class="table-responsive"><table class="table table-sm table-hover graph-table mb-0">';
        html += '<thead><tr><th>' + escapeHtml(t('graph.header.source')) + '</th><th></th><th>' + escapeHtml(t('graph.header.target')) + '</th><th>' + escapeHtml(t('graph.header.type')) + '</th><th>' + escapeHtml(t('graph.header.relevance')) + '</th><th>' + escapeHtml(t('graph.header.hop')) + '</th></tr></thead><tbody>';
        rels.forEach(function (r) {
            html += '<tr>' +
                '<td>' + escapeHtml(r.sourceCode) + '</td>' +
                '<td class="text-center">&rarr;</td>' +
                '<td>' + escapeHtml(r.targetCode) + '</td>' +
                '<td><span class="badge bg-info text-dark">' + escapeHtml(r.relationType) + '</span></td>' +
                '<td>' + relevanceBadge(r.propagatedRelevance) + '</td>' +
                '<td>' + hopBadge(r.hopDistance) + '</td>' +
                '</tr>';
        });
        html += '</tbody></table></div>';
        return html;
    }

    // ── Summary cards ─────────────────────────────────────────────────────────

    function summaryCard(icon, label, value, color) {
        return '<div class="graph-stat-card">' +
            '<span class="graph-stat-icon">' + icon + '</span>' +
            '<div><div class="graph-stat-value text-' + (color || 'dark') + '">' + value + '</div>' +
            '<div class="graph-stat-label">' + escapeHtml(label) + '</div></div></div>';
    }

    // ── Force-Directed Graph ─────────────────────────────────────────────────

    var GRAPH_NODE_COLORS = {
        'Capabilities': '#4A90D9',
        'Business Processes': '#27AE60',
        'Business Roles': '#27AE60',
        'Services': '#F39C12',
        'COI Services': '#F39C12',
        'Core Services': '#F39C12',
        'Applications': '#8E44AD',
        'User Applications': '#8E44AD',
        'Information Products': '#3498DB',
        'Communications Services': '#E74C3C',
        'Systems': '#6A5ACD',
        'Components': '#9B59B6'
    };

    var APQC_LEVEL_COLORS = {
        'Category':     '#1B4F72',
        'ProcessGroup': '#2E86C1',
        'Process':      '#5DADE2',
        'Activity':     '#AED6F1',
        'Task':         '#D6EAF8'
    };

    function getNodeColor(taxonomySheet) {
        return GRAPH_NODE_COLORS[taxonomySheet] || '#6c757d';
    }

    var GRAPH_MAX_HEIGHT = 400;
    var GRAPH_MIN_HEIGHT = 250;
    var GRAPH_HEIGHT_PER_NODE = 25;

    /**
     * Render a D3 force-directed graph from graph explorer data.
     * @param {HTMLElement} container - DOM element to render into
     * @param {Array} nodes - Array of {nodeCode, title, taxonomySheet, hopDistance, relevance}
     * @param {Array} edges - Array of {sourceCode, targetCode, relationType}
     * @param {string} originCode - The origin node code
     */
    function renderForceGraph(container, nodes, edges, originCode) {
        if (typeof d3 === 'undefined' || !nodes || nodes.length === 0) return;

        var width = container.clientWidth || 500;
        var height = Math.min(GRAPH_MAX_HEIGHT, Math.max(GRAPH_MIN_HEIGHT, nodes.length * GRAPH_HEIGHT_PER_NODE));

        // Deduplicate nodes
        var nodeMap = {};
        nodes.forEach(function (n) {
            if (!nodeMap[n.nodeCode]) nodeMap[n.nodeCode] = n;
        });
        var nodeList = Object.values(nodeMap);

        // Build D3-compatible data
        var d3Nodes = nodeList.map(function (n) {
            return {
                id: n.nodeCode,
                title: n.title || n.nodeCode,
                sheet: n.taxonomySheet || n.taxonomyRoot || '',
                hop: n.hopDistance || 0,
                relevance: n.relevance || 0,
                isOrigin: n.nodeCode === originCode
            };
        });

        var nodeIdSet = new Set(d3Nodes.map(function (n) { return n.id; }));
        var d3Links = (edges || []).filter(function (e) {
            return nodeIdSet.has(e.sourceCode) && nodeIdSet.has(e.targetCode);
        }).map(function (e) {
            return { source: e.sourceCode, target: e.targetCode, type: e.relationType };
        });

        // SVG setup
        var svg = d3.select(container)
            .append('div').attr('class', 'force-graph-container')
            .append('svg')
            .attr('width', width)
            .attr('height', height)
            .attr('viewBox', [0, 0, width, height].join(' '));

        // Arrow markers
        svg.append('defs').append('marker')
            .attr('id', 'arrowhead')
            .attr('viewBox', '-0 -5 10 10')
            .attr('refX', 20)
            .attr('refY', 0)
            .attr('orient', 'auto')
            .attr('markerWidth', 6)
            .attr('markerHeight', 6)
            .append('path')
            .attr('d', 'M 0,-5 L 10,0 L 0,5')
            .attr('fill', '#999');

        var simulation = d3.forceSimulation(d3Nodes)
            .force('link', d3.forceLink(d3Links).id(function (d) { return d.id; }).distance(80))
            .force('charge', d3.forceManyBody().strength(-200))
            .force('center', d3.forceCenter(width / 2, height / 2))
            .force('collision', d3.forceCollide().radius(25));

        // Links
        var link = svg.append('g')
            .selectAll('line')
            .data(d3Links)
            .join('line')
            .attr('stroke', '#999')
            .attr('stroke-opacity', 0.6)
            .attr('stroke-width', 1.5)
            .attr('marker-end', 'url(#arrowhead)');

        // Link labels
        var linkLabel = svg.append('g')
            .selectAll('text')
            .data(d3Links)
            .join('text')
            .attr('font-size', '8px')
            .attr('fill', '#888')
            .attr('text-anchor', 'middle')
            .text(function (d) { return d.type; });

        // Nodes
        var node = svg.append('g')
            .selectAll('g')
            .data(d3Nodes)
            .join('g')
            .call(d3.drag()
                .on('start', function (event, d) {
                    if (!event.active) simulation.alphaTarget(0.3).restart();
                    d.fx = d.x;
                    d.fy = d.y;
                })
                .on('drag', function (event, d) {
                    d.fx = event.x;
                    d.fy = event.y;
                })
                .on('end', function (event, d) {
                    if (!event.active) simulation.alphaTarget(0);
                    d.fx = null;
                    d.fy = null;
                }));

        node.append('circle')
            .attr('r', function (d) { return d.isOrigin ? 12 : 8 + (d.relevance * 4); })
            .attr('fill', function (d) { return getNodeColor(d.sheet); })
            .attr('stroke', function (d) { return d.isOrigin ? '#000' : '#fff'; })
            .attr('stroke-width', function (d) { return d.isOrigin ? 3 : 1.5; })
            .style('cursor', 'pointer');

        node.append('text')
            .attr('dx', 14)
            .attr('dy', 4)
            .attr('font-size', '10px')
            .attr('fill', '#333')
            .text(function (d) { return d.id; });

        // Tooltip on hover
        node.append('title')
            .text(function (d) {
                return d.id + ' — ' + d.title + '\nSheet: ' + d.sheet +
                    '\nRelevance: ' + (d.relevance * 100).toFixed(0) + '%' +
                    '\nHop: ' + d.hop;
            });

        // Click to set node input
        node.on('click', function (event, d) {
            var input = document.getElementById('graphNodeInput');
            if (input) input.value = d.id;
        });

        simulation.on('tick', function () {
            link
                .attr('x1', function (d) { return d.source.x; })
                .attr('y1', function (d) { return d.source.y; })
                .attr('x2', function (d) { return d.target.x; })
                .attr('y2', function (d) { return d.target.y; });

            linkLabel
                .attr('x', function (d) { return (d.source.x + d.target.x) / 2; })
                .attr('y', function (d) { return (d.source.y + d.target.y) / 2; });

            node.attr('transform', function (d) {
                d.x = Math.max(15, Math.min(width - 15, d.x));
                d.y = Math.max(15, Math.min(height - 15, d.y));
                return 'translate(' + d.x + ',' + d.y + ')';
            });
        });

        // Legend
        var legendDiv = d3.select(container.querySelector('.force-graph-container'))
            .append('div').attr('class', 'force-graph-legend');

        var usedSheets = new Set(d3Nodes.map(function (n) { return n.sheet; }));
        usedSheets.forEach(function (sheet) {
            if (sheet) {
                legendDiv.append('span').attr('class', 'force-graph-legend-item')
                    .html('<span class="force-graph-legend-dot" style="background:' +
                        getNodeColor(sheet) + '"></span> ' + sheet);
            }
        });
        legendDiv.append('span').attr('class', 'force-graph-legend-item')
            .html('<span class="force-graph-legend-dot" style="background:#000;border:2px solid #000;width:10px;height:10px;"></span> Origin');
    }

    // ── Impact Force Graph (Architecture View) ──────────────────────────────

    var IMPACT_MAX_HEIGHT = 500;
    var IMPACT_MIN_HEIGHT = 280;
    var IMPACT_HEIGHT_PER_NODE = 30;
    var IMPACT_MAX_NODES = 25;

    var RELATION_COLORS = {
        'SUPPORTS': '#27AE60',
        'REALIZES': '#4A90D9',
        'USES': '#F39C12',
        'REQUIRES': '#E74C3C',
        'DEPENDS_ON': '#8E44AD',
        'FULFILLS': '#2ECC71',
        'CONSUMES': '#E67E22',
        'ASSIGNED_TO': '#1ABC9C',
        'PRODUCES': '#3498DB',
        'COMMUNICATES_WITH': '#9B59B6',
        'CONTAINS': '#95A5A6',
        'RELATED_TO': '#BDC3C7'
    };

    /**
     * Render a D3 force-directed graph tailored for the Architecture View impact map.
     * @param {HTMLElement} container - DOM element to render into
     * @param {Array} nodes - Array of {nodeCode, title, taxonomySheet, hopDistance, relevance, anchor, includedBecause}
     * @param {Array} edges - Array of {sourceCode, targetCode, relationType, propagatedRelevance}
     * @param {Object} options - {anchorCodes: Set, hotspotCodes: Set, hotspotReasons: Object, layerConfig: Object}
     */
    function renderImpactForceGraph(container, nodes, edges, options) {
        if (typeof d3 === 'undefined' || !nodes || nodes.length === 0) return;

        var anchorCodes = options.anchorCodes || new Set();
        var hotspotCodes = options.hotspotCodes || new Set();
        var hotspotReasons = options.hotspotReasons || {};
        var layerConfig = options.layerConfig || {};

        // Cap nodes for readability
        var cappedNodes = nodes.slice(0, IMPACT_MAX_NODES);
        var cappedNodeCodes = new Set(cappedNodes.map(function (n) { return n.nodeCode; }));

        var width = container.clientWidth || 600;
        var height = Math.min(IMPACT_MAX_HEIGHT, Math.max(IMPACT_MIN_HEIGHT, cappedNodes.length * IMPACT_HEIGHT_PER_NODE));

        // Deduplicate nodes
        var nodeMap = {};
        cappedNodes.forEach(function (n) {
            if (!nodeMap[n.nodeCode]) nodeMap[n.nodeCode] = n;
        });
        var nodeList = Object.values(nodeMap);

        // Build D3-compatible data
        var d3Nodes = nodeList.map(function (n) {
            var cfg = layerConfig[n.taxonomySheet] || {};
            return {
                id: n.nodeCode,
                title: n.title || n.nodeCode,
                sheet: n.taxonomySheet || '',
                sheetLabel: cfg.label || n.taxonomySheet || '',
                hop: n.hopDistance || 0,
                relevance: n.relevance || 0,
                isAnchor: anchorCodes.has(n.nodeCode),
                isHotspot: hotspotCodes.has(n.nodeCode),
                hotspotReason: hotspotReasons[n.nodeCode] || '',
                includedBecause: n.includedBecause || ''
            };
        });

        var nodeIdSet = new Set(d3Nodes.map(function (n) { return n.id; }));
        var d3Links = (edges || []).filter(function (e) {
            return cappedNodeCodes.has(e.sourceCode) && cappedNodeCodes.has(e.targetCode);
        }).map(function (e) {
            return {
                source: e.sourceCode,
                target: e.targetCode,
                type: e.relationType || '',
                relevance: e.propagatedRelevance || 0
            };
        });

        // SVG setup with dark background
        var graphDiv = d3.select(container)
            .append('div').attr('class', 'impact-graph-container');

        var svg = graphDiv.append('svg')
            .attr('width', width)
            .attr('height', height)
            .attr('viewBox', [0, 0, width, height].join(' '));

        // Tooltip div
        var tooltip = graphDiv.append('div')
            .attr('class', 'impact-graph-tooltip')
            .style('opacity', 0);

        // Arrow markers per relation type
        var defs = svg.append('defs');
        Object.keys(RELATION_COLORS).forEach(function (relType) {
            defs.append('marker')
                .attr('id', 'impact-arrow-' + relType)
                .attr('viewBox', '-0 -5 10 10')
                .attr('refX', 22)
                .attr('refY', 0)
                .attr('orient', 'auto')
                .attr('markerWidth', 6)
                .attr('markerHeight', 6)
                .append('path')
                .attr('d', 'M 0,-5 L 10,0 L 0,5')
                .attr('fill', RELATION_COLORS[relType]);
        });
        // Default arrow
        defs.append('marker')
            .attr('id', 'impact-arrow-default')
            .attr('viewBox', '-0 -5 10 10')
            .attr('refX', 22)
            .attr('refY', 0)
            .attr('orient', 'auto')
            .attr('markerWidth', 6)
            .attr('markerHeight', 6)
            .append('path')
            .attr('d', 'M 0,-5 L 10,0 L 0,5')
            .attr('fill', '#999');

        // Force simulation
        var simulation = d3.forceSimulation(d3Nodes)
            .force('link', d3.forceLink(d3Links).id(function (d) { return d.id; }).distance(120))
            .force('charge', d3.forceManyBody().strength(-300))
            .force('center', d3.forceCenter(width / 2, height / 2))
            .force('collision', d3.forceCollide().radius(35));

        // Links
        var link = svg.append('g')
            .selectAll('line')
            .data(d3Links)
            .join('line')
            .attr('stroke', function (d) { return RELATION_COLORS[d.type] || '#999'; })
            .attr('stroke-opacity', 0.7)
            .attr('stroke-width', function (d) { return Math.max(1, 1 + d.relevance * 3); })
            .attr('marker-end', function (d) {
                return 'url(#impact-arrow-' + (RELATION_COLORS[d.type] ? d.type : 'default') + ')';
            });

        // Link labels
        var linkLabel = svg.append('g')
            .selectAll('text')
            .data(d3Links)
            .join('text')
            .attr('font-size', '7px')
            .attr('fill', '#aaa')
            .attr('text-anchor', 'middle')
            .text(function (d) { return d.type; });

        // Nodes
        var node = svg.append('g')
            .selectAll('g')
            .data(d3Nodes)
            .join('g')
            .call(d3.drag()
                .on('start', function (event, d) {
                    if (!event.active) simulation.alphaTarget(0.3).restart();
                    d.fx = d.x;
                    d.fy = d.y;
                })
                .on('drag', function (event, d) {
                    d.fx = event.x;
                    d.fy = event.y;
                })
                .on('end', function (event, d) {
                    if (!event.active) simulation.alphaTarget(0);
                    d.fx = null;
                    d.fy = null;
                }));

        node.append('circle')
            .attr('r', function (d) {
                if (d.isAnchor) return 14;
                return Math.max(6, 6 + d.relevance * 8);
            })
            .attr('fill', function (d) {
                var color = getNodeColor(d.sheet);
                var opacity = 0.6 + d.relevance * 0.4;
                // Convert hex to rgba
                var r = parseInt(color.slice(1, 3), 16);
                var g = parseInt(color.slice(3, 5), 16);
                var b = parseInt(color.slice(5, 7), 16);
                return 'rgba(' + r + ',' + g + ',' + b + ',' + opacity.toFixed(2) + ')';
            })
            .attr('stroke', function (d) {
                if (d.isAnchor) return '#FFD700';
                if (d.isHotspot) return '#E74C3C';
                return '#fff';
            })
            .attr('stroke-width', function (d) {
                if (d.isAnchor) return 3;
                if (d.isHotspot) return 2.5;
                return 1.5;
            })
            .style('cursor', 'pointer');

        // Hotspot pulse animation
        node.filter(function (d) { return d.isHotspot; })
            .classed('impact-node-pulse', true);

        // Labels: code + short title + score for anchors
        node.append('text')
            .attr('dx', 16)
            .attr('dy', 4)
            .attr('font-size', '9px')
            .attr('fill', '#e0e0e0')
            .text(function (d) {
                var label = d.id;
                if (d.title && d.title !== d.id) {
                    label += ' ' + d.title.substring(0, 18);
                }
                if (d.isAnchor) label += ' \u2605' + (d.relevance * 100).toFixed(0) + '%';
                return label;
            });

        // Rich tooltip on hover
        node.on('mouseover', function (event, d) {
            var lines = [];
            lines.push('<strong>' + d.id + '</strong> ' + d.title);
            lines.push('Layer: ' + d.sheetLabel);
            lines.push('Relevance: ' + (d.relevance * 100).toFixed(0) + '%');
            var hopLabel = d.hop === 0 ? '0 (direct match) \u2605' : d.hop;
            lines.push('Hop: ' + hopLabel);
            if (d.isAnchor) lines.push('\u2605 Direct Match (Anchor)');
            if (d.isHotspot) lines.push('\u26A0\uFE0F Hotspot: ' + d.hotspotReason);
            if (d.includedBecause) lines.push('Reason: ' + d.includedBecause);

            tooltip.html(lines.join('<br>'))
                .style('left', (event.offsetX + 15) + 'px')
                .style('top', (event.offsetY - 10) + 'px')
                .style('opacity', 1);
        })
        .on('mousemove', function (event) {
            tooltip
                .style('left', (event.offsetX + 15) + 'px')
                .style('top', (event.offsetY - 10) + 'px');
        })
        .on('mouseout', function () {
            tooltip.style('opacity', 0);
        });

        // Click to open in Graph Explorer
        node.on('click', function (event, d) {
            if (typeof openGraphExplorer === 'function') {
                openGraphExplorer(d.id);
            } else {
                var input = document.getElementById('graphNodeInput');
                if (input) input.value = d.id;
            }
        });

        // Tick handler
        simulation.on('tick', function () {
            link
                .attr('x1', function (d) { return d.source.x; })
                .attr('y1', function (d) { return d.source.y; })
                .attr('x2', function (d) { return d.target.x; })
                .attr('y2', function (d) { return d.target.y; });

            linkLabel
                .attr('x', function (d) { return (d.source.x + d.target.x) / 2; })
                .attr('y', function (d) { return (d.source.y + d.target.y) / 2; });

            node.attr('transform', function (d) {
                d.x = Math.max(15, Math.min(width - 15, d.x));
                d.y = Math.max(15, Math.min(height - 15, d.y));
                return 'translate(' + d.x + ',' + d.y + ')';
            });
        });

        // Legend
        var legendDiv = graphDiv.append('div').attr('class', 'impact-graph-legend');

        var usedSheets = new Set(d3Nodes.map(function (n) { return n.sheet; }));
        usedSheets.forEach(function (sheet) {
            if (sheet) {
                var label = (layerConfig[sheet] && layerConfig[sheet].label) ? layerConfig[sheet].label : sheet;
                legendDiv.append('span').attr('class', 'force-graph-legend-item')
                    .html('<span class="force-graph-legend-dot" style="background:' +
                        getNodeColor(sheet) + '"></span> ' + label);
            }
        });
        legendDiv.append('span').attr('class', 'force-graph-legend-item')
            .html('<span class="force-graph-legend-dot" style="background:#FFD700;border:2px solid #FFD700;width:10px;height:10px;"></span> \u2605 Anchor');
        legendDiv.append('span').attr('class', 'force-graph-legend-item')
            .html('<span class="force-graph-legend-dot" style="background:#E74C3C;border:2px solid #E74C3C;width:10px;height:10px;"></span> \u26A0\uFE0F Hotspot');

        // Relation type legend
        var usedRelTypes = new Set(d3Links.map(function (l) { return l.type; }));
        usedRelTypes.forEach(function (relType) {
            if (relType) {
                var color = RELATION_COLORS[relType] || '#999';
                legendDiv.append('span').attr('class', 'force-graph-legend-item')
                    .html('<span style="display:inline-block;width:20px;height:2px;background:' +
                        color + ';vertical-align:middle;margin-right:4px;"></span> ' + relType);
            }
        });
    }

    /**
     * Wrap graph results with a toggle for Graph/Table view.
     */
    function wrapWithGraphToggle(nodes, edges, originCode, tableHtml) {
        var content = document.getElementById('graphResultsContent');
        if (!content) return;

        var toggleHtml = '<div class="graph-view-toggle">' +
            '<button class="btn btn-sm btn-primary graph-toggle-btn" data-mode="graph">🔗 Graph</button>' +
            '<button class="btn btn-sm btn-outline-secondary graph-toggle-btn" data-mode="table">📊 Table</button>' +
            '</div>';

        content.innerHTML = toggleHtml +
            '<div id="graphViewGraph"></div>' +
            '<div id="graphViewTable" style="display:none;">' + tableHtml + '</div>';

        var graphContainer = document.getElementById('graphViewGraph');
        if (graphContainer && nodes && nodes.length > 0) {
            renderForceGraph(graphContainer, nodes, edges, originCode);
        }

        // Toggle buttons
        content.querySelectorAll('.graph-toggle-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var mode = btn.dataset.mode;
                var graphDiv = document.getElementById('graphViewGraph');
                var tableDiv = document.getElementById('graphViewTable');
                if (graphDiv) graphDiv.style.display = mode === 'graph' ? '' : 'none';
                if (tableDiv) tableDiv.style.display = mode === 'table' ? '' : 'none';
                content.querySelectorAll('.graph-toggle-btn').forEach(function (b) {
                    b.classList.toggle('btn-primary', b.dataset.mode === mode);
                    b.classList.toggle('btn-outline-secondary', b.dataset.mode !== mode);
                });
            });
        });

        attachNodeLinks();
    }

    // ── Upstream / Downstream renderer ────────────────────────────────────────

    function renderNeighborhoodResult(data, inline) {
        var html = '';

        // Summary stats
        var dirIcon = data.direction === 'UPSTREAM' ? '&#11014;&#65039;' : '&#11015;&#65039;';
        html += '<div class="graph-stats-row">';
        html += summaryCard(dirIcon, t('graph.summary.direction'), data.direction, 'primary');
        html += summaryCard('&#128205;', t('graph.summary.origin'), data.originNodeCode, 'dark');
        html += summaryCard('&#128101;', t('graph.summary.neighbors'), data.totalNeighbors, 'success');
        html += summaryCard('&#128279;', t('graph.summary.relations'), data.totalRelationships, 'info');
        html += summaryCard('&#128218;', t('graph.summary.maxhops'), data.maxHops, 'secondary');
        html += '</div>';

        // Notes
        if (data.notes && data.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-2">' +
                data.notes.map(function (n) { return escapeHtml(n); }).join('<br>') + '</div>';
        }

        html += renderElementsTable(data.neighbors, data.direction === 'UPSTREAM' ? t('graph.summary.upstream.elements') : t('graph.summary.downstream.elements'));
        html += renderRelationshipsTable(data.traversedRelationships);

        return html;
    }

    // ── Failure Impact renderer ───────────────────────────────────────────────

    function renderFailureResult(data) {
        var html = '';

        // Summary stats
        html += '<div class="graph-stats-row">';
        html += summaryCard('&#9888;&#65039;', t('graph.summary.failed.node'), data.failedNodeCode, 'danger');
        html += summaryCard('&#128308;', t('graph.summary.direct.impact'), safeLen(data.directlyAffected), 'danger');
        html += summaryCard('&#128992;', t('graph.summary.indirect.impact'), safeLen(data.indirectlyAffected), 'warning');
        html += summaryCard('&#128101;', t('graph.summary.total.affected'), data.totalAffected, 'dark');
        html += summaryCard('&#128218;', t('graph.summary.maxhops'), data.maxHops, 'secondary');
        html += '</div>';

        // Notes
        if (data.notes && data.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-2">' +
                data.notes.map(function (n) { return escapeHtml(n); }).join('<br>') + '</div>';
        }

        html += renderElementsTable(data.directlyAffected, '&#128308; Directly Affected (Hop 1)');
        html += renderElementsTable(data.indirectlyAffected, '&#128992; Indirectly Affected (Hop 2+)');
        html += renderRelationshipsTable(data.traversedRelationships);

        return html;
    }

    // ── API calls ─────────────────────────────────────────────────────────────

    function fetchGraphData(endpoint, label, callback) {
        fetch(endpoint)
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(callback)
            .catch(function (err) { showGraphError(t('graph.query.failed', label, err.message)); });
    }

    function fetchUpstream(nodeCode, maxHops, callback) {
        fetchGraphData('/api/graph/node/' + encodeURIComponent(nodeCode) + '/upstream?maxHops=' + maxHops,
            t('graph.label.upstream'), callback);
    }

    function fetchDownstream(nodeCode, maxHops, callback) {
        fetchGraphData('/api/graph/node/' + encodeURIComponent(nodeCode) + '/downstream?maxHops=' + maxHops,
            t('graph.label.downstream'), callback);
    }

    function fetchFailureImpact(nodeCode, maxHops, callback) {
        fetchGraphData('/api/graph/node/' + encodeURIComponent(nodeCode) + '/failure-impact?maxHops=' + maxHops,
            t('graph.label.failure'), callback);
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    function onUpstreamClick() {
        var code = getNodeCode();
        if (!code) { showGraphError(t('graph.enter.code')); return; }
        showGraphLoading();
        fetchUpstream(code, getMaxHops(), function (data) {
            var tableHtml = renderNeighborhoodResult(data, true);
            var allNodes = data.neighbors || [];
            var edges = data.traversedRelationships || [];
            wrapWithGraphToggle(allNodes, edges, data.originNodeCode, tableHtml);
        });
    }

    function onDownstreamClick() {
        var code = getNodeCode();
        if (!code) { showGraphError(t('graph.enter.code')); return; }
        showGraphLoading();
        fetchDownstream(code, getMaxHops(), function (data) {
            var tableHtml = renderNeighborhoodResult(data, true);
            var allNodes = data.neighbors || [];
            var edges = data.traversedRelationships || [];
            wrapWithGraphToggle(allNodes, edges, data.originNodeCode, tableHtml);
        });
    }

    function onFailureClick() {
        var code = getNodeCode();
        if (!code) { showGraphError(t('graph.enter.code')); return; }
        showGraphLoading();
        fetchFailureImpact(code, getMaxHops(), function (data) {
            var tableHtml = renderFailureResult(data);
            var allNodes = (data.directlyAffected || []).concat(data.indirectlyAffected || []);
            var edges = data.traversedRelationships || [];
            wrapWithGraphToggle(allNodes, edges, data.failedNodeCode, tableHtml);
        });
    }

    function attachNodeLinks() {
        document.querySelectorAll('.graph-node-link').forEach(function (link) {
            link.addEventListener('click', function (e) {
                e.preventDefault();
                var code = this.dataset.code;
                var input = document.getElementById('graphNodeInput');
                if (input) input.value = code;
            });
        });
    }

    // ── Open Graph Explorer for a specific node (called from taxonomy tree) ──

    function openGraphExplorer(nodeCode) {
        var input = document.getElementById('graphNodeInput');
        if (input) input.value = nodeCode;

        // Scroll the Graph Explorer panel into view
        var panel = document.getElementById('graphExplorerPanel');
        if (panel) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });

        // Clear any previous results
        var area = document.getElementById('graphResultsArea');
        if (area) area.style.display = 'none';
    }

    // ── Full-detail modal ─────────────────────────────────────────────────────

    function showInModal(title, bodyHtml) {
        var modalLabel = document.getElementById('graphDetailModalLabel');
        var modalBody = document.getElementById('graphDetailModalBody');
        if (modalLabel) modalLabel.textContent = title;
        if (modalBody) modalBody.innerHTML = bodyHtml;
        var modalEl = document.getElementById('graphDetailModal');
        if (modalEl && window.bootstrap) {
            new window.bootstrap.Modal(modalEl).show();
        }
        // Attach links inside modal too
        if (modalBody) {
            modalBody.querySelectorAll('.graph-node-link').forEach(function (link) {
                link.addEventListener('click', function (e) {
                    e.preventDefault();
                    var code = this.dataset.code;
                    var input = document.getElementById('graphNodeInput');
                    if (input) input.value = code;
                });
            });
        }
    }

    // ── Populate datalist suggestions from taxonomy data ──────────────────────

    function populateNodeSuggestions(taxonomyData) {
        var datalist = document.getElementById('graphNodeSuggestions');
        if (!datalist || !taxonomyData) return;
        datalist.innerHTML = '';
        var codes = [];
        function walk(node) {
            if (node.code) {
                codes.push({ code: node.code, name: node.name || '' });
            }
            if (node.children) {
                node.children.forEach(walk);
            }
        }
        taxonomyData.forEach(walk);
        codes.slice(0, MAX_AUTOCOMPLETE_SUGGESTIONS).forEach(function (item) {
            var option = document.createElement('option');
            option.value = item.code;
            option.textContent = item.code + ' — ' + item.name;
            datalist.appendChild(option);
        });
    }

    // ── APQC Hierarchy ───────────────────────────────────────────────────────

    function getApqcLevelColor(level) {
        return APQC_LEVEL_COLORS[level] || '#6c757d';
    }

    /**
     * Render an APQC hierarchy tree using D3.
     * @param {HTMLElement} container - DOM element to render into
     * @param {Array} roots - Array of ApqcHierarchyNode trees
     */
    function renderApqcTree(container, roots) {
        if (typeof d3 === 'undefined' || !roots || roots.length === 0) {
            container.innerHTML = '<div class="alert alert-info py-1 px-2 small mb-0">' +
                t('graph.apqc.no.data') + '</div>';
            return;
        }

        container.innerHTML = '';
        var syntheticRoot = { name: t('graph.apqc.title'), level: '', children: roots };
        var root = d3.hierarchy(syntheticRoot);

        var marginLeft = 30;
        var marginRight = 200;
        var marginTop = 10;
        var marginBottom = 10;
        var nodeRadius = 6;
        var dy = 200;
        var dx = 24;

        root.sort(function (a, b) { return d3.ascending(a.data.pcfId || '', b.data.pcfId || ''); });
        var treeLayout = d3.tree().nodeSize([dx, dy]);
        treeLayout(root);

        var x0 = Infinity, x1 = -Infinity;
        root.each(function (d) {
            if (d.x > x1) x1 = d.x;
            if (d.x < x0) x0 = d.x;
        });

        var width = container.clientWidth || 600;
        var height = x1 - x0 + marginTop + marginBottom + dx;

        var svg = d3.select(container).append('svg')
            .attr('width', width)
            .attr('height', height)
            .attr('viewBox', [-marginLeft, x0 - marginTop, width, height])
            .style('font', '12px sans-serif');

        // Links
        svg.append('g')
            .attr('fill', 'none')
            .attr('stroke', '#ccc')
            .attr('stroke-width', 1.5)
            .selectAll('path')
            .data(root.links())
            .join('path')
            .attr('d', d3.linkHorizontal()
                .x(function (d) { return d.y; })
                .y(function (d) { return d.x; }));

        // Nodes
        var node = svg.append('g')
            .selectAll('g')
            .data(root.descendants())
            .join('g')
            .attr('transform', function (d) { return 'translate(' + d.y + ',' + d.x + ')'; });

        node.append('circle')
            .attr('r', nodeRadius)
            .attr('fill', function (d) { return d.data.level ? getApqcLevelColor(d.data.level) : '#333'; })
            .attr('stroke', '#fff')
            .attr('stroke-width', 1.5);

        node.append('text')
            .attr('dy', '0.32em')
            .attr('x', function (d) { return d.children ? -10 : 10; })
            .attr('text-anchor', function (d) { return d.children ? 'end' : 'start'; })
            .text(function (d) {
                var label = d.data.name || d.data.id || '';
                return label.length > 40 ? label.substring(0, 37) + '...' : label;
            });

        node.append('title')
            .text(function (d) {
                var parts = [];
                if (d.data.id) parts.push('ID: ' + d.data.id);
                if (d.data.pcfId) parts.push('PCF: ' + d.data.pcfId);
                if (d.data.level) parts.push('Level: ' + d.data.level);
                if (d.data.taxonomyRoot) parts.push('Root: ' + d.data.taxonomyRoot);
                return parts.join('\n');
            });

        // Legend
        var legendDiv = d3.select(container).append('div')
            .attr('class', 'force-graph-legend mt-1');
        Object.keys(APQC_LEVEL_COLORS).forEach(function (level) {
            legendDiv.append('span').attr('class', 'force-graph-legend-item')
                .html('<span class="force-graph-legend-dot" style="background:' +
                    APQC_LEVEL_COLORS[level] + '"></span> ' + level);
        });
    }

    function onApqcFilterChange() {
        var checkbox = document.getElementById('graphApqcFilter');
        if (!checkbox) return;

        var area = document.getElementById('graphResultsArea');
        var content = document.getElementById('graphResultsContent');

        if (checkbox.checked) {
            if (area) area.style.display = '';
            if (content) {
                content.innerHTML =
                    '<div class="text-center text-muted py-2">' +
                    '<div class="spinner-border spinner-border-sm me-1" role="status"></div> ' +
                    'Loading APQC hierarchy&hellip;</div>';
            }
            fetch('/api/graph/apqc-hierarchy')
                .then(function (r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.json();
                })
                .then(function (data) {
                    if (!content) return;
                    if (!data || data.length === 0) {
                        content.innerHTML = '<div class="alert alert-info py-1 px-2 small mb-0">' +
                            t('graph.apqc.no.data') + '</div>';
                        return;
                    }
                    content.innerHTML =
                        '<div class="mb-1"><strong>' + t('graph.apqc.title') + '</strong> ' +
                        '<span class="badge bg-secondary">' + t('graph.apqc.processes', countNodes(data)) + '</span></div>' +
                        '<div id="apqcTreeContainer"></div>';
                    var treeContainer = document.getElementById('apqcTreeContainer');
                    if (treeContainer) renderApqcTree(treeContainer, data);
                })
                .catch(function (err) {
                    if (content) {
                        content.innerHTML = '<div class="alert alert-warning py-1 px-2 small mb-0">' +
                            t('graph.apqc.load.failed', escapeHtml(err.message)) + '</div>';
                    }
                });
        } else {
            if (area) area.style.display = 'none';
            if (content) content.innerHTML = '';
        }
    }

    function countNodes(nodes) {
        var count = 0;
        if (!nodes) return count;
        for (var i = 0; i < nodes.length; i++) {
            count++;
            if (nodes[i].children) count += countNodes(nodes[i].children);
        }
        return count;
    }

    // ── Bootstrap initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        var upBtn = document.getElementById('graphUpstreamBtn');
        var downBtn = document.getElementById('graphDownstreamBtn');
        var failBtn = document.getElementById('graphFailureBtn');
        var apqcCheckbox = document.getElementById('graphApqcFilter');

        if (upBtn) upBtn.addEventListener('click', onUpstreamClick);
        if (downBtn) downBtn.addEventListener('click', onDownstreamClick);
        if (failBtn) failBtn.addEventListener('click', onFailureClick);
        if (apqcCheckbox) apqcCheckbox.addEventListener('change', onApqcFilterChange);
    });

    // ── Public API ────────────────────────────────────────────────────────────
    window.TaxonomyGraph = {
        openGraphExplorer: openGraphExplorer,
        populateNodeSuggestions: populateNodeSuggestions,
        fetchUpstream: fetchUpstream,
        fetchDownstream: fetchDownstream,
        fetchFailureImpact: fetchFailureImpact,
        showInModal: showInModal,
        renderNeighborhoodResult: renderNeighborhoodResult,
        renderFailureResult: renderFailureResult,
        renderImpactForceGraph: renderImpactForceGraph
    };

})();
