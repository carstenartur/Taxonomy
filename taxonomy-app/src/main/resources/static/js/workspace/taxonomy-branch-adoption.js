/** Read and configure adoption for the exact branch selected in the workspace sync panel. */
window.TaxonomyBranchAdoption = (function () {
    'use strict';
    var selection, rule, pending;
    var stopped = ['CONFLICT','PREREQUISITE','AUTHORIZATION'];
    function element(suffix) { return document.getElementById('workspaceAdoption' + suffix); }
    function label(key) { return window.TaxonomyI18n.t('workspace.adoption.' + key); }

    function controls() {
        var context = window.TaxonomyContextBar?.getCurrentContext?.();
        var repository = window.TaxonomyArchitectureWorkflow?.getScope?.()?.repositoryId;
        var workspace = window.__TaxonomyAnalysisSessionContext?.runtime?.workspaceId;
        var editable = selection && context?.mode === 'EDITABLE' && context.branch === selection.branch
            && (!repository || repository === selection.repositoryId)
            && (workspace === undefined || (workspace || '') === selection.workspaceId)
            && context.contextId === selection.contextId && (!selection.workspaceId || !!context.contextId);
        var toggle = element('Toggle'), retry = element('Retry'), skip = element('Skip');
        toggle.hidden = !editable || !rule || (!rule.enabled && (!rule.sourceBranch || (!selection.workspaceId && rule.sourceBranch === rule.branch)));
        if (rule) toggle.textContent = label(rule.enabled ? 'disable' : 'enable');
        retry.hidden = skip.hidden = !editable || !rule?.enabled || !stopped.includes(rule.status) || !rule.sourceCommit;
        element('HistoryButton').hidden = !rule;
        element('HistoryButton').disabled = toggle.disabled = retry.disabled = skip.disabled = !!pending;
    }

    function clear() {
        if (pending) pending.abort();
        selection = rule = pending = null;
        var panel = element('Rule');
        if (!panel) return;
        panel.hidden = true;
        panel.open = false;
        element('Result').textContent = element('Indicator').textContent = '';
        element('History').replaceChildren();
        element('History').hidden = true;
        controls();
    }

    async function update(action) {
        var panel = element('Rule'), current = selection, previous = rule;
        if (!current || pending || !panel || (action && !panel.open)) return;
        controls();
        var button = {TOGGLE:'Toggle',RETRY:'Retry',SKIP:'Skip',HISTORY:'HistoryButton'}[action];
        if (button && element(button).hidden) return;
        var controller = new AbortController();
        pending = controller;
        controls();
        if (!previous) element('Result').textContent = label('loading');
        var options = {signal:controller.signal}, api = window.TaxonomyWorkspaceApi;
        try {
            var result;
            if (action === 'HISTORY') {
                var events = await api.branchAdoptionHistory(current,options);
                if (selection !== current || controller.signal.aborted) return;
                var history = element('History');
                var items = events.map(event => {
                    var item = document.createElement('li');
                    var time = Number.isFinite(event.recordedAt)
                        ? new Date(event.recordedAt).toLocaleString(document.documentElement.lang || undefined) : '';
                    item.textContent = [time, label('status.' + event.status), event.actor,
                        event.sourceCommit && label('source') + ' ' + event.sourceCommit,
                        event.targetBefore && label('before') + ' ' + event.targetBefore,
                        event.targetAfter && label('after') + ' ' + event.targetAfter,
                        event.detail].filter(Boolean).join(' · ');
                    return item;
                });
                if (!items.length) {
                    var empty = document.createElement('li');
                    empty.textContent = label('emptyHistory');
                    items.push(empty);
                }
                history.replaceChildren(...items);
                history.hidden = false;
                return;
            }
            if (!button) result = await api.branchAdoptionRule(current,options);
            else if (action === 'TOGGLE')
                result = await api.setBranchAdoption(current,current.contextId,!previous.enabled,previous.revision,options);
            else result = await api.decideBranchAdoption(current,current.contextId,action,previous.revision,previous.sourceCommit,options);
            if (selection !== current || controller.signal.aborted) return;
            if (!result || result.repositoryId !== current.repositoryId || (result.workspaceId || '') !== current.workspaceId
                    || result.branch !== current.branch || typeof result.enabled !== 'boolean' || !Number.isSafeInteger(result.revision))
                throw new Error('Invalid branch rule response');
            if (previous?.revision !== result.revision) {
                element('History').replaceChildren();
                element('History').hidden = true;
            }
            rule = result;
            element('Indicator').textContent = label(result.enabled ? 'on' : 'off');
            var text = [result.sourceBranch ? result.sourceBranch + ' → ' + result.branch : result.branch,
                label('status.' + result.status),result.sourceCommit,result.detail].filter(Boolean).join(' · ');
            if (element('Result').textContent !== text) element('Result').textContent = text;
        } catch (error) {
            if (selection !== current || controller.signal.aborted) return;
            rule = null;
            element('Indicator').textContent = '';
            element('Result').textContent = label('failed');
        } finally {
            if (pending === controller) { pending = null; controls(); }
        }
    }

    function select(state, context, unavailable) {
        var panel = element('Rule');
        if (!panel) return;
        if (unavailable || !state.repositoryId || !state.branch || !context || context.branch !== state.branch
                || (context.mode === 'READ_ONLY' && context.commitId)) { clear(); return; }
        var next = {repositoryId:state.repositoryId,workspaceId:state.workspaceId || '',branch:state.branch,
            contextId:context.contextId,mode:context.mode};
        if (!selection || Object.keys(next).some(key => next[key] !== selection[key])) {
            clear();
            selection = next;
            panel.hidden = false;
        }
        update();
    }

    document.addEventListener('toggle', function (event) {
        if (event.target === element('Rule') && event.target.open) update();
    },true);
    document.addEventListener('click', function (event) {
        var action = {workspaceAdoptionToggle:'TOGGLE',workspaceAdoptionRetry:'RETRY',workspaceAdoptionSkip:'SKIP',workspaceAdoptionHistoryButton:'HISTORY'}[event.target.id];
        if (action) update(action);
    });
    document.addEventListener('taxonomy-api-auth-failure', clear);
    window.addEventListener('pagehide', clear);
    return Object.freeze({select:select,clear:clear});
}());
