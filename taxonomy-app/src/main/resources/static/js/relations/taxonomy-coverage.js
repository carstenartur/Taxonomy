/**
 * taxonomy-coverage.js — Requirement Coverage Dashboard module
 *
 * Shows coverage statistics: how many requirements cover each taxonomy node,
 * top-covered nodes, and nodes with no coverage (gap candidates).
 * Lets the user record the current analysis result against a requirement ID.
 */
(function () {
    'use strict';
    var t = TaxonomyI18n.t;

    /* ------------------------------------------------------------------ */
    /*  Bootstrap on DOMContentLoaded                                      */
    /* ------------------------------------------------------------------ */
    document.addEventListener('DOMContentLoaded', function () {
        var refreshBtn = document.getElementById('coverageRefreshBtn');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', loadCoverageDashboard);
        }

        var recordBtn = document.getElementById('coverageRecordBtn');
        if (recordBtn) {
            recordBtn.addEventListener('click', recordCurrentAnalysis);
        }

        var panel = document.getElementById('coverageDashboard');
        if (panel) {
            panel.addEventListener('toggle', function () {
                if (panel.open) loadCoverageDashboard();
            });
        }
    });

    /* ------------------------------------------------------------------ */
    /*  Load coverage statistics                                           */
    /* ------------------------------------------------------------------ */
    function loadCoverageDashboard() {
        var content = document.getElementById('coverageDashboardContent');
        if (!content) return;
        content.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> ' + t('coverage.loading') + '</div>';

        fetch('/api/coverage/statistics')
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(function (stats) { renderDashboard(stats); })
            .catch(function () {
                content.innerHTML = '<div class="text-danger small p-2">\u26A0\uFE0F ' + t('coverage.load.failed') + '</div>';
            });
    }

    /* ------------------------------------------------------------------ */
    /*  Render dashboard                                                   */
    /* ------------------------------------------------------------------ */
    function renderDashboard(stats) {
        var content = document.getElementById('coverageDashboardContent');
        if (!content) return;
        var html = '';

        /* Summary badges */
        html += '<div class="d-flex gap-2 flex-wrap mb-2">';
        html += metricBadge(t('coverage.total.nodes'), stats.totalNodes, 'bg-primary');
        html += metricBadge(t('coverage.covered'), stats.coveredNodes, 'bg-success');
        html += metricBadge(t('coverage.uncovered'), stats.uncoveredNodes, stats.uncoveredNodes > 0 ? 'bg-warning text-dark' : 'bg-secondary');
        html += metricBadge(t('coverage.coverage'), (stats.coveragePercentage != null ? stats.coveragePercentage : 0).toFixed(1) + '%', coverageClass(stats.coveragePercentage || 0));
        html += metricBadge(t('coverage.requirements'), stats.totalRequirements, 'bg-info text-dark');
        html += metricBadge(t('coverage.avg.per.node'), (stats.avgRequirementsPerNode != null ? stats.avgRequirementsPerNode : 0).toFixed(2), 'bg-secondary');
        html += '</div>';

        /* Top-covered nodes */
        if (stats.topCovered && stats.topCovered.length > 0) {
            html += '<div class="small text-muted mb-1">' + t('coverage.top.covered') + '</div>';
            html += '<table class="table table-sm table-bordered mb-2 coverage-table" style="font-size:0.82em;">';
            html += '<thead><tr><th>' + t('coverage.table.node') + '</th><th>' + t('coverage.table.requirements') + '</th></tr></thead><tbody>';
            stats.topCovered.forEach(function (e) {
                html += '<tr>';
                html += '<td><a href="#" class="coverage-node-link" data-code="' + escapeHtml(e.nodeCode) + '">' + escapeHtml(e.nodeCode) + '</a></td>';
                html += '<td>' + e.requirementCount + '</td>';
                html += '</tr>';
            });
            html += '</tbody></table>';
        }

        /* Gap candidates */
        if (stats.gapCandidates && stats.gapCandidates.length > 0) {
            html += '<div class="small text-muted mb-1">' + t('coverage.gap.candidates') + '</div>';
            html += '<table class="table table-sm table-bordered mb-0 coverage-table" style="font-size:0.82em;">';
            html += '<thead><tr><th>' + t('coverage.table.node') + '</th><th>' + t('coverage.table.requirements') + '</th></tr></thead><tbody>';
            stats.gapCandidates.forEach(function (e) {
                html += '<tr>';
                html += '<td>' + escapeHtml(e.nodeCode) + '</td>';
                html += '<td><span class="badge bg-secondary">0</span></td>';
                html += '</tr>';
            });
            html += '</tbody></table>';
        }

        content.innerHTML = html || '<div class="text-muted small p-2">' + t('coverage.no.data.hint') + '</div>';

        /* Attach click handlers for node code links */
        content.querySelectorAll('.coverage-node-link').forEach(function (link) {
            link.addEventListener('click', function (ev) {
                ev.preventDefault();
                loadNodeCoverage(link.dataset.code);
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Show requirements for a single node                                */
    /* ------------------------------------------------------------------ */
    function loadNodeCoverage(nodeCode) {
        var content = document.getElementById('coverageDashboardContent');
        if (!content) return;
        content.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> ' + t('coverage.loading.node') + '</div>';

        fetch('/api/coverage/node/' + encodeURIComponent(nodeCode))
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(function (entries) {
                var html = '<div class="mb-2"><button id="coverageBackBtn" class="btn btn-sm btn-outline-secondary">' + t('coverage.back') + '</button>'
                         + ' <strong>' + escapeHtml(nodeCode) + '</strong> \u2014 ' + t('coverage.node.requirements', entries.length) + '</div>';
                if (entries.length > 0) {
                    html += '<table class="table table-sm table-bordered mb-0" style="font-size:0.82em;">';
                    html += '<thead><tr><th>' + t('coverage.table.req.id') + '</th><th>' + t('coverage.table.score') + '</th><th>' + t('coverage.table.analyzed') + '</th></tr></thead><tbody>';
                    entries.forEach(function (e) {
                        html += '<tr>';
                        html += '<td title="' + escapeHtml(e.requirementText || '') + '">' + escapeHtml(e.requirementId) + '</td>';
                        html += '<td>' + e.score + '</td>';
                        html += '<td>' + (e.analyzedAt ? new Date(e.analyzedAt).toLocaleString() : '—') + '</td>';
                        html += '</tr>';
                    });
                    html += '</tbody></table>';
                } else {
                    html += '<div class="text-muted small">' + t('coverage.node.no.requirements') + '</div>';
                }
                content.innerHTML = html;
                var backBtn = document.getElementById('coverageBackBtn');
                if (backBtn) backBtn.addEventListener('click', loadCoverageDashboard);
            })
            .catch(function () {
                content.innerHTML = '<div class="text-danger small p-2">\u26A0\uFE0F ' + t('coverage.load.node.failed') + '</div>';
            });
    }

    /* ------------------------------------------------------------------ */
    /*  Record current analysis                                            */
    /* ------------------------------------------------------------------ */
    function recordCurrentAnalysis() {
        var scores = (typeof window._getCurrentScores === 'function') ? window._getCurrentScores() : null;
        if (!scores || Object.keys(scores).length === 0) {
            alert(t('coverage.no.scores'));
            return;
        }

        var reqId = prompt(t('coverage.prompt.req.id'));
        if (!reqId || reqId.trim() === '') return;

        var reqText = '';
        var businessTextEl = document.getElementById('businessText');
        if (businessTextEl) reqText = businessTextEl.value || '';

        fetch('/api/coverage/record', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                requirementId: reqId.trim(),
                requirementText: reqText,
                scores: scores,
                minScore: 50
            })
        })
        .then(function (r) {
            if (r.ok) {
                loadCoverageDashboard();
            } else {
                alert(t('coverage.record.failed', r.status));
            }
        })
        .catch(function () {
            alert(t('coverage.record.error'));
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */
    function metricBadge(label, value, cls) {
        return '<span class="badge ' + cls + '">' + label + ': ' + value + '</span>';
    }

    function coverageClass(pct) {
        if (pct >= 70) return 'bg-success';
        if (pct >= 40) return 'bg-warning text-dark';
        return 'bg-danger';
    }

    var escapeHtml = TaxonomyUtils.escapeHtml;

    /* ------------------------------------------------------------------ */
    /*  Public API                                                         */
    /* ------------------------------------------------------------------ */
    window.TaxonomyCoverage = {
        loadCoverageDashboard: loadCoverageDashboard
    };
})();
