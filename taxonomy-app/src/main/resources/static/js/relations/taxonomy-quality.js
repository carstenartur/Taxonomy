/**
 * taxonomy-quality.js — Quality Dashboard module
 *
 * Displays relation proposal quality metrics: overall stats,
 * breakdown by type and provenance, and top-rejected proposals.
 */
(function () {
    'use strict';
    var t = TaxonomyI18n.t;

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
        content.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> ' + t('quality.loading') + '</div>';

        Promise.all([
            fetch('/api/relations/metrics').then(function (r) { return r.ok ? r.json() : null; }),
            fetch('/api/relations/metrics/by-type').then(function (r) { return r.ok ? r.json() : null; }),
            fetch('/api/relations/metrics/top-rejected?limit=5').then(function (r) { return r.ok ? r.json() : null; })
        ]).then(function (results) {
            renderDashboard(results[0], results[1], results[2]);
        }).catch(function () {
            content.innerHTML = '<div class="text-danger small p-2">\u26A0\uFE0F ' + t('quality.load.failed') + '</div>';
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
            html += metricBadge(t('quality.total'), metrics.totalProposals, 'bg-primary');
            html += metricBadge(t('quality.accepted'), metrics.accepted, 'bg-success');
            html += metricBadge(t('quality.rejected'), metrics.rejected, 'bg-danger');
            html += metricBadge(t('quality.pending'), metrics.pending, 'bg-warning text-dark');
            html += metricBadge(t('quality.rate'), rate + '%', rateClass(metrics.acceptanceRate));
            html += '</div>';
        }

        /* By-type breakdown */
        if (byType && byType.length > 0) {
            html += '<div class="small text-muted mb-1">' + t('quality.by.type') + '</div>';
            html += '<table class="table table-sm table-bordered mb-2 quality-table" style="font-size:0.82em;">';
            html += '<thead><tr><th>' + t('quality.table.type') + '</th><th>' + t('quality.table.proposed') + '</th><th>' + t('quality.table.accepted') + '</th><th>' + t('quality.table.rejected') + '</th><th>' + t('quality.table.rate') + '</th></tr></thead><tbody>';
            byType.forEach(function (row) {
                var r = (typeof row.acceptanceRate === 'number') ? (row.acceptanceRate * 100).toFixed(0) + '%' : '—';
                html += '<tr><td>' + escapeHtml(row.relationType) + '</td><td>' + row.proposed + '</td><td>' + row.accepted + '</td><td>' + row.rejected + '</td><td>' + r + '</td></tr>';
            });
            html += '</tbody></table>';
        }

        /* Top rejected */
        if (topRejected && topRejected.length > 0) {
            html += '<div class="small text-muted mb-1">' + t('quality.top.rejected') + '</div>';
            html += '<table class="table table-sm table-bordered mb-0 quality-table" style="font-size:0.82em;">';
            html += '<thead><tr><th>' + t('quality.table.source') + '</th><th>' + t('quality.table.target') + '</th><th>' + t('quality.table.type') + '</th><th>' + t('quality.table.confidence') + '</th></tr></thead><tbody>';
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

        content.innerHTML = html || '<div class="text-muted small p-2">' + t('quality.no.data') + '</div>';
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

    var escapeHtml = TaxonomyUtils.escapeHtml;

    /* ------------------------------------------------------------------ */
    /*  Public API                                                         */
    /* ------------------------------------------------------------------ */
    window.TaxonomyQuality = {
        loadQualityDashboard: loadQualityDashboard
    };
})();
