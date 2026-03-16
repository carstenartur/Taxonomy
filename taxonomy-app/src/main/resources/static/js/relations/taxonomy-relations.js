/**
 * taxonomy-relations.js — Relation Management module
 *
 * Browse, create, and delete confirmed taxonomy relations.
 * Also provides Requirement Impact Analysis via the graph API.
 */
(function () {
    'use strict';

    /* ------------------------------------------------------------------ */
    /*  Bootstrap on DOMContentLoaded                                      */
    /* ------------------------------------------------------------------ */
    document.addEventListener('DOMContentLoaded', function () {
        var panel = document.getElementById('relationsBrowser');
        if (panel) {
            panel.addEventListener('toggle', function () {
                if (panel.open) loadRelations();
            });
        }
        var typeFilter = document.getElementById('relationsTypeFilter');
        if (typeFilter) {
            typeFilter.addEventListener('change', function () {
                loadRelations();
            });
        }
        var createBtn = document.getElementById('createRelationBtn');
        if (createBtn) {
            createBtn.addEventListener('click', openCreateModal);
        }
        var submitBtn = document.getElementById('createRelationSubmit');
        if (submitBtn) {
            submitBtn.addEventListener('click', submitCreateRelation);
        }
        var impactBtn = document.getElementById('requirementImpactBtn');
        if (impactBtn) {
            impactBtn.addEventListener('click', runRequirementImpact);
        }
    });

    /* ------------------------------------------------------------------ */
    /*  Load relations                                                     */
    /* ------------------------------------------------------------------ */
    function loadRelations() {
        var content = document.getElementById('relationsTableContainer');
        if (!content) return;
        content.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> Loading\u2026</div>';

        var typeFilter = document.getElementById('relationsTypeFilter');
        var type = typeFilter ? typeFilter.value : '';
        var url = type ? '/api/relations?type=' + encodeURIComponent(type) : '/api/relations';

        fetch(url)
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (relations) { renderRelationsTable(relations); })
            .catch(function () {
                content.innerHTML = '<div class="text-danger small p-2">\u26A0\uFE0F Failed to load relations.</div>';
            });
    }

    function renderRelationsTable(relations) {
        var content = document.getElementById('relationsTableContainer');
        if (!relations || relations.length === 0) {
            content.innerHTML = '<div class="text-muted small p-2">No relations found.</div>';
            return;
        }
        var html = '<div class="small text-muted mb-1">' + relations.length + ' relation(s)</div>';
        html += '<table class="table table-sm table-bordered table-hover mb-0 relations-table" style="font-size:0.82em;">';
        html += '<thead><tr><th>Source</th><th>Target</th><th>Type</th><th>Provenance</th><th></th></tr></thead><tbody>';
        relations.forEach(function (r) {
            html += '<tr>';
            html += '<td title="' + escapeHtml(r.sourceName || '') + '">' + escapeHtml(r.sourceCode) + '</td>';
            html += '<td title="' + escapeHtml(r.targetName || '') + '">' + escapeHtml(r.targetCode) + '</td>';
            html += '<td>' + escapeHtml(r.relationType) + '</td>';
            html += '<td><span class="badge ' + provenanceBadge(r.provenance) + '">' + escapeHtml(r.provenance || 'MANUAL') + '</span></td>';
            html += '<td><button class="btn btn-sm btn-outline-danger py-0 px-1 relation-delete-btn" data-id="' + r.id + '" title="Delete relation">\u2716</button></td>';
            html += '</tr>';
        });
        html += '</tbody></table>';
        content.innerHTML = html;

        content.querySelectorAll('.relation-delete-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                deleteRelation(btn.dataset.id);
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Create relation                                                    */
    /* ------------------------------------------------------------------ */
    function openCreateModal() {
        var modal = document.getElementById('createRelationModal');
        if (modal) {
            var bsModal = new window.bootstrap.Modal(modal);
            bsModal.show();
        }
    }

    function submitCreateRelation() {
        var sourceCode = document.getElementById('newRelSource').value.trim();
        var targetCode = document.getElementById('newRelTarget').value.trim();
        var relationType = document.getElementById('newRelType').value;
        var description = document.getElementById('newRelDescription').value.trim();
        var errorEl = document.getElementById('createRelationError');

        if (errorEl) { errorEl.classList.add('d-none'); errorEl.textContent = ''; }

        if (!sourceCode || !targetCode) {
            if (errorEl) { errorEl.textContent = 'Source and Target codes are required.'; errorEl.classList.remove('d-none'); }
            return;
        }

        var spinner = document.getElementById('createRelationSpinner');
        if (spinner) spinner.classList.remove('d-none');

        fetch('/api/relations', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sourceCode: sourceCode,
                targetCode: targetCode,
                relationType: relationType,
                description: description,
                provenance: 'MANUAL'
            })
        })
        .then(function (r) {
            if (!r.ok) throw new Error('Failed (' + r.status + ')');
            return r.json();
        })
        .then(function () {
            if (spinner) spinner.classList.add('d-none');
            var modal = bootstrap.Modal.getInstance(document.getElementById('createRelationModal'));
            if (modal) modal.hide();
            loadRelations();
            if (window.TaxonomyQuality) window.TaxonomyQuality.loadQualityDashboard();
        })
        .catch(function (err) {
            if (spinner) spinner.classList.add('d-none');
            if (errorEl) { errorEl.textContent = 'Error creating relation: ' + err.message; errorEl.classList.remove('d-none'); }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Delete relation                                                    */
    /* ------------------------------------------------------------------ */
    function deleteRelation(id) {
        if (!confirm('Delete this relation?')) return;
        fetch('/api/relations/' + id, { method: 'DELETE' })
            .then(function (r) {
                if (!r.ok) throw new Error('Failed');
                loadRelations();
                if (window.TaxonomyQuality) window.TaxonomyQuality.loadQualityDashboard();
            })
            .catch(function () {
                var content = document.getElementById('relationsTableContainer');
                if (content) {
                    var errDiv = document.createElement('div');
                    errDiv.className = 'text-danger small p-1';
                    errDiv.textContent = '\u26A0\uFE0F Failed to delete relation.';
                    content.prepend(errDiv);
                    setTimeout(function () { errDiv.remove(); }, 5000);
                }
            });
    }

    /* ------------------------------------------------------------------ */
    /*  Requirement Impact Analysis                                        */
    /* ------------------------------------------------------------------ */
    function runRequirementImpact() {
        var area = document.getElementById('requirementImpactResults');
        if (!area) return;

        /* Collect current scores from the taxonomy (if available) */
        var scores = {};
        var businessText = '';
        var businessEl = document.getElementById('businessText');
        if (businessEl) businessText = businessEl.value.trim();

        /* Try to read scores from the global state exposed by taxonomy.js */
        if (window._getCurrentScores) {
            scores = window._getCurrentScores() || {};
        }

        if (Object.keys(scores).length === 0) {
            area.style.display = 'block';
            area.innerHTML = '<div class="text-warning small p-2">\u26A0\uFE0F Run an analysis first to generate scores for impact analysis.</div>';
            return;
        }

        area.style.display = 'block';
        area.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> Analyzing impact\u2026</div>';

        var maxHops = document.getElementById('graphMaxHops') ? parseInt(document.getElementById('graphMaxHops').value, 10) : 2;

        fetch('/api/graph/impact', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ scores: scores, businessText: businessText, maxHops: maxHops })
        })
        .then(function (r) {
            if (!r.ok) throw new Error('Impact analysis failed (' + r.status + ')');
            return r.json();
        })
        .then(function (data) { renderImpactResults(data); })
        .catch(function (err) {
            area.innerHTML = '<div class="text-danger small p-2">\u26A0\uFE0F ' + escapeHtml(err.message) + '</div>';
        });
    }

    function renderImpactResults(data) {
        var area = document.getElementById('requirementImpactResults');
        if (!area) return;
        var html = '';

        if (data.impactedElements && data.impactedElements.length > 0) {
            html += '<div class="small text-muted mb-1">' + data.impactedElements.length + ' impacted element(s)</div>';
            html += '<table class="table table-sm table-bordered mb-2" style="font-size:0.82em;">';
            html += '<thead><tr><th>Code</th><th>Name</th><th>Impact</th></tr></thead><tbody>';
            data.impactedElements.forEach(function (el) {
                html += '<tr>';
                html += '<td class="fw-semibold">' + escapeHtml(el.code || '') + '</td>';
                html += '<td>' + escapeHtml(el.name || el.nameEn || '') + '</td>';
                html += '<td>' + escapeHtml(el.impactType || el.impact || '') + '</td>';
                html += '</tr>';
            });
            html += '</tbody></table>';
        }

        if (data.traversedRelationships && data.traversedRelationships.length > 0) {
            html += '<div class="small text-muted mb-1">' + data.traversedRelationships.length + ' traversed relationship(s)</div>';
            html += '<table class="table table-sm table-bordered mb-0" style="font-size:0.82em;">';
            html += '<thead><tr><th>From</th><th>To</th><th>Type</th></tr></thead><tbody>';
            data.traversedRelationships.forEach(function (rel) {
                html += '<tr>';
                html += '<td>' + escapeHtml(rel.sourceCode || rel.from || '') + '</td>';
                html += '<td>' + escapeHtml(rel.targetCode || rel.to || '') + '</td>';
                html += '<td>' + escapeHtml(rel.relationType || rel.type || '') + '</td>';
                html += '</tr>';
            });
            html += '</tbody></table>';
        }

        area.innerHTML = html || '<div class="text-muted small p-2">No impact data returned.</div>';
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */
    function provenanceBadge(prov) {
        if (prov === 'MANUAL') return 'bg-primary';
        if (prov === 'ACCEPTED_PROPOSAL') return 'bg-success';
        if (prov === 'CSV_IMPORT') return 'bg-info text-dark';
        return 'bg-secondary';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    /* ------------------------------------------------------------------ */
    /*  Public API                                                         */
    /* ------------------------------------------------------------------ */
    window.TaxonomyRelations = {
        loadRelations: loadRelations,
        runRequirementImpact: runRequirementImpact
    };
})();
