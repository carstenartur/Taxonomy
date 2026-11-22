/* Free-form questions on the server-resolved source selected in the architecture workspace. */
window.TaxonomyArchitectureDialogue = (function () {
    'use strict';
    function mount(readPin) {
        const el = id => document.getElementById('architectureDialogue' + id);
        const form = el('Form');
        if (!form) return null;
        const t = (...args) => window.TaxonomyI18n.t(...args);
        let key = '', hash = null, history = [], pending = null;
        function reset() {
            pending?.abort(); pending = null; key = ''; hash = null; history = [];
            el('Turns').replaceChildren(); el('Question').value = ''; el('Status').textContent = ''; el('Send').disabled = false;
        }
        function scopeId(pin) {
            const value = pin?.source || pin?.context;
            return value && (value.workspaceId !== undefined ? value.workspaceId ?? ''
                : value.workspaceScopeKey === '__shared__' ? '' : value.workspaceScopeKey);
        }
        function identity(pin) { return pin ? JSON.stringify([pin, scopeId(pin)]) : ''; }
        function check() { if (key && key !== identity(readPin())) reset(); }
        function append(parent, tag, value) {
            const node = document.createElement(tag); node.textContent = value; parent.append(node); return node;
        }
        function show(question, answer) {
            const turn = document.createElement('li');
            append(turn, 'strong', question);
            for (const statement of answer.statements) {
                append(turn, 'p', statement.text);
                const details = document.createElement('details');
                append(details, 'summary', t('dialogue.evidence'));
                for (const fact of statement.evidence) {
                    append(details, 'cite', fact.source);
                    append(details, 'blockquote', fact.text);
                }
                turn.append(details);
            }
            if (!answer.statements.length) append(turn, 'p', t('dialogue.insufficient'));
            append(turn, 'small', t('dialogue.coverage', answer.provider, answer.suppliedFacts, answer.totalFacts));
            el('Turns').append(turn);
        }
        form.addEventListener('submit', async event => {
            event.preventDefault();
            const pin = readPin(), question = el('Question').value.trim();
            if (!pin || !question) { el('Status').textContent = t('dialogue.source'); return; }
            if (pending) return;
            if (key && key !== identity(pin)) reset();
            key = identity(pin);
            const controller = new AbortController(); pending = controller;
            const current = () => pending === controller && identity(readPin()) === key;
            el('Send').disabled = true; el('Status').textContent = t('dialogue.wait');
            try {
                const value = pin.source || pin.context, workspace = scopeId(pin);
                const query = new URLSearchParams({repositoryId:value.repositoryId, workspaceId:workspace, branch:value.branch});
                const body = Object.assign({}, pin, {sourceHash:hash, question, history, language:document.documentElement.lang.startsWith('de') ? 'de' : 'en'});
                const adapter = window.AnalysisAdapterApi;
                const response = await window.TaxonomyApiClient.request('/api/architecture/dialogue?' + query, {
                    method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body), signal:controller.signal
                }, {retries:0, timeoutMillis:120000, pinnedWorkspaceId:workspace, signal:controller.signal,
                    isCurrent:current, observationSurface:'architecture-dialogue', observeAdmission:adapter.admission,
                    observeResponse:adapter.follow, operationError:adapter.describe});
                const answer = await response.json();
                if (!current()) { check(); return; }
                hash = answer.sourceHash; show(question, answer);
                const summary = answer.statements.map(s => s.text).join('\n') || t('dialogue.insufficient');
                history = history.concat({question, answer:summary}).slice(-4);
                el('Question').value = ''; el('Status').textContent = ''; el('Question').focus();
            } catch (error) {
                if (!current()) { check(); return; }
                el('Status').textContent = error.status === 503 ? t('dialogue.provider') : error.message;
            } finally {
                if (pending === controller) { pending = null; el('Send').disabled = false; }
            }
        });
        for (const name of ['architecture-diagram-updated','architecture-source-changed','analysis-draft-saved',
            'analysis-draft-reset','analysis-invalidated','analysis-draft-restored']) document.addEventListener('taxonomy:' + name, check);
        return {reset};
    }
    return {mount};
}());
