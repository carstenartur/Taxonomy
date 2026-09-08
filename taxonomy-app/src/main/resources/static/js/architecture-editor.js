(function () {
    'use strict';
    var api = window.ArchitectureEditorApi;
    var view = null;
    var selected = null;
    var page = 0;
    var generation = 0;
    var loader = null;
    var pending = null;
    var busy = false;
    var PAGE_SIZE = 50;
    var dialog = el('editorPreviewDialog');
    var fieldMap = {
        title: 'editorTitle', description: 'editorDescription', 'x-editor-status': 'editorElementStatus',
        'x-owner': 'editorOwner', 'x-responsible-organization': 'editorOrganization',
        'x-product-reference': 'editorProduct', 'x-system-reference': 'editorSystem'
    };
    var renderer = window.ArchitectureEditorRenderer(el('editorGraph'), selectElement, selectRelation, function (visible, total) {
        el('editorGraphSummary').textContent = t('editor.visible', visible, total);
    });
    function el(id) { return document.getElementById(id); }
    function t(key) { return window.TaxonomyI18n.t.apply(null, arguments); }
    function short(commit) { return commit ? commit.substring(0, 12) : t('editor.emptyBranch'); }
    function context() { return view.document.context; }
    function selectedElement() { return view && view.model.elements.find(function (element) { return element.id === selected; }); }
    function relationId(relation) { return relation.sourceId + ' ' + relation.relationType + ' ' + relation.targetId; }
    function setStatus(message) { el('editorStatus').textContent = message; }
    function report(error, target) {
        var problem = error.responseBody || {};
        var message = problem.code ? t('editor.problem.' + problem.code) : error.message;
        if (!message || message === 'editor.problem.' + problem.code) message = problem.detail || error.message;
        target = target || el('editorError');
        target.textContent = message + (problem.dependencies && problem.dependencies.length ? '\n' + problem.dependencies.join('\n') : '')
            + (problem.currentCommit ? '\n' + short(problem.expectedCommit) + ' → ' + short(problem.currentCommit) : '');
        target.hidden = false;
    }
    function option(select, value, label) { var item = document.createElement('option'); item.value = value; item.textContent = label || value; select.append(item); }
    function button(label, action, disabled) {
        var node = document.createElement('button'); node.type = 'button'; node.textContent = label;
        node.disabled = Boolean(disabled); node.addEventListener('click', action); return node;
    }
    function scopeFromUrl() {
        var params = new URLSearchParams(location.search);
        var result = {};
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) { if (params.has(key)) result[key] = params.get(key); });
        return result;
    }
    function linkSelection(replace) {
        var url = new URL(location.href);
        ['repositoryId', 'workspaceScopeKey', 'branch', 'commit'].forEach(function (key) {
            if (context()[key]) url.searchParams.set(key, context()[key]); else url.searchParams.delete(key);
        });
        if (selected) url.searchParams.set('element', selected); else url.searchParams.delete('element');
        if (replace) history.replaceState(null, '', url); else history.pushState(null, '', url);
    }
    function permissions() {
        var writable = view && view.mayEdit && !busy;
        el('editorNew').disabled = !writable;
        el('editorFields').disabled = !writable;
        el('editorRelationFields').disabled = !writable || !selectedElement();
        el('editorDelete').disabled = !writable || !selectedElement();
        el('editorAccept').disabled = busy || !pending;
    }
    async function load(scope, commit, preserveDraft) {
        var ticket = ++generation;
        if (loader) loader.abort();
        loader = new AbortController();
        el('architectureEditor').setAttribute('aria-busy', 'true');
        setStatus(t('editor.loading'));
        el('editorError').hidden = true;
        try {
            var next = await api.load(scope, commit, loader.signal);
            if (ticket !== generation) return false;
            view = next;
            Object.freeze(view.document.context);
            if (!selectedElement()) selected = null;
            render(preserveDraft);
            linkSelection(true);
            return true;
        } catch (error) {
            if (ticket === generation) { report(error); view = null; permissions(); }
            return false;
        } finally {
            if (ticket === generation) el('architectureEditor').setAttribute('aria-busy', 'false');
        }
    }
    function render(preserveDraft) {
        var c = context();
        el('editorContext').textContent = c.repositoryId + ' / ' + c.workspaceScopeKey + ' / ' + c.branch + ' · ' + short(c.commit) + ' · ' + c.actor;
        var state = view.document.projectionState;
        setStatus(t('editor.projection.' + state));
        el('editorMode').textContent = view.mayEdit ? t('editor.writable') : t('editor.readOnly');
        el('editorWorkspace').hidden = c.writeMode !== 'READ_ONLY' || state === 'HISTORICAL';
        el('editorRebuild').hidden = !view.mayEdit || !c.commit || state === 'READY';
        ['Svg', 'Pdf'].forEach(function (format) {
            var link = el('editor' + format); link.hidden = !c.commit || !view.model.elements.length;
            if (!link.hidden) link.href = api.exportUrl(c, format.toLowerCase());
        });
        if (!preserveDraft) {
            el('editorType').replaceChildren();
            view.schema.elementTypes.forEach(function (type) { option(el('editorType'), type, t('editor.type.' + type)); });
            el('editorRelationType').replaceChildren();
            view.schema.relationTypes.forEach(function (type) { option(el('editorRelationType'), type); });
            el('editorRelationStatus').replaceChildren();
            view.schema.relationStatuses.forEach(function (status) { option(el('editorRelationStatus'), status, t('editor.status.' + status)); });
            el('editorRelationStatus').value = 'proposed';
            renderForm();
        }
        el('editorDsl').value = view.document.dsl;
        renderTree(); renderRelations(); renderHistory();
        renderer.render(view.scene, selected);
        permissions();
    }
    function renderTree() {
        var query = el('editorSearch').value.toLocaleLowerCase();
        var matches = view.model.elements.filter(function (item) {
            return (item.id + ' ' + item.title + ' ' + item.type).toLocaleLowerCase().includes(query);
        }).sort(function (a, b) { return a.type.localeCompare(b.type) || a.id.localeCompare(b.id); });
        page = Math.min(page, Math.max(0, Math.ceil(matches.length / PAGE_SIZE) - 1));
        var root = el('editorTree'); root.replaceChildren();
        var groups = new Map();
        matches.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).forEach(function (item) {
            if (!groups.has(item.type)) {
                var details = document.createElement('details'); details.open = true;
                var summary = document.createElement('summary'); summary.textContent = t('editor.type.' + item.type);
                var list = document.createElement('ul'); details.append(summary, list); root.append(details); groups.set(item.type, list);
            }
            var row = document.createElement('li');
            var select = button(item.title || item.id, function () { selectElement(item.id); });
            select.setAttribute('aria-current', String(item.id === selected));
            var info = document.createElement('small');
            var sceneNode = view.scene.nodes.find(function (node) { return node.id === item.id; });
            info.textContent = (sceneNode && sceneNode.parentId ? sceneNode.parentId + ' › ' : '') + item.id;
            select.append(info); row.append(select); groups.get(item.type).append(row);
        });
        el('editorPage').textContent = t('editor.page', page + 1, Math.max(1, Math.ceil(matches.length / PAGE_SIZE)), matches.length);
        el('editorPrevious').disabled = page === 0; el('editorNext').disabled = (page + 1) * PAGE_SIZE >= matches.length;
    }
    function renderForm() {
        var item = selectedElement();
        el('editorSelection').textContent = item ? item.id : t('editor.newDraft');
        el('editorType').value = item ? item.type : view.schema.elementTypes[0];
        Object.keys(fieldMap).forEach(function (key) {
            var value = item && (key.startsWith('x-') ? item.extensions[key] : item[key]);
            el(fieldMap[key]).value = value || (key === 'x-editor-status' ? 'draft' : '');
        });
        var parent = item && view.scene.nodes.find(function (node) { return node.id === item.id; });
        el('editorParent').value = parent && parent.parentId || '';
        el('editorDraft').hidden = true;
    }
    function selectElement(id, fromHistory) {
        if (busy || !view) return;
        selected = id; pending = null;
        renderForm(); renderRelations(); permissions();
        renderer.select(selected);
        el('editorTree').querySelectorAll('button').forEach(function (item) {
            item.setAttribute('aria-current', String(item.querySelector('small').textContent.split(' › ').pop() === selected));
        });
        var offset = view.document.dsl.indexOf('element ' + selected + ' type ');
        if (offset >= 0) el('editorDsl').setSelectionRange(offset, view.document.dsl.indexOf('\n}', offset) + 2);
        if (!fromHistory) linkSelection(false);
        setStatus(selected ? t('editor.selected', selected) : t('editor.newDraft'));
    }
    function selectRelation(edge) {
        selectElement(edge.sourceId);
        el('editorRelationTarget').value = edge.targetId;
        el('editorRelationType').value = edge.relationType;
        var relation = view.model.relations.find(function (item) { return relationId(item) === edge.id; });
        el('editorRelationStatus').value = relation && relation.status || 'proposed';
        el('editorRelationType').focus();
    }
    function renderRelations() {
        var list = el('editorRelations'); list.replaceChildren();
        view.model.relations.filter(function (relation) { return relation.sourceId === selected || relation.targetId === selected; }).slice(0, 50).forEach(function (relation) {
            var row = document.createElement('li'); row.append(document.createTextNode(relationId(relation) + ' · ' + (relation.status || 'proposed') + ' '));
            row.append(button(t('editor.select'), function () { selectRelation({ id: relationId(relation), sourceId: relation.sourceId, targetId: relation.targetId, relationType: relation.relationType }); }));
            row.append(button(t('editor.delete'), function () { stage(Object.assign({ kind: 'DELETE_RELATION' }, relationPayload(relation))); }, !view.mayEdit));
            list.append(row);
        });
        var evidence = view.model.evidence.filter(function (item) { return item.forRelationSource === selected || item.forRelationTarget === selected; });
        var item = selectedElement();
        el('editorEvidence').textContent = JSON.stringify({ taxonomy: item && item.taxonomy, extensions: item && item.extensions, evidence: evidence }, null, 2);
    }
    function renderHistory() {
        var historyList = el('editorHistory'); historyList.replaceChildren();
        view.document.history.forEach(function (entry) {
            var row = document.createElement('li');
            row.append(document.createTextNode(short(entry.commit) + ' · ' + entry.kind + ' · ' + entry.actor + ' · ' + entry.occurredAt + ' — ' + entry.rationale + ' '));
            row.append(button(t(entry.kind === 'UNDO' ? 'editor.redo' : 'editor.undo'), function () {
                stage({ kind: entry.kind === 'UNDO' ? 'REDO' : 'UNDO', targetCommit: entry.commit });
            }, !view.mayEdit || entry.actor !== context().actor));
            historyList.append(row);
        });
    }
    function relationPayload(relation) { return { sourceId: relation.sourceId, relationType: relation.relationType, targetId: relation.targetId }; }
    function metadata(causation) {
        var id = crypto.randomUUID();
        return { commandId: id, correlationId: pending ? pending.metadata.correlationId : id,
            causationId: causation || id, rationale: el('editorRationale').value };
    }
    async function stage(intent) {
        if (busy || !view || !view.mayEdit) return;
        if (!el('editorRationale').reportValidity()) return;
        pending = Object.assign({}, intent, { context: context(), metadata: metadata() });
        await previewPending();
    }
    async function previewPending() {
        var ticket = generation;
        busy = true; permissions();
        el('editorPreviewError').textContent = ''; el('editorReapply').hidden = true;
        try {
            var preview = await api.preview(pending);
            if (ticket !== generation) return;
            el('editorChanges').replaceChildren();
            el('editorPreviewContext').textContent = context().repositoryId + ' / ' + context().workspaceScopeKey + ' / ' + context().branch
                + ' · ' + short(preview.context.commit) + ' · ' + pending.metadata.rationale;
            preview.change.changes.forEach(function (change) {
                var title = document.createElement('h3'); title.textContent = change.id;
                var diff = document.createElement('div'); diff.className = 'editor-diff';
                [change.before, change.after].forEach(function (text, index) {
                    var part = document.createElement('div');
                    var label = document.createElement('p'); label.textContent = t(index === 0 ? 'editor.before' : 'editor.after');
                    var pre = document.createElement('pre'); pre.textContent = text || t('editor.absent'); part.append(label, pre); diff.append(part);
                });
                el('editorChanges').append(title, diff);
            });
            if (!dialog.open) dialog.showModal();
            if (!preview.change.changes.length) { pending = null; el('editorPreviewError').textContent = t('editor.problem.NO_CHANGE'); }
        } catch (error) {
            if (ticket !== generation) return;
            report(error, dialog.open ? el('editorPreviewError') : el('editorError'));
            if (error.status === 412) { if (!dialog.open) dialog.showModal(); report(error, el('editorPreviewError')); el('editorReapply').hidden = false; }
            else pending = null;
        } finally {
            busy = false; permissions();
            if (dialog.open) {
                el(!el('editorReapply').hidden ? 'editorReapply' : pending ? 'editorAccept' : 'editorCancel').focus();
            }
        }
    }
    el('editorForm').addEventListener('submit', function (event) {
        event.preventDefault();
        var properties = {}; Object.keys(fieldMap).forEach(function (key) { properties[key] = el(fieldMap[key]).value; });
        stage({ kind: selectedElement() ? 'UPDATE_ELEMENT' : 'CREATE_ELEMENT', id: selected, type: el('editorType').value, properties: properties });
    });
    el('editorForm').addEventListener('input', function () { el('editorDraft').hidden = false; pending = null; });
    el('editorRelationForm').addEventListener('submit', function (event) {
        event.preventDefault();
        var relation = { sourceId: selected, relationType: el('editorRelationType').value, targetId: el('editorRelationTarget').value.trim() };
        var exists = view.model.relations.some(function (item) { return relationId(item) === relationId(relation); });
        stage(Object.assign({ kind: exists ? 'UPDATE_RELATION' : 'CREATE_RELATION', status: el('editorRelationStatus').value }, relation));
    });
    el('editorDelete').onclick = function () { stage({ kind: 'DELETE_ELEMENT', id: selected }); };
    el('editorMove').onclick = function () { stage({ kind: 'MOVE_ELEMENT', id: selected, parentId: el('editorParent').value.trim() || null }); };
    el('editorDiscard').onclick = function () { pending = null; renderForm(); };
    el('editorNew').onclick = function () { selectElement(null); el('editorTitle').focus(); };
    el('editorSearch').oninput = function () { if (view) { page = 0; renderTree(); } };
    el('editorPrevious').onclick = function () { page--; renderTree(); el('editorSearch').focus(); };
    el('editorNext').onclick = function () { page++; renderTree(); el('editorSearch').focus(); };
    el('editorZoomIn').onclick = function () { renderer.zoom(1.4); };
    el('editorZoomOut').onclick = function () { renderer.zoom(1 / 1.4); };
    el('editorFit').onclick = renderer.fit; el('editorFocus').onclick = renderer.focus;
    el('editorRefresh').onclick = function () { if (!busy) { pending = null; load(view ? context() : scopeFromUrl(), null).then(function (ok) { if (ok) renderer.focus(); }); } };
    el('editorCancel').onclick = function () { if (!busy) { pending = null; dialog.close(); } };
    dialog.addEventListener('cancel', function (event) { if (busy) event.preventDefault(); else pending = null; });
    el('editorAccept').onclick = async function () {
        if (!pending || busy) return;
        var ticket = generation; var acceptedCommand = pending;
        busy = true; permissions();
        try {
            var accepted = await api.execute(acceptedCommand);
            if (ticket !== generation) return;
            pending = null; dialog.close();
            if (acceptedCommand.kind === 'CREATE_ELEMENT') selected = 'arch-' + acceptedCommand.metadata.commandId;
            var ok = await load(accepted.context, accepted.context.commit);
            if (ok) { setStatus(t('editor.accepted', short(accepted.context.commit)) + ' · ' + t('editor.projection.' + accepted.projectionState)); renderer.focus(); }
        } catch (error) {
            if (ticket !== generation) return;
            report(error, el('editorPreviewError'));
            if (error.status === 412) el('editorReapply').hidden = false;
            // Keep this exact identity on ambiguous transport failure; never silently replay with a new ID.
        } finally { busy = false; permissions(); if (!dialog.open) el(view && view.mayEdit ? 'editorTitle' : 'editorRefresh').focus(); }
    };
    el('editorReapply').onclick = async function () {
        if (!pending || busy) return;
        var original = pending; busy = true; permissions();
        var ok = await load(original.context, null, true);
        busy = false;
        if (!ok) { pending = null; permissions(); return; }
        pending = Object.assign({}, original, { context: context(), metadata: metadata(original.metadata.commandId) });
        await previewPending();
    };
    el('editorRebuild').onclick = async function () {
        if (busy || !view) return;
        busy = true; permissions();
        try { await api.rebuild(context()); await load(context(), null); } catch (error) { report(error); }
        finally { busy = false; permissions(); }
    };
    el('editorWorkspace').onclick = async function () {
        if (busy || !view) return;
        busy = true; permissions(); el('editorWorkspace').disabled = true;
        try {
            var workspace = await api.createWorkspace(context());
            selected = null; pending = null;
            await load({ repositoryId: workspace.sourceRepositoryId, workspaceScopeKey: workspace.workspaceId, branch: workspace.currentBranch }, null);
            renderer.fit();
        } catch (error) { report(error); }
        finally { busy = false; permissions(); el('editorWorkspace').disabled = false; }
    };
    window.addEventListener('popstate', function () {
        if (dialog.open) dialog.close(); pending = null;
        selected = new URLSearchParams(location.search).get('element');
        load(scopeFromUrl(), new URLSearchParams(location.search).get('commit')).then(function (ok) { if (ok) renderer.focus(); });
    });
    window.TaxonomyI18n.ready().then(function () {
        document.querySelectorAll('[data-i18n]').forEach(function (node) { node.textContent = t(node.getAttribute('data-i18n')); });
        selected = new URLSearchParams(location.search).get('element');
        return load(scopeFromUrl(), new URLSearchParams(location.search).get('commit'));
    }).then(function (ok) { if (ok) { renderer.fit(); if (selected) renderer.focus(); } });
}());
