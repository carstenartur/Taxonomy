/**
 * taxonomy-quality.js — Quality Dashboard module
 *
 * Displays relation proposal quality metrics: overall stats,
 * breakdown by type and provenance, and top-rejected proposals.
 */
(function () {
    'use strict';

    /* ------------------------------------------------------------------ */
    /*  Bootstrap on DOMContentLoaded                                      */
    /* ------------------------------------------------------------------ */
    document.addEventListener('DOMContentLoaded', function () {
        var refreshBtn = document.getElementById('qualityRefreshBtn');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', loadQualityDashboard);
        }
        var panel = document.getElementById('qualityDashboard');
        if (panel) {
            panel.addEventListener('toggle', function () {
                if (panel.open) loadQualityDashboard();
            });
        }
    });

    /* ------------------------------------------------------------------ */
    /*  Load all metrics in parallel                                       */
    /* ------------------------------------------------------------------ */
    function loadQualityDashboard() {
        var content = document.getElementById('qualityDashboardContent');
        if (!content) return;
        content.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> Loading metrics\u2026</div>';

        Promise.all([
            fetch('/api/relations/metrics').then(function (r) { return r.ok ? r.json() : null; }),
            fetch('/api/relations/metrics/by-type').then(function (r) { return r.ok ? r.json() : null; }),
            fetch('/api/relations/metrics/top-rejected?limit=5').then(function (r) { return r.ok ? r.json() : null; })
        ]).then(function (results) {
            renderDashboard(results[0], results[1], results[2]);
        }).catch(function () {
            content.innerHTML = '<div class="text-danger small p-2">\u26A0\uFE0F Failed to load quality metrics.</div>';
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Render dashboard                                                   */
    /* ------------------------------------------------------------------ */
    function renderDashboard(metrics, byType, topRejected) {
        var content = document.getElementById('qualityDashboardContent');
        if (!content) return;
        var html = '';

        /* Summary row */
        if (metrics) {
            var rate = (typeof metrics.acceptanceRate === 'number') ? (metrics.acceptanceRate * 100).toFixed(1) : '—';
            html += '<div class="d-flex gap-2 flex-wrap mb-2">';
            html += metricBadge('Total', metrics.totalProposals, 'bg-primary');
            html += metricBadge('Accepted', metrics.accepted, 'bg-success');
            html += metricBadge('Rejected', metrics.rejected, 'bg-danger');
            html += metricBadge('Pending', metrics.pending, 'bg-warning text-dark');
            html += metricBadge('Rate', rate + '%', rateClass(metrics.acceptanceRate));
            html += '</div>';
        }

        /* By-type breakdown */
        if (byType && byType.length > 0) {
            html += '<div class="small text-muted mb-1">By relation type:</div>';
            html += '<table class="table table-sm table-bordered mb-2 quality-table" style="font-size:0.82em;">';
            html += '<thead><tr><th>Type</th><th>Proposed</th><th>Accepted</th><th>Rejected</th><th>Rate</th></tr></thead><tbody>';
            byType.forEach(function (t) {
                var r = (typeof t.acceptanceRate === 'number') ? (t.acceptanceRate * 100).toFixed(0) + '%' : '—';
                html += '<tr><td>' + escapeHtml(t.relationType) + '</td><td>' + t.proposed + '</td><td>' + t.accepted + '</td><td>' + t.rejected + '</td><td>' + r + '</td></tr>';
            });
            html += '</tbody></table>';
        }

        /* Top rejected */
        if (topRejected && topRejected.length > 0) {
            html += '<div class="small text-muted mb-1">Top rejected (highest confidence):</div>';
            html += '<table class="table table-sm table-bordered mb-0 quality-table" style="font-size:0.82em;">';
            html += '<thead><tr><th>Source</th><th>Target</th><th>Type</th><th>Conf.</th></tr></thead><tbody>';
            topRejected.forEach(function (p) {
                html += '<tr title="' + escapeHtml(p.rationale || '') + '">';
                html += '<td>' + escapeHtml(p.sourceCode) + '</td>';
                html += '<td>' + escapeHtml(p.targetCode) + '</td>';
                html += '<td>' + escapeHtml(p.relationType) + '</td>';
                html += '<td>' + (p.confidence * 100).toFixed(0) + '%</td>';
                html += '</tr>';
            });
            html += '</tbody></table>';
        }

        content.innerHTML = html || '<div class="text-muted small p-2">No quality data available.</div>';
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */
    function metricBadge(label, value, cls) {
        return '<span class="badge ' + cls + '">' + label + ': ' + value + '</span>';
    }

    function rateClass(rate) {
        if (rate >= 0.7) return 'bg-success';
        if (rate >= 0.4) return 'bg-warning text-dark';
        return 'bg-danger';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    /* ------------------------------------------------------------------ */
    /*  Public API                                                         */
    /* ------------------------------------------------------------------ */
    window.TaxonomyQuality = {
        loadQualityDashboard: loadQualityDashboard
    };
})();
