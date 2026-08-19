/* taxonomy-analysis-session.js – lifecycle, persistence and invalidation of ad-hoc analyses */

(function () {
    'use strict';

    if (window.TaxonomyAnalysisSession || !window.TaxonomyState) return;

    var S = window.TaxonomyState;
    var SCHEMA_VERSION = 1;
    var AUTOSAVE_DELAY_MS = 900;
    var CHANGE_POLL_MS = 1500;
    var MAX_RESTORE_ATTEMPTS = 50;
    var WORKSPACE_HEADER = 'X-Taxonomy-Workspace-Id';
    var WORKSPACE_STORAGE_KEY = 'taxonomy-analysis-workspace-id';

    var runtime = {
        workspaceId: null,
        version: null,
        restoring: false,
        invalidating: false,
        conflict: false,
        initialized: false,
        saveTimer: null,
        staleTimer: null,
        lastSavedComparable: null,
        lastObservedComparable: null,
        restoredPayload: null
    };

    var labels = {
        en: {
            staleTitle: 'The requirement text has changed',
            staleBody: 'The scores, architecture view, relationship hypotheses, gap, pattern, recommendation, impact and export data still belong to the previously analysed text.',
            discardEdit: 'Discard text change',
            discardAnalysis: 'Discard previous analysis',
            addRequirement: 'Save as additional requirement',
            newProject: 'Start a new empty project',
            saveVersion: 'Save as new requirement version',
            discarded: 'The previous analysis and every derived view were discarded. Persisted projects, versions and confirmed relations were kept.',
            resumedTitle: 'Draft restored',
            resumedBody: 'Your unfinished analysis was restored from the current workspace.',
            resumeChoiceTitle: 'A saved analysis draft exists',
            resumeChoiceBody: 'This workspace contains a newer saved draft. Choose which state to continue with.',
            loadSaved: 'Load saved draft',
            keepLocal: 'Keep current input',
            conflictTitle: 'The draft was changed elsewhere',
            conflictBody: 'Another tab or device saved a newer version. Your local state was not overwritten.',
            reloadRemote: 'Load newer saved state',
            preserveAsRequirement: 'Save my state as a requirement',
            projectDialogTitle: 'Start a new architecture project',
            requirementDialogTitle: 'Add requirement to the same architecture',
            project: 'Project',
            projectKey: 'Project key',
            projectTitle: 'Project title',
            projectDescription: 'Description',
            requirementKey: 'Requirement key',
            requirementTitle: 'Requirement title',
            requirementText: 'Requirement text',
            createFirstRequirement: 'Create the first requirement from the current text',
            cancel: 'Cancel',
            create: 'Create',
            saving: 'Saving…',
            loadingProjects: 'Loading projects…',
            noProjects: 'No project exists yet. Create a new project first.',
            createFailed: 'Could not create the project or requirement.',
            versionSaved: 'A new immutable requirement version was created.',
            draftSaved: 'Draft saved',
            draftSaveFailed: 'The draft could not be saved.',
            currentQuickAnalysis: 'Quick analysis (working draft)',
            versionReason: 'Requirement text changed in the analysis workspace',
            requirementReason: 'Created from the ad-hoc analysis workspace'
        },
        de: {
            staleTitle: 'Der Anforderungstext wurde geändert',
            staleBody: 'Bewertungen, Architekturansicht, Relationshypothesen, Gap-, Pattern-, Empfehlungs-, Impact- und Exportdaten gehören noch zum zuvor analysierten Text.',
            discardEdit: 'Textänderung verwerfen',
            discardAnalysis: 'Bisherige Analyse verwerfen',
            addRequirement: 'Als zusätzliche Anforderung speichern',
            newProject: 'Neues leeres Projekt beginnen',
            saveVersion: 'Als neue Anforderungsversion speichern',
            discarded: 'Die bisherige Analyse und alle daraus abgeleiteten Ansichten wurden verworfen. Gespeicherte Projekte, Versionen und bestätigte Relationen blieben erhalten.',
            resumedTitle: 'Entwurf fortgesetzt',
            resumedBody: 'Ihre angefangene Analyse wurde aus dem aktuellen Workspace wiederhergestellt.',
            resumeChoiceTitle: 'Ein gespeicherter Analyseentwurf ist vorhanden',
            resumeChoiceBody: 'In diesem Workspace liegt ein neuerer Entwurf vor. Wählen Sie aus, mit welchem Stand Sie weiterarbeiten.',
            loadSaved: 'Gespeicherten Entwurf laden',
            keepLocal: 'Aktuelle Eingabe behalten',
            conflictTitle: 'Der Entwurf wurde an anderer Stelle geändert',
            conflictBody: 'Ein anderer Tab oder ein anderes Gerät hat einen neueren Stand gespeichert. Ihr lokaler Stand wurde nicht überschrieben.',
            reloadRemote: 'Neueren gespeicherten Stand laden',
            preserveAsRequirement: 'Meinen Stand als Anforderung sichern',
            projectDialogTitle: 'Neues Architekturprojekt beginnen',
            requirementDialogTitle: 'Anforderung zur selben Architektur hinzufügen',
            project: 'Projekt',
            projectKey: 'Projektschlüssel',
            projectTitle: 'Projekttitel',
            projectDescription: 'Beschreibung',
            requirementKey: 'Anforderungsschlüssel',
            requirementTitle: 'Titel der Anforderung',
            requirementText: 'Anforderungstext',
            createFirstRequirement: 'Erste Anforderung aus dem aktuellen Text erzeugen',
            cancel: 'Abbrechen',
            create: 'Erstellen',
            saving: 'Speichern…',
            loadingProjects: 'Projekte werden geladen…',
            noProjects: 'Es existiert noch kein Projekt. Legen Sie zuerst ein neues Projekt an.',
            createFailed: 'Projekt oder Anforderung konnte nicht erstellt werden.',
            versionSaved: 'Eine neue unveränderliche Anforderungsversion wurde erstellt.',
            draftSaved: 'Entwurf gespeichert',
            draftSaveFailed: 'Der Entwurf konnte nicht gespeichert werden.',
            currentQuickAnalysis: 'Schnellanalyse (Arbeitsentwurf)',
            versionReason: 'Anforderungstext im Analysearbeitsbereich geändert',
            requirementReason: 'Aus dem Ad-hoc-Analysearbeitsbereich angelegt'
        }
    };

    function language() {
        return (document.documentElement.lang || 'en').toLowerCase().startsWith('de')
            ? 'de' : 'en';
    }

    function text(key) {
        return labels[language()][key] || labels.en[key] || key;
    }

    function businessTextElement() {
        return document.getElementById('businessText');
    }

    function csrfHeaders() {
        var headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (token && header && token.content && header.content) {
            headers[header.content] = token.content;
        }
        if (runtime.workspaceId) {
            headers[WORKSPACE_HEADER] = runtime.workspaceId;
        }
        return headers;
    }

    function responseMessage(response, fallback) {
        return response.json().then(function (problem) {
            return problem && (problem.detail || problem.message || problem.title)
                ? String(problem.detail || problem.message || problem.title)
                : fallback;
        }).catch(function () { return fallback; });
    }

    function jsonRequest(url, options) {
        var request = Object.assign({}, options || {});
        request.headers = Object.assign({}, csrfHeaders(), request.headers || {});
        return fetch(url, request).then(function (response) {
            if (!response.ok) {
                return responseMessage(response, 'HTTP ' + response.status).then(function (message) {
                    var error = new Error(message);
                    error.status = response.status;
                    throw error;
                });
            }
            if (response.status === 204) return null;
            return response.json();
        });
    }

    function rememberedWorkspaceId() {
        try {
            var value = window.sessionStorage.getItem(WORKSPACE_STORAGE_KEY);
            return value && value.trim() ? value.trim() : null;
        } catch (error) {
            return null;
        }
    }

    function rememberWorkspaceId(workspaceId) {
        try {
            if (workspaceId) {
                window.sessionStorage.setItem(WORKSPACE_STORAGE_KEY, workspaceId);
            } else {
                window.sessionStorage.removeItem(WORKSPACE_STORAGE_KEY);
            }
        } catch (error) {
            // Storage may be disabled. The server-side draft remains available.
        }
    }

    function requestUrl(input) {
        try {
            if (typeof input === 'string') return new URL(input, window.location.href);
            if (input && typeof input.url === 'string') {
                return new URL(input.url, window.location.href);
            }
        } catch (error) {
            return null;
        }
        return null;
    }

    function installWorkspaceFetchRouting() {
        if (window.fetch.__taxonomyWorkspaceRouting === true) return;
        var nativeFetch = window.fetch.bind(window);

        function routedFetch(input, init) {
            var url = requestUrl(input);
            var request = Object.assign({}, init || {});
            if (runtime.workspaceId && url && url.origin === window.location.origin
                    && url.pathname.indexOf('/api/') === 0) {
                var headers = new Headers(input instanceof Request ? input.headers : undefined);
                new Headers(request.headers || {}).forEach(function (value, name) {
                    headers.set(name, value);
                });
                headers.set(WORKSPACE_HEADER, runtime.workspaceId);
                request.headers = headers;
            }

            var response = nativeFetch(input, request);
            if (url && /\/api\/workspace\/[^/]+\/switch$/.test(url.pathname)
                    && String(request.method || (input && input.method) || 'GET').toUpperCase() === 'POST') {
                var target = decodeURIComponent(url.pathname.split('/').slice(-2, -1)[0]);
                response.then(function (result) {
                    if (!result.ok) return;
                    rememberWorkspaceId(target);
                    window.setTimeout(function () { window.location.reload(); }, 0);
                }).catch(function () { /* caller handles the transport failure */ });
            }
            return response;
        }

        Object.defineProperty(routedFetch, '__taxonomyWorkspaceRouting', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
        window.fetch = routedFetch;
    }

    function draftEndpoint() {
        return runtime.workspaceId
            ? '/api/analysis-drafts/' + encodeURIComponent(runtime.workspaceId)
            : null;
    }

    function hasScores() {
        return S.currentScores && typeof S.currentScores === 'object'
            && Object.keys(S.currentScores).length > 0;
    }

    function hasDerivedAnalysis() {
        return hasScores()
            || Boolean(S.currentArchView)
            || (Array.isArray(S.currentDiscrepancies) && S.currentDiscrepancies.length > 0)
            || (Array.isArray(window._currentProvisionalRelations)
                && window._currentProvisionalRelations.length > 0);
    }

    function isStale() {
        var input = businessTextElement();
        return Boolean(input && hasDerivedAnalysis() && S.lastAnalyzedText !== null
            && input.value !== S.lastAnalyzedText);
    }

    function currentPayload() {
        var input = businessTextElement();
        return {
            schemaVersion: SCHEMA_VERSION,
            businessText: input ? input.value : '',
            lastAnalyzedText: S.lastAnalyzedText,
            storedBusinessText: S.storedBusinessText,
            scores: S.currentScores,
            reasons: S.currentReasons || {},
            discrepancies: S.currentDiscrepancies || [],
            architectureView: S.currentArchView,
            provisionalRelations: Array.isArray(window._currentProvisionalRelations)
                ? window._currentProvisionalRelations : [],
            evaluatedNodes: S.evaluatedNodes instanceof Set
                ? Array.from(S.evaluatedNodes) : [],
            currentView: S.currentView || 'list',
            savedAt: new Date().toISOString()
        };
    }

    function comparable(payload) {
        var copy = Object.assign({}, payload || {});
        delete copy.savedAt;
        return JSON.stringify(copy);
    }

    function meaningful(payload) {
        return Boolean(payload && (
            String(payload.businessText || '').trim()
            || (payload.scores && Object.keys(payload.scores).length > 0)
            || payload.architectureView
            || (payload.discrepancies && payload.discrepancies.length > 0)
            || (payload.provisionalRelations && payload.provisionalRelations.length > 0)
        ));
    }

    function statusArea() {
        return document.getElementById('statusArea');
    }

    function announce(message, assertive) {
        var id = assertive ? 'a11yAlert' : 'a11yStatus';
        var live = document.getElementById(id);
        if (!live) return;
        live.textContent = '';
        window.requestAnimationFrame(function () { live.textContent = message; });
    }

    function showActionAlert(kind, title, body, actions, marker) {
        var area = statusArea();
        if (!area) return;
        area.replaceChildren();
        area.dataset.analysisSessionMessage = marker || '';

        var alert = document.createElement('div');
        alert.className = 'alert alert-' + kind + ' py-2';
        alert.setAttribute('role', kind === 'danger' ? 'alert' : 'status');

        var heading = document.createElement('strong');
        heading.textContent = title;
        alert.appendChild(heading);

        if (body) {
            var paragraph = document.createElement('div');
            paragraph.className = 'small mt-1';
            paragraph.textContent = body;
            alert.appendChild(paragraph);
        }

        if (actions && actions.length) {
            var controls = document.createElement('div');
            controls.className = 'd-flex gap-2 flex-wrap mt-2';
            actions.forEach(function (action) {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = action.className || 'btn btn-sm btn-outline-secondary';
                button.textContent = action.label;
                button.dataset.analysisSessionAction = action.id || 'action';
                button.addEventListener('click', action.handler);
                controls.appendChild(button);
            });
            alert.appendChild(controls);
        }

        area.appendChild(alert);
        announce(title + (body ? '. ' + body : ''), kind === 'danger');
    }

    function rememberDisabledState(element, stale) {
        if (stale) {
            if (!element.hasAttribute('data-analysis-disabled-before-stale')) {
                element.setAttribute('data-analysis-disabled-before-stale',
                    element.disabled ? 'true' : 'false');
            }
            element.disabled = true;
            element.setAttribute('aria-disabled', 'true');
        } else if (element.hasAttribute('data-analysis-disabled-before-stale')) {
            element.disabled = element.getAttribute('data-analysis-disabled-before-stale') === 'true';
            element.removeAttribute('data-analysis-disabled-before-stale');
            if (!element.disabled) element.removeAttribute('aria-disabled');
        }
    }

    function guardStaleActions(stale) {
        document.body.classList.toggle('analysis-results-stale', stale);
        document.querySelectorAll(
            '#exportGroup button, #suggestedRelationsPanel button, '
            + '#gapAnalyzeBtn, #patternDetectBtn, #recommendBtn, #requirementImpactBtn'
        ).forEach(function (button) {
            rememberDisabledState(button, stale);
        });
    }

    function resetPanel(contentId, message) {
        var content = document.getElementById(contentId);
        if (!content) return;
        content.replaceChildren();
        var placeholder = document.createElement('div');
        placeholder.className = 'text-muted';
        placeholder.textContent = message;
        content.appendChild(placeholder);
    }

    function clearDerivedUi() {
        if (window.TaxonomyScoring) {
            if (typeof window.TaxonomyScoring.cancelStreamingAnalysis === 'function') {
                window.TaxonomyScoring.cancelStreamingAnalysis();
            }
            if (typeof window.TaxonomyScoring.clearAnalysisLog === 'function') {
                window.TaxonomyScoring.clearAnalysisLog();
            }
            if (typeof window.TaxonomyScoring.renderArchitectureView === 'function') {
                window.TaxonomyScoring.renderArchitectureView(null);
            }
            if (typeof window.TaxonomyScoring.renderSuggestedRelations === 'function') {
                window.TaxonomyScoring.renderSuggestedRelations(null);
            }
        }

        var analysisLog = document.getElementById('analysisLog');
        if (analysisLog) {
            analysisLog.style.display = 'none';
            analysisLog.open = false;
        }
        var analysisLogContent = document.getElementById('analysisLogContent');
        if (analysisLogContent) analysisLogContent.replaceChildren();

        resetPanel('llmCommLogContent', language() === 'de'
            ? 'Noch keine LLM-Aufrufe. Starten Sie eine Analyse, um die Kommunikation zu sehen.'
            : 'No LLM calls yet. Run an analysis to see LLM communication details.');

        var architecturePanel = document.getElementById('architectureViewPanel');
        if (architecturePanel) architecturePanel.style.display = 'none';
        var architectureContent = document.getElementById('architectureViewContent');
        if (architectureContent) architectureContent.replaceChildren();
        var architecturePlaceholder = document.getElementById('architecturePlaceholder');
        if (architecturePlaceholder) architecturePlaceholder.style.display = '';

        var suggestedPanel = document.getElementById('suggestedRelationsPanel');
        if (suggestedPanel) suggestedPanel.style.display = 'none';
        var suggestedBadge = document.getElementById('suggestedRelationsBadge');
        if (suggestedBadge) suggestedBadge.textContent = '0';
        resetPanel('suggestedRelationsContent', language() === 'de'
            ? 'Noch keine vorgeschlagenen Beziehungen.'
            : 'No suggested relationships yet.');

        var copilotPanel = document.getElementById('copilotPanel');
        if (copilotPanel) copilotPanel.style.display = 'none';
        resetPanel('copilotContent', language() === 'de'
            ? 'Starten Sie den Copilot für eine vollständige Architekturanalyse.'
            : 'Run Copilot to start a full architecture analysis.');

        [
            ['gapAnalysisPanel', 'gapAnalysisContent', language() === 'de'
                ? 'Führen Sie eine Gap-Analyse aus, um fehlende Beziehungen und unvollständige Muster zu erkennen.'
                : 'Run gap analysis to identify missing relations and incomplete patterns.'],
            ['patternDetectionPanel', 'patternDetectionContent', language() === 'de'
                ? 'Erkennen Sie Architekturmuster in den bewerteten Knoten.'
                : 'Detect architecture patterns across scored nodes.'],
            ['recommendationPanel', 'recommendationContent', language() === 'de'
                ? 'Erzeugen Sie nach einer Analyse eine Architekturempfehlung.'
                : 'Generate an architecture recommendation after an analysis.']
        ].forEach(function (entry) {
            var panel = document.getElementById(entry[0]);
            if (panel) panel.open = false;
            resetPanel(entry[1], entry[2]);
        });

        var impact = document.getElementById('requirementImpactResults');
        if (impact) {
            impact.style.display = 'none';
            impact.replaceChildren();
        }

        ['analyzeViewContext', 'exportViewContext', 'graphViewContext'].forEach(function (id) {
            var element = document.getElementById(id);
            if (!element) return;
            element.replaceChildren();
            element.classList.add('d-none');
        });

        resetPanel('provenanceContent', language() === 'de'
            ? 'Provenienzinformationen werden nach einer Analyse angezeigt.'
            : 'Requirement provenance information will be displayed after analysis.');

        var summaryButton = document.getElementById('viewSummary');
        if (summaryButton) summaryButton.style.display = 'none';
        var manualApply = document.getElementById('manualApplyBtn');
        if (manualApply) manualApply.remove();

        var exportGroup = document.getElementById('exportGroup');
        if (exportGroup) exportGroup.style.display = 'none';
        var exportHint = document.getElementById('exportHint');
        if (exportHint) exportHint.classList.remove('d-none');

        if (window.TaxonomyBrowse && Array.isArray(S.taxonomyData) && S.taxonomyData.length) {
            if (S.currentView === 'summary'
                    && typeof window.TaxonomyBrowse.switchView === 'function') {
                window.TaxonomyBrowse.switchView('list');
            } else if (typeof window.TaxonomyBrowse.renderView === 'function') {
                window.TaxonomyBrowse.renderView(S.taxonomyData, null);
            }
            if (typeof window.TaxonomyBrowse.updateExportGroupVisibility === 'function') {
                window.TaxonomyBrowse.updateExportGroupVisibility();
            }
        }
    }

    function invalidate(options) {
        options = options || {};
        var input = businessTextElement();
        var preservedText = input ? input.value : '';

        runtime.invalidating = true;
        S.currentScores = null;
        S.currentReasons = {};
        S.currentDiscrepancies = [];
        S.currentArchView = null;
        S.storedBusinessText = null;
        S.evaluatedNodes = new Set();
        S.lastAnalyzedText = null;
        S.pendingProposalNodeCode = null;
        window._taxonomyCurrentScores = null;
        window._currentProvisionalRelations = [];

        if (input) {
            input.value = options.keepText === false ? '' : preservedText;
            input.classList.remove('stale-results');
        }

        clearDerivedUi();
        guardStaleActions(false);
        runtime.conflict = false;
        runtime.invalidating = false;

        document.dispatchEvent(new CustomEvent('taxonomy:analysis-invalidated', {
            detail: {
                reason: options.reason || 'user',
                keptRequirementText: options.keepText !== false
            }
        }));

        if (!options.silent) {
            showActionAlert('info', text('discarded'), '', [], 'discarded');
        }
        queueSave(0);
    }

    function discardTextChange() {
        var input = businessTextElement();
        if (!input || S.lastAnalyzedText === null) return;
        input.value = S.lastAnalyzedText;
        input.classList.remove('stale-results');
        guardStaleActions(false);
        if (statusArea()) {
            statusArea().replaceChildren();
            delete statusArea().dataset.analysisSessionMessage;
        }
        queueSave(0);
        input.focus();
    }

    function projectContextFromUrl() {
        var parameters = new URLSearchParams(window.location.search);
        var projectId = parameters.get('projectId');
        var requirementId = parameters.get('requirementId');
        return projectId && requirementId
            ? { projectId: projectId, requirementId: requirementId }
            : null;
    }

    function analysisRunning() {
        var button = document.getElementById('analyzeBtn');
        var spinner = document.getElementById('analyzeSpinner');
        return Boolean(button && button.disabled && spinner
            && !spinner.classList.contains('d-none'));
    }

    function showStaleActions() {
        if (!isStale() || runtime.conflict || analysisRunning()) return;
        var area = statusArea();
        if (area && area.dataset.analysisSessionMessage === 'stale'
                && area.querySelector('[data-analysis-session-action="discard-analysis"]')) {
            guardStaleActions(true);
            return;
        }

        var actions = [
            {
                id: 'discard-edit',
                label: text('discardEdit'),
                className: 'btn btn-sm btn-outline-secondary',
                handler: discardTextChange
            },
            {
                id: 'discard-analysis',
                label: text('discardAnalysis'),
                className: 'btn btn-sm btn-warning',
                handler: function () {
                    invalidate({ keepText: true, reason: 'requirement-text-changed' });
                }
            },
            {
                id: 'add-requirement',
                label: text('addRequirement'),
                className: 'btn btn-sm btn-outline-primary',
                handler: function () {
                    openRequirementDialog(businessTextElement().value);
                }
            },
            {
                id: 'new-project',
                label: text('newProject'),
                className: 'btn btn-sm btn-outline-danger',
                handler: function () {
                    openNewProjectDialog({ seedRequirementText: null });
                }
            }
        ];

        var context = projectContextFromUrl();
        if (context) {
            actions.splice(2, 0, {
                id: 'save-version',
                label: text('saveVersion'),
                className: 'btn btn-sm btn-primary',
                handler: function () {
                    saveAsRequirementVersion(context, businessTextElement().value);
                }
            });
        }

        showActionAlert('warning', text('staleTitle'), text('staleBody'), actions, 'stale');
        guardStaleActions(true);
    }

    function queueStaleActions() {
        window.clearTimeout(runtime.staleTimer);
        runtime.staleTimer = window.setTimeout(function () {
            if (isStale()) {
                guardStaleActions(true);
                if (!analysisRunning()) showStaleActions();
            } else {
                guardStaleActions(false);
            }
        }, 340);
    }

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
                className: 'btn btn-sm btn-outline-light',
                handler: function () {
                    openRequirementDialog(businessTextElement().value);
                }
            }
        ], 'conflict');
    }

    function generateProjectKey() {
        var now = new Date();
        var date = String(now.getFullYear())
            + String(now.getMonth() + 1).padStart(2, '0')
            + String(now.getDate()).padStart(2, '0');
        return 'P-' + date + '-' + String(now.getTime()).slice(-4);
    }

    function deriveTitle(requirementText) {
        var clean = String(requirementText || '').replace(/\s+/g, ' ').trim();
        if (!clean) return language() === 'de' ? 'Neue Architektur' : 'New architecture';
        var firstSentence = clean.split(/(?<=[.!?])\s+/)[0];
        return firstSentence.substring(0, 120);
    }

    function nextRequirementKey(requirements) {
        var maximum = 0;
        (requirements || []).forEach(function (requirement) {
            var match = /^REQ-(\d+)$/.exec(requirement.requirementKey || '');
            if (match) maximum = Math.max(maximum, Number(match[1]));
        });
        return 'REQ-' + String(maximum + 1).padStart(3, '0');
    }

    function createDialog(title) {
        var dialog = document.createElement('dialog');
        dialog.className = 'taxonomy-accessible-dialog';
        dialog.setAttribute('aria-modal', 'true');

        var form = document.createElement('form');
        form.noValidate = true;

        var heading = document.createElement('h2');
        heading.className = 'h5';
        heading.textContent = title;

        var body = document.createElement('div');
        body.className = 'mb-3';

        var error = document.createElement('div');
        error.className = 'alert alert-danger d-none';
        error.setAttribute('role', 'alert');
        error.setAttribute('tabindex', '-1');

        var footer = document.createElement('div');
        footer.className = 'd-flex justify-content-end gap-2';

        var cancel = document.createElement('button');
        cancel.type = 'button';
        cancel.className = 'btn btn-outline-secondary';
        cancel.textContent = text('cancel');
        cancel.addEventListener('click', function () { dialog.close(); });

        var submit = document.createElement('button');
        submit.type = 'submit';
        submit.className = 'btn btn-primary';
        submit.textContent = text('create');

        footer.append(cancel, submit);
        form.append(heading, body, error, footer);
        dialog.appendChild(form);
        document.body.appendChild(dialog);
        dialog.addEventListener('close', function () { dialog.remove(); }, { once: true });
        dialog.showModal();
        return { dialog: dialog, form: form, body: body, error: error, submit: submit };
    }

    function field(labelText, input) {
        var group = document.createElement('div');
        group.className = 'mb-3';
        var label = document.createElement('label');
        label.className = 'form-label';
        if (!input.id) input.id = 'analysis-session-' + Math.random().toString(36).slice(2);
        label.htmlFor = input.id;
        label.textContent = labelText;
        group.append(label, input);
        return group;
    }

    function setDialogBusy(parts, busy) {
        parts.submit.disabled = busy;
        parts.submit.textContent = busy ? text('saving') : text('create');
    }

    function showDialogError(parts, error) {
        parts.error.textContent = error && error.message ? error.message : text('createFailed');
        parts.error.classList.remove('d-none');
        parts.error.focus();
    }

    function createRequirement(projectId, values) {
        return jsonRequest('/api/projects/' + encodeURIComponent(projectId) + '/requirements', {
            method: 'POST',
            body: JSON.stringify({
                requirementKey: values.requirementKey,
                title: values.title,
                text: values.text,
                status: 'DRAFT',
                priority: 50,
                criticality: 'MEDIUM',
                requirementType: 'FUNCTIONAL',
                reviewStatus: 'PROPOSED',
                ownerUsername: null,
                changeReason: text('requirementReason'),
                source: null
            })
        });
    }

    function discardDraftAndNavigate(url) {
        invalidate({ keepText: false, silent: true, reason: 'promoted-to-project' });
        return deleteDraft().finally(function () { window.location.assign(url); });
    }

    function openRequirementDialog(requirementText) {
        showActionAlert('info', text('loadingProjects'), '', [], 'loading-projects');
        jsonRequest('/api/projects', { method: 'GET' }).then(function (projects) {
            if (!projects || projects.length === 0) {
                openNewProjectDialog({ seedRequirementText: requirementText });
                return;
            }

            var parts = createDialog(text('requirementDialogTitle'));
            var projectSelect = document.createElement('select');
            projectSelect.className = 'form-select';
            projectSelect.required = true;
            projects.forEach(function (project) {
                var option = document.createElement('option');
                option.value = project.id;
                option.textContent = project.projectKey + ' — ' + project.title;
                projectSelect.appendChild(option);
            });

            var keyInput = document.createElement('input');
            keyInput.className = 'form-control';
            keyInput.required = true;
            keyInput.maxLength = 64;
            keyInput.value = 'REQ-001';

            var titleInput = document.createElement('input');
            titleInput.className = 'form-control';
            titleInput.required = true;
            titleInput.maxLength = 240;
            titleInput.value = deriveTitle(requirementText);

            var textInput = document.createElement('textarea');
            textInput.className = 'form-control';
            textInput.rows = 10;
            textInput.required = true;
            textInput.value = requirementText || '';

            parts.body.append(
                field(text('project'), projectSelect),
                field(text('requirementKey'), keyInput),
                field(text('requirementTitle'), titleInput),
                field(text('requirementText'), textInput)
            );

            function refreshKey() {
                jsonRequest('/api/projects/' + encodeURIComponent(projectSelect.value) + '/requirements', {
                    method: 'GET'
                }).then(function (requirements) {
                    keyInput.value = nextRequirementKey(requirements);
                }).catch(function () { keyInput.value = 'REQ-001'; });
            }
            projectSelect.addEventListener('change', refreshKey);
            refreshKey();

            parts.form.addEventListener('submit', function (event) {
                event.preventDefault();
                if (!parts.form.reportValidity()) return;
                setDialogBusy(parts, true);
                createRequirement(projectSelect.value, {
                    requirementKey: keyInput.value.trim(),
                    title: titleInput.value.trim(),
                    text: textInput.value
                }).then(function (requirement) {
                    parts.dialog.close();
                    return discardDraftAndNavigate('/projects/' + encodeURIComponent(projectSelect.value)
                        + '/requirements/' + encodeURIComponent(requirement.id));
                }).catch(function (error) {
                    setDialogBusy(parts, false);
                    showDialogError(parts, error);
                });
            });
            projectSelect.focus();
        }).catch(function (error) {
            showActionAlert('danger', text('createFailed'), error.message, [], 'project-load-error');
        });
    }

    function openNewProjectDialog(options) {
        options = options || {};
        var seed = options.seedRequirementText;
        var parts = createDialog(text('projectDialogTitle'));

        var keyInput = document.createElement('input');
        keyInput.className = 'form-control';
        keyInput.required = true;
        keyInput.maxLength = 64;
        keyInput.value = generateProjectKey();

        var titleInput = document.createElement('input');
        titleInput.className = 'form-control';
        titleInput.required = true;
        titleInput.maxLength = 240;
        titleInput.value = deriveTitle(seed || '');

        var descriptionInput = document.createElement('textarea');
        descriptionInput.className = 'form-control';
        descriptionInput.rows = 3;
        descriptionInput.maxLength = 4000;

        parts.body.append(
            field(text('projectKey'), keyInput),
            field(text('projectTitle'), titleInput),
            field(text('projectDescription'), descriptionInput)
        );

        var createRequirementCheck = null;
        if (seed) {
            var checkGroup = document.createElement('div');
            checkGroup.className = 'form-check mb-3';
            createRequirementCheck = document.createElement('input');
            createRequirementCheck.type = 'checkbox';
            createRequirementCheck.className = 'form-check-input';
            createRequirementCheck.id = 'analysis-session-create-first-requirement';
            createRequirementCheck.checked = true;
            var checkLabel = document.createElement('label');
            checkLabel.className = 'form-check-label';
            checkLabel.htmlFor = createRequirementCheck.id;
            checkLabel.textContent = text('createFirstRequirement');
            checkGroup.append(createRequirementCheck, checkLabel);
            parts.body.appendChild(checkGroup);
        }

        parts.form.addEventListener('submit', function (event) {
            event.preventDefault();
            if (!parts.form.reportValidity()) return;
            setDialogBusy(parts, true);
            jsonRequest('/api/projects', {
                method: 'POST',
                body: JSON.stringify({
                    projectKey: keyInput.value.trim(),
                    title: titleInput.value.trim(),
                    description: descriptionInput.value.trim(),
                    status: 'DRAFT',
                    targetArchitecture: null,
                    targetDate: null,
                    budgetAmount: null,
                    budgetCurrency: null
                })
            }).then(function (project) {
                if (seed && createRequirementCheck && createRequirementCheck.checked) {
                    return createRequirement(project.id, {
                        requirementKey: 'REQ-001',
                        title: deriveTitle(seed),
                        text: seed
                    }).then(function (requirement) {
                        return '/projects/' + encodeURIComponent(project.id)
                            + '/requirements/' + encodeURIComponent(requirement.id);
                    });
                }
                return '/projects';
            }).then(function (url) {
                parts.dialog.close();
                return discardDraftAndNavigate(url);
            }).catch(function (error) {
                setDialogBusy(parts, false);
                showDialogError(parts, error);
            });
        });
        keyInput.focus();
    }

    function saveAsRequirementVersion(context, requirementText) {
        jsonRequest('/api/projects/' + encodeURIComponent(context.projectId)
                + '/requirements/' + encodeURIComponent(context.requirementId) + '/versions', {
            method: 'POST',
            body: JSON.stringify({
                text: requirementText,
                changeReason: text('versionReason'),
                source: null
            })
        }).then(function () {
            invalidate({ keepText: true, silent: true, reason: 'promoted-to-version' });
            showActionAlert('success', text('versionSaved'), '', [], 'version-saved');
        }).catch(function (error) {
            showActionAlert('danger', text('createFailed'), error.message, [], 'version-error');
        });
    }

    function resolveWorkspaceAndLoad() {
        var remembered = rememberedWorkspaceId();
        installWorkspaceFetchRouting();

        if (remembered) {
            runtime.workspaceId = remembered;
            return loadDraft({ probe: true }).then(function (view) {
                rememberWorkspaceId(remembered);
                return view;
            }).catch(function (error) {
                runtime.workspaceId = null;
                runtime.version = null;
                rememberWorkspaceId(null);
                if (window.console) {
                    window.console.warn('[Taxonomy] Remembered workspace is no longer available', error);
                }
                return resolveActiveWorkspaceAndLoad();
            });
        }
        return resolveActiveWorkspaceAndLoad();
    }

    function resolveActiveWorkspaceAndLoad() {
        return jsonRequest('/api/workspace/current', { method: 'GET' })
            .then(function (workspace) {
                if (!workspace || !workspace.workspaceId) return null;
                runtime.workspaceId = workspace.workspaceId;
                rememberWorkspaceId(runtime.workspaceId);
                return loadDraft();
            })
            .catch(function (error) {
                if (window.console) window.console.warn('[Taxonomy] Workspace draft unavailable', error);
                return null;
            });
    }

    function installObservers() {
        var input = businessTextElement();
        if (input) {
            input.addEventListener('input', function () {
                queueStaleActions();
                queueSave();
            });
            new MutationObserver(queueStaleActions).observe(input, {
                attributes: true,
                attributeFilter: ['class']
            });
        }

        var area = statusArea();
        if (area) {
            new MutationObserver(function () {
                if (isStale() && (area.dataset.analysisSessionMessage !== 'stale'
                        || !area.querySelector('[data-analysis-session-action="discard-analysis"]'))) {
                    queueStaleActions();
                }
            }).observe(area, { childList: true, subtree: true });
        }

        // Intercept the legacy one-size-fits-all reset button before its target
        // listener can leave architecture panels and other derived state behind.
        document.addEventListener('click', function (event) {
            var button = event.target.closest && event.target.closest('#statusArea .btn-warning');
            if (!button || button.dataset.analysisSessionAction || !isStale()) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            invalidate({ keepText: true, reason: 'legacy-stale-reset' });
        }, true);

        document.addEventListener('taxonomy:view-rendered', function () {
            queueSave();
            queueStaleActions();
        });
        document.addEventListener('taxonomy:analysis-invalidated', function () {
            queueSave(0);
        });

        window.setInterval(function () {
            if (runtime.restoring || runtime.invalidating || runtime.conflict) return;
            var serialized = comparable(currentPayload());
            if (serialized !== runtime.lastObservedComparable) {
                runtime.lastObservedComparable = serialized;
                queueSave();
            }
        }, CHANGE_POLL_MS);

        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'hidden') saveDraft();
        });
        window.addEventListener('pagehide', function () { saveDraft(); });
    }

    function initialize() {
        if (runtime.initialized) return;
        runtime.initialized = true;
        installObservers();
        resolveWorkspaceAndLoad();
    }

    window.TaxonomyAnalysisSession = Object.freeze({
        invalidate: invalidate,
        saveNow: saveDraft,
        reload: function () { runtime.conflict = false; return loadDraft({ force: true }); },
        addAsRequirement: function () {
            var input = businessTextElement();
            openRequirementDialog(input ? input.value : '');
        },
        startNewProject: function () { openNewProjectDialog({ seedRequirementText: null }); },
        state: function () {
            return Object.freeze({
                workspaceId: runtime.workspaceId,
                version: runtime.version,
                conflict: runtime.conflict,
                restoring: runtime.restoring
            });
        }
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize, { once: true });
    } else {
        initialize();
    }
}());
