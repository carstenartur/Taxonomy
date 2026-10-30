/* One visible architecture stand. Reads and dialog intents are fenced to that stand. */
(function () {
    'use strict';
    var api = window.TaxonomyArchitectureLifecycleApi;
    var root, summary, status, rows, historyList, historySearch, disclosure, currentButton, reconcileButton;
    var listeners = new Set();
    var pendingRequirements = new Map();
    var subjectKey = null;
    var state = {overview:null, sequence:0, generation:0, selection:selectionFromUrl(), context:null, observed:null, history:null};
    function t(key) { return window.TaxonomyI18n.t.apply(null, arguments); }
    function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }
    function scope() { return window.TaxonomyArchitectureWorkflow?.getScope() || {}; }
    function scopeKey(value) {
        value = value || {};
        return JSON.stringify([value.repositoryId || '', value.workspaceScopeKey || value.workspaceId || '__shared__', value.branch || '']);
    }
    function contextKey(value) {
        value = value || {};
        return JSON.stringify([scopeKey(value), value.commit || null, value.revision, value.actor, value.writeMode]);
    }
    function selectionOf(value) {
        if (value == null) return {commit:null, revision:null};
        if (typeof value === 'object') return {commit:value.commit || null, revision:value.revision == null ? null : String(value.revision)};
        return /^[a-f0-9]{40}$/i.test(String(value)) ? {commit:String(value), revision:null} : {commit:null, revision:String(value)};
    }
    function selectionFromUrl() {
        var params = new URLSearchParams(location.search);
        return selectionOf({commit:params.get('commit'), revision:params.get('revision')});
    }
    function isEditor() { return Boolean(document.getElementById('architectureEditor')); }
    function signalStandChange() { listeners.forEach(function (listener) { listener(); }); }
    function invalidateStand() { state.generation++; signalStandChange(); }
    function historicalSelection() {
        return Boolean(state.selection.commit || state.observed?.projectionState === 'HISTORICAL'
            || state.overview?.projectionState === 'HISTORICAL'
            || (state.selection.revision !== null && !state.overview));
    }
    function clearHistory() {
        state.history = null;
        if (historyList) { historyList.replaceChildren(); historyList.hidden = true; }
    }
    function resetOverview() {
        state.overview = null;
        if (rows) rows.replaceChildren();
        if (summary) summary.textContent = t('lifecycle.title');
        if (reconcileButton) reconcileButton.hidden = true;
        window.TaxonomyAppChrome?.updateContext({architecture:{modelStand:null}, editable:false, publicationState:null, realizationStatus:null});
    }
    function beginSelection(context, selection) {
        var next = selectionOf(selection);
        var changed = scopeKey(context) !== scopeKey(state.context) || JSON.stringify(next) !== JSON.stringify(state.selection);
        ++state.sequence;
        state.context = clone(context || scope()); state.selection = next; state.observed = null;
        resetOverview(); if (changed) clearHistory(); invalidateStand();
        window.TaxonomyAppChrome?.updateContext({architecture:{repositoryId:state.context.repositoryId,workspaceScopeKey:state.context.workspaceScopeKey || state.context.workspaceId,branch:state.context.branch,modelStand:null,commit:null,revision:null}});
        if (root) root.hidden = true;
    }
    function failedSelection(error) {
        ++state.sequence; state.observed = null; resetOverview(); clearHistory(); invalidateStand();
        window.TaxonomyAppChrome?.updateContext({editable:false, publicationState:'UNAVAILABLE', architecture:{modelStand:null}});
        if (root) { root.hidden = false; status.textContent = errorText(error); currentButton.hidden = false; }
    }
    async function refresh(context, selection) {
        var requested = clone(context || state.context || scope());
        var selected = arguments.length > 1 ? selectionOf(selection) : state.selection;
        var changed = scopeKey(requested) !== scopeKey(state.context) || JSON.stringify(selected) !== JSON.stringify(state.selection);
        var ticket = ++state.sequence;
        state.context = requested; state.selection = selected;
        if (changed) { state.observed = null; resetOverview(); clearHistory(); invalidateStand(); }
        try {
            var value = await api.overview(requested, selected.commit, selected.revision);
            if (ticket !== state.sequence) return null;
            if (state.observed) value.mayEdit = Boolean(value.mayEdit && state.observed.mayEdit);
            if (state.overview && (contextKey(value.context) !== contextKey(state.overview.context)
                    || value.source !== state.overview.source || value.mayEdit !== state.overview.mayEdit)) invalidateStand();
            state.overview = value; state.context = clone(value.context);
            window.TaxonomyArchitectureWorkflow?.setScope(value.context);
            render(); signalStandChange();
            return value;
        } catch (error) {
            if (ticket !== state.sequence) return null;
            failedSelection(error); return null;
        }
    }
    function observe(documentValue, mayEdit) {
        if (!documentValue?.context) return;
        var selected = documentValue.source === 'GIT_CHECKPOINT'
            ? selectionOf(documentValue.context.commit) : selectionOf(documentValue.context.revision);
        state.observed = {projectionState:documentValue.projectionState, source:documentValue.source, mayEdit:Boolean(mayEdit)};
        state.context = clone(documentValue.context); state.selection = selected;
        return refresh(documentValue.context, selected);
    }
    function requestStand(selection) {
        var selected = selectionOf(selection);
        if (isEditor()) {
            document.dispatchEvent(new CustomEvent('taxonomy:architecture-stand-requested', {detail:{context:clone(state.context || scope()), selection:selected}}));
            return;
        }
        var url = new URL(location.href);
        url.searchParams.delete('commit'); url.searchParams.delete('revision');
        if (selected.commit) url.searchParams.set('commit', selected.commit);
        if (selected.revision !== null) url.searchParams.set('revision', selected.revision);
        window.history.pushState(null, '', url); refresh(undefined, selected);
    }
    function captureStand() {
        return {generation:state.generation, context:clone(state.overview?.context), source:state.overview?.source, selection:JSON.stringify(state.selection), subject:subjectKey};
    }
    function matchesStand(captured, requireEditable) {
        return Boolean(captured.context && state.overview && captured.generation === state.generation
            && scopeKey(scope()) === scopeKey(captured.context)
            && contextKey(state.context) === contextKey(captured.context)
            && contextKey(state.overview.context) === contextKey(captured.context)
            && captured.source === state.overview.source && captured.selection === JSON.stringify(state.selection)
            && !historicalSelection() && (!requireEditable || state.overview.mayEdit));
    }
    function matchesRetryScope(captured) {
        var current = state.overview;
        return Boolean(captured.context && current && !historicalSelection()
            && captured.subject === subjectKey && captured.source === current.source
            && ['WORKSPACE_REVISION','WORKSPACE_AND_PORTFOLIO'].includes(current.source)
            && scopeKey(scope()) === scopeKey(captured.context)
            && scopeKey(state.context) === scopeKey(captured.context)
            && scopeKey(current.context) === scopeKey(captured.context)
            && current.context.actor === captured.context.actor && current.context.writeMode === captured.context.writeMode
            && (current.mayEdit || ['CHECKPOINT_PENDING','VERSION_CHANGED','RECONCILIATION_REQUIRED'].includes(current.projectionState)));
    }
    function errorText(error) { return error?.responseBody?.detail || error?.message || t('lifecycle.failed'); }
    function staleError() { return new Error(t('lifecycle.stale')); }
    function node(tag, text, className) {
        var value = document.createElement(tag); if (text != null) value.textContent = text;
        if (className) value.className = className; return value;
    }
    function button(label, callback) {
        var value = node('button', label); value.type = 'button'; value.addEventListener('click', callback); return value;
    }
    function metadata(reason) { var id = crypto.randomUUID(); return {commandId:id, correlationId:id, causationId:id, rationale:reason}; }
    function publicationOf(result) { return result?.publication || result; }
    function pendingPublication(result) { return publicationOf(result)?.state === 'CHECKPOINT_PENDING'; }
    function showPublishedCurrentStand(context) {
        if (!context || historicalSelection() || scopeKey(context) !== scopeKey(state.context)
                || scopeKey(scope()) !== scopeKey(state.context)) return;
        var url = new URL(location.href); url.searchParams.delete('commit'); url.searchParams.delete('revision');
        window.history.replaceState(null,'',url);
        window.TaxonomyAppChrome?.refreshNavigation();
        refresh(context,null);
    }
    function publishChanged(result, reconciled) {
        document.dispatchEvent(new CustomEvent(reconciled ? 'taxonomy:architecture-reconciled' : 'taxonomy:lifecycle-changed', {detail:result}));
        if (!isEditor() && reconciled) showPublishedCurrentStand(publicationOf(result)?.context);
    }
    function selectedProject(value, context) {
        var explicit = Object.prototype.hasOwnProperty.call(context,'project');
        var projectId = explicit ? context.project?.id : new URLSearchParams(location.search).get('projectId');
        var selected = (value.projects || []).find(function (project) { return projectId != null && String(project.id) === String(projectId); });
        var projectKey = context.project?.key || selected?.projectKey;
        if (!projectKey && projectId != null && String(context.source?.projectId) === String(projectId)) projectKey = context.source.projectKey;
        return {id:projectId,key:projectKey,project:selected};
    }
    function filteredRequirements(value) {
        var context = window.TaxonomyAppChrome?.getContext() || {};
        var selected = selectedProject(value,context);
        return (value.requirements || []).filter(function (requirement) {
            return (!selected.id && !selected.key) || (selected.id != null && String(requirement.projectId) === String(selected.id))
                || (selected.key && requirement.projectKey === selected.key)
                || (requirement.contributors || []).some(function (value) {
                    return (selected.id != null && String(value.projectId || value.id || value) === String(selected.id))
                        || (selected.key && (value.projectKey || value) === selected.key);
                });
        });
    }
    function renderContext(value) {
        var c = value.context || {};
        var stand = value.source === 'GIT_CHECKPOINT' ? t('editor.selectedVersion', c.commit?.substring(0, 12) || '') : t('editor.revision', c.revision);
        var facts = {architecture:{repositoryId:c.repositoryId, title:value.architectureTitle, branch:c.branch,
            workspaceScopeKey:c.workspaceScopeKey, modelStand:stand, commit:c.commit, revision:c.revision}, editable:Boolean(value.mayEdit), publicationState:value.publicationState || value.projectionState};
        var current = window.TaxonomyAppChrome?.getContext() || {};
        var project = selectedProject(value,current).project;
        if (!Object.prototype.hasOwnProperty.call(current,'project') && project && !historicalSelection()) facts.project = {id:project.id,key:project.projectKey,title:project.title};
        var requirement = (value.requirements || []).find(function (item) { return String(item.requirementId) === String(current.requirement?.id); });
        // Historical source evidence must not relabel the current business text version.
        if (requirement && !historicalSelection()) facts.requirement = {version:requirement.version, versionId:requirement.versionId};
        if (requirement) facts.realizationStatus = requirement.realization?.state || null;
        window.TaxonomyAppChrome?.updateContext(facts);
    }
    function render() {
        if (!root || !state.overview) return;
        var value = state.overview;
        var visible = filteredRequirements(value);
        summary.textContent = t('lifecycle.summary', visible.length);
        status.textContent = t('lifecycle.state.' + (value.projectionState || value.publicationState || 'READY'));
        currentButton.hidden = !historicalSelection() && !['VERSION_CHANGED','RECONCILIATION_REQUIRED','CHECKPOINT_PENDING'].includes(value.projectionState);
        reconcileButton.hidden = historicalSelection() || !['VERSION_CHANGED','RECONCILIATION_REQUIRED'].includes(value.projectionState);
        rows.replaceChildren();
        visible.forEach(function (requirement) {
            var row = node('tr'); row.dataset.requirementId = requirement.requirementId; row.dataset.requirementKey = requirement.requirementKey;
            var identity = node('td');
            var link = node(requirement.projectId && requirement.requirementId ? 'a' : 'span', [requirement.requirementKey, requirement.title].filter(Boolean).join(' · '));
            if (link.tagName === 'A') link.href = window.TaxonomyArchitectureWorkflow?.scopeUrl(window.TaxonomyI18n.resolveUrl('/projects/' + requirement.projectId + '/requirements/' + requirement.requirementId)) || '#';
            identity.append(link);
            var versionCell = node('td',String(requirement.version)); versionCell.dataset.lifecycleVersion = '';
            var reviewed = requirement.realization?.reviewed;
            var reviewedCell = node('td',reviewed ? String(reviewed.requirementVersion) : t('lifecycle.unreviewed')); reviewedCell.dataset.lifecycleReviewedVersion = '';
            var realization = node('td');
            var code = requirement.realization?.state || 'OPEN';
            realization.dataset.state = code; realization.textContent = t('lifecycle.realization.' + code);
            var action = node('td');
            var review = button(t('lifecycle.review'), function () { openReview(requirement).catch(function (error) { status.textContent = errorText(error); }); });
            review.dataset.lifecycleReview = requirement.requirementId; review.dataset.lifecycleAction = 'review'; review.disabled = !value.mayEdit || historicalSelection();
            var rowHistory = button(t('lifecycle.history'), function () { loadHistory(0,null,{projectKey:requirement.projectKey,requirementKey:requirement.requirementKey,text:historySearch.value.trim()}); });
            rowHistory.dataset.lifecycleAction = 'history';
            var evidence = node('details'); evidence.append(node('summary',t('lifecycle.evidence')));
            var facts = node('dl');
            function fact(label,value) { if (value !== undefined && value !== null) facts.append(node('dt',t('lifecycle.'+label)),node('dd',String(value))); }
            fact('contentHash',requirement.contentHash);
            if (reviewed) {
                fact('reviewedVersion',reviewed.requirementVersion); fact('reviewedHash',reviewed.contentHash);
                fact('contributor',reviewed.contributorProject); fact('actor',reviewed.actor); fact('occurredAt',reviewed.occurredAt); fact('reason',reviewed.rationale);
                var dependencies = node('ul'); (reviewed.dependencies || []).forEach(function (dependency) { dependencies.append(node('li',dependency.id+' · '+dependency.hash)); });
                facts.append(node('dt',t('lifecycle.dependencies')),node('dd')); facts.lastChild.append(dependencies);
            }
            fact('changedDependencies',(requirement.realization?.changedDependencies || []).join(', '));
            evidence.append(facts);
            var contributors = node('ul'); (requirement.contributors || []).forEach(function (value) { contributors.append(node('li',[value.projectKey,t('lifecycle.version',value.requirementVersion),value.contentHash].join(' · '))); });
            evidence.append(node('h3',t('lifecycle.contributions')),contributors);
            var links = node('ul'); (requirement.links || []).forEach(function (value) { links.append(node('li',[value.projectKey,value.requirementKey,t('lifecycle.version',value.requirementVersion),value.contentHash].join(' · '))); });
            evidence.append(node('h3',t('lifecycle.related')),links);
            identity.append(evidence);
            action.append(review,rowHistory); row.append(identity, versionCell, reviewedCell, realization, action); rows.append(row);
        });
        root.hidden = false; renderContext(value);
    }
    async function loadHistory(offset, expectedHead, filters) {
        if (!state.overview) return;
        var current = window.TaxonomyAppChrome?.getContext() || {};
        var paired = current.requirement?.key && current.project?.key;
        var selectedFilters = filters || {projectKey:paired ? current.project.key : null,requirementKey:paired ? current.requirement.key : null,text:historySearch.value.trim()};
        var token = {context:clone(state.context), generation:state.generation, filters:clone(selectedFilters)}; state.history = token;
        historyList.hidden = false; historyList.textContent = t('lifecycle.loading');
        try {
            var result = await api.history(token.context, {projectKey:token.filters.projectKey, requirementKey:token.filters.requirementKey,text:token.filters.text,
                expectedHead:expectedHead || undefined, offset:offset || 0, limit:25, scanLimit:500});
            if (state.history !== token || token.generation !== state.generation) return;
            historyList.replaceChildren();
            historyList.append(node('p', t('lifecycle.history.coverage.' + result.coverage)));
            var list = node('ol');
            (result.entries || result.commits || []).forEach(function (entry) {
                var item = node('li'); var message = entry.message || entry.fullMessage || entry.subject || '';
                var subject = message.split(/\r?\n/, 1)[0];
                var commit = entry.commitId || entry.commit || entry.id;
                var link = node('a', subject || (commit || '').substring(0, 12));
                var url = new URL(window.TaxonomyI18n.resolveUrl('/architecture/editor'), location.href);
                Object.entries(token.context).forEach(function (pair) { if (['repositoryId','workspaceScopeKey','branch'].includes(pair[0])) url.searchParams.set(pair[0],pair[1]); });
                url.searchParams.set('workspaceId',token.context.workspaceScopeKey === '__shared__' ? '' : token.context.workspaceScopeKey);
                url.searchParams.set('commit',commit); url.searchParams.set('lang',window.TaxonomyI18n.getLocale()); link.href = url.pathname + url.search;
                var details = node('details'); details.append(node('summary', t('lifecycle.history.details')),
                    node('pre', message), node('p', [entry.author, entry.timestamp || entry.occurredAt, commit].filter(Boolean).join(' · ')));
                details.append(node('p',t('lifecycle.history.parents') + ': ' + (entry.parents || []).join(', ')),
                    node('p',t('lifecycle.history.signature') + ': ' + JSON.stringify(entry.signature ?? null)),
                    node('pre',JSON.stringify(entry.changes || [],null,2)));
                item.append(link, details); list.append(item);
            });
            historyList.append(list);
            if (result.hasMore || result.nextOffset != null) historyList.append(button(t('lifecycle.history.more'), function () { loadHistory(result.nextOffset == null ? (offset || 0) + 25 : result.nextOffset, result.head, token.filters); }));
        } catch (error) { if (state.history === token) historyList.textContent = errorText(error); }
    }
    function dialogShell(id, title) {
        var dialog = node('dialog', null, 'taxonomy-lifecycle__dialog'); dialog.id = id;
        var heading = node('h2', title); heading.id = id + 'Title'; dialog.setAttribute('aria-labelledby', heading.id);
        var form = node('form'); form.addEventListener('submit', function (event) { event.preventDefault(); });
        var message = node('p'); message.setAttribute('role', 'status'); message.dataset.lifecycleMessage = '';
        var close = button(t('lifecycle.close'), function () { dialog.close(); });
        close.dataset.lifecycleClose = ''; dialog.append(heading, form, message, close); document.body.append(dialog);
        dialog.addEventListener('close', function () { dialog.remove(); });
        return {dialog:dialog, form:form, message:message, close:close};
    }
    function field(form, id, label, tag) {
        var labelNode = node('label', label); labelNode.htmlFor = id;
        var input = node(tag || 'input'); input.id = id; form.append(labelNode, input); return input;
    }
    function option(select, value, label) { var item = node('option', label); item.value = value; select.append(item); }
    // Both reviewed realizations and reconciliation share this stand fence. Reconciliation
    // deliberately permits VERSION_CHANGED, whose mayEdit is false, but never a historical stand.
    function intentFence(captured, shell, preview, accept, editable) {
        var pending = null, uncertain = false;
        function recoverable() {
            return Boolean(pending?.context && state.overview && !historicalSelection()
                && ['WORKSPACE_REVISION','WORKSPACE_AND_PORTFOLIO'].includes(state.overview.source)
                && state.overview.projectionState === 'CHECKPOINT_PENDING'
                && scopeKey(scope()) === scopeKey(captured.context)
                && contextKey(state.overview.context) === contextKey(pending.context));
        }
        function valid() { return matchesStand(captured, editable) || recoverable() || (uncertain && matchesRetryScope(captured)); }
        function ensure() {
            if (valid()) return true;
            preview.disabled = true; accept.disabled = true; accept.hidden = true;
            shell.message.textContent = t('lifecycle.stale'); return false;
        }
        function changed() {
            if (!ensure()) return;
            if (pending || uncertain) { accept.hidden = false; accept.disabled = false; preview.disabled = true; accept.textContent = t(uncertain ? 'lifecycle.retryUnknown' : 'lifecycle.retry'); }
        }
        listeners.add(changed); shell.dialog.addEventListener('close', function () { listeners.delete(changed); });
        return {valid:valid, ensure:ensure, pending:function (publication) { pending = clone(publication); },
            uncertain:function () { uncertain = true; changed(); },
            rejected:function () { uncertain = false; pending = null; },
            afterSuccess:function (result, reconciled) {
                var current = valid();
                if (pendingPublication(result)) {
                    pending = clone(publicationOf(result)); shell.message.textContent = t('lifecycle.pending');
                    if (current) publishChanged(result, reconciled); else ensure();
                    return false;
                }
                shell.message.textContent = t('lifecycle.saved') + (current ? '' : ' ' + t('lifecycle.stale'));
                if (current) { shell.dialog.close(); publishChanged(result, reconciled); }
                else { preview.disabled = true; accept.disabled = true; accept.hidden = true; }
                return true;
            }};
    }
    async function openReview(requirement) {
        var captured = captureStand();
        if (!matchesStand(captured, true)) throw staleError();
        var projects = await api.projects(captured.context);
        if (!matchesStand(captured, true)) throw staleError();
        var detail = await api.requirement(captured.context, requirement.projectId, requirement.requirementId);
        if (!matchesStand(captured, true)) throw staleError();
        var current = detail.requirement || detail;
        var version = current.currentVersion || current;
        var shell = dialogShell('lifecycleReviewDialog', t('lifecycle.review'));
        var disposition = field(shell.form, 'lifecycleReviewStatus', t('lifecycle.disposition'), 'select');
        ['OPEN','PARTIAL','COMPLETE'].forEach(function (value) { option(disposition, value, t('lifecycle.realization.' + value)); });
        var contributor = field(shell.form, 'lifecycleReviewProject', t('lifecycle.contributor'), 'select');
        (projects || []).forEach(function (project) { option(contributor, project.id, [project.projectKey,project.title].filter(Boolean).join(' · ')); });
        contributor.value = requirement.projectId;
        var dependencies = field(shell.form, 'lifecycleReviewDependencies', t('lifecycle.dependencies'), 'select'); dependencies.multiple = true;
        (state.overview.dependencies || []).forEach(function (dependency) { option(dependencies, dependency.id, dependency.title || dependency.id); });
        var reason = field(shell.form, 'lifecycleReviewReason', t('lifecycle.reason'), 'textarea'); reason.required = true; reason.maxLength = 1000;
        var preview = button(t('lifecycle.preview'), previewReview); preview.id = 'lifecycleReviewPreview';
        var accept = button(t('lifecycle.accept'), acceptReview); accept.id = 'lifecycleReviewAccept'; accept.hidden = true;
        var changes = node('pre'); changes.dataset.lifecyclePreview = ''; shell.form.append(preview, changes, accept);
        var fence = intentFence(captured, shell, preview, accept, true);
        var prepared = null, edit = 0, busy = false, locked = false;
        shell.form.addEventListener('input', invalidate); shell.form.addEventListener('change', invalidate);
        function invalidate() { if (locked) return; edit++; prepared = null; accept.hidden = true; changes.textContent = ''; }
        async function previewReview() {
            if (busy || locked || !fence.ensure() || !shell.form.reportValidity()) return;
            var ticket = edit; busy = true; preview.disabled = true; accept.hidden = true;
            var request = {context:clone(captured.context), metadata:metadata(reason.value), projectId:requirement.projectId,
                requirementId:requirement.requirementId, expectedVersionId:version.id || requirement.versionId,
                expectedContentHash:version.contentHash || requirement.contentHash,
                contributorProjectId:Number(contributor.value), status:disposition.value,
                dependencyIds:Array.from(dependencies.selectedOptions).map(function (item) { return item.value; })};
            try {
                var result = await api.previewReview(request);
                if (!fence.ensure() || ticket !== edit) return;
                prepared = Object.assign(request, {previewFingerprint:result.fingerprint});
                changes.textContent = JSON.stringify(result.changes || result.realization, null, 2);
                accept.hidden = false; accept.disabled = false; shell.message.textContent = t('lifecycle.preview.ready');
            } catch (error) { if (fence.valid()) shell.message.textContent = errorText(error); }
            finally { busy = false; preview.disabled = !fence.valid(); }
        }
        async function acceptReview() {
            if (busy || !prepared || !fence.ensure()) return;
            busy = true; accept.disabled = true; preview.disabled = true;
            try {
                var result = await api.acceptReview(prepared);
                if (pendingPublication(result)) { locked = true; [disposition,contributor,dependencies,reason].forEach(function (input) { input.disabled = true; }); }
                fence.afterSuccess(result, false);
            } catch (error) {
                if ([400,403,404,409,412,422].includes(error.status)) {
                    prepared = null; accept.hidden = true; fence.rejected();
                } else {
                    locked = true; [disposition,contributor,dependencies,reason].forEach(function (input) { input.disabled = true; });
                    fence.uncertain();
                }
                shell.message.textContent = errorText(error) + (fence.valid() ? '' : ' ' + t('lifecycle.stale'));
            } finally { busy = false; accept.disabled = !prepared || !fence.valid(); preview.disabled = locked || !fence.valid(); }
        }
        shell.dialog.showModal(); reason.focus();
    }
    function openReconciliation() {
        var captured = captureStand();
        if (!matchesStand(captured, false)) { status.textContent = t('lifecycle.stale'); return; }
        var shell = dialogShell('lifecycleReconciliationDialog', t('lifecycle.reconcile'));
        var reason = field(shell.form, 'lifecycleReconciliationReason', t('lifecycle.reason'), 'textarea'); reason.required = true; reason.maxLength = 1000;
        var conflicts = node('div'); conflicts.dataset.lifecycleConflicts = '';
        var preview = button(t('lifecycle.preview'), previewReconciliation); preview.id = 'lifecycleReconciliationPreview';
        var accept = button(t('lifecycle.accept'), acceptReconciliation); accept.id = 'lifecycleReconciliationAccept'; accept.hidden = true;
        var changes = node('pre'); shell.form.append(conflicts, preview, changes, accept);
        var fence = intentFence(captured, shell, preview, accept, false);
        var prepared = null, choices = {}, headCommit = null, edit = 0, busy = false, locked = false;
        shell.form.addEventListener('input', invalidate); shell.form.addEventListener('change', invalidate);
        function invalidate() { if (locked) return; edit++; prepared = null; accept.hidden = true; }
        async function previewReconciliation() {
            if (busy || locked || !fence.ensure() || !shell.form.reportValidity()) return;
            var ticket = edit; busy = true; preview.disabled = true; accept.hidden = true;
            var request = {context:clone(captured.context), metadata:metadata(reason.value), resolutions:clone(choices), headCommit:headCommit};
            try {
                var result = await api.previewReconciliation(request);
                if (!fence.ensure() || ticket !== edit) return;
                headCommit = result.headCommit; conflicts.replaceChildren();
                (result.conflicts || []).forEach(function (conflict, index) {
                    var group = node('fieldset'); group.append(node('legend', conflict.id));
                    group.append(node('p', conflict.reason));
                    ['base','local','incoming'].forEach(function (part) {
                        var details = node('details'); details.append(node('summary',t('lifecycle.reconcile.' + part)), node('pre',conflict[part] || '')); group.append(details);
                    });
                    var select = field(group, 'lifecycleResolution' + index, t('lifecycle.reconcile.choice'), 'select'); select.dataset.conflictId = conflict.id;
                    option(select,'',t('lifecycle.choose')); option(select,'LOCAL',t('lifecycle.reconcile.local')); option(select,'INCOMING',t('lifecycle.reconcile.incoming'));
                    select.value = choices[conflict.id] || conflict.resolution || '';
                    select.addEventListener('change', function () { if (select.value) choices[conflict.id] = select.value; else delete choices[conflict.id]; });
                    conflicts.append(group);
                });
                changes.textContent = JSON.stringify(result.changes || [], null, 2);
                if (result.canAccept) { prepared = Object.assign(request,{headCommit:result.headCommit,previewFingerprint:result.fingerprint}); accept.hidden = false; accept.disabled = false; }
                shell.message.textContent = t(result.canAccept ? 'lifecycle.preview.ready' : 'lifecycle.reconcile.resolve');
            } catch (error) { if (fence.valid()) shell.message.textContent = errorText(error); }
            finally { busy = false; preview.disabled = !fence.valid(); }
        }
        async function acceptReconciliation() {
            if (busy || !prepared || !fence.ensure()) return;
            busy = true; accept.disabled = true; preview.disabled = true;
            try {
                var result = await api.acceptReconciliation(prepared);
                if (pendingPublication(result)) { locked = true; reason.disabled = true; conflicts.querySelectorAll('select').forEach(function (select) { select.disabled = true; }); }
                fence.afterSuccess(result, true);
            } catch (error) {
                if ([400,403,404,409,412,422].includes(error.status)) {
                    prepared = null; accept.hidden = true; fence.rejected();
                } else {
                    locked = true; reason.disabled = true; conflicts.querySelectorAll('select').forEach(function (select) { select.disabled = true; });
                    fence.uncertain();
                }
                // A newer read may confirm the current stand; retry still sends this original UUID, body and revision.
                shell.message.textContent = errorText(error) + (fence.valid() ? '' : ' ' + t('lifecycle.stale'));
            } finally { busy = false; accept.disabled = !prepared || !fence.valid(); preview.disabled = locked || !fence.valid(); }
        }
        shell.dialog.showModal(); reason.focus();
    }
    async function saveRequirement(command) {
        if (!state.overview && !historicalSelection()) await refresh();
        var captured = captureStand();
        var intent = Object.assign({},command); delete intent.context; delete intent.metadata;
        var key = JSON.stringify([scopeKey(scope()),intent]);
        var retained = pendingRequirements.get(key);
        if (retained && command.metadata && command.metadata.commandId !== retained.request.metadata.commandId) throw new Error(t('lifecycle.pending'));
        var recovery = Boolean(retained && (retained.publication || retained.uncertain) && matchesRetryScope(retained.stand));
        if (!recovery && (!matchesStand(captured,true) || state.observed?.mayEdit === false)) throw staleError();
        if (!retained) {
            var request = Object.assign({},command,{context:command.context || clone(captured.context),
                metadata:command.metadata || metadata(command.requirement?.changeReason || t('lifecycle.requirement.reason'))});
            if (contextKey(request.context) !== contextKey(captured.context)) throw staleError();
            retained = {request:clone(request),stand:captured,publication:null,uncertain:false}; pendingRequirements.set(key,retained);
        } else if (!recovery && contextKey(retained.request.context) !== contextKey(captured.context)) throw staleError();
        // Expose the captured identity to the owning form so a pending result can retry this exact intent.
        command.context = clone(retained.request.context); command.metadata = clone(retained.request.metadata);
        try {
            var result = await api.saveRequirement(retained.request);
            retained.uncertain = false;
            if (pendingPublication(result)) retained.publication = clone(publicationOf(result)); else pendingRequirements.delete(key);
            if (matchesStand(captured,!recovery)) publishChanged(result,false);
            return result;
        } catch (error) {
            if ([400,403,404,409,412,422].includes(error.status)) pendingRequirements.delete(key);
            else retained.uncertain = true;
            throw error;
        }
    }
    function openNewArchitecture() {
        var shell = dialogShell('lifecycleArchitectureDialog', t('lifecycle.newArchitecture'));
        var name = field(shell.form, 'lifecycleArchitectureName', t('lifecycle.architecture.name')); name.required = true; name.maxLength = 200;
        var description = field(shell.form, 'lifecycleArchitectureDescription', t('lifecycle.architecture.description'), 'textarea');
        var create = button(t('lifecycle.architecture.create'), submit); create.id = 'lifecycleArchitectureCreate'; shell.form.append(create);
        var request = null, architecture = null, workspace = null, attempted = false, workspaceAttempted = false, busy = false;
        var openingGeneration = state.generation;
        async function submit() {
            if (busy || !shell.form.reportValidity()) return;
            busy = true; create.disabled = true;
            if (!request) request = {displayName:name.value.trim(),description:description.value,slug:'architecture-' + crypto.randomUUID()};
            name.disabled = true; description.disabled = true;
            try {
                if (!architecture && attempted) {
                    shell.message.textContent = t('lifecycle.architecture.recover');
                    var owned = await api.architectures();
                    architecture = (owned.repositories || owned || []).find(function (item) { return item.slug === request.slug; });
                }
                if (!architecture) { attempted = true; architecture = await api.createArchitecture(request); }
                if (!workspace && workspaceAttempted) {
                    var copies = await api.workspaces(architecture.repositoryId);
                    workspace = (copies.workspaces || copies || []).find(function (item) {
                        return item.sourceRepositoryId === architecture.repositoryId && item.currentBranch === architecture.defaultBranch
                            && item.displayName === request.displayName;
                    });
                }
                if (!workspace) {
                    workspaceAttempted = true;
                    workspace = await api.createWorkspace({repositoryId:architecture.repositoryId,workspaceScopeKey:'__shared__',branch:architecture.defaultBranch},request.displayName);
                }
                var target = new URL(window.TaxonomyI18n.resolveUrl('/architecture/editor'),location.href);
                target.search = location.search;
                ['commit','revision','element','viewId','layoutRevision','projectId','requirementId'].forEach(function (key) { target.searchParams.delete(key); });
                target.searchParams.set('repositoryId',architecture.repositoryId); target.searchParams.set('workspaceScopeKey',workspace.workspaceId);
                target.searchParams.set('workspaceId',workspace.workspaceId); target.searchParams.set('branch',workspace.currentBranch || architecture.defaultBranch);
                target.searchParams.set('lang',window.TaxonomyI18n.getLocale());
                if (openingGeneration === state.generation) location.assign(target.pathname + target.search);
                else {
                    shell.message.textContent = t('lifecycle.saved') + ' ' + t('lifecycle.stale');
                    var link = node('a',request.displayName); link.href = target.pathname + target.search; shell.form.append(link);
                    create.hidden = true;
                }
            } catch (error) { shell.message.textContent = errorText(error); }
            finally { busy = false; create.disabled = false; }
        }
        shell.dialog.showModal(); name.focus();
    }
    function initialize() {
        root = document.getElementById('architectureLifecycle'); if (!root) return;
        root.replaceChildren();
        disclosure = node('details'); summary = node('summary', t('lifecycle.title')); disclosure.append(summary);
        var body = node('div', null, 'taxonomy-lifecycle__body');
        status = node('p'); status.setAttribute('role','status'); status.dataset.lifecycleStatus = '';
        var commands = node('div', null, 'taxonomy-lifecycle__commands');
        var refreshButton = button(t('lifecycle.refresh'), function () { refresh(); }); refreshButton.dataset.lifecycleRefresh = ''; refreshButton.dataset.lifecycleAction = 'refresh';
        var newArchitecture = button(t('lifecycle.newArchitecture'), openNewArchitecture); newArchitecture.dataset.lifecycleNewArchitecture = ''; newArchitecture.dataset.lifecycleAction = 'new-architecture';
        reconcileButton = button(t('lifecycle.reconcile'), openReconciliation); reconcileButton.dataset.lifecycleReconcile = ''; reconcileButton.dataset.lifecycleAction = 'reconcile'; reconcileButton.hidden = true;
        commands.append(refreshButton,newArchitecture,reconcileButton);
        var table = node('table'); var head = node('thead'); var header = node('tr');
        ['requirement','selectedVersion','reviewedVersion','realization','actions'].forEach(function (key) { var cell = node('th',t('lifecycle.' + key)); cell.scope = 'col'; header.append(cell); });
        head.append(header); rows = node('tbody'); table.append(head,rows);
        var historyButton = button(t('lifecycle.history'),function () { loadHistory(0); }); historyButton.dataset.lifecycleHistory = ''; historyButton.dataset.lifecycleAction = 'history';
        var historyForm = node('form'); historyForm.id = 'lifecycleHistoryForm';
        historySearch = field(historyForm,'lifecycleHistorySearch',t('lifecycle.history.search')); historySearch.type = 'search'; historySearch.maxLength = 300;
        var search = node('button',t('lifecycle.history.searchAction')); search.type = 'submit'; historyForm.append(search);
        historyForm.addEventListener('submit',function (event) {
            event.preventDefault();
            var filters = state.history?.filters ? Object.assign({},state.history.filters,{text:historySearch.value.trim()}) : undefined;
            loadHistory(0,null,filters);
        });
        historyList = node('div'); historyList.dataset.lifecycleHistoryList = ''; historyList.hidden = true;
        var tableContainer = node('div',null,'taxonomy-lifecycle__table'); tableContainer.append(table);
        body.append(status,commands,tableContainer,historyButton,historyForm,historyList); disclosure.append(body);
        currentButton = button(t('lifecycle.currentStand'),function () { requestStand(null); }); currentButton.dataset.lifecycleCurrent = ''; currentButton.dataset.lifecycleAction = 'current'; currentButton.hidden = true;
        root.append(disclosure,currentButton);
        if (!isEditor()) refresh(scope(),selectionFromUrl());
    }
    document.addEventListener('taxonomy:entity-context-changed', function () {
        var value = window.TaxonomyAppChrome?.getContext() || {};
        var next = JSON.stringify([value.project?.id,value.project?.key,value.requirement?.id,value.requirement?.key]);
        if (subjectKey !== next) { subjectKey = next; clearHistory(); invalidateStand(); }
        if (state.overview) render();
    });
    document.addEventListener('taxonomy:lifecycle-changed', function (event) {
        if (isEditor() || historicalSelection()) return;
        var next = publicationOf(event.detail)?.context;
        if (next && scopeKey(next) === scopeKey(state.context)) showPublishedCurrentStand(next);
    });
    window.addEventListener('popstate',function () { if (!isEditor()) refresh(scope(),selectionFromUrl()); });
    window.TaxonomyArchitectureLifecycle = {state:function () { return clone(state.overview); }, refresh:refresh, beginSelection:beginSelection, observe:observe,
        failedSelection:failedSelection, saveRequirement:saveRequirement, openNewArchitecture:openNewArchitecture};
    window.TaxonomyI18n.ready().then(initialize);
}());
