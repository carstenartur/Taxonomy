/* requirement-copilot.js – resumable full-analysis UI for saved requirements. */
(function () {
    'use strict';

    const match = window.location.pathname.match(/(?:^|\/)projects\/(\d+)\/requirements\/(\d+)$/);
    if (!match) return;
    const projectId = Number(match[1]);
    const requirementId = Number(match[2]);
    const locale = (new URLSearchParams(window.location.search).get('lang')
        || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const terminal = new Set(['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED']);
    let activeOperationId = null;
    let polling = false;
    let manualCopilotReady = false;
    let lastAnnouncement = null;

    const copy = {
        en: {
            title: 'Copilot full analysis', run: 'Run full analysis', cancel: 'Cancel',
            profile: 'Profile', standard: 'Standard', full: 'Full', exhaustive: 'Exhaustive',
            force: 'Force a new run even when the current state was already analysed',
            autopilotReady: 'Autopilot active: new requirement versions are analysed automatically.',
            autopilotOff: 'Autopilot is not active', review: 'Human review remains required for mappings, responsibilities, solution/product selection, procurement and branch merge.',
            failed: 'Copilot operation failed', completed: 'Copilot completed. Opening the selected immutable snapshot…',
            pass: 'passes complete', provider: 'Provider', status: 'Status'
        },
        de: {
            title: 'Copilot-Vollanalyse', run: 'Vollanalyse starten', cancel: 'Abbrechen',
            profile: 'Profil', standard: 'Standard', full: 'Vollständig', exhaustive: 'Umfassend',
            force: 'Neuen Lauf erzwingen, auch wenn dieser Stand bereits analysiert wurde',
            autopilotReady: 'Autopilot aktiv: Neue Anforderungsversionen werden automatisch analysiert.',
            autopilotOff: 'Autopilot ist nicht aktiv', review: 'Menschliche Prüfung bleibt erforderlich für Zuordnungen, Zuständigkeiten, Lösungs-/Produktauswahl, Beschaffung und Branch-Merge.',
            failed: 'Die Copilot-Operation ist fehlgeschlagen', completed: 'Copilot abgeschlossen. Der ausgewählte unveränderliche Snapshot wird geöffnet…',
            pass: 'Durchläufe abgeschlossen', provider: 'Provider', status: 'Status'
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
        document.getElementById('copilotRun')?.addEventListener('click', start);
        document.getElementById('copilotCancel')?.addEventListener('click', cancel);
        try {
            const [status, latest] = await Promise.all([
                api().status(),
                api().latest(projectId, requirementId)
            ]);
            renderPolicy(status);
            if (latest) {
                renderOperation(latest);
                if (!terminal.has(latest.status)) poll(latest.operationId);
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
                <div id="copilotError" class="alert alert-danger d-none" role="alert"></div>
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
                <div id="copilotOperation" class="border rounded p-3 mt-3 d-none" role="status" aria-live="polite">
                    <div class="d-flex flex-wrap justify-content-between gap-2">
                        <strong id="copilotOperationStatus"></strong>
                        <span id="copilotOperationProvider" class="small text-body-secondary"></span>
                    </div>
                    <p id="copilotOperationMessage" class="mb-2"></p>
                    <div class="progress" role="progressbar" aria-label="Copilot progress" aria-valuemin="0" aria-valuemax="100">
                        <div id="copilotProgressBar" class="progress-bar progress-bar-striped progress-bar-animated" style="width:0%" aria-valuenow="0"></div>
                    </div>
                    <div id="copilotOperationProgress" class="small text-body-secondary mt-1"></div>
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
    }

    async function start() {
        setControls(true);
        clearError();
        try {
            const profile = document.getElementById('copilotProfile')?.value || 'FULL';
            const operation = await api().start(projectId, requirementId, {
                profile: profile,
                verificationPasses: profile === 'EXHAUSTIVE' ? 2 : 1,
                force: Boolean(document.getElementById('copilotForce')?.checked),
                proposeSolutions: profile !== 'STANDARD',
                proposeProducts: profile !== 'STANDARD'
            });
            renderOperation(operation);
            poll(operation.operationId);
        } catch (error) {
            showError(error);
            setControls(false);
        }
    }

    async function cancel() {
        if (!activeOperationId) return;
        setControls(true);
        try {
            renderOperation(await api().cancel(projectId, activeOperationId));
        } catch (error) {
            showError(error);
        } finally {
            setControls(false);
        }
    }

    async function poll(operationId) {
        if (!operationId || polling) return;
        activeOperationId = operationId;
        polling = true;
        try {
            for (;;) {
                await new Promise(resolve => setTimeout(resolve, 1500));
                const operation = await api().get(projectId, operationId);
                renderOperation(operation);
                if (terminal.has(operation.status)) {
                    if ((operation.status === 'SUCCESS' || operation.status === 'PARTIAL')
                            && operation.selectedSnapshotId) {
                        announce(t('completed'));
                        const url = new URL(window.location.href);
                        url.searchParams.set('snapshot', operation.selectedSnapshotId);
                        url.searchParams.set('lang', locale);
                        window.location.replace(url);
                    }
                    return;
                }
            }
        } catch (error) {
            showError(error);
        } finally {
            polling = false;
            setControls(false);
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
        setControls(false);
    }

    function renderOperation(operation) {
        if (!operation) return;
        activeOperationId = operation.operationId;
        const panel = document.getElementById('copilotOperation');
        panel?.classList.remove('d-none');
        setText('copilotOperationStatus', humanize(operation.status));
        setText('copilotOperationMessage', operation.message || '');
        setText('copilotOperationProgress', operation.completedPasses + ' / '
            + operation.verificationPasses + ' ' + t('pass'));
        setText('copilotOperationProvider', t('provider') + ': ' + (operation.provider || '—'));

        const progress = document.getElementById('copilotProgressBar');
        if (progress) {
            const percentage = Math.round(100 * operation.completedPasses
                / Math.max(1, operation.verificationPasses));
            progress.style.width = percentage + '%';
            progress.setAttribute('aria-valuenow', String(percentage));
        }
        const cancelButton = document.getElementById('copilotCancel');
        if (cancelButton) cancelButton.disabled = terminal.has(operation.status);
        setControls(!terminal.has(operation.status));
        announce((operation.message || '') + ' ' + operation.completedPasses
            + '/' + operation.verificationPasses);
    }

    function setControls(running) {
        const run = document.getElementById('copilotRun');
        const spinner = document.getElementById('copilotRunSpinner');
        if (run) run.disabled = running || !manualCopilotReady;
        spinner?.classList.toggle('d-none', !running);
    }

    function showError(error) {
        const target = document.getElementById('copilotError');
        if (!target) return;
        target.textContent = error?.message || t('failed');
        target.classList.remove('d-none');
        announce(target.textContent);
    }

    function clearError() {
        document.getElementById('copilotError')?.classList.add('d-none');
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

    function escapeHtml(value) {
        return window.TaxonomyUtils.escapeHtml(value);
    }
}());
