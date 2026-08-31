/* requirement-copilot.js – resilient, resumable full-analysis UI for saved requirements. */
(function () {
    'use strict';

    const match = window.location.pathname.match(/(?:^|\/)projects\/(\d+)\/requirements\/(\d+)$/);
    if (!match) return;

    const projectId = Number(match[1]);
    const requirementId = Number(match[2]);
    const locale = (new URLSearchParams(window.location.search).get('lang')
        || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const terminal = new Set(['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED']);
    const active = new Set(['PENDING', 'RUNNING', 'RECONNECTING', 'CANCELLING']);

    let activeOperationId = null;
    let activeOperation = null;
    let pollingGeneration = 0;
    let manualCopilotReady = false;
    let lastAnnouncement = null;
    let lastServerContact = null;
    let consecutivePollFailures = 0;

    const copy = {
        en: {
            title: 'Copilot full analysis', run: 'Run full analysis', cancel: 'Cancel',
            profile: 'Profile', standard: 'Standard', full: 'Full', exhaustive: 'Exhaustive',
            force: 'Force a new run even when the current state was already analysed',
            autopilotReady: 'Autopilot active: new requirement versions are analysed automatically.',
            autopilotOff: 'Autopilot is not active',
            review: 'Human review remains required for mappings, responsibilities, solution/product selection, procurement and branch merge.',
            failed: 'Copilot operation failed', completed: 'Copilot completed. Opening the selected immutable snapshot…',
            pass: 'passes complete', provider: 'AI target', operation: 'Operation', phase: 'Phase',
            snapshot: 'Selected snapshot',
            lastContact: 'Last server contact', queued: 'Waiting for analysis capacity',
            scoring: 'The current verification pass is being evaluated', finalizing: 'Selecting and enriching the best immutable result',
            completedPhase: 'Completed and ready for review',
            partialPhase: 'Completed with partial results', failedPhase: 'Failed', cancelledPhase: 'Cancelled',
            reconnecting: 'The server status cannot currently be reached. The analysis has not been declared failed; Taxonomy is reconnecting.',
            reconnectAttempt: 'Reconnect attempt', cancelling: 'Cancellation requested. Waiting for the authoritative terminal state…',
            retryStatus: 'Retry status connection now', openResult: 'Open result',
            exportTitle: 'Report export', exportRunning: 'Generating and validating the requested export…',
            exportSuccess: 'Export created', exportFailed: 'Export failed', bytes: 'bytes'
        },
        de: {
            title: 'Copilot-Vollanalyse', run: 'Vollanalyse starten', cancel: 'Abbrechen',
            profile: 'Profil', standard: 'Standard', full: 'Vollständig', exhaustive: 'Umfassend',
            force: 'Neuen Lauf erzwingen, auch wenn dieser Stand bereits analysiert wurde',
            autopilotReady: 'Autopilot aktiv: Neue Anforderungsversionen werden automatisch analysiert.',
            autopilotOff: 'Autopilot ist nicht aktiv',
            review: 'Menschliche Prüfung bleibt erforderlich für Zuordnungen, Zuständigkeiten, Lösungs-/Produktauswahl, Beschaffung und Branch-Merge.',
            failed: 'Die Copilot-Operation ist fehlgeschlagen', completed: 'Copilot abgeschlossen. Der ausgewählte unveränderliche Snapshot wird geöffnet…',
            pass: 'Durchläufe abgeschlossen', provider: 'KI-Ziel', operation: 'Vorgang', phase: 'Phase',
            snapshot: 'Ausgewählter Snapshot',
            lastContact: 'Letzter Serverkontakt', queued: 'Warten auf freie Analysekapazität',
            scoring: 'Der aktuelle Prüfdurchlauf wird bewertet', finalizing: 'Das beste unveränderliche Ergebnis wird ausgewählt und ergänzt',
            completedPhase: 'Abgeschlossen und zur Prüfung bereit',
            partialPhase: 'Mit Teilergebnissen abgeschlossen', failedPhase: 'Fehlgeschlagen', cancelledPhase: 'Abgebrochen',
            reconnecting: 'Der Serverstatus ist momentan nicht erreichbar. Die Analyse wurde nicht als fehlgeschlagen erklärt; Taxonomy stellt die Verbindung wieder her.',
            reconnectAttempt: 'Wiederverbindungsversuch', cancelling: 'Abbruch angefordert. Warten auf den autoritativen Endzustand…',
            retryStatus: 'Statusverbindung jetzt erneut versuchen', openResult: 'Ergebnis öffnen',
            exportTitle: 'Berichtsexport', exportRunning: 'Der angeforderte Export wird erzeugt und geprüft…',
            exportSuccess: 'Export erstellt', exportFailed: 'Export fehlgeschlagen', bytes: 'Bytes'
        }
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        initialize();
    }

    function t(key) { return copy[locale][key] || copy.en[key] || key; }

    async function initialize() {
        ensureSurface();
        translate();
        installControlInventory();
        document.getElementById('copilotRun')?.addEventListener('click', start);
        document.getElementById('copilotCancel')?.addEventListener('click', cancel);
        document.getElementById('copilotRetryStatus')?.addEventListener('click', retryStatusConnection);
        document.addEventListener('click', interceptDecisionReportExport, true);

        try {
            const [status, latest] = await Promise.all([
                api().status(),
                api().latest(projectId, requirementId)
            ]);
            renderPolicy(status);
            if (latest) {
                lastServerContact = new Date();
                renderOperation(latest);
                if (!terminal.has(latest.status)) startPolling(latest.operationId);
            }
        } catch (error) {
            showError(error);
        }
    }

    function ensureSurface() {
        if (document.getElementById('requirementCopilotCard')) return;
        const main = document.getElementById('requirementMain');
        const anchor = main?.querySelector('section.card');
        if (!main || !anchor) return;

        const section = document.createElement('section');
        section.id = 'requirementCopilotCard';
        section.className = 'card shadow-sm mb-3 border-primary';
        section.setAttribute('aria-labelledby', 'copilotHeading');
        section.innerHTML = `
            <div class="card-header d-flex flex-wrap justify-content-between gap-2 align-items-center">
                <h2 id="copilotHeading" class="h5 mb-0">Copilot full analysis</h2>
                <div id="copilotPolicy"></div>
            </div>
            <div class="card-body">
                <div id="copilotError" class="alert alert-danger d-none" role="alert" tabindex="-1"></div>
                <div class="row g-3 align-items-end">
                    <div class="col-12 col-md-3">
                        <label id="copilotProfileLabel" for="copilotProfile" class="form-label">Profile</label>
                        <select id="copilotProfile" class="form-select">
                            <option id="copilotProfileStandard" value="STANDARD">Standard</option>
                            <option id="copilotProfileFull" value="FULL" selected>Full</option>
                            <option id="copilotProfileExhaustive" value="EXHAUSTIVE">Exhaustive</option>
                        </select>
                    </div>
                    <div class="col-12 col-md-5">
                        <div class="form-check">
                            <input id="copilotForce" class="form-check-input" type="checkbox">
                            <label id="copilotForceLabel" class="form-check-label" for="copilotForce">
                                Force a new run even when the current state was already analysed
                            </label>
                        </div>
                    </div>
                    <div class="col-12 col-md-4 d-flex gap-2 flex-wrap">
                        <button id="copilotRun" type="button" class="btn btn-primary">
                            <span id="copilotRunSpinner" class="spinner-border spinner-border-sm d-none" aria-hidden="true"></span>
                            <span id="copilotRunLabel">Run full analysis</span>
                        </button>
                        <button id="copilotCancel" type="button" class="btn btn-outline-danger" disabled>Cancel</button>
                    </div>
                </div>
                <p id="copilotReviewBoundary" class="small text-body-secondary mt-3 mb-0"></p>
                <div id="copilotOperation" class="border rounded p-3 mt-3 d-none" role="status" aria-live="polite"
                     data-operation-surface="copilot">
                    <div class="d-flex flex-wrap justify-content-between gap-2 align-items-start">
                        <div>
                            <strong id="copilotOperationStatus"></strong>
                            <div id="copilotOperationPhase" class="small text-body-secondary"></div>
                        </div>
                        <span id="copilotOperationProvider" class="small text-body-secondary"></span>
                    </div>
                    <p id="copilotOperationMessage" class="mb-2 mt-2"></p>
                    <div id="copilotProgress" class="progress" role="progressbar" aria-label="Copilot progress">
                        <div id="copilotProgressBar" class="progress-bar progress-bar-striped progress-bar-animated"></div>
                    </div>
                    <div id="copilotOperationProgress" class="small text-body-secondary mt-1"></div>
                    <div id="copilotResultActions"
                         class="d-none flex-wrap justify-content-between align-items-center gap-2 mt-3 p-2 border rounded bg-body-tertiary">
                        <span id="copilotSelectedSnapshot" class="small text-body-secondary"></span>
                        <a id="copilotOpenResult" class="btn btn-sm btn-outline-primary" href="#">Open result</a>
                    </div>
                    <div class="d-flex flex-wrap justify-content-between gap-2 mt-2 small text-body-secondary">
                        <span id="copilotOperationId"></span>
                        <span id="copilotLastContact"></span>
                    </div>
                    <div id="copilotReconnect" class="alert alert-warning py-2 px-3 mt-3 mb-0 d-none" role="status">
                        <span id="copilotReconnectMessage"></span>
                        <button id="copilotRetryStatus" type="button" class="btn btn-sm btn-outline-warning ms-2"></button>
                    </div>
                </div>
            </div>`;
        anchor.insertAdjacentElement('afterend', section);
    }

    function translate() {
        setText('copilotHeading', t('title'));
        setText('copilotRunLabel', t('run'));
        setText('copilotCancel', t('cancel'));
        setText('copilotProfileLabel', t('profile'));
        setText('copilotProfileStandard', t('standard'));
        setText('copilotProfileFull', t('full'));
        setText('copilotProfileExhaustive', t('exhaustive'));
        setText('copilotForceLabel', t('force'));
        setText('copilotReviewBoundary', t('review'));
        setText('copilotRetryStatus', t('retryStatus'));
        setText('copilotOpenResult', t('openResult'));
    }

    function installControlInventory() {
        markControl('copilotProfile', 'profile', 'toggle');
        markControl('copilotForce', 'force-new-run', 'toggle');
        markControl('copilotRun', 'run-full-analysis', 'operation');
        markControl('copilotCancel', 'cancel-full-analysis', 'cancel');
        markControl('copilotRetryStatus', 'retry-status-connection', 'operation');
        markControl('copilotOpenResult', 'open-selected-result', 'navigation');
    }

    function markControl(id, control, outcome) {
        const element = document.getElementById(id);
        if (!element) return;
        element.dataset.sessionControl = control;
        element.dataset.sessionTestOutcome = outcome;
    }

    async function start() {
        stopPolling();
        setControls(true);
        clearError();
        hideReconnect();
        try {
            const profile = document.getElementById('copilotProfile')?.value || 'FULL';
            const operation = await api().start(projectId, requirementId, {
                profile: profile,
                verificationPasses: profile === 'EXHAUSTIVE' ? 2 : 1,
                force: Boolean(document.getElementById('copilotForce')?.checked),
                proposeSolutions: profile !== 'STANDARD',
                proposeProducts: profile !== 'STANDARD'
            });
            lastServerContact = new Date();
            activeOperation = operation;
            renderOperation(operation);
            if (!terminal.has(operation.status)) startPolling(operation.operationId);
            else handleTerminal(operation);
        } catch (error) {
            showError(error);
            setControls(false);
        }
    }

    async function cancel() {
        if (!activeOperationId) return;
        clearError();
        renderLocalState('CANCELLING', t('cancelling'));
        setControls(true);
        try {
            const operation = await api().cancel(projectId, activeOperationId);
            lastServerContact = new Date();
            activeOperation = operation;
            renderOperation(operation);
            if (terminal.has(operation.status)) {
                stopPolling();
                handleTerminal(operation);
            } else {
                startPolling(activeOperationId);
            }
        } catch (error) {
            showError(error);
            if (activeOperation && !terminal.has(activeOperation.status)) startPolling(activeOperationId);
        }
    }

    function startPolling(operationId) {
        if (!operationId) return;
        pollingGeneration += 1;
        const generation = pollingGeneration;
        activeOperationId = operationId;
        poll(operationId, generation);
    }

    function stopPolling() {
        pollingGeneration += 1;
    }

    async function poll(operationId, generation) {
        while (generation === pollingGeneration) {
            await delay(consecutivePollFailures > 0
                ? Math.min(15000, 1500 * Math.pow(2, consecutivePollFailures - 1))
                : 1500);
            if (generation !== pollingGeneration) return;

            try {
                const operation = await api().get(projectId, operationId);
                if (generation !== pollingGeneration || operation.operationId !== operationId) return;
                consecutivePollFailures = 0;
                lastServerContact = new Date();
                activeOperation = operation;
                hideReconnect();
                renderOperation(operation);
                if (terminal.has(operation.status)) {
                    stopPolling();
                    handleTerminal(operation);
                    return;
                }
            } catch (error) {
                if (generation !== pollingGeneration) return;
                consecutivePollFailures += 1;
                renderReconnect(error, consecutivePollFailures);
            }
        }
    }

    function retryStatusConnection() {
        if (!activeOperationId) return;
        consecutivePollFailures = 0;
        startPolling(activeOperationId);
    }

    function handleTerminal(operation) {
        setControls(false);
        if ((operation.status === 'SUCCESS' || operation.status === 'PARTIAL')
                && operation.selectedSnapshotId) {
            announce(t('completed'));
            window.setTimeout(function () {
                const url = new URL(window.location.href);
                url.searchParams.set('snapshot', operation.selectedSnapshotId);
                url.searchParams.set('lang', locale);
                window.location.replace(url);
            }, 600);
        }
    }

    function renderPolicy(status) {
        const target = document.getElementById('copilotPolicy');
        if (!target || !status) return;
        const cls = status.autopilotReady ? 'text-bg-success' : 'text-bg-secondary';
        target.innerHTML = '<span class="badge ' + cls + '">'
            + escapeHtml(status.autopilotReady ? t('autopilotReady') : t('autopilotOff'))
            + '</span><div class="small text-body-secondary mt-1">'
            + escapeHtml(status.reason || '') + '</div>';
        manualCopilotReady = Boolean(status.manualCopilotReady);
        const button = document.getElementById('copilotRun');
        if (button) button.title = manualCopilotReady ? '' : (status.reason || '');
        setControls(Boolean(activeOperation && !terminal.has(activeOperation.status)));
    }

    function primaryOperationMessage(operation) {
        if (!operation) return '';
        const status = String(operation.status || '').toUpperCase();
        if (status !== 'FAILED' && status !== 'PARTIAL') {
            return operation.message || '';
        }
        const evidence = [];
        (Array.isArray(operation.jobs) ? operation.jobs : []).forEach(job => {
            (Array.isArray(job.items) ? job.items : []).forEach(item => {
                if (item && item.errorMessage) evidence.push(String(item.errorMessage));
            });
            if (job && job.errorSummary) evidence.push(String(job.errorSummary));
        });
        if (operation.message) evidence.push(String(operation.message));
        const unique = evidence.map(value => value.trim()).filter(Boolean)
            .filter((value, index, values) => values.indexOf(value) === index);
        return unique.find(value => value.includes('PROMPT_BUDGET_EXCEEDED'))
            || unique[0] || t('failed');
    }

    function renderOperation(operation) {
        if (!operation) return;
        activeOperationId = operation.operationId;
        activeOperation = operation;
        const panel = document.getElementById('copilotOperation');
        panel?.classList.remove('d-none');
        panel?.setAttribute('data-operation-id', operation.operationId || '');
        panel?.setAttribute('data-operation-status', operation.status || '');

        const status = String(operation.status || '');
        const message = primaryOperationMessage(operation);
        if (panel) {
            panel.setAttribute('role', status === 'FAILED' ? 'alert' : 'status');
            panel.setAttribute('aria-live', status === 'FAILED' ? 'assertive' : 'polite');
        }
        if (status === 'FAILED') clearError();
        setText('copilotOperationStatus', humanize(status));
        setText('copilotOperationMessage', message);
        setText('copilotOperationProvider', t('provider') + ': ' + (operation.provider || '—'));
        setText('copilotOperationId', t('operation') + ': ' + (operation.operationId || '—'));
        setText('copilotLastContact', t('lastContact') + ': ' + formatTime(lastServerContact));

        const phase = operationPhase(operation);
        setText('copilotOperationPhase', t('phase') + ': ' + phase.label);
        renderProgress(operation, phase);
        renderResultAction(operation);

        const cancelButton = document.getElementById('copilotCancel');
        if (cancelButton) cancelButton.disabled = terminal.has(operation.status);
        setControls(!terminal.has(operation.status));
        announce(message + ' ' + operation.completedPasses
            + '/' + operation.verificationPasses);
    }

    function renderResultAction(operation) {
        const surface = document.getElementById('copilotResultActions');
        const snapshot = document.getElementById('copilotSelectedSnapshot');
        const link = document.getElementById('copilotOpenResult');
        if (!surface || !snapshot || !link) return;

        const status = String(operation?.status || '').toUpperCase();
        const snapshotId = String(operation?.selectedSnapshotId || '').trim();
        const available = (status === 'SUCCESS' || status === 'PARTIAL')
            && snapshotId.length > 0;
        surface.classList.toggle('d-none', !available);
        surface.classList.toggle('d-flex', available);
        if (!available) {
            snapshot.textContent = '';
            link.removeAttribute('href');
            link.removeAttribute('aria-label');
            return;
        }

        snapshot.textContent = t('snapshot') + ': ' + snapshotId;
        const resultUrl = new URL(window.location.href);
        resultUrl.searchParams.set('snapshot', snapshotId);
        resultUrl.searchParams.set('lang', locale);
        link.href = resultUrl.pathname + resultUrl.search + resultUrl.hash;
        link.setAttribute('aria-label', t('openResult') + ': ' + snapshotId);
    }

    function renderLocalState(status, message) {
        const operation = Object.assign({}, activeOperation || {}, {
            operationId: activeOperationId,
            status: status,
            message: message
        });
        activeOperation = operation;
        renderOperation(operation);
    }

    function operationPhase(operation) {
        const jobs = Array.isArray(operation.jobs) ? operation.jobs : [];
        const running = jobs.find(job => job.status === 'RUNNING');
        const pending = jobs.find(job => job.status === 'PENDING');
        const status = String(operation.status || '').toUpperCase();
        if (status === 'CANCELLING') return { key: 'CANCELLING', label: t('cancelling'), indeterminate: true };
        if (status === 'RECONNECTING') return { key: 'RECONNECTING', label: t('reconnecting'), indeterminate: true };
        if (status === 'SUCCESS') return { key: 'COMPLETED', label: t('completedPhase'), indeterminate: false };
        if (status === 'PARTIAL') return { key: 'PARTIAL', label: t('partialPhase'), indeterminate: false };
        if (status === 'FAILED') return { key: 'FAILED', label: t('failedPhase'), indeterminate: false };
        if (status === 'CANCELLED') return { key: 'CANCELLED', label: t('cancelledPhase'), indeterminate: false };
        if (running) return { key: 'SCORING', label: t('scoring'), indeterminate: true };
        if (pending) return { key: 'QUEUED', label: t('queued'), indeterminate: true };
        return { key: 'FINALIZING', label: t('finalizing'), indeterminate: true };
    }

    function renderProgress(operation, phase) {
        const total = Math.max(1, Number(operation.verificationPasses) || 1);
        const completed = Math.max(0, Number(operation.completedPasses) || 0);
        const percentage = Math.max(0, Math.min(100, Math.round(100 * completed / total)));
        const progress = document.getElementById('copilotProgress');
        const bar = document.getElementById('copilotProgressBar');
        if (!progress || !bar) return;

        if (phase.indeterminate && !terminal.has(operation.status)) {
            progress.removeAttribute('aria-valuemin');
            progress.removeAttribute('aria-valuemax');
            progress.removeAttribute('aria-valuenow');
            progress.setAttribute('aria-valuetext', phase.label + '; ' + completed + ' / ' + total + ' ' + t('pass'));
            bar.className = 'progress-bar progress-bar-striped progress-bar-animated';
            bar.style.width = Math.max(12, percentage) + '%';
        } else {
            progress.setAttribute('aria-valuemin', '0');
            progress.setAttribute('aria-valuemax', '100');
            progress.setAttribute('aria-valuenow', String(terminal.has(operation.status) ? 100 : percentage));
            progress.removeAttribute('aria-valuetext');
            bar.className = 'progress-bar';
            bar.style.width = (terminal.has(operation.status) ? 100 : percentage) + '%';
        }
        setText('copilotOperationProgress', completed + ' / ' + total + ' ' + t('pass'));
    }

    function renderReconnect(error, attempt) {
        const panel = document.getElementById('copilotOperation');
        panel?.classList.remove('d-none');
        panel?.setAttribute('data-operation-status', 'RECONNECTING');
        setText('copilotOperationStatus', 'Reconnecting');
        setText('copilotOperationPhase', t('phase') + ': RECONNECTING');
        setText('copilotOperationMessage', t('reconnecting'));
        setText('copilotLastContact', t('lastContact') + ': ' + formatTime(lastServerContact));
        const reconnect = document.getElementById('copilotReconnect');
        reconnect?.classList.remove('d-none');
        setText('copilotReconnectMessage', t('reconnectAttempt') + ' ' + attempt
            + (error?.message ? ': ' + error.message : ''));
        const phase = { label: t('reconnecting'), indeterminate: true };
        renderProgress(activeOperation || {
            status: 'RECONNECTING', completedPasses: 0, verificationPasses: 1
        }, phase);
        setControls(true);
        announce(t('reconnecting'));
    }

    function hideReconnect() {
        document.getElementById('copilotReconnect')?.classList.add('d-none');
    }

    function setControls(running) {
        const run = document.getElementById('copilotRun');
        const spinner = document.getElementById('copilotRunSpinner');
        const profile = document.getElementById('copilotProfile');
        const force = document.getElementById('copilotForce');
        if (run) {
            run.disabled = running || !manualCopilotReady;
            run.setAttribute('aria-busy', running ? 'true' : 'false');
        }
        if (profile) profile.disabled = running;
        if (force) force.disabled = running;
        spinner?.classList.toggle('d-none', !running);
    }

    function showError(error) {
        const target = document.getElementById('copilotError');
        if (!target) return;
        target.textContent = error?.message || t('failed');
        target.classList.remove('d-none');
        target.focus();
        announce(target.textContent);
    }

    function clearError() {
        const target = document.getElementById('copilotError');
        target?.classList.add('d-none');
        if (target) target.textContent = '';
    }

    async function interceptDecisionReportExport(event) {
        const button = event.target?.closest?.('[data-decision-report-format]');
        if (!button) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        const format = String(button.dataset.decisionReportFormat || '').toLowerCase();
        markExportControls();
        await runDecisionReportExport(button, format);
    }

    function markExportControls() {
        document.querySelectorAll('[data-decision-report-format]').forEach(button => {
            button.dataset.sessionControl = 'decision-report-' + button.dataset.decisionReportFormat;
            button.dataset.sessionTestOutcome = 'export';
        });
    }

    async function runDecisionReportExport(button, format) {
        const snapshotId = new URLSearchParams(window.location.search).get('snapshot');
        const surface = ensureExportSurface(button);
        const operationId = 'export-' + Date.now().toString(36) + '-' + format;
        if (!snapshotId) {
            renderExportState(surface, operationId, 'FAILED', t('exportFailed'), 'No selected snapshot');
            return;
        }

        setExportControlsDisabled(true);
        renderExportState(surface, operationId, 'RUNNING', t('exportRunning'), '');
        dispatchExport(operationId, format, 'RUNNING');
        try {
            const response = await window.TaxonomyPortfolioApi.downloadDecisionReport(
                projectId, snapshotId, format, locale);
            const blob = await response.blob();
            const disposition = response.headers.get('Content-Disposition') || '';
            const filenameMatch = disposition.match(/filename="?([^";]+)"?/i);
            const filename = filenameMatch ? filenameMatch[1]
                : `taxonomy-decision-rationale-report.${format}`;
            validateExport(response, blob, format, filename);
            downloadBlob(blob, filename);
            renderExportState(surface, operationId, 'SUCCESS', t('exportSuccess'),
                filename + ' · ' + blob.size + ' ' + t('bytes'));
            dispatchExport(operationId, format, 'SUCCESS', {
                filename: filename,
                bytes: blob.size,
                contentType: response.headers.get('Content-Type') || blob.type || ''
            });
        } catch (error) {
            renderExportState(surface, operationId, 'FAILED', t('exportFailed'), error?.message || String(error));
            dispatchExport(operationId, format, 'FAILED', { error: error?.message || String(error) });
        } finally {
            setExportControlsDisabled(false);
        }
    }

    function ensureExportSurface(button) {
        let surface = document.getElementById('requirementExportOperation');
        if (surface) return surface;
        surface = document.createElement('div');
        surface.id = 'requirementExportOperation';
        surface.className = 'alert alert-info mt-2 mb-0';
        surface.setAttribute('role', 'status');
        surface.setAttribute('aria-live', 'polite');
        surface.dataset.operationSurface = 'export';
        button.closest('.border.rounded')?.appendChild(surface);
        return surface;
    }

    function renderExportState(surface, operationId, status, title, detail) {
        if (!surface) return;
        surface.className = 'alert mt-2 mb-0 ' + (status === 'FAILED'
            ? 'alert-danger' : status === 'SUCCESS' ? 'alert-success' : 'alert-info');
        surface.dataset.operationId = operationId;
        surface.dataset.operationStatus = status;
        surface.textContent = '';
        const strong = document.createElement('strong');
        strong.textContent = t('exportTitle') + ': ' + title;
        surface.appendChild(strong);
        if (detail) surface.appendChild(document.createTextNode(' — ' + detail));
        if (status === 'RUNNING') {
            const spinner = document.createElement('span');
            spinner.className = 'spinner-border spinner-border-sm ms-2';
            spinner.setAttribute('aria-hidden', 'true');
            surface.appendChild(spinner);
        }
        announce(surface.textContent);
    }

    function validateExport(response, blob, format, filename) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        if (!blob || blob.size < 1) throw new Error('The export response was empty');
        const type = String(response.headers.get('Content-Type') || blob.type || '').toLowerCase();
        const expected = {
            docx: ['application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'application/octet-stream'],
            html: ['text/html', 'application/xhtml+xml'],
            json: ['application/json', 'application/problem+json']
        }[format] || [];
        if (expected.length && !expected.some(candidate => type.includes(candidate))) {
            throw new Error('Unexpected content type for ' + format + ': ' + (type || 'missing'));
        }
        if (!filename.toLowerCase().endsWith('.' + format)) {
            throw new Error('Unexpected export filename: ' + filename);
        }
    }

    function downloadBlob(blob, filename) {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.setTimeout(function () { URL.revokeObjectURL(url); }, 0);
    }

    function setExportControlsDisabled(disabled) {
        document.querySelectorAll('[data-decision-report-format]').forEach(button => {
            button.disabled = disabled;
            button.setAttribute('aria-busy', disabled ? 'true' : 'false');
        });
    }

    function dispatchExport(operationId, format, status, detail) {
        document.dispatchEvent(new CustomEvent('taxonomy:export-operation-state', {
            detail: Object.assign({
                operationId: operationId,
                operationType: 'EXPORT',
                format: format,
                status: status,
                updatedAt: new Date().toISOString()
            }, detail || {})
        }));
    }

    function announce(message) {
        const normalized = String(message || '').trim();
        if (!normalized || normalized === lastAnnouncement) return;
        const live = document.getElementById('requirementLive');
        if (live) {
            lastAnnouncement = normalized;
            live.textContent = normalized;
        }
    }

    function api() {
        if (!window.TaxonomyCopilotApi) {
            throw new Error('Copilot API boundary is not available');
        }
        return window.TaxonomyCopilotApi;
    }

    function setText(id, value) {
        const element = document.getElementById(id);
        if (element) element.textContent = value;
    }

    function humanize(value) {
        return String(value || '—').toLowerCase().replaceAll('_', ' ')
            .replace(/\b\w/g, character => character.toUpperCase());
    }

    function formatTime(value) {
        if (!value) return '—';
        try { return value.toLocaleTimeString(locale); }
        catch (error) { return String(value); }
    }

    function delay(milliseconds) {
        return new Promise(resolve => window.setTimeout(resolve, milliseconds));
    }

    function escapeHtml(value) {
        return window.TaxonomyUtils.escapeHtml(value);
    }
}());
