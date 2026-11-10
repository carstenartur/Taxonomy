/* taxonomy-graph.js – Graph Explorer UI for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    var t = TaxonomyI18n.t;

    // ── Helpers ────────────────────────────────────────────────────────────────

    var MAX_AUTOCOMPLETE_SUGGESTIONS = 200;
    var resultSequence = 0;
    var nodeQuery = 'upstream';
    var selectionPending = false;
    var selectedNodeCode = null;

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

    // Node, requirement and hierarchy queries share one visible result. A token
    // also prevents an older response from restoring a result after switching.
    function clearResults() {
        resultSequence += 1;
        selectedNodeCode = null;
        selectionPending = false;
        ['graphResultsArea', 'requirementImpactResults', 'graphResultContext'].forEach(function (id) {
            var area = document.getElementById(id);
            if (area) area.style.display = 'none';
        });
        ['graphResultsContent', 'requirementImpactResults', 'graphResultContext'].forEach(function (id) {
            var content = document.getElementById(id);
            if (content) content.innerHTML = '';
        });
    }

    function beginResult(kind, code, queryMode) {
        clearResults();
        if (kind === 'node') {
            selectedNodeCode = code;
            if (queryMode) nodeQuery = queryMode;
        }
        var area = document.getElementById(kind === 'requirement'
            ? 'requirementImpactResults' : 'graphResultsArea');
        if (area) area.style.display = '';
        var context = document.getElementById('graphResultContext');
        if (context) {
            var label = kind === 'requirement' ? t('graph.result.requirement')
                : kind === 'apqc' ? t('graph.apqc.title') : t('graph.result.node', code || '—');
            context.innerHTML = '<strong>' + escapeHtml(label) + '</strong>';
            context.style.display = '';
        }
        var apqc = document.getElementById('graphApqcFilter');
        if (apqc) apqc.checked = kind === 'apqc';
        return resultSequence;
    }

    function isCurrentResult(sequence) {
        return sequence === resultSequence;
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
            content.innerHTML = '<div class="alert alert-warning py-1 px-2 small mb-0" role="alert">' +
                escapeHtml(msg) + '</div>';
        }
    }

    // ── Render helpers ────────────────────────────────────────────────────────

    function evidenceTuple(value) {
        if (!value) return null;
        var workspace = Object.hasOwn(value, 'workspaceId')
            ? value.workspaceId || '__shared__' : value.workspaceScopeKey;
        if (value.workspaceScopeKey && value.workspaceScopeKey !== workspace) return null;
        return [value.repositoryId, workspace, value.branch, value.commitId || value.commit];
    }

    async function isCurrentEvidence(context, evidence) {
        try {
            var current = evidenceTuple(await AnalysisApi.captureContext());
            var expected = evidenceTuple(context), returned = evidenceTuple(evidence);
            return Boolean(current && expected && returned && expected.every(function (value, index) {
                return typeof value === 'string' && value.length > 0 && value === current[index] && value === returned[index];
            }));
        } catch (error) { return false; }
    }

    function renderEvidence(evidence) {
        if (!evidence || !evidence.commitId) return '<div class="alert alert-warning small" role="status">'
            + escapeHtml(t('graph.evidence.unavailable')) + '</div>';
        var bounds = evidence.effectiveMaxHops > 0 ? '<p class="mb-1">' + escapeHtml(t('graph.evidence.bounds',
            evidence.effectiveMaxHops, evidence.requestedMaxHops, Math.round(evidence.minimumRelevance * 100), evidence.hopDecay)) + '</p>' : '';
        var weights = Object.entries(evidence.relationTypeWeights || {}).map(function (entry) {
            return escapeHtml(entry[0]) + ': ' + escapeHtml(entry[1]);
        }).join(' · ');
        var details = '<details><summary>' + escapeHtml(t('graph.evidence.details')) + '</summary><p class="mb-1">'
            + escapeHtml(t('graph.evidence.policy', evidence.queryPolicy, evidence.readAuthority)) + '</p><p class="mb-1">'
            + escapeHtml(t('graph.evidence.readiness', evidence.readinessState, evidence.snapshotRelationCount)) + '</p>'
            + (evidence.effectiveMaxHops > 0 ? '<p class="mb-1">' + escapeHtml(t('graph.evidence.anchors',
                evidence.anchorHighThreshold, evidence.anchorLowThreshold, evidence.minimumAnchors)) + '</p>' : '')
            + (weights ? '<p class="mb-1">' + escapeHtml(t('graph.evidence.weights')) + ': ' + weights + '</p>' : '') + '</details>';
        return '<section class="graph-evidence border rounded p-2 mb-2 small" aria-label="' + escapeHtml(t('graph.evidence.title')) + '">'
            + '<strong>' + escapeHtml(t('graph.evidence.title')) + '</strong><p class="mb-1 text-break">'
            + escapeHtml(t('graph.evidence.scope', evidence.repositoryId, evidence.workspaceId || evidence.workspaceScopeKey,
                evidence.branch, evidence.commitId)) + '</p>' + bounds + '<p class="mb-1">'
            + escapeHtml(t('graph.evidence.state.' + evidence.completeness)) + '</p><p class="mb-1">'
            + escapeHtml(t('graph.evidence.scopeLimit')) + '</p>' + details + '</section>';
    }

    function renderEdgeEvidence(evidence) {
        if (!evidence) return escapeHtml(t('graph.evidence.notRecorded'));
        return '<details class="small"><summary>' + escapeHtml(t('graph.evidence.edge')) + '</summary><dl>'
            + ['provenance', 'status', 'causation', 'commit', 'confidence'].map(function (field) {
                var value = evidence[{causation:'causationId', commit:'commitId'}[field] || field];
                if (field === 'confidence' && typeof value === 'number') value = Math.round(value * 100) + '%';
                return '<dt>' + escapeHtml(t('graph.evidence.' + field)) + '</dt><dd>'
                    + escapeHtml(value === null || value === undefined || value === '' ? t('graph.evidence.notRecorded') : value) + '</dd>';
            }).join('') + '</dl></details>';
    }

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
                '<td>' + escapeHtml(e.title || '') + (e.nodeEvidence ? '<small class="d-block text-muted">' + escapeHtml(t('graph.node.source.' + e.nodeEvidence.source)) + '</small>' : '') + '</td>' +
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
        html += '<thead><tr><th>' + escapeHtml(t('graph.header.source')) + '</th><th></th><th>' + escapeHtml(t('graph.header.target')) + '</th><th>' + escapeHtml(t('graph.header.type')) + '</th><th>' + escapeHtml(t('graph.header.relevance')) + '</th><th>' + escapeHtml(t('graph.header.hop')) + '</th><th>' + escapeHtml(t('graph.evidence.edge')) + '</th></tr></thead><tbody>';
        rels.forEach(function (r) {
            html += '<tr>' +
                '<td>' + escapeHtml(r.sourceCode) + '</td>' +
                '<td class="text-center">&rarr;</td>' +
                '<td>' + escapeHtml(r.targetCode) + '</td>' +
                '<td><span class="badge bg-info text-dark">' + escapeHtml(r.relationType) + '</span></td>' +
                '<td>' + relevanceBadge(r.propagatedRelevance) + '</td>' +
                '<td>' + hopBadge(r.hopDistance) + '</td><td>' + renderEdgeEvidence(r.evidence) + '</td>' +
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

    var GRAPH_NODE_COLOR_TOKENS = {
        'Capabilities': '--taxonomy-layer-cap-surface',
        'CP': '--taxonomy-layer-cap-surface',
        'Business Processes': '--taxonomy-layer-proc-surface',
        'Business Roles': '--taxonomy-layer-proc-surface',
        'BP': '--taxonomy-layer-proc-surface',
        'BR': '--taxonomy-layer-proc-surface',
        'Services': '--taxonomy-layer-svc-surface',
        'COI Services': '--taxonomy-layer-svc-surface',
        'Core Services': '--taxonomy-layer-svc-surface',
        'CI': '--taxonomy-layer-svc-surface',
        'CR': '--taxonomy-layer-svc-surface',
        'Applications': '--taxonomy-layer-app-surface',
        'User Applications': '--taxonomy-layer-app-surface',
        'UA': '--taxonomy-layer-app-surface',
        'Information Products': '--taxonomy-layer-info-surface',
        'IP': '--taxonomy-layer-info-surface',
        'Communications Services': '--taxonomy-layer-comm-surface',
        'CO': '--taxonomy-layer-comm-surface',
        'Systems': '--taxonomy-layer-system-surface',
        'Components': '--taxonomy-layer-component-surface'
    };

    var APQC_LEVEL_COLORS = {
        'Category':     '#1B4F72',
        'ProcessGroup': '#2E86C1',
        'Process':      '#5DADE2',
        'Activity':     '#AED6F1',
        'Task':         '#D6EAF8'
    };

    function resolveGraphNodeColorPalette() {
        var styles = getComputedStyle(document.documentElement);
        var tokenNames = new Set(Object.values(GRAPH_NODE_COLOR_TOKENS));
        tokenNames.add('--taxonomy-layer-default-surface');
        var palette = {};
        tokenNames.forEach(function (token) {
            var value = styles.getPropertyValue(token).trim();
            if (!/^#[0-9a-fA-F]{6}$/.test(value)) {
                throw new Error('Missing or invalid required taxonomy color token: ' + token);
            }
            palette[token] = value;
        });
        return palette;
    }

    function getNodeColor(taxonomySheet, palette) {
        var token = GRAPH_NODE_COLOR_TOKENS[taxonomySheet]
            || '--taxonomy-layer-default-surface';
        var value = palette[token];
        if (!value) {
            throw new Error('Unresolved required taxonomy color token: ' + token);
        }
        return value;
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

        var nodeColorPalette = resolveGraphNodeColorPalette();
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
            .attr('fill', function (d) { return getNodeColor(d.sheet, nodeColorPalette); })
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
                        getNodeColor(sheet, nodeColorPalette) + '"></span> ' + sheet);
            }
        });
        legendDiv.append('span').attr('class', 'force-graph-legend-item')
            .html('<span class="force-graph-legend-dot" style="background:#000;border:2px solid #000;width:10px;height:10px;"></span> Origin');
    }

    /**
     * Wrap graph results with a toggle for Graph/Table view.
     */
    function wrapWithGraphToggle(nodes, edges, originCode, tableHtml, evidence) {
        var content = document.getElementById('graphResultsContent');
        if (!content) return;

        var toggleHtml = '<div class="graph-view-toggle">' +
            '<button class="btn btn-sm btn-primary graph-toggle-btn" data-mode="graph">🔗 Graph</button>' +
            '<button class="btn btn-sm btn-outline-secondary graph-toggle-btn" data-mode="table">📊 Table</button>' +
            '</div>';

        var emptyHtml = !nodes || nodes.length === 0
            ? '<div class="alert alert-info py-1 px-2 small" role="status">'
                + escapeHtml(t('graph.empty')) + '</div>' : '';
        content.innerHTML = renderEvidence(evidence) + emptyHtml + toggleHtml +
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
        var html = inline ? '' : renderEvidence(data.evidence);

        // Summary stats
        var dirIcon = data.direction === 'UPSTREAM' ? '&#11014;&#65039;' : '&#11015;&#65039;';
        html += '<div class="graph-stats-row">';
        html += summaryCard(dirIcon, t('graph.summary.direction'), data.direction, 'primary');
        html += summaryCard('&#128205;', t('graph.summary.origin'), data.originNodeCode, 'dark');
        html += summaryCard('&#128101;', t('graph.summary.neighbors'), data.totalNeighbors, 'secondary');
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

    function renderFailureResult(data, inline) {
        var html = inline ? '' : renderEvidence(data.evidence);

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

    async function fetchGraphData(request, label, callback) {
        var sequence = resultSequence;
        try {
            var context = await AnalysisApi.captureContext();
            if (!isCurrentResult(sequence)) return;
            var data = await request(context);
            if (!isCurrentResult(sequence)) return;
            var current = await isCurrentEvidence(context, data.evidence);
            if (!isCurrentResult(sequence)) return;
            if (current) callback(data);
            else showGraphError(t('graph.evidence.changed'));
        } catch (error) {
            if (isCurrentResult(sequence)) showGraphError(t('graph.query.failed', label, error.message));
        }
    }

    function fetchUpstream(nodeCode, maxHops, callback) {
        return fetchGraphData(function (context) { return AnalysisApi.graphUpstream(nodeCode, maxHops, context); },
            t('graph.label.upstream'), callback);
    }
    function fetchDownstream(nodeCode, maxHops, callback) {
        return fetchGraphData(function (context) { return AnalysisApi.graphDownstream(nodeCode, maxHops, context); },
            t('graph.label.downstream'), callback);
    }
    function fetchFailureImpact(nodeCode, maxHops, callback) {
        return fetchGraphData(function (context) { return AnalysisApi.graphFailureImpact(nodeCode, maxHops, context); },
            t('graph.label.failure'), callback);
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    function onUpstreamClick() {
        nodeQuery = 'upstream';
        selectionPending = false;
        var code = getNodeCode();
        beginResult('node', code);
        if (!code) { showGraphError(t('graph.enter.code')); return; }
        showGraphLoading();
        fetchUpstream(code, getMaxHops(), function (data) {
            var tableHtml = renderNeighborhoodResult(data, true);
            var allNodes = data.neighbors || [];
            var edges = data.traversedRelationships || [];
            wrapWithGraphToggle(allNodes, edges, data.originNodeCode, tableHtml, data.evidence);
        });
    }

    function onDownstreamClick() {
        nodeQuery = 'downstream';
        selectionPending = false;
        var code = getNodeCode();
        beginResult('node', code);
        if (!code) { showGraphError(t('graph.enter.code')); return; }
        showGraphLoading();
        fetchDownstream(code, getMaxHops(), function (data) {
            var tableHtml = renderNeighborhoodResult(data, true);
            var allNodes = data.neighbors || [];
            var edges = data.traversedRelationships || [];
            wrapWithGraphToggle(allNodes, edges, data.originNodeCode, tableHtml, data.evidence);
        });
    }

    function onFailureClick() {
        nodeQuery = 'failure';
        selectionPending = false;
        var code = getNodeCode();
        beginResult('node', code);
        if (!code) { showGraphError(t('graph.enter.code')); return; }
        showGraphLoading();
        fetchFailureImpact(code, getMaxHops(), function (data) {
            var tableHtml = renderFailureResult(data, true);
            var allNodes = (data.directlyAffected || []).concat(data.indirectlyAffected || []);
            var edges = data.traversedRelationships || [];
            wrapWithGraphToggle(allNodes, edges, data.failedNodeCode, tableHtml, data.evidence);
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

    // Intentional selections share the graph's current query without navigating,
    // scrolling, or moving keyboard focus away from the taxonomy diagram.
    function refreshSelectedNode() {
        if (!selectionPending) return;
        if (nodeQuery === 'downstream') onDownstreamClick();
        else if (nodeQuery === 'failure') onFailureClick();
        else if (nodeQuery === 'enriched') window.TaxonomyAnalysis.runEnrichedFailureImpact();
        else onUpstreamClick();
    }

    function selectNode(code) {
        code = typeof code === 'string' ? code.trim() : '';
        var input = document.getElementById('graphNodeInput');
        if (!code || !input) return;
        input.value = code;
        if (selectedNodeCode === code) return;
        clearResults();
        selectedNodeCode = code;
        selectionPending = true;
        var pane = document.getElementById('tab-graph');
        if (pane && !pane.classList.contains('d-none') && !pane.hidden) refreshSelectedNode();
    }

    // ── Open Graph Explorer for a specific node (called from taxonomy tree) ──

    function openGraphExplorer(nodeCode) {
        selectionPending = false;
        if (typeof window.navigateToPage === 'function') window.navigateToPage('graph');
        var input = document.getElementById('graphNodeInput');
        if (input) input.value = typeof nodeCode === 'string' ? nodeCode.trim() : '';

        // Reveal the page before scrolling or measuring the graph's layout.
        var panel = document.getElementById('graphExplorerPanel');
        if (panel) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
        if (input) input.focus({ preventScroll: true });
        onUpstreamClick();
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
            var sequence = beginResult('apqc');
            if (area) area.style.display = '';
            if (content) {
                content.innerHTML =
                    '<div class="text-center text-muted py-2">' +
                    '<div class="spinner-border spinner-border-sm me-1" role="status"></div> ' +
                    'Loading APQC hierarchy&hellip;</div>';
            }
            fetchGraphData(function (context) { return AnalysisApi.apqcHierarchy(context); }, t('graph.apqc.title'), function (data) {
                if (!content || !isCurrentResult(sequence)) return;
                var nodes = data.nodes || [];
                content.innerHTML = renderEvidence(data.evidence) + '<div class="mb-1"><strong>' + escapeHtml(t('graph.apqc.title')) + '</strong> '
                    + '<span class="badge bg-secondary">' + escapeHtml(t('graph.apqc.processes', countNodes(nodes))) + '</span></div>'
                    + (nodes.length ? '<div id="apqcTreeContainer"></div>' : '<p role="status">' + escapeHtml(t('graph.apqc.no.data')) + '</p>');
                var treeContainer = document.getElementById('apqcTreeContainer');
                if (treeContainer) renderApqcTree(treeContainer, nodes);
            });
        } else {
            clearResults();
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
    document.addEventListener('taxonomy:analysis-invalidated', function () {
        selectionPending = false;
        clearResults();
    });
    ['taxonomy:lifecycle-changed', 'taxonomy:architecture-reconciled', 'taxonomy:architecture-source-changed'].forEach(function (event) {
        document.addEventListener(event, clearResults);
    });
    document.addEventListener('taxonomy:page-activated', function (event) {
        if (event.detail?.page === 'graph') refreshSelectedNode();
    });

    // ── Public API ────────────────────────────────────────────────────────────
    window.TaxonomyGraph = {
        renderEvidence: renderEvidence,
        isCurrentEvidence: isCurrentEvidence,
        beginResult: beginResult,
        isCurrentResult: isCurrentResult,
        clearResults: clearResults,
        openGraphExplorer: openGraphExplorer,
        selectNode: selectNode,
        populateNodeSuggestions: populateNodeSuggestions,
        fetchUpstream: fetchUpstream,
        fetchDownstream: fetchDownstream,
        fetchFailureImpact: fetchFailureImpact,
        showInModal: showInModal,
        renderNeighborhoodResult: renderNeighborhoodResult,
        renderFailureResult: renderFailureResult,
        renderElementsTable: renderElementsTable,
        renderRelationshipsTable: renderRelationshipsTable,
        attachNodeLinks: attachNodeLinks
    };

})();
