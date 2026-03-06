/* taxonomy-graph.js – Graph Explorer UI for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    // ── Helpers ────────────────────────────────────────────────────────────────

    function escapeHtml(s) {
        if (!s) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
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
        html += summaryCard('&#128308;', 'Direct Impact', data.directlyAffected ? data.directlyAffected.length : 0, 'danger');
        html += summaryCard('&#128992;', 'Indirect Impact', data.indirectlyAffected ? data.indirectlyAffected.length : 0, 'warning');
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

    function fetchUpstream(nodeCode, maxHops, callback) {
        fetch('/api/graph/node/' + encodeURIComponent(nodeCode) + '/upstream?maxHops=' + maxHops)
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(callback)
            .catch(function (err) { showGraphError('Upstream query failed: ' + err.message); });
    }

    function fetchDownstream(nodeCode, maxHops, callback) {
        fetch('/api/graph/node/' + encodeURIComponent(nodeCode) + '/downstream?maxHops=' + maxHops)
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(callback)
            .catch(function (err) { showGraphError('Downstream query failed: ' + err.message); });
    }

    function fetchFailureImpact(nodeCode, maxHops, callback) {
        fetch('/api/graph/node/' + encodeURIComponent(nodeCode) + '/failure-impact?maxHops=' + maxHops)
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(callback)
            .catch(function (err) { showGraphError('Failure impact query failed: ' + err.message); });
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    function onUpstreamClick() {
        var code = getNodeCode();
        if (!code) { showGraphError('Please enter a node code.'); return; }
        showGraphLoading();
        fetchUpstream(code, getMaxHops(), function (data) {
            var content = document.getElementById('graphResultsContent');
            if (content) content.innerHTML = renderNeighborhoodResult(data, true);
            attachNodeLinks();
        });
    }

    function onDownstreamClick() {
        var code = getNodeCode();
        if (!code) { showGraphError('Please enter a node code.'); return; }
        showGraphLoading();
        fetchDownstream(code, getMaxHops(), function (data) {
            var content = document.getElementById('graphResultsContent');
            if (content) content.innerHTML = renderNeighborhoodResult(data, true);
            attachNodeLinks();
        });
    }

    function onFailureClick() {
        var code = getNodeCode();
        if (!code) { showGraphError('Please enter a node code.'); return; }
        showGraphLoading();
        fetchFailureImpact(code, getMaxHops(), function (data) {
            var content = document.getElementById('graphResultsContent');
            if (content) content.innerHTML = renderFailureResult(data);
            attachNodeLinks();
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
        // Limit to first 200 for performance
        codes.slice(0, 200).forEach(function (item) {
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
