/**
 * taxonomy-relations.js — Relation Management module
 *
 * Browse, create, and delete confirmed taxonomy relations.
 * Also provides Requirement Impact Analysis via the graph API.
 */
(function () {
    'use strict';
    var t = TaxonomyI18n.t;
    var relationLoadSequence = 0;
    var projectionRebuildPromise = null;

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
    function loadRelations(allowReadyRefresh) {
        if (projectionRebuildPromise) return projectionRebuildPromise;
        var content = document.getElementById('relationsTableContainer');
        if (!content) return;
        // Manual loads, including click events, get one status-driven refresh.
        allowReadyRefresh = allowReadyRefresh !== false;
        var sequence = ++relationLoadSequence;
        content.setAttribute('aria-busy', 'true');
        content.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> ' + t('relations.loading') + '</div>';

        var typeFilter = document.getElementById('relationsTypeFilter');
        var type = typeFilter ? typeFilter.value : '';

        return relationApiReady()
            .then(function (api) {
                return api.readSnapshot(type);
            })
            .then(function (response) {
                if (!response.ok) {
                    throw projectionReadError(response);
                }
                return response.json();
            })
            .then(function (relations) {
                if (sequence !== relationLoadSequence) return;
                if (!Array.isArray(relations)) throw new Error(t('relations.load.failed'));
                content.setAttribute('aria-busy', 'false');
                renderRelationsTable(relations);
            })
            .catch(function (error) {
                if (sequence === relationLoadSequence) return renderRelationsLoadError(content, error, allowReadyRefresh);
            });
    }

    function relationApiReady() {
        if (window.TaxonomyRelationsApi) {
            return Promise.resolve(window.TaxonomyRelationsApi);
        }
        if (window.TaxonomyRelationsApiReady) {
            return window.TaxonomyRelationsApiReady;
        }
        return Promise.reject(new Error(
            'Relation API client is not available.'));
    }

    function projectionReadError(response) {
        var state = response.headers.get(
            'X-Taxonomy-Relation-Projection-State');
        var pending = response.headers.get(
            'X-Taxonomy-Relation-Pending-Recovery');
        var detail = 'HTTP ' + response.status;
        if (state) detail += ', projection=' + state;
        if (pending && pending !== '0') {
            detail += ', pending recovery=' + pending;
        }

        var error = new Error(t('relations.load.failed') + ': ' + detail);
        error.projectionState = state;
        error.httpStatus = response.status;
        return error;
    }

    function renderRelationsLoadError(content, error, allowReadyRefresh) {
        var sequence = relationLoadSequence;
        content.setAttribute('aria-busy', 'false');
        content.replaceChildren();

        var alert = document.createElement('div');
        alert.className = error && (error.projectionState || error.projectionPending)
            ? 'alert alert-warning d-flex flex-wrap align-items-center gap-2 mb-0'
            : 'alert alert-danger d-flex flex-wrap align-items-center gap-2 mb-0';
        alert.setAttribute('role', 'alert');

        var message = document.createElement('span');
        message.className = 'flex-grow-1';
        var state = error && error.projectionState;
        message.textContent = state === 'NOT_BUILT' ? t('relations.projection.not.built')
            : state === 'STALE' ? t('relations.projection.stale')
            : error && error.message ? error.message : t('relations.load.failed');
        alert.appendChild(message);

        var retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'btn btn-sm btn-outline-secondary';
        retry.textContent = translatedOr(
            'relations.load.retry',
            translatedOr('workspace.manager.refresh', 'Retry'));
        retry.addEventListener('click', loadRelations);
        alert.appendChild(retry);

        content.appendChild(alert);
        if (state !== 'NOT_BUILT' && state !== 'STALE') return;

        var detail = document.createElement('div');
        detail.className = 'small w-100';
        detail.textContent = error.message;
        alert.appendChild(detail);
        return relationApiReady()
            .then(function (api) { return inspectProjection(api); })
            .then(function (snapshot) {
                if (sequence !== relationLoadSequence) return;
                if (snapshot.status.readinessState === 'READY' && allowReadyRefresh) return loadRelations(false);
                var status = snapshot.status;
                detail.textContent = t('relations.projection.context', status.repositoryId,
                    status.workspaceId || t('relations.projection.central'), status.branch,
                    status.currentHeadCommit || '—');
                if (!canRebuildProjection(snapshot)) {
                    message.textContent = status.readinessState === 'READY' ? t('relations.projection.unavailable')
                        : t('relations.projection.check.failed', status.readinessState || '—');
                    return;
                }
                message.textContent = status.readinessState === 'NOT_BUILT'
                    ? t('relations.projection.not.built') : t('relations.projection.stale');
                var rebuild = document.createElement('button');
                rebuild.type = 'button';
                rebuild.className = 'btn btn-sm btn-outline-primary';
                rebuild.textContent = t('relations.projection.rebuild');
                rebuild.title = t('relations.projection.rebuild.hint');
                rebuild.addEventListener('click', function () { return rebuildProjection(snapshot, content); });
                alert.appendChild(rebuild);
            })
            .catch(function (inspectionError) {
                if (sequence === relationLoadSequence) {
                    detail.textContent = t('relations.projection.check.failed', inspectionError.message);
                }
            });
    }

    function inspectProjection(api, workspaceId) {
        return api.readProjectionReadiness(workspaceId).then(function (response) {
            if (!response.ok) throw projectionReadError(response);
            return response.json().then(function (status) {
                if (!status || !status.repositoryId || !status.branch
                        || !Object.prototype.hasOwnProperty.call(status, 'workspaceId')) {
                    throw projectionContextChanged();
                }
                return { status: status, etag: response.headers.get('ETag') };
            });
        });
    }

    function canRebuildProjection(snapshot) {
        return (snapshot.status.readinessState === 'NOT_BUILT' || snapshot.status.readinessState === 'STALE')
            && /^"[a-f0-9]{40}"$/i.test(snapshot.etag || '')
            && snapshot.etag === '"' + snapshot.status.currentHeadCommit + '"';
    }

    function sameProjectionContext(left, right) {
        return left.repositoryId === right.repositoryId && left.workspaceId === right.workspaceId
            && left.branch === right.branch;
    }

    function projectionContextChanged() {
        var error = new Error(t('relations.projection.changed'));
        error.contextChanged = true;
        return error;
    }

    function rebuildProjection(snapshot, content) {
        if (projectionRebuildPromise) return projectionRebuildPromise;
        var sequence = ++relationLoadSequence;
        content.setAttribute('aria-busy', 'true');
        content.querySelectorAll('button').forEach(function (button) { button.disabled = true; });
        var progress = document.createElement('div');
        progress.className = 'small p-2';
        progress.setAttribute('role', 'status');
        progress.textContent = t('relations.projection.rebuilding');
        content.appendChild(progress);
        projectionRebuildPromise = relationApiReady()
            .then(function (api) {
                return inspectProjection(api, snapshot.status.workspaceId).then(function (current) {
                    if (!sameProjectionContext(snapshot.status, current.status)) throw projectionContextChanged();
                    if (current.status.readinessState === 'READY') return { reload: true };
                    if (!canRebuildProjection(current)) throw projectionContextChanged();
                    return api.rebuildProjection(current.status, current.etag).then(function (response) {
                        if (!response.ok) throw projectionReadError(response);
                        if (response.status === 202) return { pending: true };
                        return response.json().then(function (status) {
                            if (!status || status.operationStatus !== 'REBUILT' || status.readinessState !== 'READY'
                                    || !sameProjectionContext(current.status, status)
                                    || status.currentHeadCommit !== current.status.currentHeadCommit) {
                                throw projectionContextChanged();
                            }
                            return { reload: true };
                        });
                    });
                });
            })
            .catch(function (error) {
                if (sequence !== relationLoadSequence) return;
                var message = error.contextChanged || error.status === 412 || error.httpStatus === 412
                    ? t('relations.projection.changed') : t('relations.projection.rebuild.failed', error.message);
                renderRelationsLoadError(content, new Error(message));
            })
            .then(function (outcome) {
                projectionRebuildPromise = null;
                if (sequence !== relationLoadSequence || !outcome) return;
                if (outcome.pending) {
                    var error = new Error(t('relations.projection.pending'));
                    error.projectionPending = true;
                    return renderRelationsLoadError(content, error);
                }
                if (outcome.reload) return loadRelations();
            });
        return projectionRebuildPromise;
    }

    function translatedOr(key, fallback) {
        var value = t(key);
        return value && value !== key ? value : fallback;
    }

    function renderRelationsTable(relations) {
        var content = document.getElementById('relationsTableContainer');
        if (!relations || relations.length === 0) {
            content.innerHTML = '<div class="text-muted small p-2">' + t('relations.none') + '</div>';
            return;
        }
        var html = '<div class="small text-muted mb-1">' + t('relations.count', relations.length) + '</div>';
        html += '<table class="table table-sm table-bordered table-hover mb-0 relations-table" style="font-size:0.82em;">';
        html += '<thead><tr><th>' + t('relations.table.source') + '</th><th>' + t('relations.table.target') + '</th><th>' + t('relations.table.type') + '</th><th>' + t('relations.table.provenance') + '</th><th></th></tr></thead><tbody>';
        relations.forEach(function (r) {
            html += '<tr>';
            html += '<td title="' + escapeHtml(r.sourceName || '') + '">' + escapeHtml(r.sourceCode) + '</td>';
            html += '<td title="' + escapeHtml(r.targetName || '') + '">' + escapeHtml(r.targetCode) + '</td>';
            html += '<td>' + escapeHtml(r.relationType) + '</td>';
            html += '<td><span class="badge ' + provenanceBadge(r.provenance) + '">' + escapeHtml(r.provenance || 'MANUAL') + '</span></td>';
            html += '<td><button class="btn btn-sm btn-outline-danger py-0 px-1 relation-delete-btn" data-id="' + r.id + '" title="' + t('relations.delete.title') + '">\u2716</button></td>';
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
            if (errorEl) { errorEl.textContent = t('relations.create.required'); errorEl.classList.remove('d-none'); }
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
            if (errorEl) { errorEl.textContent = t('relations.create.error', err.message); errorEl.classList.remove('d-none'); }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Delete relation                                                    */
    /* ------------------------------------------------------------------ */
    function deleteRelation(id) {
        if (!confirm(t('relations.delete.confirm'))) return;
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
                    errDiv.textContent = t('relations.delete.failed');
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
        var graph = window.TaxonomyGraph;
        var sequence = graph.beginResult('requirement');

        /* Collect current scores from the taxonomy (if available) */
        var scores = {};
        var businessText = '';
        var businessEl = document.getElementById('businessText');
        if (businessEl) businessText = businessEl.value.trim();

        /* Use the same current scores as the other analysis views. */
        if (window.TaxonomyState && window.TaxonomyState.currentScores) {
            scores = window.TaxonomyState.currentScores;
        } else if (window._getCurrentScores) {
            scores = window._getCurrentScores() || {};
        }

        if (Object.keys(scores).length === 0) {
            area.style.display = 'block';
            area.innerHTML = '<div class="text-warning small p-2">' + t('relations.impact.no.scores') + '</div>';
            return;
        }

        area.style.display = 'block';
        area.innerHTML = '<div class="text-center text-muted py-2"><div class="spinner-border spinner-border-sm" role="status"></div> ' + t('relations.impact.analyzing') + '</div>';

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
        .then(function (data) {
            if (graph.isCurrentResult(sequence)) renderImpactResults(data);
        })
        .catch(function (err) {
            if (graph.isCurrentResult(sequence)) {
                area.innerHTML = '<div class="text-danger small p-2" role="alert">\u26A0\uFE0F ' + escapeHtml(err.message) + '</div>';
            }
        });
    }

    function renderImpactResults(data) {
        var area = document.getElementById('requirementImpactResults');
        if (!area) return;
        var graph = window.TaxonomyGraph;
        var html = graph.renderElementsTable(data.impactedElements, t('relations.impact.elements.title'))
            + graph.renderRelationshipsTable(data.traversedRelationships);
        if (!html) html = '<div class="text-muted small p-2" role="status">' + t('relations.impact.no.data') + '</div>';
        if (data.notes && data.notes.length) {
            html += '<div class="alert alert-info small py-1 px-2 mt-2 mb-0">'
                + data.notes.map(escapeHtml).join('<br>') + '</div>';
        }
        area.innerHTML = html;
        graph.attachNodeLinks();
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

    var escapeHtml = TaxonomyUtils.escapeHtml;

    /* ------------------------------------------------------------------ */
    /*  Public API                                                         */
    /* ------------------------------------------------------------------ */
    window.TaxonomyRelations = {
        loadRelations: loadRelations,
        runRequirementImpact: runRequirementImpact
    };
})();
