/* taxonomy-analysis-session-draft.js – optimistic autosave, restore and conflict handling */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C) throw new Error('Analysis session core must load before draft lifecycle');
    var S = C.S;
    var runtime = C.runtime;
    var AUTOSAVE_DELAY_MS = C.AUTOSAVE_DELAY_MS;
    var MAX_RESTORE_ATTEMPTS = C.MAX_RESTORE_ATTEMPTS;
    var text = C.text;
    var businessTextElement = C.businessTextElement;
    var currentPayload = C.currentPayload;
    var comparable = C.comparable;
    var meaningful = C.meaningful;
    var draftEndpoint = C.draftEndpoint;
    var jsonRequest = C.jsonRequest;
    var hasDerivedAnalysis = C.hasDerivedAnalysis;
    var isStale = C.isStale;
    var showActionAlert = C.showActionAlert;
    var showStaleActions = C.showStaleActions;
    function openRequirementDialog() { return C.openRequirementDialog.apply(null, arguments); }

    function queueSave(delay) {
        if (runtime.restoring || runtime.invalidating || runtime.conflict) return;
        window.clearTimeout(runtime.saveTimer);
        runtime.saveTimer = window.setTimeout(saveDraft, delay === undefined
            ? AUTOSAVE_DELAY_MS : delay);
    }

    function deleteDraft() {
        var endpoint = draftEndpoint();
        if (!endpoint || runtime.version === null) return Promise.resolve();
        return jsonRequest(endpoint + '?expectedVersion=' + encodeURIComponent(runtime.version), {
            method: 'DELETE'
        }).then(function () {
            runtime.version = null;
            runtime.lastSavedComparable = comparable(currentPayload());
            runtime.lastObservedComparable = runtime.lastSavedComparable;
        }).catch(function (error) {
            if (error.status === 409) showDraftConflict();
            else if (window.console) window.console.warn('[Taxonomy] Draft delete failed', error);
        });
    }

    function saveDraft() {
        window.clearTimeout(runtime.saveTimer);
        runtime.saveTimer = null;
        var endpoint = draftEndpoint();
        if (!endpoint || runtime.restoring || runtime.invalidating || runtime.conflict) {
            return Promise.resolve();
        }

        var payload = currentPayload();
        var serialized = comparable(payload);
        runtime.lastObservedComparable = serialized;
        if (serialized === runtime.lastSavedComparable) return Promise.resolve();

        if (!meaningful(payload)) {
            return deleteDraft();
        }

        return jsonRequest(endpoint, {
            method: 'PUT',
            body: JSON.stringify({
                payload: payload,
                expectedVersion: runtime.version
            })
        }).then(function (view) {
            runtime.version = view.version;
            runtime.lastSavedComparable = serialized;
            runtime.conflict = false;
            document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-saved', {
                detail: { workspaceId: runtime.workspaceId, version: runtime.version }
            }));
        }).catch(function (error) {
            if (error.status === 409) {
                showDraftConflict();
                return;
            }
            if (window.console) window.console.warn('[Taxonomy] Draft save failed', error);
            document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-save-failed', {
                detail: { message: error.message }
            }));
        });
    }

    function localStateIsPristine() {
        var input = businessTextElement();
        return (!input || !input.value.trim()) && !hasDerivedAnalysis();
    }

    function applyDraft(view) {
        if (!view || !view.payload) return;
        var payload = view.payload;
        runtime.restoring = true;
        runtime.version = view.version;
        runtime.restoredPayload = payload;

        var input = businessTextElement();
        if (input) input.value = payload.businessText || '';
        S.currentScores = payload.scores || null;
        S.currentReasons = payload.reasons || {};
        S.currentDiscrepancies = payload.discrepancies || [];
        S.currentArchView = payload.architectureView || null;
        S.storedBusinessText = payload.storedBusinessText || null;
        S.lastAnalyzedText = payload.lastAnalyzedText === undefined
            ? null : payload.lastAnalyzedText;
        S.evaluatedNodes = new Set(payload.evaluatedNodes || []);
        window._taxonomyCurrentScores = S.currentScores;
        window._currentProvisionalRelations = payload.provisionalRelations || [];

        runtime.lastSavedComparable = comparable(payload);
        runtime.lastObservedComparable = runtime.lastSavedComparable;
        restoreRenderedState(payload, 0);
    }

    function restoreRenderedState(payload, attempt) {
        var ready = window.TaxonomyBrowse && window.TaxonomyScoring
            && Array.isArray(S.taxonomyData) && S.taxonomyData.length > 0;
        if (!ready) {
            if (attempt < MAX_RESTORE_ATTEMPTS) {
                window.setTimeout(function () {
                    restoreRenderedState(payload, attempt + 1);
                }, 100);
            } else {
                runtime.restoring = false;
            }
            return;
        }

        var view = payload.currentView;
        var allowedViews = ['list', 'tabs', 'sunburst', 'tree', 'decision', 'summary'];
        if (allowedViews.indexOf(view) >= 0 && (view !== 'summary' || S.currentArchView)) {
            S.currentView = view;
        }
        window.TaxonomyBrowse.renderView(S.taxonomyData, S.currentScores);
        window.TaxonomyBrowse.updateExportGroupVisibility();
        window.TaxonomyScoring.renderArchitectureView(S.currentArchView);
        window.TaxonomyScoring.renderSuggestedRelations(payload.provisionalRelations || []);

        var summaryButton = document.getElementById('viewSummary');
        if (summaryButton) summaryButton.style.display = S.currentArchView ? '' : 'none';

        var input = businessTextElement();
        if (input && S.lastAnalyzedText !== null && input.value !== S.lastAnalyzedText
                && hasDerivedAnalysis()) {
            input.classList.add('stale-results');
        }

        runtime.restoring = false;
        if (isStale()) {
            showStaleActions();
        } else {
            showActionAlert('info', text('resumedTitle'), text('resumedBody'), [], 'resumed');
        }
        document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-restored', {
            detail: { workspaceId: runtime.workspaceId, version: runtime.version }
        }));
    }

    function loadDraft(options) {
        options = options || {};
        var endpoint = draftEndpoint();
        if (!endpoint) return Promise.resolve(null);
        return jsonRequest(endpoint, { method: 'GET' }).then(function (view) {
            if (!view) {
                runtime.version = null;
                runtime.lastSavedComparable = comparable(currentPayload());
                runtime.lastObservedComparable = runtime.lastSavedComparable;
                return null;
            }
            if (options.force || localStateIsPristine()) {
                applyDraft(view);
            } else {
                showResumeChoice(view);
            }
            return view;
        }).catch(function (error) {
            if (options.probe) throw error;
            if (window.console) window.console.warn('[Taxonomy] Draft load failed', error);
            return null;
        });
    }

    function showResumeChoice(view) {
        showActionAlert('info', text('resumeChoiceTitle'), text('resumeChoiceBody'), [
            {
                id: 'load-saved',
                label: text('loadSaved'),
                className: 'btn btn-sm btn-primary',
                handler: function () { applyDraft(view); }
            },
            {
                id: 'keep-local',
                label: text('keepLocal'),
                className: 'btn btn-sm btn-outline-secondary',
                handler: function () {
                    runtime.version = view.version;
                    runtime.lastSavedComparable = comparable(view.payload);
                    runtime.conflict = false;
                    queueSave(0);
                }
            }
        ], 'resume-choice');
    }

    function showDraftConflict() {
        runtime.conflict = true;
        showActionAlert('danger', text('conflictTitle'), text('conflictBody'), [
            {
                id: 'reload-remote',
                label: text('reloadRemote'),
                className: 'btn btn-sm btn-light',
                handler: function () {
                    runtime.conflict = false;
                    loadDraft({ force: true });
                }
            },
            {
                id: 'preserve-requirement',
                label: text('preserveAsRequirement'),
                className: 'btn btn-sm btn-outline-dark',
                handler: function () {
                    openRequirementDialog(businessTextElement().value);
                }
            }
        ], 'conflict');
    }

    Object.assign(C, {
        queueSave: queueSave,
        deleteDraft: deleteDraft,
        saveDraft: saveDraft,
        localStateIsPristine: localStateIsPristine,
        applyDraft: applyDraft,
        restoreRenderedState: restoreRenderedState,
        loadDraft: loadDraft,
        showResumeChoice: showResumeChoice,
        showDraftConflict: showDraftConflict
    });
}());
