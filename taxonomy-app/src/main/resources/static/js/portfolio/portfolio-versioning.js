(function () {
    'use strict';

    const match = location.pathname.match(/^\/projects\/(\d+)\/versioning$/);
    if (!match) return;

    const projectId = Number(match[1]);
    const locale = (new URLSearchParams(location.search).get('lang')
        || document.documentElement.lang
        || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const state = {
        project: null,
        repository: null,
        exported: null,
        preview: null,
        account: null
    };

    const labels = {
        en: {
            title: 'Versioning & collaboration', portfolio: 'Portfolio', reports: 'Reports',
            preview: 'Current portfolio change preview', refresh: 'Refresh',
            dsl: 'Technical TaxDSL preview', commit: 'Commit portfolio state',
            target: 'Target branch', message: 'Commit message',
            commitAction: 'Commit reviewed state', restore: 'Restore a branch into the portfolio',
            branch: 'Branch', previewRestore: 'Preview materialization',
            applyRestore: 'Apply reviewed materialization', merge: 'Merge portfolio branches',
            source: 'Source branch', mergeTarget: 'Target branch', mergeMessage: 'Merge message',
            mergeAction: 'Merge reviewed branches', clean: 'Repository baseline loaded',
            committed: 'Portfolio committed successfully.', materialized: 'Branch materialized successfully.',
            merged: 'Branches merged successfully.', destructive: 'The target branch removes lines from the current portfolio projection. Review carefully before applying.',
            safe: 'No removed lines were detected in the textual preview.', added: 'Added lines',
            removed: 'Removed lines', expected: 'Expected target HEAD', actual: 'Current target HEAD',
            permission: 'Your role cannot perform Git mutations.', failed: 'The Git operation failed.',
            projects: 'Projects', requirements: 'Requirements', solutions: 'Solutions', products: 'Products',
            sourceTargetDiffer: 'Source and target branch must differ'
        },
        de: {
            title: 'Versionierung & Zusammenarbeit', portfolio: 'Portfolio', reports: 'Berichte',
            preview: 'Vorschau der aktuellen Portfolioänderungen', refresh: 'Aktualisieren',
            dsl: 'Technische TaxDSL-Vorschau', commit: 'Portfoliozustand committen',
            target: 'Zielbranch', message: 'Commitnachricht',
            commitAction: 'Geprüften Stand committen', restore: 'Branch in das Portfolio zurückspielen',
            branch: 'Branch', previewRestore: 'Materialisierung prüfen',
            applyRestore: 'Geprüfte Materialisierung anwenden', merge: 'Portfolio-Branches zusammenführen',
            source: 'Quellbranch', mergeTarget: 'Zielbranch', mergeMessage: 'Mergenachricht',
            mergeAction: 'Geprüfte Branches mergen', clean: 'Repository-Basis geladen',
            committed: 'Das Portfolio wurde erfolgreich committed.', materialized: 'Der Branch wurde erfolgreich materialisiert.',
            merged: 'Die Branches wurden erfolgreich zusammengeführt.', destructive: 'Der Zielbranch entfernt Zeilen aus der aktuellen Portfolioprojektion. Vor dem Anwenden sorgfältig prüfen.',
            safe: 'In der Textvorschau wurden keine entfernten Zeilen erkannt.', added: 'Hinzugefügte Zeilen',
            removed: 'Entfernte Zeilen', expected: 'Erwarteter Ziel-HEAD', actual: 'Aktueller Ziel-HEAD',
            permission: 'Ihre Rolle darf keine Git-Mutationen durchführen.', failed: 'Die Git-Operation ist fehlgeschlagen.',
            projects: 'Projekte', requirements: 'Anforderungen', solutions: 'Lösungen', products: 'Produkte',
            sourceTargetDiffer: 'Quell- und Zielbranch müssen verschieden sein'
        }
    };

    document.addEventListener('DOMContentLoaded', initialize);

    function text(key) {
        return labels[locale][key] || labels.en[key] || key;
    }

    async function initialize() {
        translate();
        wire();
        await load();
    }

    function translate() {
        document.documentElement.lang = locale;
        document.title = `${text('title')} — Taxonomy`;
        document.querySelector('.skip-link').textContent = locale === 'de'
            ? 'Zur Versionierung springen' : 'Skip to versioning';
        document.getElementById('versioningPageTitle').textContent = text('title');
        document.getElementById('versioningHeading').textContent = text('title');
        document.getElementById('portfolioBack').textContent = text('portfolio');
        document.getElementById('portfolioBack').href = `/projects?lang=${locale}`;
        document.getElementById('reportsLink').textContent = text('reports');
        document.getElementById('reportsLink').href = `/projects/${projectId}/reports?lang=${locale}`;
        document.getElementById('previewHeading').textContent = text('preview');
        document.getElementById('refreshPreview').textContent = text('refresh');
        document.querySelector('#dslPreview').previousElementSibling.textContent = text('dsl');
        document.getElementById('commitHeading').textContent = text('commit');
        document.querySelector('label[for="commitBranch"]').textContent = text('target');
        document.querySelector('label[for="commitMessage"]').textContent = text('message');
        document.querySelector('#commitForm button[type="submit"]').textContent = text('commitAction');
        document.getElementById('materializeHeading').textContent = text('restore');
        document.querySelector('label[for="materializeBranch"]').textContent = text('branch');
        document.getElementById('previewMaterialize').textContent = text('previewRestore');
        document.getElementById('applyMaterialize').textContent = text('applyRestore');
        document.getElementById('mergeHeading').textContent = text('merge');
        document.querySelector('label[for="mergeSource"]').textContent = text('source');
        document.querySelector('label[for="mergeTarget"]').textContent = text('mergeTarget');
        document.querySelector('label[for="mergeMessage"]').textContent = text('mergeMessage');
        document.querySelector('#mergeForm button[type="submit"]').textContent = text('mergeAction');
    }

    function wire() {
        document.getElementById('refreshPreview').addEventListener('click', load);
        document.getElementById('commitForm').addEventListener('submit', commitPortfolio);
        document.getElementById('previewMaterialize').addEventListener('click', previewMaterialize);
        document.getElementById('applyMaterialize').addEventListener('click', applyMaterialize);
        document.getElementById('mergeForm').addEventListener('submit', mergeBranches);
        document.getElementById('materializeBranch').addEventListener('change', clearMaterializationPreview);
    }

    async function load() {
        busy(true);
        clearMessages();
        try {
            [state.project, state.repository, state.exported, state.account] = await Promise.all([
                api(`/api/projects/${projectId}`),
                api('/api/git/state'),
                api('/api/projects/git/export'),
                api('/api/account/me')
            ]);
            render();
        } catch (problem) {
            showError(problem);
        } finally {
            busy(false);
        }
    }

    function render() {
        document.getElementById('versioningProject').textContent =
            `${state.project.projectKey} — ${state.project.title}`;
        document.getElementById('activeBranch').textContent =
            state.repository.currentBranch || state.exported.activeBranch || '—';
        document.getElementById('headCommit').textContent = short(
            state.repository.headCommit || state.exported.headCommit);
        document.getElementById('workspaceId').textContent = state.exported.workspaceId || 'shared';
        document.getElementById('dslPreview').textContent = state.exported.dsl;
        document.getElementById('changeState').textContent = text('clean');
        renderCounts();
        populateBranches();
        document.getElementById('commitMessage').value =
            `Portfolio ${state.project.projectKey}: reviewed project state`;
        document.getElementById('mergeMessage').value =
            `Merge portfolio branches for ${state.project.projectKey}`;
        applyCapabilities();
    }

    function renderCounts() {
        const target = document.getElementById('portfolioCounts');
        target.innerHTML = metric(text('projects'), state.exported.projectCount)
            + metric(text('requirements'), state.exported.requirementCount)
            + metric(text('solutions'), state.exported.solutionCount)
            + metric(text('products'), state.exported.productCount);
    }

    function metric(label, value) {
        return `<div><div class="card h-100"><div class="card-body">`
            + `<div class="h3">${Number(value || 0)}</div>`
            + `<div class="small text-body-secondary">${escapeHtml(label)}</div>`
            + `</div></div></div>`;
    }

    function populateBranches() {
        const branches = (state.repository.branches || []).map(branch => branch.name).filter(Boolean);
        if (state.repository.currentBranch && !branches.includes(state.repository.currentBranch)) {
            branches.unshift(state.repository.currentBranch);
        }
        ['commitBranch', 'materializeBranch', 'mergeSource', 'mergeTarget'].forEach(id => {
            const select = document.getElementById(id);
            const previous = select.value;
            select.textContent = '';
            branches.forEach(branch => {
                const option = document.createElement('option');
                option.value = branch;
                option.textContent = branch;
                select.appendChild(option);
            });
            if (previous && branches.includes(previous)) select.value = previous;
        });
        document.getElementById('commitBranch').value = state.repository.currentBranch;
        document.getElementById('materializeBranch').value = state.repository.currentBranch;
        document.getElementById('mergeTarget').value = state.repository.currentBranch;
        const source = branches.find(branch => branch !== state.repository.currentBranch);
        if (source) document.getElementById('mergeSource').value = source;
    }

    async function commitPortfolio(event) {
        event.preventDefault();
        busy(true);
        clearMessages();
        try {
            const branch = document.getElementById('commitBranch').value;
            const message = document.getElementById('commitMessage').value.trim();
            const result = await mutate('/api/projects/git/commit', { branch, message });
            showInfo(`${text('committed')} ${branch} @ ${short(result.commitId)}`);
            await load();
        } catch (problem) {
            showError(problem);
        } finally {
            busy(false);
        }
    }

    async function previewMaterialize() {
        busy(true);
        clearMessages();
        try {
            const branch = document.getElementById('materializeBranch').value;
            state.preview = await api(
                `/api/projects/git/materialize-preview?branch=${encodeURIComponent(branch)}`);
            const target = document.getElementById('materializePreview');
            target.innerHTML = `<dl class="portfolio-card-meta">`
                + `<dt>${escapeHtml(text('expected'))}</dt>`
                + `<dd><code>${escapeHtml(short(state.preview.targetHead))}</code></dd>`
                + `<dt>${escapeHtml(text('added'))}</dt><dd>${state.preview.addedLines}</dd>`
                + `<dt>${escapeHtml(text('removed'))}</dt><dd>${state.preview.removedLines}</dd>`
                + `</dl><div class="alert ${state.preview.destructiveChangePossible
                    ? 'alert-warning' : 'alert-success'} mt-3">`
                + `${escapeHtml(state.preview.destructiveChangePossible
                    ? text('destructive') : text('safe'))}</div>`
                + previewLines(text('added'), state.preview.addedPreview, 'text-success')
                + previewLines(text('removed'), state.preview.removedPreview, 'text-danger');
            document.getElementById('applyMaterialize').classList.toggle(
                'd-none', !state.preview.changed);
        } catch (problem) {
            showError(problem);
        } finally {
            busy(false);
        }
    }

    function previewLines(title, lines, css) {
        if (!lines || !lines.length) return '';
        return `<details class="mt-2"><summary>${escapeHtml(title)}</summary>`
            + `<pre class="border rounded p-2 mt-2 ${css}">${escapeHtml(lines.join('\n'))}</pre>`
            + `</details>`;
    }

    async function applyMaterialize() {
        if (!state.preview) return;
        if (!window.confirm(state.preview.destructiveChangePossible
            ? text('destructive') : text('previewRestore'))) return;

        busy(true);
        clearMessages();
        try {
            const branch = document.getElementById('materializeBranch').value;
            const result = await mutate('/api/projects/git/materialize', {
                branch,
                expectedHead: state.preview.targetHead
            });
            showInfo(`${text('materialized')} ${result.branch} @ ${short(result.commitId)}`);
            clearMaterializationPreview();
            await load();
        } catch (problem) {
            showError(problem);
        } finally {
            busy(false);
        }
    }

    async function mergeBranches(event) {
        event.preventDefault();
        const sourceBranch = document.getElementById('mergeSource').value;
        const targetBranch = document.getElementById('mergeTarget').value;
        if (sourceBranch === targetBranch) {
            showError(new Error(text('sourceTargetDiffer')));
            return;
        }

        busy(true);
        clearMessages();
        try {
            const result = await mutate('/api/projects/git/merge', {
                sourceBranch,
                targetBranch,
                message: document.getElementById('mergeMessage').value.trim()
            });
            document.getElementById('mergeResult').innerHTML =
                `<div class="alert alert-success">${escapeHtml(text('merged'))} `
                + `<strong>${escapeHtml(result.strategy)}</strong> · `
                + `<code>${escapeHtml(short(result.mergeCommitId))}</code></div>`;
            showInfo(text('merged'));
            await load();
        } catch (problem) {
            showError(problem);
        } finally {
            busy(false);
        }
    }

    function clearMaterializationPreview() {
        state.preview = null;
        document.getElementById('materializePreview').textContent = '';
        document.getElementById('applyMaterialize').classList.add('d-none');
    }

    function applyCapabilities() {
        if (state.account && state.account.architectureMutationAllowed) return;
        document.querySelectorAll('#commitForm button,#applyMaterialize,#mergeForm button')
            .forEach(button => {
                button.disabled = true;
                button.title = text('permission');
            });
    }

    async function api(path) {
        const response = await fetch(path, {
            headers: { Accept: 'application/json' },
            credentials: 'same-origin',
            cache: 'no-store'
        });
        if (!response.ok) throw await responseError(response);
        return response.json();
    }

    async function mutate(path, body) {
        const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
        csrf(headers);
        const response = await fetch(path, {
            method: 'POST',
            headers,
            credentials: 'same-origin',
            body: JSON.stringify(body)
        });
        if (!response.ok) throw await responseError(response);
        return response.json();
    }

    async function responseError(response) {
        const problem = await response.json().catch(() => null);
        return new Error(problem?.detail || problem?.message || problem?.error
            || `${text('failed')} HTTP ${response.status}`);
    }

    function csrf(headers) {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const name = document.querySelector('meta[name="_csrf_header"]')?.content
            || 'X-CSRF-TOKEN';
        if (token) headers[name] = token;
    }

    function busy(active) {
        document.getElementById('versioningBusy').classList.toggle('d-none', !active);
    }

    function clearMessages() {
        ['versioningError', 'versioningInfo'].forEach(id => {
            const element = document.getElementById(id);
            element.classList.add('d-none');
            element.textContent = '';
        });
    }

    function showError(problem) {
        const target = document.getElementById('versioningError');
        target.textContent = problem?.message || text('failed');
        target.classList.remove('d-none');
        target.focus();
        document.getElementById('versioningLive').textContent = target.textContent;
    }

    function showInfo(message) {
        const target = document.getElementById('versioningInfo');
        target.textContent = message;
        target.classList.remove('d-none');
        document.getElementById('versioningLive').textContent = message;
    }

    function short(value) {
        return value ? String(value).slice(0, 12) : '—';
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#39;');
    }
}());
