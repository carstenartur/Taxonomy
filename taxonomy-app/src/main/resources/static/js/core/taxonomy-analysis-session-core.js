/* taxonomy-analysis-session-core.js – shared state, transport and UI primitives */
(function () {
    'use strict';
    if (window.__TaxonomyAnalysisSessionContext || window.TaxonomyAnalysisSession
            || !window.TaxonomyState) return;

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
        resetting: false,
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
            requirementReason: 'Created from the ad-hoc analysis workspace',
            newAnalysisConfirm: 'Start a new analysis? The current requirement text, scores and every derived view in this working draft will be discarded. Saved projects and requirements are not deleted.',
            newAnalysisReadyTitle: 'New analysis ready',
            newAnalysisReadyBody: 'The working draft was reset. You can enter a new requirement now.',
            newAnalysisFailed: 'The working draft could not be reset.',
            analysisCancelledTitle: 'Analysis cancelled',
            analysisCancelledBody: 'The current request was stopped. The requirement text and results already received remain available.',
            noAnalysisRunning: 'No analysis is currently running.',
            draftSavedNowTitle: 'Draft saved',
            draftSavedNowBody: 'The current working state was saved in this workspace.'
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
            requirementReason: 'Aus dem Ad-hoc-Analysearbeitsbereich angelegt',
            newAnalysisConfirm: 'Neue Analyse beginnen? Der aktuelle Anforderungstext, die Bewertungen und alle daraus abgeleiteten Ansichten dieses Arbeitsentwurfs werden verworfen. Gespeicherte Projekte und Anforderungen werden nicht gelöscht.',
            newAnalysisReadyTitle: 'Neue Analyse bereit',
            newAnalysisReadyBody: 'Der Arbeitsentwurf wurde zurückgesetzt. Sie können jetzt eine neue Anforderung eingeben.',
            newAnalysisFailed: 'Der Arbeitsentwurf konnte nicht zurückgesetzt werden.',
            analysisCancelledTitle: 'Analyse abgebrochen',
            analysisCancelledBody: 'Die laufende Anfrage wurde gestoppt. Der Anforderungstext und bereits empfangene Ergebnisse bleiben erhalten.',
            noAnalysisRunning: 'Zurzeit läuft keine Analyse.',
            draftSavedNowTitle: 'Entwurf gespeichert',
            draftSavedNowBody: 'Der aktuelle Arbeitsstand wurde in diesem Workspace gespeichert.'
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
        var api = window.TaxonomyAnalysisSessionApi;
        if (!api || typeof api.request !== 'function') {
            return Promise.reject(new Error('Analysis session API client is not available'));
        }
        return api.request(url, request).then(function (response) {
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

    function installWorkspaceEventSourceRouting() {
        var NativeEventSource = window.EventSource;
        if (typeof NativeEventSource !== 'function'
                || NativeEventSource.__taxonomyWorkspaceRouting === true) return;

        function RoutedEventSource(url, configuration) {
            var resolved = requestUrl(url);
            var target = url;
            if (runtime.workspaceId && resolved
                    && resolved.origin === window.location.origin
                    && resolved.pathname.indexOf('/api/') === 0) {
                resolved.searchParams.set('workspaceId', runtime.workspaceId);
                target = resolved.href;
            }
            return new NativeEventSource(target, configuration);
        }

        RoutedEventSource.prototype = NativeEventSource.prototype;
        ['CONNECTING', 'OPEN', 'CLOSED'].forEach(function (constant) {
            if (constant in NativeEventSource) {
                RoutedEventSource[constant] = NativeEventSource[constant];
            }
        });
        Object.defineProperty(RoutedEventSource, '__taxonomyWorkspaceRouting', {
            configurable: false,
            enumerable: false,
            value: true,
            writable: false
        });
        window.EventSource = RoutedEventSource;
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
        var payload = {
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
        payload.draftState = meaningful(payload) ? 'ACTIVE' : 'EMPTY';
        return payload;
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

    window.__TaxonomyAnalysisSessionContext = {
        S: S,
        runtime: runtime,
        labels: labels,
        SCHEMA_VERSION: SCHEMA_VERSION,
        AUTOSAVE_DELAY_MS: AUTOSAVE_DELAY_MS,
        CHANGE_POLL_MS: CHANGE_POLL_MS,
        MAX_RESTORE_ATTEMPTS: MAX_RESTORE_ATTEMPTS,
        WORKSPACE_HEADER: WORKSPACE_HEADER,
        WORKSPACE_STORAGE_KEY: WORKSPACE_STORAGE_KEY,
        language: language,
        text: text,
        businessTextElement: businessTextElement,
        csrfHeaders: csrfHeaders,
        responseMessage: responseMessage,
        jsonRequest: jsonRequest,
        rememberedWorkspaceId: rememberedWorkspaceId,
        rememberWorkspaceId: rememberWorkspaceId,
        requestUrl: requestUrl,
        installWorkspaceFetchRouting: installWorkspaceFetchRouting,
        installWorkspaceEventSourceRouting: installWorkspaceEventSourceRouting,
        draftEndpoint: draftEndpoint,
        hasScores: hasScores,
        hasDerivedAnalysis: hasDerivedAnalysis,
        isStale: isStale,
        currentPayload: currentPayload,
        comparable: comparable,
        meaningful: meaningful,
        statusArea: statusArea,
        announce: announce,
        showActionAlert: showActionAlert,
        rememberDisabledState: rememberDisabledState,
        guardStaleActions: guardStaleActions,
        resetPanel: resetPanel
    };
}());
