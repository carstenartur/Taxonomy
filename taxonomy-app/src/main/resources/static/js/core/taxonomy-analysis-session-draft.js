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
    var DECISION_CONTROL_SELECTOR = [
        '#analyzeBtn',
        '#copilotBtn',
        '#taskNextAction',
        '#exportGroup button',
        '#suggestedRelationsPanel button',
        '#gapAnalyzeBtn',
        '#patternDetectBtn',
        '#recommendBtn',
        '#requirementImpactBtn'
    ].join(', ');
    function openRequirementDialog() { return C.openRequirementDialog.apply(null, arguments); }

    runtime.saveInFlight = runtime.saveInFlight || null;
    runtime.saveQueued = Boolean(runtime.saveQueued);

    function queueSave(delay) {
        if (runtime.restoring || runtime.invalidating || runtime.resetting
                || runtime.conflict) return;
        window.clearTimeout(runtime.saveTimer);
        runtime.saveTimer = window.setTimeout(saveDraft, delay === undefined
            ? AUTOSAVE_DELAY_MS : delay);
    }

    function setDraftDecisionPending(pending) {
        runtime.draftDecisionPending = pending;
        document.querySelectorAll(DECISION_CONTROL_SELECTOR).forEach(function (control) {
            if (pending) {
                if (control.dataset.analysisDraftDecisionPending !== 'true') {
                    control.dataset.analysisDraftPreviousAriaDisabled =
                        control.getAttribute('aria-disabled') || '';
                }
                control.dataset.analysisDraftDecisionPending = 'true';
                control.setAttribute('aria-disabled', 'true');
            } else if (control.dataset.analysisDraftDecisionPending === 'true') {
                var previous = control.dataset.analysisDraftPreviousAriaDisabled;
                if (previous) control.setAttribute('aria-disabled', previous);
                else control.removeAttribute('aria-disabled');
                delete control.dataset.analysisDraftPreviousAriaDisabled;
                delete control.dataset.analysisDraftDecisionPending;
            }
        });
    }

    function installDraftDecisionGuard() {
        document.addEventListener('click', function (event) {
            if (!runtime.draftDecisionPending) return;
            var control = event.target && event.target.closest
                ? event.target.closest(DECISION_CONTROL_SELECTOR) : null;
            if (!control) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            var decision = document.querySelector(
                '#statusArea [data-analysis-session-action]');
            if (decision && typeof decision.focus === 'function') decision.focus();
        }, true);
    }

    function finishMutation(operation) {
        runtime.saveInFlight = operation.then(function (result) {
            return result;
        }).finally(function () {
            runtime.saveInFlight = null;
            if (runtime.saveQueued) {
                runtime.saveQueued = false;
                return saveDraft();
            }
            return null;
        });
        return runtime.saveInFlight;
    }

    function deleteDraft() {
        window.clearTimeout(runtime.saveTimer);
        runtime.saveTimer = null;
        var endpoint = draftEndpoint();
        if (!endpoint || runtime.version === null) return Promise.resolve();
        if (runtime.saveInFlight) {
            runtime.saveQueued = true;
            return runtime.saveInFlight;
        }
        var operation = jsonRequest(
            endpoint + '?expectedVersion=' + encodeURIComponent(runtime.version),
            { method: 'DELETE' }
        ).then(function () {
            runtime.version = null;
            runtime.lastSavedComparable = comparable(currentPayload());
            runtime.lastObservedComparable = runtime.lastSavedComparable;
        }).catch(function (error) {
            if (error.status === 409) showDraftConflict();
            else if (window.console) window.console.warn('[Taxonomy] Draft delete failed', error);
        });
        return finishMutation(operation);
    }

    function performResetDraft() {
        var endpoint = draftEndpoint();
        if (!endpoint) {
            runtime.resetting = false;
            return Promise.resolve(null);
        }
        var options = typeof C.analysisOptions === 'function'
            ? C.analysisOptions() : null;
        var operation = jsonRequest(endpoint + '/reset', {
            method: 'POST',
            body: JSON.stringify({ analysisOptions: options })
        }).then(function (view) {
            runtime.version = view.version;
            runtime.restoredPayload = view.payload;
            runtime.lastSavedComparable = comparable(view.payload);
            runtime.lastObservedComparable = runtime.lastSavedComparable;
            runtime.conflict = false;
            document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-reset', {
                detail: { workspaceId: runtime.workspaceId, version: runtime.version }
            }));
            return view;
        }).finally(function () {
            runtime.resetting = false;
        });
        return finishMutation(operation);
    }

    /**
     * Starts a new versioned draft lifecycle without deleting the persisted row.
     * The server-side empty revision prevents another stale tab from silently
     * resurrecting the discarded requirement text.
     */
    function resetDraft() {
        window.clearTimeout(runtime.saveTimer);
        runtime.saveTimer = null;
        runtime.saveQueued = false;
        runtime.resetting = true;
        runtime.conflict = false;
        if (runtime.saveInFlight) {
            return runtime.saveInFlight.catch(function () { return null; })
                .then(performResetDraft);
        }
        return performResetDraft();
    }

    function saveDraft() {
        window.clearTimeout(runtime.saveTimer);
        runtime.saveTimer = null;
        var endpoint = draftEndpoint();
        if (!endpoint || runtime.restoring || runtime.invalidating || runtime.resetting
                || runtime.conflict) {
            return Promise.resolve(false);
        }
        if (runtime.saveInFlight) {
            runtime.saveQueued = true;
            return runtime.saveInFlight;
        }

        var payload = currentPayload();
        var serialized = comparable(payload);
        runtime.lastObservedComparable = serialized;
        if (serialized === runtime.lastSavedComparable) return Promise.resolve(true);

        if (!meaningful(payload) && runtime.version === null) {
            runtime.lastSavedComparable = serialized;
            runtime.lastObservedComparable = serialized;
            return Promise.resolve(true);
        }

        var operation = jsonRequest(endpoint, {
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
            return true;
        }).catch(function (error) {
            if (error.status === 409) {
                showDraftConflict();
                return false;
            }
            if (window.console) window.console.warn('[Taxonomy] Draft save failed', error);
            document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-save-failed', {
                detail: { message: error.message }
            }));
            return false;
        });
        return finishMutation(operation);
    }

    function localStateIsPristine() {
        var input = businessTextElement();
        return (!input || !input.value.trim()) && !hasDerivedAnalysis();
    }

    function clearRestoredAnalysisState() {
        var input = businessTextElement();
        if (input) {
            input.value = '';
            input.classList.remove('stale-results');
        }
        S.currentScores = null;
        S.currentRawScores = {};
        S.currentEffectiveScores = {};
        S.currentScoreDetails = {};
        S.currentProductSuitabilityScores = {};
        S.scoreSemanticsVersion = 0;
        S.currentScoreSemanticsWarnings = [];
        S.currentReasons = {};
        S.currentDiscrepancies = [];
        S.currentProductCoverageGaps = [];
        S.currentArchView = null;
        S.storedBusinessText = null;
        S.lastAnalyzedText = null;
        S.evaluatedNodes = new Set();
        S.pendingProposalNodeCode = null;
        S.currentView = 'list';
        window._taxonomyCurrentScores = null;
        window._currentProvisionalRelations = [];
    }

    function applyDraft(view) {
        if (!view || !view.payload) {
            runtime.restoring = false;
            setDraftDecisionPending(false);
            return;
        }
        var payload = view.payload;
        runtime.restoring = true;
        setDraftDecisionPending(true);
        runtime.version = view.version;
        runtime.restoredPayload = payload;

        if (payload.draftState === 'EMPTY') {
            // EMPTY is an authoritative tombstone. Never hydrate stale score-envelope fields that
            // may survive in an older or malformed payload; the visible and in-memory state must
            // satisfy the same invariant as a freshly started analysis session.
            clearRestoredAnalysisState();
        } else {
            var input = businessTextElement();
            if (input) input.value = payload.businessText || '';
            S.currentRawScores = payload.rawScores || payload.scores || {};
            S.currentEffectiveScores = payload.effectiveScores || payload.scores || {};
            S.currentScoreDetails = payload.scoreDetails || {};
            S.currentProductSuitabilityScores = payload.productSuitabilityScores || {};
            S.scoreSemanticsVersion = payload.scoreSemanticsVersion || 0;
            S.currentScoreSemanticsWarnings = payload.scoreSemanticsWarnings || [];
            S.currentScores = S.currentEffectiveScores;
            S.currentReasons = payload.reasons || {};
            S.currentDiscrepancies = payload.discrepancies || [];
            S.currentProductCoverageGaps = payload.productCoverageGaps || [];
            S.currentArchView = payload.architectureView || null;
            S.storedBusinessText = payload.storedBusinessText || null;
            S.lastAnalyzedText = payload.lastAnalyzedText === undefined
                ? null : payload.lastAnalyzedText;
            S.evaluatedNodes = new Set(payload.evaluatedNodes || []);
            window._taxonomyCurrentScores = S.currentScores;
            window._currentProvisionalRelations = payload.provisionalRelations || [];
        }

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
                setDraftDecisionPending(false);
            }
            return;
        }

        if (payload.draftState === 'EMPTY') {
            if (typeof C.clearDerivedUi === 'function') C.clearDerivedUi();
            runtime.restoring = false;
            setDraftDecisionPending(false);
            runtime.conflict = false;
            var emptyArea = document.getElementById('statusArea');
            if (emptyArea) {
                emptyArea.replaceChildren();
                delete emptyArea.dataset.analysisSessionMessage;
            }
            document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-restored', {
                detail: { workspaceId: runtime.workspaceId, version: runtime.version }
            }));
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
        setDraftDecisionPending(false);
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

        // Loading, rendering and any explicit local-vs-remote choice form one
        // restoration transaction. Autosave and conflicting task actions must not
        // run until that transaction has established which state owns the next
        // optimistic revision.
        runtime.restoring = true;
        setDraftDecisionPending(true);
        return jsonRequest(endpoint, { method: 'GET' }).then(function (view) {
            if (!view) {
                runtime.version = null;
                var payload = currentPayload();
                var serialized = comparable(payload);
                runtime.restoring = false;
                setDraftDecisionPending(false);
                if (meaningful(payload)) {
                    // Local input may have changed while the GET was in flight.
                    // The server has no draft, so never label that input as saved.
                    runtime.lastSavedComparable = null;
                    runtime.lastObservedComparable = null;
                    queueSave(0);
                } else {
                    runtime.lastSavedComparable = serialized;
                    runtime.lastObservedComparable = serialized;
                }
                return null;
            }
            if (options.force || localStateIsPristine()) {
                applyDraft(view);
            } else {
                // Keep restoring=true until the user chooses the authoritative
                // state; background polling and task actions remain blocked.
                showResumeChoice(view);
            }
            return view;
        }).catch(function (error) {
            if (options.probe) throw error;
            runtime.restoring = false;
            setDraftDecisionPending(false);
            if (window.console) window.console.warn('[Taxonomy] Draft load failed', error);
            return null;
        });
    }

    function showResumeChoice(view) {
        setDraftDecisionPending(true);
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
                    runtime.restoring = false;
                    setDraftDecisionPending(false);
                    queueSave(0);
                }
            }
        ], 'resume-choice');
    }

    function showDraftConflict() {
        runtime.conflict = true;
        setDraftDecisionPending(true);
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

    installDraftDecisionGuard();

    Object.assign(C, {
        queueSave: queueSave,
        setDraftDecisionPending: setDraftDecisionPending,
        deleteDraft: deleteDraft,
        resetDraft: resetDraft,
        saveDraft: saveDraft,
        localStateIsPristine: localStateIsPristine,
        applyDraft: applyDraft,
        restoreRenderedState: restoreRenderedState,
        loadDraft: loadDraft,
        showResumeChoice: showResumeChoice,
        showDraftConflict: showDraftConflict
    });
}());
