/* Durable question recovery and viewport-visible Copilot feedback; no provider retry loop. */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C || !window.TaxonomyRecoveryViewport) throw new Error('Recovery requires session and viewport modules');
    var S = C.S, busy = false, decisionBusy = false, poll = null, reading = false, liveWork = '';
    var generation = C.runtime.analysisGeneration || 0;
    var STORAGE = 'taxonomy.recovery.active.v1';
    function text(de, en) { return C.language() === 'de' ? de : en; }
    var ui = window.TaxonomyRecoveryViewport.mount({ text: text, open: open, cancel: cancel,
        scopeKey: function () { var c = current(); return c ? c.workspaceId + '/' + c.id : ''; } });
    function input() { return (document.getElementById('businessText')?.value || '').trim(); }
    function current() { return S.recoveryContext; }
    function inScope(context) {
        return Boolean(context && current() === context && context.workspaceId === C.runtime.workspaceId
            && context.request.businessText === input()
            && generation === (C.runtime.analysisGeneration || 0) && !C.runtime.invalidating);
    }
    // Presentation compatibility only. The server independently loads and authorizes the durable evidence.
    function evidenceChecksum(value) {
        // Keep persisted baselines bounded even for long explanations. These independent
        // integer checksums are presentation metadata, never server evidence or authorization.
        var hashes = [0x811c9dc5, 0x9e3779b9, 0x85ebca6b, 0xc2b2ae35];
        var primes = [0x01000193, 0x27d4eb2f, 0x165667b1, 0x5bd1e995];
        for (var at = 0; at < value.length; at++) {
            var code = value.charCodeAt(at);
            for (var lane = 0; lane < hashes.length; lane++) hashes[lane] = Math.imul(hashes[lane] ^ code, primes[lane]);
        }
        return 'v1:' + value.length + ':' + hashes.map(function (hash) {
            return (hash >>> 0).toString(16).padStart(8, '0');
        }).join('');
    }
    function nodeEvidence(raw, effective, displayed, reasons) {
        return evidenceChecksum(JSON.stringify([raw || {}, effective || {}, displayed || {}, reasons || {}].map(function (values) {
            return Object.keys(values).sort().map(function (key) { return [key, values[key]]; });
        })));
    }
    function resultNodeEvidence(result) {
        var effective = result.effectiveScores || result.scores || {};
        return nodeEvidence(result.rawScores || result.scores, effective, result.scores || effective, result.reasons);
    }
    function currentNodeEvidence() {
        var effective = S.currentEffectiveScores || S.currentScores || {};
        return nodeEvidence(S.currentRawScores, effective, S.currentScores || effective, S.currentReasons);
    }
    function evidenceScope(context) {
        var request = context.request, scope = request.analysisScope;
        return evidenceChecksum(JSON.stringify([context.id, context.workspaceId, request.businessText,
            scope?.mode || null, (scope?.taxonomyRoots || []).slice().sort()]));
    }
    function acceptedNodeEvidence(context) {
        var baseline = context?.nodeEvidenceBaseline;
        return baseline && baseline.scope === evidenceScope(context) && Number.isSafeInteger(baseline.version)
            && typeof baseline.evidence === 'string' ? baseline : null;
    }
    function rememberNodeEvidence(result, context) {
        if (result.recovery?.id !== context.id || !Number.isSafeInteger(result.recovery?.version)) return;
        // recoveryContext is already saved/restored by the versioned draft lifecycle.
        // Retain the last accepted server evidence separately from editable score/reason maps.
        context.nodeEvidenceBaseline = { scope: evidenceScope(context), version: result.recovery.version,
            evidence: resultNodeEvidence(result) };
    }
    function nodeEvidenceMatches(context) {
        var baseline = acceptedNodeEvidence(context);
        return Boolean(baseline && S.analysisRecovery?.id === context.id && baseline.version === S.analysisRecovery.version
            && baseline.evidence === currentNodeEvidence());
    }
    function stopped() { return S.analysisRecovery?.state === 'CANCELLED' || current()?.followupState === 'CANCELLED'; }
    function statusKnown() { return !current()?.syncState && !current()?.observationError; }
    function decisionsAvailable() {
        return !busy && !decisionBusy && statusKnown() && reading?.context !== current()
            && ['PAUSED', 'STOPPED', 'COMPLETED_WITH_GAPS'].includes(S.analysisRecovery?.state);
    }
    function errorStatus(error) { return Number(error.httpStatus || error.status) || 0; }
    function connectionFailure(error) {
        return ['NETWORK_ERROR', 'TIMEOUT'].includes(error.code)
            || !errorStatus(error) && /failed to fetch|network request failed|networkerror|load failed/i.test(error.message || '');
    }
    function recoveryGuidance(context) {
        if (context.syncState === 'CHECKING') return text(
            'Der aktuelle Laufstatus wird geladen. Es wird keine weitere KI-Anfrage gesendet.',
            'Loading the current run status. No additional model request is being sent.');
        if (context.syncState === 'UNAVAILABLE') {
            if ([401, 403].includes(context.observationStatus)) return text(
                'Der Laufstatus kann mit den aktuellen Zugriffsrechten nicht geladen werden. Prüfe den Zugang zu diesem Arbeitsbereich. Weitere Bewertungen bleiben bis zur Statusklärung gesperrt.',
                'The run status cannot be loaded with the current access rights. Check access to this workspace. Further assessments remain unavailable until the status is confirmed.');
            if (context.observationKind !== 'CONNECTION') return text(
                'Die Statusantwort konnte nicht verarbeitet werden. Der zuletzt geladene Ergebnisstand bleibt sichtbar. Lade den Status erneut; weitere Bewertungen bleiben bis zur Statusklärung gesperrt. Die technischen Angaben enthalten den Fehler.',
                'The status response could not be processed. The last loaded result remains visible. Load the status again; further assessments remain unavailable until it is confirmed. Technical details contain the error.');
            return text(
                'Die Verbindung zur Anwendung ist gestört; der aktuelle Laufstatus ist unbekannt. Der Server könnte noch weiterarbeiten. Der zuletzt geladene Ergebnisstand bleibt sichtbar. Prüfe zuerst die Verbindung und lade den Status, bevor du weitere Bewertungen anforderst.',
                'The connection to the application is interrupted; the current run status is unknown. The server may still be working. The last loaded result remains visible. Check the connection and load the status before requesting further assessments.');
        }
        if (context.recoveryEvent === 'CONFLICT') return text(
            'Die letzte Aktion wurde wegen eines Konflikts mit dem gespeicherten Lauf abgewiesen. Wir haben den aktuellen Stand geladen. Prüfe die aktualisierten Ergebnisse und offenen Bewertungen und wähle dann die passende Aktion. Die Aktion wurde nicht automatisch wiederholt.',
            'The last action was rejected because it conflicted with the saved run. We have loaded the current state. Review the updated results and open assessments before choosing the next action. The action was not repeated automatically.');
        if (context.recoveryEvent === 'UNCONFIRMED' || context.recoveryEvent === 'RECONNECTED') return text(
            'Der aktuelle Stand wurde vom Server geladen. Nach der Verbindungsunterbrechung wurde keine KI-Anfrage automatisch wiederholt. Richte die nächste Aktion nach dem angezeigten Laufstatus aus.',
            'The current state has been loaded from the server. No model request was repeated automatically after the connection interruption. Choose the next action according to the displayed run status.');
        if (context.recoveryEvent === 'REJECTED') return text(
            'Die letzte Aktion wurde vom Server abgewiesen. Der gespeicherte Laufstatus wurde neu geladen. Die technischen Angaben enthalten den Ablehnungsgrund.',
            'The server rejected the last action. The saved run status has been reloaded. Technical details contain the rejection reason.');
        if (S.analysisRecovery?.state === 'RUNNING') return text(
            'Die Analyse wird auf dem Server bearbeitet. Du kannst den Status aktualisieren oder den Lauf abbrechen.',
            'The analysis is being processed on the server. You can refresh its status or cancel the run.');
        return '';
    }
    function setBusy(value) {
        busy = value;
        if (!value) liveWork = '';
        ['copilotBtn', 'analyzeBtn'].forEach(function (id) {
            var b = document.getElementById(id);
            if (b) { b.disabled = value; b.setAttribute('aria-busy', String(value)); }
        });
        var spinner = document.getElementById('copilotSpinner');
        if (spinner) spinner.classList.toggle('d-none', !value);
    }
    function remember() {
        try {
            var c = current();
            if (c) sessionStorage.setItem(STORAGE, JSON.stringify({ id: c.id, workspaceId: c.workspaceId }));
            else sessionStorage.removeItem(STORAGE);
        } catch (ignored) { /* The durable server journal remains the authority. */ }
    }
    async function save(required) {
        remember();
        var session = window.TaxonomyAnalysisSession;
        if (session?.state?.().ready && session.saveNow) {
            try { if (await session.saveNow() === true) return true; }
            catch (ignored) { /* The draft UI retains the underlying persistence error. */ }
        }
        if (required) throw new Error(text(
            'Der abgeschlossene Folgeschritt konnte nicht gespeichert werden. Wiederholen versucht zuerst das Speichern, nicht die bereits erfolgreiche Abfrage.',
            'The completed follow-up could not be saved. Retry first persists its result, without repeating the successful request.'));
        return false;
    }
    function partialResultMessage() {
        if (!S.currentArchView) return text(
            'Teilergebnis verfügbar. Für diesen Ergebnisstand liegt noch keine Architekturansicht vor. Offene Bewertungen bleiben erhalten.',
            'Partial result available. No architecture view is retained for this result yet. Unassessed areas remain open.');
        if (S.currentArchView && Array.isArray(S.currentArchView.includedElements) && !S.currentArchView.includedElements.length) {
            return text('Noch keine bewertete Architektur verfügbar. Auftrag und offene Prüfungen bleiben erhalten.',
                'No assessed architecture is available yet. The requirement and open assessments are retained.');
        }
        return text(
            'Architektur-Teilergebnis verfügbar. Offene Bereiche sind nicht negativ bewertet. Globale Lücken-, Muster- und Empfehlungsaussagen bleiben bis zur Nachbewertung offen.',
            'Partial architecture available. Open areas are not negative assessments. Global gap, pattern and recommendation conclusions remain open until assessment completes.');
    }
    async function request(context, suffix, method) {
        var response = await window.TaxonomyAnalysisSessionApi.request(
            '/api/analysis-continuations/' + encodeURIComponent(context.id) + suffix,
            { method: method || 'GET', cache: 'no-store' }, context.workspaceId);
        if (!response.ok) {
            var problem = await response.json().catch(function () { return {}; });
            var error = new Error(String(problem.detail || problem.message || problem.error || 'HTTP ' + response.status));
            error.status = response.status; throw error;
        }
        return response.json();
    }
    function message() {
        var r = S.analysisRecovery;
        if (!r) return '';
        var label = {
            RUNNING: text('Bewertung läuft', 'Assessment running'),
            PAUSED: text('Pausiert – Prüfung erforderlich', 'Paused – review required'),
            COMPLETED: text('Bewertung abgeschlossen', 'Assessment complete'),
            COMPLETED_WITH_GAPS: text('Teilergebnis – offene Bewertungsbereiche', 'Partial result – unassessed areas'),
            CANCELLED: text('Abgebrochen – gültige Ergebnisse erhalten', 'Cancelled – valid results retained'),
            STOPPED: text('Unterbrochen – gespeicherte Antworten erhalten', 'Interrupted – saved answers retained')
        }[r.state] || r.state;
        var c = current();
        if (c?.followupState === 'RUNNING') label = c.followupLabel;
        if (c?.followupState === 'PAUSED') label = text('Copilot-Folgeschritt fehlgeschlagen', 'Copilot follow-up failed');
        if (c?.followupState === 'CANCELLED') label = text('Abgebrochen – gültige Ergebnisse erhalten', 'Cancelled – valid results retained');
        if (c?.followupState === 'COMPLETED') label = text('Copilot abgeschlossen', 'Copilot complete');
        if (c?.followupState === 'COMPLETED_SCOPED') label = text('Gewählte Analyse abgeschlossen', 'Selected analysis complete');
        if (c?.followupState === 'COMPLETED_WITH_GAPS') label = text('Copilot beendet – Teilergebnis', 'Copilot finished – partial result');
        if (c?.syncState === 'CHECKING') label = text('Aktuellen Laufstatus laden', 'Loading current run status');
        if (c?.syncState === 'UNAVAILABLE') label = c.observationKind === 'CONNECTION'
            ? text('Verbindung gestört – Status unbekannt', 'Connection interrupted – status unknown')
            : text('Status unbekannt – erneute Prüfung erforderlich', 'Run status unknown – check again');
        var openCount = (r.openQuestions || []).length;
        var relations = S.currentRelationSearchReport?.progress?.verifiedRelations;
        var detail = (openCount ? ' · ' + openCount + text(' Prüfungen offen', ' assessments open') : '')
            + (Number.isFinite(relations) ? ' · ' + relations + text(' Beziehungen bestätigt', ' relations verified') : '');
        if (busy && liveWork && statusKnown()) return liveWork + detail;
        return label + ' · ' + (r.completedCalls || 0) + (c?.syncState
            ? text(' Abfragen zuletzt bestätigt', ' requests last confirmed') : text(' Abfragen abgeschlossen', ' requests complete')) + detail
            + (r.currentNode && r.state === 'RUNNING' && statusKnown() ? ' · ' + r.currentNode : '');
    }
    function update() {
        var r = S.analysisRecovery;
        ui.update(message(), busy, Boolean(r && (['RUNNING', 'PAUSED', 'STOPPED'].includes(r.state)
            || current()?.followupState === 'RUNNING' || current()?.followupState === 'PAUSED')));
        renderCoverage();
        if (typeof document.dispatchEvent === 'function') document.dispatchEvent(new CustomEvent('taxonomy:analysis-recovery-updated'));
    }
    function recordedPauseReason(result) {
        if (!['PAUSED', 'STOPPED'].includes(result.recovery?.state)) return null;
        var report = Object.prototype.hasOwnProperty.call(result, 'relationSearchReport')
            ? result.relationSearchReport : result.architectureView?.relationSearchReport;
        // Budget exhaustion can be recorded only in the relation report, while
        // errorMessage contains a partial-result summary or is absent entirely.
        var budget = /^(CALL_BUDGET|SOURCE_LIMIT|WORK_LIMIT)(:|$)/;
        var summary = result.errorMessage?.startsWith('RELATION_SEARCH_PARTIAL:');
        var reasons = [report?.stopReason, summary ? null : result.errorMessage]
            .concat((report?.warnings || []).filter(function (warning) { return budget.test(warning); }))
            .concat((report?.result?.unfinished || []).map(function (work) { return work.reason; })
                .filter(function (reason) { return budget.test(reason); }));
        return Array.from(new Set(reasons.filter(function (reason) {
            return typeof reason === 'string' && reason.trim();
        }))).join('\n') || null;
    }
    function hydrate(result, context) {
        if (!result || !inScope(context)) return;
        var baseline = acceptedNodeEvidence(context);
        var completedSource = context.request.analysisScope?.mode === 'TAXONOMIES_ONLY'
            && ['COMPLETED', 'COMPLETED_WITH_GAPS'].includes(result.recovery?.state);
        if (completedSource && S.lastAnalyzedText === context.request.businessText
                && (baseline ? baseline.evidence !== currentNodeEvidence() : currentNodeEvidence() !== resultNodeEvidence(result))) {
            // Refreshing a journal must not erase manual edits or independent assessment additions.
            // Legacy drafts without a baseline can only establish one from an exact evidence match.
            return;
        }
        rememberNodeEvidence(result, context);
        S.analysisRecovery = result.recovery || S.analysisRecovery;
        context.pauseReason = recordedPauseReason(result);
        S.analysisCoverage = result.analysisCoverage || null;
        S.lastAnalysisScope = result.analysisScope || context.request.analysisScope || null;
        S.lastAnalysisReasonLength = result.analysisReasonLength || null;
        window.TaxonomyAnalysisScope?.acceptResult?.(S.lastAnalysisScope);
        if (Array.isArray(result.tree) && result.tree.length) S.taxonomyData = result.tree;
        S.currentRawScores = result.rawScores || {};
        S.currentEffectiveScores = result.effectiveScores || result.scores || {};
        S.currentScores = S.currentEffectiveScores;
        S.currentScoreDetails = result.scoreDetails || {};
        S.currentProductSuitabilityScores = result.productSuitabilityScores || {};
        S.scoreSemanticsVersion = result.scoreSemanticsVersion || 0;
        S.currentScoreSemanticsWarnings = result.scoreSemanticsWarnings || [];
        S.currentReasons = result.reasons || {};
        S.currentDiscrepancies = result.discrepancies || [];
        S.currentProductCoverageGaps = result.productCoverageGaps || [];
        // A temporary response without a view does not erase the accepted same-scope
        // architecture. New requirements/workspaces still go through their normal reset.
        var sameEvidenceScope = S.lastAnalyzedText === context.request.businessText;
        S.currentArchView = result.architectureView || (sameEvidenceScope ? S.currentArchView : null);
        var hasRelationEvidence = Object.prototype.hasOwnProperty.call(result, 'relationSearchReport')
            || (result.architectureView && Object.prototype.hasOwnProperty.call(
                result.architectureView, 'relationSearchReport'));
        if (hasRelationEvidence || !sameEvidenceScope) {
            window.TaxonomyScoring?.acceptRelationSearchReport?.(result);
        }
        S.lastAnalyzedText = context.request.businessText;
        S.storedBusinessText = context.request.businessText;
        S.lastAnalysisProvider = result.provider || context.request.provider;
        S.lastAnalysisStatus = result.status || 'UNKNOWN';
        S.lastAnalysisDurationMillis = result.analysisDurationMillis ?? null;
        window._taxonomyCurrentScores = S.currentScores;
        window._currentProvisionalRelations = result.provisionalRelations || [];
        window.TaxonomyBrowse?.renderView(S.taxonomyData, S.currentScores);
        window.TaxonomyScoring?.renderArchitectureView(S.currentArchView);
        window.TaxonomyScoring?.renderSuggestedRelations(result.provisionalRelations || []);
    }
    function refresh(options) {
        options = options || {};
        var c = current();
        if (!inScope(c)) return Promise.resolve(false);
        if (reading?.context === c) {
            // A read sent before a rejected/lost write cannot confirm its outcome.
            return options.afterDecision ? reading.promise.then(function () {
                return inScope(c) ? refresh(Object.assign({}, options, { afterDecision: false })) : false;
            }) : reading.promise;
        }
        var observation = { context: c, version: S.analysisRecovery?.version, installedVersion: null };
        var wasOpen = Boolean(ui.isOpen?.()), recovering = Boolean(c.syncState || c.observationError);
        var reopen = wasOpen || Boolean(options.afterDecision);
        reading = observation;
        if (recovering || options.afterDecision || wasOpen && !options.background) {
            c.syncState = 'CHECKING'; update();
            if (wasOpen) open();
        }
        observation.promise = Promise.resolve().then(async function () {
            try {
                var snapshot = await request(c, '');
                if (!inScope(c)) return false;
                if (snapshot.recovery?.id !== c.id || !Number.isSafeInteger(snapshot.recovery?.version)
                        || snapshot.request?.businessText !== c.request.businessText) throw new Error(text(
                    'Keine bestätigte Statusantwort für diese Analyse.', 'No confirmed status response for this analysis.'));
                if (snapshot.recovery.version < (S.analysisRecovery?.version || 0)) {
                    // A completed write may have overtaken this older observation.
                    if ((S.analysisRecovery?.version || 0) > observation.version) return false;
                    throw new Error(text('Die Statusantwort ist veraltet. Lade den aktuellen Stand erneut.',
                        'The status response is outdated. Load the current state again.'));
                }
                var previousState = S.analysisRecovery?.state, previousVersion = S.analysisRecovery?.version;
                observation.installedVersion = snapshot.recovery.version;
                c.confirmed = true; c.syncState = null;
                if (recovering && !c.recoveryEvent) c.recoveryEvent = 'RECONNECTED';
                S.analysisRecovery = snapshot.recovery;
                if (snapshot.clusterOperationId) { c.distributed = true; c.operationId = snapshot.clusterOperationId; }
                c.observationError = null; c.observationStatus = null; c.observationKind = null;
                // A running new attempt can still carry an older paused result.
                if (!busy && snapshot.recovery.state !== 'RUNNING') hydrate(snapshot.result, c);
                c.request = snapshot.request;
                if (snapshot.request.relationSourceContinuationId) c.noFollowups = true;
                if (options.background && previousState === snapshot.recovery.state && previousVersion === snapshot.recovery.version && !recovering)
                    reopen = false; // Progress heartbeats do not rebuild an open dialog.
                if (!busy && snapshot.recovery.state !== 'RUNNING') {
                    await save();
                    if (snapshot.recovery.state === 'PAUSED' && !ui.isDismissed?.()) reopen = true;
                }
                return true;
            } catch (error) {
                if (!inScope(c) || (S.analysisRecovery?.version || 0) > (observation.installedVersion ?? observation.version)) return false;
                c.syncState = 'UNAVAILABLE'; c.observationError = error.message;
                c.observationStatus = errorStatus(error); c.observationKind = connectionFailure(error) ? 'CONNECTION' : 'STATUS';
                return false;
            } finally {
                if (reading === observation) reading = false;
                if (inScope(c)) {
                    update();
                    if (reopen && !busy) open();
                    if (S.analysisRecovery?.state === 'RUNNING' && statusKnown()) scheduleRead();
                }
            }
        });
        return observation.promise;
    }
    function scheduleRead() {
        if (current()?.distributed || !statusKnown()) return; // Reconnect is a read-only explicit action.
        clearTimeout(poll);
        poll = setTimeout(function () { poll = null; refresh({ background: true }); }, 1200);
    }
    // Capture presentation before scoring touches lastAnalyzedText or hides its panels.
    // Only the same continuation may retain this view; do not attach it to new evidence.
    function retainArchitecture(context, action) {
        var view = action !== 'START' && context.request.includeArchitectureView !== false
            && S.lastAnalyzedText === context.request.businessText ? S.currentArchView : null;
        var fields = view ? ['architectureViewPanel', 'architecturePlaceholder'].map(function (id) {
            var element = document.getElementById(id);
            return element ? { id: id, element: element, display: element.style.display } : null;
        }).filter(Boolean) : [];
        return function () {
            if (!view || !inScope(context) || S.currentArchView !== view) return;
            fields.forEach(function (field) {
                if (document.getElementById(field.id) === field.element) field.element.style.display = field.display;
            });
        };
    }
    async function recoverDecision(context, error) {
        context.rejectionStatus = errorStatus(error) || null; context.rejectionMessage = error.message;
        context.decisionDiagnostic = error.message;
        context.recoveryEvent = context.rejectionStatus === 409 ? 'CONFLICT' : context.rejectionStatus ? 'REJECTED' : 'UNCONFIRMED';
        context.syncState = 'CHECKING'; update(); open();
        // Confirm the same identity without resubmitting the decision.
        await refresh({ afterDecision: true });
        if (inScope(context)) open();
    }
    async function run(action, question, newOperation) {
        var c = current();
        if (!inScope(c) || busy || decisionBusy || stopped()) return;
        if (action !== 'START' && (!decisionsAvailable() || action === 'CONTINUE' && S.analysisRecovery?.state !== 'STOPPED')) {
            open(); return;
        }
        if (!window.TaxonomyAnalysisSession?.state?.().ready) {
            ui.error(text('Arbeitsbereich ist noch nicht bereit.', 'Workspace is not ready.')); return;
        }
        var body = Object.assign({}, c.request, { resumable: true, continuationId: c.id,
            continuationAction: action || 'START', continuationVersion: S.analysisRecovery?.version,
            continuationQuestion: Array.isArray(question) ? (question.length === 1 ? question[0] : null) : question || null,
            continuationQuestions: Array.isArray(question) && question.length > 1 ? question.slice() : [] });
        var restoreArchitecture = retainArchitecture(c, body.continuationAction);
        ui.close(); setBusy(true); c.followupState = null; c.followups = {};
        c.recoveryEvent = null; c.decisionDiagnostic = null;
        S.analysisRecovery = Object.assign({}, S.analysisRecovery, { state: 'RUNNING' });
        update(); remember(); scheduleRead();
        try {
            var promise = window.TaxonomyScoring.runAnalysis({ continuation: body, recoveryManaged: true,
                newOperation: newOperation === true });
            restoreArchitecture();
            if (!promise || typeof promise.then !== 'function') throw new Error(text(
                'Die Analyse wurde von der Vorprüfung abgewiesen.', 'Analysis preflight rejected the start.'));
            var result = await promise;
            if (!inScope(c)) return;
            restoreArchitecture();
            if (!result?.recovery) throw new Error(text('Keine bestätigte Fortsetzungsantwort.', 'No confirmed continuation response.'));
            S.analysisRecovery = result.recovery; S.analysisCoverage = result.analysisCoverage;
            rememberNodeEvidence(result, c);
            S.lastAnalysisStatus = result.status || 'UNKNOWN';
            c.confirmed = true; c.syncState = null; c.recoveryEvent = null; c.decisionDiagnostic = null;
            c.observationError = null; c.observationStatus = null;
            c.pauseReason = recordedPauseReason(result);
            setBusy(false); update(); await save();
            if (['PAUSED', 'STOPPED'].includes(result.recovery.state)) open();
            else if (result.recovery.state === 'COMPLETED' || result.recovery.state === 'COMPLETED_WITH_GAPS') {
                if (!c.noFollowups) await followups();
            }
            return result;
        } catch (error) {
            if (!inScope(c)) return;
            setBusy(false); await recoverDecision(c, error);
        } finally {
            clearTimeout(poll); poll = null;
            if (inScope(c)) { restoreArchitecture(); setBusy(false); update(); if (S.analysisRecovery?.state === 'RUNNING') scheduleRead(); }
        }
    }
    async function followups() {
        var c = current();
        if (!inScope(c) || busy || stopped() || !statusKnown()) return;
        if ((S.analysisCoverage?.failedOrBlockedNodes || 0) > 0 || S.lastAnalysisStatus === 'PARTIAL'
                || S.analysisRecovery?.state === 'COMPLETED_WITH_GAPS' || S.analysisRecovery?.openQuestions?.length) {
            // Existing gap/recommendation algorithms infer absence from missing scores. They
            // cannot safely make global claims with missing inputs; keep the useful architecture.
            c.followupState = 'COMPLETED_WITH_GAPS';
            window.TaxonomyAnalysis.renderPartialCopilot?.(partialResultMessage());
            update(); await save(); return;
        }
        if (window.TaxonomyAnalysisScope?.restrictsGlobalAnalysis(S.lastAnalysisScope || c.request.analysisScope)) {
            c.followupState = 'COMPLETED_SCOPED';
            window.TaxonomyAnalysis.renderPartialCopilot?.(text(
                'Der gewählte Analyseumfang ist abgeschlossen. Globale Lücken, Muster und Empfehlungen benötigen eine vollständige Analyse mit Relationssuche.',
                'The selected analysis scope is complete. Global gaps, patterns and recommendations require a complete analysis with relation search.'));
            update(); await save(); return;
        }
        ui.close(); setBusy(true); c.followupState = 'RUNNING'; update();
        try {
            await window.TaxonomyAnalysis.runCopilotFlow();
            if (inScope(c) && !stopped()) c.followupState = 'COMPLETED';
        } catch (error) {
            if (inScope(c) && !stopped()) { c.followupState = 'PAUSED'; c.followupError = error.message; }
        } finally {
            if (inScope(c)) { setBusy(false); update(); await save(); if (c.followupState === 'PAUSED') open(); }
        }
    }
    async function stage(key, operation, label) {
        var c = current();
        if (!c) return operation(); // Compatibility path for explicitly entered manual scores.
        if (!inScope(c)) throw new Error(text('Laufkontext geändert.', 'Operation context changed.'));
        if (!statusKnown()) throw new Error(text('Lade zuerst den aktuellen Laufstatus.', 'Load the current run status first.'));
        if (stopped()) throw new Error(text('Lauf abgebrochen.', 'Run cancelled.'));
        c.followups = c.followups || {};
        var saved = c.followups[key];
        c.followupLabel = label || key; update();
        if (saved?.state !== 'SUCCESS') {
            var result = await operation();
            if (!inScope(c) || stopped()) throw new Error(text('Laufkontext geändert.', 'Operation context changed.'));
            // Retain successful work even if saving fails. Explicit retry saves it again;
            // dependent stages are not admitted until the checkpoint is confirmed durable.
            saved = c.followups[key] = { state: 'SUCCESS', result: result };
        }
        await save(true);
        if (!inScope(c) || stopped()) throw new Error(text('Laufkontext geändert.', 'Operation context changed.'));
        return saved.result;
    }
    async function cancel() {
        var c = current();
        if (!inScope(c) || decisionBusy) return;
        decisionBusy = true;
        try {
            var snapshot = await request(c, '/cancel', 'POST');
            if (!inScope(c)) return;
            S.analysisRecovery = snapshot.recovery;
            c.confirmed = true; c.syncState = null; c.recoveryEvent = null; c.decisionDiagnostic = null;
            c.observationError = null; c.observationStatus = null; c.observationKind = null;
            c.followupState = 'CANCELLED';
            if (!busy && snapshot.result) hydrate(Object.assign({}, snapshot.result, { recovery: snapshot.recovery,
                status: snapshot.recovery.state === 'CANCELLED' ? 'CANCELLED' : snapshot.result.status }), c);
            clearTimeout(poll); poll = null;
            ui.close(); update(); await save();
        } catch (error) { if (inScope(c)) await recoverDecision(c, error); }
        finally {
            decisionBusy = false;
            if (inScope(c) && !busy && ui.isOpen?.()) open();
        }
    }
    function open() {
        var c = current(), r = S.analysisRecovery;
        if (!inScope(c) || !r) return;
        var options = [];
        var questions = r.openQuestions || [];
        var selected = questions.find(function (q) { return !q.skipped; }) || questions[0];
        var selectedKeys = new Set(selected ? [selected.key] : []);
        // Resolve presentation names only from the retained snapshot, never from a newer catalogue.
        var names = new Map(), queue = Array.isArray(S.taxonomyData) ? S.taxonomyData.slice() : [], seen = new Set();
        for (var at = 0; at < queue.length && at < 100000; at++) {
            var node = queue[at]; if (!node || seen.has(node)) continue; seen.add(node);
            names.set(node.code, C.language() === 'de' ? node.nameDe || node.nameEn : node.nameEn || node.nameDe);
            if (Array.isArray(node.children) && queue.length + node.children.length <= 100000) queue.push.apply(queue, node.children);
        }
        function nodeLabel(q) { return (q.nodes || []).map(function (code) {
            var name = names.get(code); return name && name !== code ? name + ' (' + code + ')' : code;
        }).join(', '); }
        function explanation(q) {
            if (!q) return '';
            if (q.error?.startsWith('RELATION_UNRESOLVED:')) return q.error.substring('RELATION_UNRESOLVED:'.length).trim();
            if (q.outcomeUncertain) return text('Der Ausgang dieser Anfrage ist unklar. Eine Wiederholung benötigt Ihre Entscheidung.',
                'The outcome of this request is uncertain. Repeating it requires your decision.');
            return text('Diese Bewertung konnte nicht abgeschlossen werden. Andere verwertbare Ergebnisse bleiben erhalten.',
                'This assessment could not be completed. Other usable results are retained.');
        }
        var version = r.version;
        function decide(action, keys) {
            if (!inScope(c)) return;
            if (S.analysisRecovery?.version !== version || !decisionsAvailable()) { open(); return; }
            return run(action, keys);
        }
        var ready = decisionsAvailable();
        var refreshOption = { id: 'analysisRecoveryRefresh', shortLabel: text('Status laden', 'Load status'),
            label: c.syncState === 'UNAVAILABLE' ? text('Verbindung prüfen und Status laden', 'Check connection and load status')
                : text('Status aktualisieren', 'Refresh status'),
            disabled: reading?.context === c, handler: function () { return refresh(); } };
        if (!statusKnown()) options.push(refreshOption);
        if (ready && r.state === 'STOPPED') {
            options.push({ id: 'analysisRecoveryContinue', shortLabel: text('Fortsetzen', 'Continue'),
                label: text('Gespeicherten Stand fortsetzen', 'Continue saved progress'), handler: function () { return decide('CONTINUE'); } });
        } else if (!busy && statusKnown() && r.state === 'COMPLETED' && !stopped()) {
            options.push({ id: 'analysisRecoveryRetryStage', label: text('Copilot fortsetzen', 'Continue Copilot'), handler: followups });
        }
        if (ready && !stopped() && selected) {
            options.push({ id: 'analysisRecoveryRetry', shortLabel: text('Neu bewerten', 'Reassess'), label: text('Neue KI-Bewertung anfordern', 'Request a new AI assessment'),
                handler: function () { if (selectedKeys.size) return decide('RETRY', Array.from(selectedKeys)); } });
            if (!selected.skipped) options.push({ id: 'analysisRecoverySkip', shortLabel: text('Offenlassen', 'Leave open'), label: text('Bereich offenlassen und fortfahren', 'Leave area unassessed and continue'),
                handler: function () { if (selectedKeys.size) return decide('SKIP', Array.from(selectedKeys)); } });
        }
        if (!stopped() && (['RUNNING', 'PAUSED', 'STOPPED'].includes(r.state) || ['RUNNING', 'PAUSED'].includes(c.followupState)))
            options.push({ id: 'analysisRecoveryCancelDialog', shortLabel: text('Abbrechen', 'Cancel'), label: text('Lauf abbrechen', 'Cancel run'), handler: cancel });
        if (statusKnown()) options.push(refreshOption);
        ui.open('Copilot', message(), function (body, el) {
            var guidance = recoveryGuidance(c);
            if (guidance) { var statusNote = el('p', guidance); statusNote.id = 'analysisRecoveryGuidance'; statusNote.setAttribute('role', 'status'); body.append(statusNote); }
            if (statusKnown() && ['PAUSED', 'STOPPED'].includes(r.state)) {
                // A semantic question is independent of why execution stopped.
                // Always retain the actual stop diagnostics above the selection.
                var reason = el('p', text('Grund der Unterbrechung: ', 'Reason for interruption: ')
                    + (c.pauseReason || text('Kein konkreter Unterbrechungsgrund gespeichert.',
                        'No specific interruption reason was recorded.')));
                reason.id = 'analysisRecoveryStopReason';
                reason.style.whiteSpace = 'pre-wrap';
                body.append(reason);
            }
            var note = el('p', explanation(selected));
            body.append(note);
            body.append(el('p', text('Gültige Bewertungen bleiben erhalten. Unbekannt bedeutet nicht „nicht relevant“.',
                'Valid assessments are retained. Unknown does not mean irrelevant.')));
            if (c.observationError || c.decisionDiagnostic) {
                var diagnostics = el('details'); diagnostics.id = 'analysisRecoveryTechnical';
                diagnostics.append(el('summary', text('Technische Angaben', 'Technical details')),
                    el('pre', Array.from(new Set([c.decisionDiagnostic, c.observationError].filter(Boolean))).join('\n')));
                body.append(diagnostics);
            }
            if (c.followupError) body.append(el('p', c.followupError));
            if (!selected) return;
            if (!stopped() && ready) body.append(el('p', text(
                'Neu bewerten sendet eine neue KI-Anfrage für die gewählte Bewertung und setzt die Analyse fort. Offenlassen setzt die weitere Analyse fort; der betroffene Bereich bleibt nicht bewertet.',
                'Reassess sends a new AI request for the selected assessment and continues the analysis. Leave open continues the remaining analysis; the affected area stays unassessed.')));
            var assessmentState = el('p'), attempts = el('p'), uncertain = el('p'), clarification = el('p');
            function updateSelected() {
                note.textContent = explanation(selected);
                if (technical) technical.textContent = selected.error || '';
                assessmentState.textContent = selected.skipped
                    ? text('Bewertung offengelassen – betroffener Bereich nicht bewertet.', 'Assessment left unassessed – affected area remains open.')
                    : selected.error?.startsWith('RELATION_UNRESOLVED')
                        ? text('Beziehungsbewertung erfordert Klärung.', 'Relation assessment needs clarification.')
                        : text('LLM-Bewertung fehlgeschlagen.', 'LLM assessment failed.');
                attempts.textContent = text('Versuche: ', 'Attempts: ') + selected.attempts;
                uncertain.textContent = selected.outcomeUncertain ? text(
                    'Der Anbieter könnte die letzte Anfrage bereits verarbeitet oder berechnet haben. Nach einer Worker-Unterbrechung ist eine explizite Entscheidung erforderlich: wiederholen oder offenlassen. Gespeicherte gültige Antworten werden ohne erneute Anbieteranfrage wiederverwendet.',
                    'The provider may already have processed or billed the last request. After a worker interruption, an explicit decision is required: retry or leave open. Saved validated answers are reused without another provider request.') : '';
                uncertain.hidden = !selected.outcomeUncertain;
                var semantic = selected.error?.startsWith('RELATION_UNRESOLVED:');
                clarification.textContent = semantic ? text(
                    'Die fachliche Zuordnung ist offen. Du kannst den Bereich offenlassen und die übrige Analyse fortsetzen oder die Anforderung ergänzen und neu analysieren. Eine erneute KI-Bewertung kann ohne zusätzliche Informationen zur gleichen Unsicherheit führen.',
                    'The semantic assignment remains open. You can leave this area unassessed and continue the remaining analysis, or refine the requirement and start a new analysis. Another model assessment without additional information may lead to the same uncertainty.') : '';
                clarification.hidden = !semantic;
            }
            if (questions.length > 1) {
                var label = el('label', text('Offene LLM-Bewertung – betroffene Knoten: ', 'Open LLM assessment – affected nodes: '));
                var select = el('select'); select.id = 'analysisRecoveryQuestion'; select.className = 'form-select';
                questions.forEach(function (q) { var o = el('option', nodeLabel(q)); o.value = q.key; select.append(o); });
                select.value = selected.key;
                select.addEventListener('change', function () {
                    selected = questions.find(function (q) { return q.key === select.value; });
                    if (selectedKeys.size <= 1) { selectedKeys.clear(); selectedKeys.add(selected.key); }
                    updateSelected(); updateSelection();
                    if (multiple?.open) renderSelectionPage();
                    details.open = false; detailBody.replaceChildren(); loadedKey = null;
                });
                label.append(select); body.append(label);
            }
            else body.append(el('p', text('Betroffene Knoten: ', 'Affected nodes: ') + nodeLabel(selected)));
            var selectionSummary = el('p'); selectionSummary.id = 'analysisRecoverySelectionCount';
            function updateSelection() {
                selectionSummary.textContent = selectedKeys.size + text(' Prüfungen für diese Aktion ausgewählt.', ' assessments selected for this action.');
                var retry = options.find(function (o) { return o.id === 'analysisRecoveryRetry'; });
                if (retry) {
                    retry.disabled = !selectedKeys.size;
                    retry.label = selectedKeys.size > 1 ? text('Ausgewählte Prüfungen wiederholen', 'Retry selected assessments')
                        : text('LLM-Anfrage wiederholen und fortsetzen', 'Retry LLM request and continue');
                    var button = document.getElementById(retry.id);
                    if (button) { button.disabled = retry.disabled; button.textContent = retry.label; button.setAttribute('aria-label', retry.label);
                        if (button.dataset) button.dataset.fullLabel = retry.label; }
                }
                var skip = options.find(function (o) { return o.id === 'analysisRecoverySkip'; });
                if (skip) {
                    skip.disabled = !selectedKeys.size || questions.some(function (q) { return selectedKeys.has(q.key) && q.skipped; });
                    var skipButton = document.getElementById(skip.id); if (skipButton) skipButton.disabled = skip.disabled;
                }
            }
            var multiple, selectionPage, pageNumber = 0;
            function renderSelectionPage() {
                selectionPage.replaceChildren();
                questions.slice(pageNumber * 50, (pageNumber + 1) * 50).forEach(function (q) {
                    var label = el('label'); label.className = 'd-block';
                    var check = el('input'); check.type = 'checkbox'; check.checked = selectedKeys.has(q.key);
                    check.addEventListener('change', function () {
                        if (check.checked) selectedKeys.add(q.key); else selectedKeys.delete(q.key); updateSelection();
                    });
                    label.append(check, el('span', ' ' + nodeLabel(q))); selectionPage.append(label);
                });
                if (questions.length > 50) {
                    selectionPage.append(el('p', (pageNumber * 50 + 1) + '–' + Math.min((pageNumber + 1) * 50, questions.length) + ' / ' + questions.length));
                    [['analysisRecoveryPreviousPage', -1, text('Vorherige Seite', 'Previous page')],
                     ['analysisRecoveryNextPage', 1, text('Nächste Seite', 'Next page')]].forEach(function (entry) {
                        var button = el('button', entry[2]); button.type = 'button'; button.id = entry[0]; button.className = 'btn btn-sm btn-outline-secondary';
                        button.disabled = entry[1] < 0 ? pageNumber === 0 : (pageNumber + 1) * 50 >= questions.length;
                        button.addEventListener('click', function () { if (!button.disabled) { pageNumber += entry[1]; renderSelectionPage(); } }); selectionPage.append(button);
                    });
                }
            }
            if (questions.length > 1 && !busy && !stopped()) {
                multiple = el('details'); multiple.id = 'analysisRecoveryMultiple';
                multiple.append(el('summary', text('Mehrere Prüfungen auswählen', 'Select multiple assessments')));
                selectionPage = el('div'); multiple.append(selectionSummary, selectionPage); body.append(multiple);
                multiple.addEventListener('toggle', function () { if (multiple.open) renderSelectionPage(); });
            }
            updateSelection(); updateSelected(); body.append(assessmentState, clarification, attempts, uncertain);
            var details = el('details'); details.append(el('summary', text('Prompt und Antwort anzeigen', 'Show prompt and response')),
                el('p', text('Die Auswahl bezeichnet eine interne LLM-Anfrage und ihre betroffenen Knoten. Prompt und Antwort enthalten die Bewertungsdetails.',
                    'The selection identifies an internal LLM request and its affected nodes. The prompt and response contain the assessment details.')));
            var technical = el('p', selected.error || '');
            var detailBody = el('div'); details.append(technical, detailBody); body.append(details); var loadedKey = null;
            details.addEventListener('toggle', async function () {
                if (!details.open || loadedKey === selected.key) return;
                var key = selected.key; loadedKey = key;
                try {
                    var evidence = await request(c, '/questions/' + encodeURIComponent(key));
                    if (!inScope(c) || key !== selected.key) return;
                    detailBody.replaceChildren(el('h3', 'Prompt'), el('pre', evidence.prompt || text('Nicht gespeichert.', 'Not retained.')),
                        el('h3', text('Antwort', 'Response')), el('pre', evidence.rawResponse || text('Keine Antwort erhalten.', 'No response received.')));
                } catch (error) { if (!inScope(c) || key !== selected.key) return; loadedKey = null; detailBody.textContent = error.message; }
            });
        }, options);
    }
    function renderCoverage() {
        var tree = document.getElementById('taxonomyTree');
        if (!tree) return;
        var coverage = S.analysisCoverage;
        var warning = document.getElementById('analysisCoverageWarning');
        var isOpen = Boolean(coverage?.failedOrBlockedNodes);
        if (!isOpen) {
            warning?.remove();
        } else {
            if (!warning) {
                warning = document.createElement('div'); warning.id = 'analysisCoverageWarning';
                warning.className = 'analysis-coverage-warning'; tree.parentNode.insertBefore(warning, tree);
            }
            var message = text('Teilergebnis: ', 'Partial result: ') + coverage.failedOrBlockedNodes
                + text(' Knoten wegen offener Bewertung ungeklärt. Die übrigen Bewertungen bleiben gültig.',
                    ' nodes unresolved because assessment is open. Other assessments remain valid.');
            if (warning.textContent !== message) warning.textContent = message;
        }
        // Action buttons also carry data-code. Only actual catalogue rows get a badge.
        // Unchanged observations must not tear down/reinsert reading content on every heartbeat.
        tree.querySelectorAll('.tax-node[data-code]').forEach(function (node) {
            var code = node.getAttribute('data-code'), assessment = isOpen ? coverage.nodes?.[code] : null;
            var label = !assessment ? '' : assessment.reason && /^(FAILED|LEFT_OPEN|BLOCKED_BY|INTERRUPTED):/.test(assessment.reason)
                ? text('Nicht bewertet', 'Unassessed') : assessment.descendants !== 'COMPLETE'
                    && assessment.state !== 'UNKNOWN' ? text('Unterbaum teilweise bewertet', 'Subtree partly assessed') : '';
            var row = node.querySelector(':scope > .tax-node-header') || node;
            var badge = row.querySelector(':scope > .analysis-coverage-node');
            var id = 'analysisCoverage-' + code;
            var described = (node.getAttribute('aria-describedby') || '').split(/\s+/).filter(function (value) { return value && value !== id; });
            if (!label) { badge?.remove(); }
            else {
                if (!badge) {
                    badge = document.createElement('span'); badge.id = id;
                    badge.className = 'analysis-coverage-node'; row.append(badge);
                }
                if (badge.textContent !== label) badge.textContent = label;
                described.push(id);
            }
            var next = described.join(' ');
            if ((node.getAttribute('aria-describedby') || '') !== next) {
                if (next) node.setAttribute('aria-describedby', next); else node.removeAttribute('aria-describedby');
            }
        });
    }
    function startAnalysis(manual) {
        if (busy || decisionBusy) return;
        if (inScope(current()) && (!statusKnown() || S.analysisRecovery?.state === 'RUNNING')) { open(); return; }
        if (!manual && inScope(current()) && S.analysisRecovery) { open(); return; }
        if (!input()) { window.TaxonomyBrowse?.showStatus('warning', text('Bitte eine Anforderung eingeben.', 'Enter a requirement.')); return; }
        var ready = window.TaxonomyAnalysisSession?.state?.();
        if (!ready?.ready || !ready.workspaceId || !crypto?.randomUUID) {
            window.TaxonomyBrowse?.showStatus('warning', text('Analyse ist noch nicht bereit.', 'Analysis is not ready.')); return;
        }
        var scope;
        try { scope = window.TaxonomyAnalysisScope?.read(); }
        catch (invalidScope) { window.TaxonomyBrowse?.showStatus('warning', invalidScope.message); return; }
        generation = C.runtime.analysisGeneration || 0;
        S.recoveryContext = { id: crypto.randomUUID(), workspaceId: ready.workspaceId, noFollowups: Boolean(manual),
            request: { businessText: input(), includeArchitectureView: scope?.mode !== 'TAXONOMIES_ONLY', analysisScope: scope,
                provider: document.getElementById('providerSelect')?.value || null }, followups: {} };
        S.currentRelationSearchReport = null;
        S.analysisRecovery = { id: current().id, version: 0, state: 'NEW', completedCalls: 0, openQuestions: [] };
        // This newly allocated identity owns no previous scores or derived evidence.
        // A repeated START for an existing operation must retain its accepted results.
        return run('START', null, true);
    }
    function startCopilot() { return startAnalysis(false); }
    function startManagedAnalysis() { return startAnalysis(true); }
    function extendRelations() {
        var source = current(), recovery = S.analysisRecovery, sourceStatus = S.lastAnalysisStatus;
        if (inScope(source) && (!statusKnown() || reading?.context === source)) { open(); return; }
        if (busy || decisionBusy || !inScope(source) || !crypto?.randomUUID
                || source.request.analysisScope?.mode !== 'TAXONOMIES_ONLY'
                || !['COMPLETED', 'COMPLETED_WITH_GAPS'].includes(recovery?.state)
                || !Number.isSafeInteger(recovery.version) || !nodeEvidenceMatches(source)) return;
        var request = Object.assign({}, source.request, {
            analysisScope: { taxonomyRoots: (source.request.analysisScope.taxonomyRoots || []).slice(), mode: 'FULL' },
            includeArchitectureView: true, relationSourceContinuationId: source.id, relationSourceVersion: recovery.version
        });
        S.recoveryContext = { id: crypto.randomUUID(), workspaceId: source.workspaceId,
            request: request, noFollowups: true, followups: {} };
        S.analysisRecovery = { id: current().id, version: 0, state: 'NEW', completedCalls: 0, openQuestions: [] };
        var target = current();
        return Promise.resolve(run('START', null, false)).then(async function (result) {
            if (!result && current() === target && !target.confirmed && [400, 403, 404, 409, 413, 422].includes(target.rejectionStatus)) {
                clearTimeout(poll); poll = null;
                S.recoveryContext = source; S.analysisRecovery = recovery; S.lastAnalysisStatus = sourceStatus;
                setBusy(false); remember(); update();
                ui.error(target.rejectionMessage); await save();
            }
            return result;
        });
    }
    async function restore() {
        if (busy || reading || !window.TaxonomyAnalysisSession?.state?.().ready) return;
        generation = C.runtime.analysisGeneration || 0;
        if (inScope(current())) { update(); await refresh(); return; }
        var pointer;
        try { pointer = JSON.parse(sessionStorage.getItem(STORAGE)); } catch (ignored) { return; }
        if (!pointer || pointer.workspaceId !== C.runtime.workspaceId) return;
        var context = { id: pointer.id, workspaceId: pointer.workspaceId, request: { businessText: input() }, followups: {} };
        try {
            var snapshot = await request(context, '');
            if (context.workspaceId !== C.runtime.workspaceId || (input() && input() !== snapshot.request.businessText)) return;
            var field = document.getElementById('businessText'); if (field && !input()) field.value = snapshot.request.businessText;
            context.request = snapshot.request;
            context.noFollowups = Boolean(snapshot.request.relationSourceContinuationId)
                || snapshot.request.analysisScope?.mode === 'TAXONOMIES_ONLY';
            S.recoveryContext = context;
            S.analysisRecovery = snapshot.recovery;
            if (snapshot.recovery.state !== 'RUNNING') hydrate(snapshot.result, context);
            update(); await save();
            if (snapshot.recovery.state === 'PAUSED' && !ui.isDismissed()) open();
            else if (snapshot.recovery.state === 'RUNNING') scheduleRead();
        } catch (ignored) { /* An inaccessible foreign/deleted operation never replaces the current draft. */ }
    }
    document.addEventListener('taxonomy:analysis-evidence-imported', function () {
        clearTimeout(poll); poll = null; S.recoveryContext = null; S.analysisRecovery = null;
        generation = C.runtime.analysisGeneration || 0; setBusy(false); remember(); ui.close();
        // The imported coverage remains valid metadata; only the execution binding is discarded.
        update();
    });
    document.addEventListener('taxonomy:analysis-draft-restored', restore);
    document.addEventListener('taxonomy:view-rendered', renderCoverage);
    document.addEventListener('taxonomy:analysis-draft-reset', function () {
        clearTimeout(poll); poll = null; S.recoveryContext = null; S.analysisRecovery = null; S.analysisCoverage = null; S.currentRelationSearchReport = null;
        generation = C.runtime.analysisGeneration || 0; setBusy(false); remember(); ui.close(); update();
    });
    document.getElementById('businessText')?.addEventListener('input', function () {
        if (current() && !inScope(current())) { clearTimeout(poll); poll = null; setBusy(false); ui.close(); ui.update('', false, false); }
    });
    document.addEventListener('taxonomy:analysis-invalidated', function () {
        clearTimeout(poll); poll = null; setBusy(false); ui.close(); ui.update('', false, false);
        remember();
    });
    // Observe live activity, without a timer which changes focus, scroll position or score values.
    document.addEventListener('taxonomy:analysis-progress', function (event) {
        if (!inScope(current()) || stopped()) return;
        var snapshot = event.detail;
        if (snapshot?.transport === 'artemis') {
            current().distributed = true; current().operationId = snapshot.operationId;
            clearTimeout(poll); poll = null;
        }
        if (!busy) return;
        liveWork = window.TaxonomyAnalysisProgress?.workSummary?.(snapshot) || '';
        if (snapshot?.node) S.analysisRecovery.currentNode = snapshot.node;
        update();
    });
    window.TaxonomyAnalysisSessionReady?.then(restore);
    window.TaxonomyAnalysisRecovery = { startCopilot: startCopilot, startManagedAnalysis: startManagedAnalysis,
        extendRelations: extendRelations, nodeEvidenceMatches: nodeEvidenceMatches, stage: stage, open: open,
        refresh: refresh, cancel: cancel, renderCoverage: renderCoverage, partialResultMessage: partialResultMessage,
        isManaged: function () { return inScope(current()); },
        isStatusKnown: function () { return statusKnown() && reading?.context !== current(); },
        hasOpenEvaluations: function () { return !statusKnown() || Boolean(S.analysisCoverage?.failedOrBlockedNodes)
            || Boolean(S.analysisRecovery?.openQuestions?.length)
            || ['PAUSED', 'STOPPED', 'CANCELLED', 'COMPLETED_WITH_GAPS'].includes(S.analysisRecovery?.state); } };
}());
