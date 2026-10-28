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
        workspaceResolved: false,
        saveTimer: null,
        staleTimer: null,
        lastSavedComparable: null,
        lastObservedComparable: null,
        restoredPayload: null
    };

    var labels = {
        en: {
            serverDraftMissingTitle: 'The saved analysis is no longer present on the server',
            serverDraftMissingBody: 'The previous results and progress were cleared to match the server.',
            serverDraftMissingLocalBody: 'The previous results and progress were cleared. Your unsaved requirement text is kept locally. Choose whether to save it as a new draft or use the empty server state.',
            useServerState: 'Use server state',
            saveLocalInput: 'Save input as a new draft',
            serverUnavailableTitle: 'The server state is currently unconfirmed',
            serverUnavailableBody: 'The last confirmed results remain visible. Saving and analysis actions are paused until the connection and saved state have been checked. You can continue editing the requirement text.',
            checkServer: 'Check connection and load status',
            serverReconnectedTitle: 'Server state confirmed',
            serverReconnectedBody: 'The saved revision is unchanged. Local edits have been retained.',
            serverWorkspaceMissingTitle: 'The previous workspace is no longer available',
            serverWorkspaceMissingBody: 'Its analysis results were cleared. The application is loading the current server workspace; local requirement edits are retained.',
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
            newAnalysisWorkspaceUnavailable: 'The analysis workspace is not ready yet. Wait for initialization and try again.',
            analysisCancelledTitle: 'Analysis cancelled',
            analysisCancelledBody: 'The current request was stopped. The requirement text and results already received remain available.',
            noAnalysisRunning: 'No analysis is currently running.',
            draftSavedNowTitle: 'Draft saved',
            draftSavedNowBody: 'The current working state was saved in this workspace.'
        },
        de: {
            serverDraftMissingTitle: 'Die gespeicherte Analyse ist auf dem Server nicht mehr vorhanden',
            serverDraftMissingBody: 'Die bisherigen Ergebnisse und Fortschrittsanzeigen wurden an den leeren Serverstand angeglichen.',
            serverDraftMissingLocalBody: 'Die bisherigen Ergebnisse und Fortschrittsanzeigen wurden entfernt. Ihr ungespeicherter Anforderungstext bleibt lokal erhalten. Wählen Sie, ob Sie ihn als neuen Entwurf speichern oder den leeren Serverstand übernehmen möchten.',
            useServerState: 'Serverstand übernehmen',
            saveLocalInput: 'Eingabe als neuen Entwurf speichern',
            serverUnavailableTitle: 'Der Serverstand ist derzeit nicht bestätigt',
            serverUnavailableBody: 'Die zuletzt bestätigten Ergebnisse bleiben sichtbar. Speichern und Analyseaktionen pausieren, bis Verbindung und gespeicherter Stand geprüft sind. Sie können den Anforderungstext weiter bearbeiten.',
            checkServer: 'Verbindung prüfen und Status laden',
            serverReconnectedTitle: 'Serverstand bestätigt',
            serverReconnectedBody: 'Die gespeicherte Revision ist unverändert. Lokale Änderungen wurden beibehalten.',
            serverWorkspaceMissingTitle: 'Der bisherige Arbeitsbereich ist nicht mehr verfügbar',
            serverWorkspaceMissingBody: 'Seine Analyseergebnisse wurden entfernt. Die Anwendung lädt den aktuellen Arbeitsbereich vom Server; lokale Texteingaben bleiben erhalten.',
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
            newAnalysisWorkspaceUnavailable: 'Der Analyse-Workspace ist noch nicht bereit. Warten Sie die Initialisierung ab und versuchen Sie es erneut.',
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
            || Boolean(S.currentRelationSearchReport)
            || Boolean(S.currentArchView)
            || (Array.isArray(S.currentDiscrepancies) && S.currentDiscrepancies.length > 0)
            || (Array.isArray(S.currentProductCoverageGaps)
                && S.currentProductCoverageGaps.length > 0)
            || (Array.isArray(window._currentProvisionalRelations)
                && window._currentProvisionalRelations.length > 0);
    }

    function isStale() {
        var input = businessTextElement();
        return Boolean(input && hasDerivedAnalysis() && S.lastAnalyzedText !== null
            && input.value !== S.lastAnalyzedText);
    }

    // Only accepted canonical evidence can be replaced by a durable reference. Compare
    // every persisted result field; manual edits and legacy unsaved checkpoints stay full.
    var evidenceFields = ['lastAnalyzedText', 'lastAnalysisProvider', 'analysisOperationId',
        'analysisProviderId', 'lastAnalysisStatus', 'analysisDurationMillis', 'analysisCoverage',
        'analysisScope', 'analysisReasonLength', 'analysisLanguage', 'scores', 'rawScores',
        'effectiveScores', 'scoreDetails', 'productSuitabilityScores', 'scoreSemanticsVersion',
        'scoreSemanticsWarnings', 'reasons', 'discrepancies', 'productCoverageGaps',
        'architectureView', 'relationSearchReport', 'provisionalRelations', 'evaluatedNodes', 'copilot'];
    function evidencePayload(payload) {
        return JSON.stringify(evidenceFields.map(function (key) { return [key, payload[key]]; }));
    }
    function retainAnalysisReference(result, request) {
        var continuationId = result.recovery?.id || null;
        var operationId = result.analysisOperationId || S.analysisOperationId || null;
        if (!runtime.workspaceId || (!continuationId && !(operationId && request?.durable))
                || Object.keys(S.recoveryContext?.followups || {}).length) return;
        var canonical = {
            lastAnalyzedText: request?.businessText ?? S.lastAnalyzedText,
            lastAnalysisProvider: result.provider || request?.provider || null,
            analysisOperationId: operationId, analysisProviderId: result.analysisProviderId || request?.provider || null,
            lastAnalysisStatus: result.status || 'UNKNOWN', analysisDurationMillis: result.analysisDurationMillis ?? null,
            analysisCoverage: result.analysisCoverage || null, analysisScope: result.analysisScope || request?.analysisScope || null,
            analysisReasonLength: result.analysisReasonLength || null, analysisLanguage: result.analysisLanguage || request?.analysisLanguage || null,
            scores: result.effectiveScores || result.scores || {}, rawScores: result.rawScores || result.scores || {},
            effectiveScores: result.effectiveScores || result.scores || {}, scoreDetails: result.scoreDetails || {},
            productSuitabilityScores: result.productSuitabilityScores || {}, scoreSemanticsVersion: result.scoreSemanticsVersion || 0,
            scoreSemanticsWarnings: result.scoreSemanticsWarnings || [], reasons: result.reasons || {},
            discrepancies: result.discrepancies || [], productCoverageGaps: result.productCoverageGaps || [],
            architectureView: result.architectureView || null,
            relationSearchReport: result.relationSearchReport || result.architectureView?.relationSearchReport || null,
            provisionalRelations: result.provisionalRelations || [], evaluatedNodes: [], copilot: result.copilot || null
        };
        // A same-scope view retained from an earlier response is useful, but cannot
        // be reconstructed from this result. Never compact it or other extra evidence.
        if (evidencePayload(currentPayload(true)) !== evidencePayload(canonical)) return;
        runtime.analysisReference = { operationId: operationId, continuationId: continuationId,
            workspaceId: runtime.workspaceId };
        runtime.analysisEvidence = evidencePayload(currentPayload(true));
    }
    function matchesAnalysisReference() {
        var ref = runtime.analysisReference;
        return Boolean(ref && ref.workspaceId === runtime.workspaceId
            && (!ref.continuationId || ref.continuationId === S.analysisRecovery?.id)
            && (!ref.operationId || ref.operationId === S.analysisOperationId)
            && !Object.keys(S.recoveryContext?.followups || {}).length
            && runtime.analysisEvidence === evidencePayload(currentPayload(true)));
    }
    function canHydrateAnalysisResult(result) {
        if (runtime.analysisReference) return matchesAnalysisReference();
        if (!runtime.preserveRestoredEvidence) return true;
        // A legacy full draft may contain edits whose provenance predates references.
        // Read-only status refresh must not replace them with a different server view.
        var current = [S.currentRawScores || {}, S.currentEffectiveScores || S.currentScores || {},
            S.currentScores || {}, S.currentReasons || {}, S.currentDiscrepancies || [],
            S.currentProductCoverageGaps || [], S.currentArchView || null,
            S.currentRelationSearchReport || null, window._currentProvisionalRelations || [], S.currentCopilot || null];
        var canonical = [result.rawScores || result.scores || {}, result.effectiveScores || result.scores || {},
            result.effectiveScores || result.scores || {}, result.reasons || {}, result.discrepancies || [],
            result.productCoverageGaps || [], result.architectureView || null,
            result.relationSearchReport || result.architectureView?.relationSearchReport || null,
            result.provisionalRelations || [], result.copilot || null];
        return JSON.stringify(current) === JSON.stringify(canonical);
    }
    async function resolveAnalysisReference(payload) {
        var ref = payload.analysisReference, workspace = runtime.workspaceId;
        if (!ref || ref.workspaceId !== workspace || (!ref.continuationId && !ref.operationId)) {
            throw new Error('The saved analysis reference does not belong to this workspace.');
        }
        var api = window.TaxonomyAnalysisSessionApi, result, request;
        if (ref.continuationId) {
            var response = await api.request('/api/analysis-continuations/' + encodeURIComponent(ref.continuationId),
                { method: 'GET', cache: 'no-store' }, workspace);
            if (!response.ok) throw new Error('The saved analysis could not be loaded (HTTP ' + response.status + ').');
            var snapshot = await response.json();
            if (snapshot.recovery?.id !== ref.continuationId || typeof snapshot.request?.businessText !== 'string') {
                throw new Error('The saved analysis reference could not be confirmed.');
            }
            if (!snapshot.result || typeof snapshot.result !== 'object') throw new Error('The saved analysis result is not available yet. Refresh its status.');
            result = snapshot.result; request = snapshot.request;
            result = Object.assign({}, result, { recovery: snapshot.recovery });
        } else {
            var inputResponse = await api.getRunInput(ref.operationId, { workspaceId: workspace });
            request = await inputResponse.json();
            if (request.operationId !== ref.operationId || request.scope?.workspaceId !== workspace
                    || typeof request.businessText !== 'string') throw new Error('The saved analysis scope could not be confirmed.');
            var resultResponse = await api.getRunResult(ref.operationId, { workspaceId: workspace });
            if (resultResponse.status === 202) throw new Error('The saved analysis is still running. Refresh its status to retrieve the result.');
            result = await resultResponse.json();
            if (result.analysisOperationId && result.analysisOperationId !== ref.operationId) throw new Error('The saved analysis identity changed.');
        }
        return Object.assign({}, payload, {
            canonicalAnalysisResult: result, canonicalAnalysisRequest: request,
            taxonomyTree: result.tree || null,
            lastAnalyzedText: request.businessText, storedBusinessText: request.businessText,
            lastAnalysisProvider: result.provider || request.provider || null,
            analysisOperationId: result.analysisOperationId || ref.operationId || null,
            analysisProviderId: result.analysisProviderId || request.provider || null,
            lastAnalysisStatus: result.status || 'IN_PROGRESS', analysisDurationMillis: result.analysisDurationMillis ?? null,
            analysisCoverage: result.analysisCoverage || null, analysisScope: result.analysisScope || request.analysisScope || null,
            analysisReasonLength: result.analysisReasonLength || null, analysisLanguage: result.analysisLanguage || request.analysisLanguage || null,
            analysisRecovery: result.recovery || null,
            recoveryContext: ref.continuationId ? { id: ref.continuationId, workspaceId: workspace,
                request: request, confirmed: true, noFollowups: true, followups: {},
                nodeEvidenceBaseline: payload.nodeEvidenceBaseline || null } : null,
            scores: result.effectiveScores || result.scores || {}, rawScores: result.rawScores || result.scores || {},
            effectiveScores: result.effectiveScores || result.scores || {}, scoreDetails: result.scoreDetails || {},
            productSuitabilityScores: result.productSuitabilityScores || {}, scoreSemanticsVersion: result.scoreSemanticsVersion || 0,
            scoreSemanticsWarnings: result.scoreSemanticsWarnings || [], reasons: result.reasons || {},
            discrepancies: result.discrepancies || [], productCoverageGaps: result.productCoverageGaps || [],
            architectureView: result.architectureView || null,
            relationSearchReport: result.relationSearchReport || result.architectureView?.relationSearchReport || null,
            provisionalRelations: result.provisionalRelations || [], evaluatedNodes: [], copilot: result.copilot || null
        });
    }
    function currentPayload(fullEvidence) {
        var input = businessTextElement();
        var payload = {
            schemaVersion: SCHEMA_VERSION,
            businessText: input ? input.value : '',
            lastAnalyzedText: S.lastAnalyzedText,
            lastAnalysisProvider: S.lastAnalysisProvider,
            analysisOperationId: S.analysisOperationId || null,
            analysisProviderId: S.analysisProviderId || null,
            lastAnalysisStatus: S.lastAnalysisStatus,
            analysisDurationMillis: S.lastAnalysisDurationMillis,
            analysisCoverage: S.analysisCoverage || null,
            analysisScope: S.lastAnalysisScope || null,
            analysisReasonLength: S.lastAnalysisReasonLength || null,
            analysisLanguage: S.lastAnalysisLanguage || null,
            activeAnalysisLanguage: S.activeAnalysisLanguage || null,
            analysisRecovery: S.analysisRecovery || null,
            recoveryContext: S.recoveryContext || null,
            copilot: S.currentCopilot || null,
            storedBusinessText: S.storedBusinessText,
            scores: S.currentScores,
            rawScores: S.currentRawScores || {},
            effectiveScores: S.currentEffectiveScores || S.currentScores || {},
            scoreDetails: S.currentScoreDetails || {},
            productSuitabilityScores: S.currentProductSuitabilityScores || {},
            scoreSemanticsVersion: S.scoreSemanticsVersion || 0,
            scoreSemanticsWarnings: S.currentScoreSemanticsWarnings || [],
            reasons: S.currentReasons || {},
            discrepancies: S.currentDiscrepancies || [],
            productCoverageGaps: S.currentProductCoverageGaps || [],
            architectureView: S.currentArchView,
            relationSearchReport: S.currentRelationSearchReport || null,
            provisionalRelations: Array.isArray(window._currentProvisionalRelations)
                ? window._currentProvisionalRelations : [],
            evaluatedNodes: S.evaluatedNodes instanceof Set
                ? Array.from(S.evaluatedNodes) : [],
            currentView: S.currentView || 'list',
            savedAt: new Date().toISOString()
        };
        payload.draftState = meaningful(payload) ? 'ACTIVE' : 'EMPTY';
        if (!fullEvidence && matchesAnalysisReference()) {
            evidenceFields.concat(['analysisRecovery', 'recoveryContext', 'storedBusinessText']).forEach(function (key) {
                // These options are editable presentation metadata, rather than copied evidence.
                if (!['analysisScope', 'analysisReasonLength', 'analysisLanguage'].includes(key)) delete payload[key];
            });
            payload.analysisReference = Object.assign({}, runtime.analysisReference);
            if (S.recoveryContext?.nodeEvidenceBaseline) payload.nodeEvidenceBaseline = S.recoveryContext.nodeEvidenceBaseline;
        }
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
            || payload.analysisReference
            || (payload.scores && Object.keys(payload.scores).length > 0)
            || payload.relationSearchReport
            || payload.architectureView
            || (payload.discrepancies && payload.discrepancies.length > 0)
            || (payload.productCoverageGaps && payload.productCoverageGaps.length > 0)
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
        var decisionMarker = area.dataset.analysisSessionMessage;
        var keepDecision = runtime.draftDecisionPending
            && (decisionMarker === 'resume-choice' || decisionMarker === 'conflict')
            && marker !== 'resume-choice' && marker !== 'conflict';
        if (keepDecision) {
            // Progress/errors from other lifecycle commands must not strand an
            // unresolved draft. Keep its controls and only the newest feedback.
            var previousFeedback = area.querySelector('[data-analysis-session-feedback]');
            if (previousFeedback) previousFeedback.remove();
        } else {
            area.replaceChildren();
            area.dataset.analysisSessionMessage = marker || '';
        }

        var alert = document.createElement('div');
        if (keepDecision) alert.dataset.analysisSessionFeedback = 'true';
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
        if (window.TaxonomyBrowse && window.TaxonomyBrowse.rememberStatusFeedback) {
            window.TaxonomyBrowse.rememberStatusFeedback();
        }
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
        retainAnalysisReference: retainAnalysisReference,
        matchesAnalysisReference: matchesAnalysisReference,
        canHydrateAnalysisResult: canHydrateAnalysisResult,
        currentAnalysisEvidence: function () { return evidencePayload(currentPayload(true)); },
        resolveAnalysisReference: resolveAnalysisReference,
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
