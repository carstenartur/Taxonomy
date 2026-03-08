/* taxonomy-graph.js – Graph Explorer UI for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    // ── Helpers ────────────────────────────────────────────────────────────────

    var MAX_AUTOCOMPLETE_SUGGESTIONS = 200;

    function escapeHtml(s) {
        if (!s) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

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
                'Querying graph&hellip;</div>';
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
        if (hop === 0) return '<span class="badge bg-dark">origin</span>';
        return '<span class="badge bg-light text-dark border">hop ' + hop + '</span>';
    }

    function renderElementsTable(elements, title) {
        if (!elements || elements.length === 0) return '';
        var html = '<h6 class="graph-section-title">' + escapeHtml(title) +
            ' <span class="badge bg-secondary">' + elements.length + '</span></h6>';
        html += '<div class="table-responsive"><table class="table table-sm table-hover graph-table mb-2">';
        html += '<thead><tr><th>Code</th><th>Title</th><th>Sheet</th><th>Relevance</th><th>Hop</th><th>Reason</th></tr></thead><tbody>';
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
        var html = '<h6 class="graph-section-title">Traversed Relationships' +
            ' <span class="badge bg-secondary">' + rels.length + '</span></h6>';
        html += '<div class="table-responsive"><table class="table table-sm table-hover graph-table mb-0">';
        html += '<thead><tr><th>Source</th><th></th><th>Target</th><th>Type</th><th>Relevance</th><th>Hop</th></tr></thead><tbody>';
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
        'Communications Services': '#E74C3C'
    };

    function getNodeColor(taxonomySheet) {
        return GRAPH_NODE_COLORS[taxonomySheet] || '#6c757d';
    }

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
        var height = Math.min(400, Math.max(250, nodes.length * 25));

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
        html += summaryCard(dirIcon, 'Direction', data.direction, 'primary');
        html += summaryCard('&#128205;', 'Origin', data.originNodeCode, 'dark');
        html += summaryCard('&#128101;', 'Neighbors', data.totalNeighbors, 'success');
        html += summaryCard('&#128279;', 'Relations', data.totalRelationships, 'info');
        html += summaryCard('&#128218;', 'Max Hops', data.maxHops, 'secondary');
        html += '</div>';

        // Notes
        if (data.notes && data.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-2">' +
                data.notes.map(function (n) { return escapeHtml(n); }).join('<br>') + '</div>';
        }

        html += renderElementsTable(data.neighbors, data.direction === 'UPSTREAM' ? 'Upstream Elements' : 'Downstream Elements');
        html += renderRelationshipsTable(data.traversedRelationships);

        return html;
    }

    // ── Failure Impact renderer ───────────────────────────────────────────────

    function renderFailureResult(data) {
        var html = '';

        // Summary stats
        html += '<div class="graph-stats-row">';
        html += summaryCard('&#9888;&#65039;', 'Failed Node', data.failedNodeCode, 'danger');
        html += summaryCard('&#128308;', 'Direct Impact', safeLen(data.directlyAffected), 'danger');
        html += summaryCard('&#128992;', 'Indirect Impact', safeLen(data.indirectlyAffected), 'warning');
        html += summaryCard('&#128101;', 'Total Affected', data.totalAffected, 'dark');
        html += summaryCard('&#128218;', 'Max Hops', data.maxHops, 'secondary');
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
            .catch(function (err) { showGraphError(label + ' query failed: ' + err.message); });
    }

    function fetchUpstream(nodeCode, maxHops, callback) {
        fetchGraphData('/api/graph/node/' + encodeURIComponent(nodeCode) + '/upstream?maxHops=' + maxHops,
            'Upstream', callback);
    }

    function fetchDownstream(nodeCode, maxHops, callback) {
        fetchGraphData('/api/graph/node/' + encodeURIComponent(nodeCode) + '/downstream?maxHops=' + maxHops,
            'Downstream', callback);
    }

    function fetchFailureImpact(nodeCode, maxHops, callback) {
        fetchGraphData('/api/graph/node/' + encodeURIComponent(nodeCode) + '/failure-impact?maxHops=' + maxHops,
            'Failure impact', callback);
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    function onUpstreamClick() {
        var code = getNodeCode();
        if (!code) { showGraphError('Please enter a node code.'); return; }
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
        if (!code) { showGraphError('Please enter a node code.'); return; }
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
        if (!code) { showGraphError('Please enter a node code.'); return; }
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

    // ── Bootstrap initialization ──────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        var upBtn = document.getElementById('graphUpstreamBtn');
        var downBtn = document.getElementById('graphDownstreamBtn');
        var failBtn = document.getElementById('graphFailureBtn');

        if (upBtn) upBtn.addEventListener('click', onUpstreamClick);
        if (downBtn) downBtn.addEventListener('click', onDownstreamClick);
        if (failBtn) failBtn.addEventListener('click', onFailureClick);
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
        renderFailureResult: renderFailureResult
    };

})();
