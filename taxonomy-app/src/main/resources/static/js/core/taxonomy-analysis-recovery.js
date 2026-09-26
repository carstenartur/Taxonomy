/* Durable question recovery and viewport-visible Copilot feedback; no provider retry loop. */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C || !window.TaxonomyRecoveryViewport) throw new Error('Recovery requires session and viewport modules');
    var S = C.S, busy = false, decisionBusy = false, poll = null, reading = false;
    var generation = C.runtime.analysisGeneration || 0;
    var STORAGE = 'taxonomy.recovery.active.v1';
    function text(de, en) { return C.language() === 'de' ? de : en; }
    var ui = window.TaxonomyRecoveryViewport.mount({ text: text, open: open, cancel: cancel });
    function input() { return (document.getElementById('businessText')?.value || '').trim(); }
    function current() { return S.recoveryContext; }
    function inScope(context) {
        return Boolean(context && current() === context && context.workspaceId === C.runtime.workspaceId
            && context.request.businessText === input()
            && generation === (C.runtime.analysisGeneration || 0) && !C.runtime.invalidating);
    }
    function stopped() { return S.analysisRecovery?.state === 'CANCELLED' || current()?.followupState === 'CANCELLED'; }
    function setBusy(value) {
        busy = value;
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
    async function save() {
        remember();
        var session = window.TaxonomyAnalysisSession;
        if (session?.state?.().ready && session.saveNow) {
            try { await session.saveNow(); } catch (ignored) { /* Existing draft UI reports its failure. */ }
        }
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
            PAUSED: text('Pausiert – Entscheidung erforderlich', 'Paused – decision required'),
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
        if (c?.followupState === 'COMPLETED_WITH_GAPS') label = text('Copilot beendet – Teilergebnis', 'Copilot finished – partial result');
        return label + ' · ' + (r.completedCalls || 0) + text(' Abfragen abgeschlossen', ' questions complete')
            + (r.currentNode && r.state === 'RUNNING' ? ' · ' + r.currentNode : '');
    }
    function update() {
        var r = S.analysisRecovery;
        ui.update(message(), busy, Boolean(r && (!['COMPLETED', 'CANCELLED'].includes(r.state)
            || current()?.followupState === 'RUNNING' || current()?.followupState === 'PAUSED')));
        renderCoverage();
    }
    function hydrate(result, context) {
        if (!result || !inScope(context)) return;
        S.analysisRecovery = result.recovery || S.analysisRecovery;
        context.pauseReason = ['PAUSED', 'STOPPED'].includes(S.analysisRecovery?.state) ? result.errorMessage : null;
        S.analysisCoverage = result.analysisCoverage || null;
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
        S.currentArchView = result.architectureView || null;
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
    async function refresh() {
        var c = current();
        if (!inScope(c) || reading) return;
        reading = true;
        try {
            var snapshot = await request(c, '');
            if (!inScope(c)) return;
            S.analysisRecovery = snapshot.recovery;
            c.observationError = null;
            // Refresh may report a RUNNING new attempt plus an older paused result. Never hydrate it.
            if (!busy && snapshot.recovery.state !== 'RUNNING') hydrate(snapshot.result, c);
            c.request = snapshot.request;
            update();
            if (snapshot.recovery.state === 'RUNNING') scheduleRead();
            else if (!busy) { await save(); if (snapshot.recovery.state === 'PAUSED') open(); }
        } catch (error) {
            if (inScope(c)) {
                c.observationError = error.message;
                ui.update(text('Laufstatus nicht abrufbar: ', 'Cannot read operation status: ') + error.message, busy, true);
                // Only retry observation. Never turn an ambiguous POST into another provider call.
                if (busy && ![401, 403, 404].includes(error.status)) scheduleRead();
            }
        } finally { reading = false; }
    }
    function scheduleRead() {
        clearTimeout(poll);
        poll = setTimeout(function () { poll = null; refresh(); }, 1200);
    }
    async function run(action, question) {
        var c = current();
        if (!inScope(c) || busy || decisionBusy || stopped()) return;
        if (!window.TaxonomyAnalysisSession?.state?.().ready) {
            ui.error(text('Arbeitsbereich ist noch nicht bereit.', 'Workspace is not ready.')); return;
        }
        var body = Object.assign({}, c.request, { resumable: true, continuationId: c.id,
            continuationAction: action || 'START', continuationVersion: S.analysisRecovery?.version,
            continuationQuestion: question || null });
        ui.close(); setBusy(true); c.followupState = null; c.followups = {};
        S.analysisRecovery = Object.assign({}, S.analysisRecovery, { state: 'RUNNING' });
        update(); remember(); scheduleRead();
        try {
            var promise = window.TaxonomyScoring.runAnalysis({ continuation: body, recoveryManaged: true });
            if (!promise || typeof promise.then !== 'function') throw new Error(text(
                'Die Analyse wurde von der Vorprüfung abgewiesen.', 'Analysis preflight rejected the start.'));
            var result = await promise;
            if (!inScope(c)) return;
            if (!result?.recovery) throw new Error(text('Keine bestätigte Fortsetzungsantwort.', 'No confirmed continuation response.'));
            S.analysisRecovery = result.recovery; S.analysisCoverage = result.analysisCoverage;
            c.observationError = null;
            c.pauseReason = ['PAUSED', 'STOPPED'].includes(result.recovery.state) ? result.errorMessage : null;
            setBusy(false); update(); await save();
            if (['PAUSED', 'STOPPED'].includes(result.recovery.state)) open();
            else if (result.recovery.state === 'COMPLETED' || result.recovery.state === 'COMPLETED_WITH_GAPS') {
                await followups();
            }
        } catch (error) {
            if (!inScope(c)) return;
            setBusy(false); c.observationError = error.message;
            // A rejected write may never have started. Keep its identity; do not fabricate a new run.
            await refresh(); open(); ui.error(error.message);
        } finally {
            clearTimeout(poll); poll = null;
            if (inScope(c)) { setBusy(false); update(); if (S.analysisRecovery?.state === 'RUNNING') scheduleRead(); }
        }
    }
    async function followups() {
        var c = current();
        if (!inScope(c) || busy || stopped()) return;
        if ((S.analysisCoverage?.failedOrBlockedNodes || 0) > 0) {
            // Existing gap/recommendation algorithms infer absence from missing scores. They
            // cannot safely make global claims with missing inputs; keep the useful architecture.
            c.followupState = 'COMPLETED_WITH_GAPS';
            window.TaxonomyAnalysis.renderPartialCopilot?.(text(
                'Architektur-Teilergebnis gespeichert. Offene Bereiche sind nicht negativ bewertet. Globale Lücken-, Muster- und Empfehlungsaussagen bleiben bis zur Nachbewertung offen.',
                'Partial architecture saved. Open areas are not negative assessments. Global gap, pattern and recommendation conclusions remain open until assessment completes.'));
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
        if (stopped()) throw new Error(text('Lauf abgebrochen.', 'Run cancelled.'));
        c.followups = c.followups || {};
        var saved = c.followups[key];
        if (saved?.state === 'SUCCESS') return saved.result;
        c.followupLabel = label || key; update();
        var result = await operation();
        if (!inScope(c) || stopped()) throw new Error(text('Laufkontext geändert.', 'Operation context changed.'));
        c.followups[key] = { state: 'SUCCESS', result: result };
        await save(); return result;
    }
    async function cancel() {
        var c = current();
        if (!inScope(c) || decisionBusy) return;
        decisionBusy = true;
        try {
            var snapshot = await request(c, '/cancel', 'POST');
            if (!inScope(c)) return;
            S.analysisRecovery = snapshot.recovery;
            c.followupState = 'CANCELLED';
            if (!busy && snapshot.result) hydrate(Object.assign({}, snapshot.result, { recovery: snapshot.recovery,
                status: snapshot.recovery.state === 'CANCELLED' ? 'CANCELLED' : snapshot.result.status }), c);
            clearTimeout(poll); poll = null;
            ui.close(); update(); await save();
        } catch (error) { if (inScope(c)) ui.error(error.message); }
        finally { decisionBusy = false; }
    }
    function open() {
        var c = current(), r = S.analysisRecovery;
        if (!inScope(c) || !r) return;
        var options = [];
        var questions = r.openQuestions || [];
        var selected = questions.find(function (q) { return !q.skipped; }) || questions[0];
        if (!busy && !stopped() && selected) {
            options.push({ id: 'analysisRecoveryRetry', shortLabel: text('Wiederholen', 'Retry'), label: text('Abfrage wiederholen und fortsetzen', 'Retry question and continue'),
                handler: function () { run('RETRY', selected.key); } });
            if (!selected.skipped) options.push({ id: 'analysisRecoverySkip', shortLabel: text('Offenlassen', 'Leave open'), label: text('Bereich offenlassen und fortfahren', 'Leave area unassessed and continue'),
                handler: function () { run('SKIP', selected.key); } });
        }
        if (!busy && r.state === 'STOPPED' && !stopped() && questions.every(function (q) { return q.skipped; })) {
            options.push({ id: 'analysisRecoveryContinue', label: text('Gespeicherten Stand fortsetzen', 'Continue saved progress'), handler: function () { run('CONTINUE'); } });
        } else if (!busy && r.state === 'COMPLETED' && !stopped()) {
            options.push({ id: 'analysisRecoveryRetryStage', label: text('Copilot fortsetzen', 'Continue Copilot'), handler: followups });
        }
        if (!stopped() && (r.state !== 'COMPLETED' || c.followupState !== 'COMPLETED'))
            options.push({ id: 'analysisRecoveryCancelDialog', shortLabel: text('Abbrechen', 'Cancel'), label: text('Lauf abbrechen', 'Cancel run'), handler: cancel });
        if (c.observationError || r.state === 'RUNNING') options.push({ id: 'analysisRecoveryRefresh', shortLabel: text('Aktualisieren', 'Refresh'), label: text('Status aktualisieren', 'Refresh status'), handler: refresh });
        ui.open('Copilot', message(), function (body, el) {
            var note = el('p', selected?.error || c.pauseReason || '');
            if (note.textContent) body.append(note);
            body.append(el('p', text('Gültige Bewertungen bleiben erhalten. Unbekannt bedeutet nicht „nicht relevant“.',
                'Valid assessments are retained. Unknown does not mean irrelevant.')));
            if (c.observationError) body.append(el('p', c.observationError));
            if (c.followupError) body.append(el('p', c.followupError));
            if (!selected) return;
            if (questions.length > 1) {
                var label = el('label', text('Offene Abfrage: ', 'Open question: '));
                var select = el('select'); select.id = 'analysisRecoveryQuestion'; select.className = 'form-select';
                questions.forEach(function (q) { var o = el('option', q.nodes.join(', ')); o.value = q.key; select.append(o); });
                select.value = selected.key;
                select.addEventListener('change', function () {
                    selected = questions.find(function (q) { return q.key === select.value; });
                    note.textContent = selected.error;
                    var skip = document.getElementById('analysisRecoverySkip'); if (skip) skip.disabled = selected.skipped;
                    details.open = false; detailBody.replaceChildren(); loadedKey = null;
                });
                label.append(select); body.append(label);
            }
            body.append(el('p', selected.nodes.join(', ')));
            body.append(el('p', text('Versuche: ', 'Attempts: ') + selected.attempts));
            if (selected.outcomeUncertain) body.append(el('p', text(
                'Der Anbieter könnte die letzte Anfrage bereits verarbeitet oder berechnet haben.',
                'The provider may already have processed or billed the last request.')));
            var details = el('details'); details.append(el('summary', text('Prompt und Antwort anzeigen', 'Show prompt and response')));
            var detailBody = el('div'); details.append(detailBody); body.append(details); var loadedKey = null;
            details.addEventListener('toggle', async function () {
                if (!details.open || loadedKey === selected.key) return;
                var key = selected.key; loadedKey = key;
                try {
                    var evidence = await request(c, '/questions/' + encodeURIComponent(key));
                    if (!inScope(c) || key !== selected.key) return;
                    detailBody.replaceChildren(el('h3', 'Prompt'), el('pre', evidence.prompt || text('Nicht gespeichert.', 'Not retained.')),
                        el('h3', text('Antwort', 'Response')), el('pre', evidence.rawResponse || text('Keine Antwort erhalten.', 'No response received.')));
                } catch (error) { loadedKey = null; detailBody.textContent = error.message; }
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
            var code = node.getAttribute('data-code'), assessment = isOpen ? coverage.nodes[code] : null;
            var label = !assessment ? '' : assessment.reason && /^(FAILED|LEFT_OPEN|BLOCKED_BY)/.test(assessment.reason)
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
    function startCopilot() {
        if (busy || decisionBusy) return;
        if (inScope(current()) && S.analysisRecovery) { open(); return; }
        if (!input()) { window.TaxonomyBrowse?.showStatus('warning', text('Bitte eine Anforderung eingeben.', 'Enter a requirement.')); return; }
        var ready = window.TaxonomyAnalysisSession?.state?.();
        if (!ready?.ready || !ready.workspaceId || !crypto?.randomUUID) {
            window.TaxonomyBrowse?.showStatus('warning', text('Analyse ist noch nicht bereit.', 'Analysis is not ready.')); return;
        }
        generation = C.runtime.analysisGeneration || 0;
        S.recoveryContext = { id: crypto.randomUUID(), workspaceId: ready.workspaceId,
            request: { businessText: input(), includeArchitectureView: true,
                provider: document.getElementById('providerSelect')?.value || null }, followups: {} };
        S.analysisRecovery = { id: current().id, version: 0, state: 'NEW', completedCalls: 0, openQuestions: [] };
        run('START');
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
            context.request = snapshot.request; S.recoveryContext = context;
            S.analysisRecovery = snapshot.recovery;
            if (snapshot.recovery.state !== 'RUNNING') hydrate(snapshot.result, context);
            update(); await save();
            if (snapshot.recovery.state === 'PAUSED') open();
            else if (snapshot.recovery.state === 'RUNNING') scheduleRead();
        } catch (ignored) { /* An inaccessible foreign/deleted operation never replaces the current draft. */ }
    }
    document.addEventListener('taxonomy:analysis-evidence-imported', function () {
        clearTimeout(poll); poll = null; S.recoveryContext = null; S.analysisRecovery = null;
        generation = C.runtime.analysisGeneration || 0; setBusy(false); remember(); ui.close();
        // The imported coverage remains valid metadata; only the execution binding is discarded.
        renderCoverage();
    });
    document.addEventListener('taxonomy:analysis-draft-restored', restore);
    document.addEventListener('taxonomy:view-rendered', renderCoverage);
    document.addEventListener('taxonomy:analysis-draft-reset', function () {
        clearTimeout(poll); poll = null; S.recoveryContext = null; S.analysisRecovery = null; S.analysisCoverage = null;
        generation = C.runtime.analysisGeneration || 0; setBusy(false); remember(); ui.close(); update();
    });
    document.getElementById('businessText')?.addEventListener('input', function () {
        if (current() && !inScope(current())) { clearTimeout(poll); poll = null; setBusy(false); ui.close(); ui.update('', false, false); }
    });
    document.addEventListener('taxonomy:analysis-invalidated', function () {
        clearTimeout(poll); poll = null; setBusy(false); ui.close(); ui.update('', false, false);
    });
    // Observe live activity, without a timer which changes focus, scroll position or score values.
    document.addEventListener('taxonomy:analysis-progress', function (event) {
        if (!busy || !inScope(current()) || stopped()) return;
        var snapshot = event.detail;
        if (snapshot?.node) S.analysisRecovery.currentNode = snapshot.node;
        update();
    });
    window.TaxonomyAnalysisSessionReady?.then(restore);
    window.TaxonomyAnalysisRecovery = { startCopilot: startCopilot, stage: stage, open: open,
        refresh: refresh, cancel: cancel, renderCoverage: renderCoverage,
        isManaged: function () { return inScope(current()); },
        hasOpenEvaluations: function () { return Boolean(S.analysisCoverage?.failedOrBlockedNodes)
            || ['PAUSED', 'STOPPED', 'CANCELLED'].includes(S.analysisRecovery?.state); } };
}());
