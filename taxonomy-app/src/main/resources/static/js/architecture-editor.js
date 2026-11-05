(function () {
    'use strict';
    function mount(root) {
    if (root.__architectureEditor) return root.__architectureEditor;
    var active = false, activeSource = {}, refreshNeeded = false, draftBaseline = '';
    var api = window.ArchitectureEditorApi;
    var view = null;
    var selected = null;
    var page = 0;
    var generation = 0;
    var loader = null;
    var pending = null;
    var pendingCheckpoint = null;
    var busy = false;
    var loading = false;
    var layout = new URLSearchParams(location.search).get('layout') || 'stable';
    var layoutRevision = new URLSearchParams(location.search).get('layoutRevision');
    var selectedView = new URLSearchParams(location.search).get('viewId');
    var formBaseline = {};
    var PAGE_SIZE = 50;
    var dialog = el('editorPreviewDialog');
    var fieldMap = {
        title: 'editorTitle', description: 'editorDescription', 'x-editor-status': 'editorElementStatus',
        'x-owner': 'editorOwner', 'x-responsible-organization': 'editorOrganization',
        'x-product-reference': 'editorProduct', 'x-system-reference': 'editorSystem'
    };
    var renderer = window.ArchitectureEditorRenderer(el('editorGraph'), selectElement, selectRelation, function (visible, total) {
        el('editorGraphSummary').textContent = t('editor.visible', visible, total);
    }, function (ids) { focusPanel.observe(ids); }, function (id) { focusPanel.review(id); });
    var planningPanel = window.TaxonomyPlanningEditor.create({ t: t, stage: stage });
    var focusPanel = window.ArchitectureFocusPanel({ t: t, request: api.suggest, present: renderer.presentFocus,
        language: function () { return document.documentElement.lang === 'de' ? 'de' : 'en'; },
        accept: acceptFocus, undo: undoFocus, canUndo: canUndoFocus,
        locate: function (id) { if (view && view.model.elements.some(function (e) { return e.id === id; })) { selectElement(id); renderer.focus(); } }
    });
    var adoptionPanel = window.AnalysisSnapshotAdoption({t:t,api:api,
        metadata:function () { var audit = metadata(); if (!audit.rationale.trim()) audit.rationale = t('adoption.rationale'); return audit; },
        report:report,selectedView:function () { return selectedView; },
        onBusy:function (value) { busy = value; permissions(); },
        onSelectView:async function (id) { selectedView = id; await load(context(),view.document.source === 'GIT_CHECKPOINT' ? context().commit : context().revision,true); },
        onAccepted:async function (result) {
            selectedView = result.viewId;
            await load(result.accepted ? result.accepted.context : context(),null);
            renderer.fit({initial:true});
        }
    });
    function el(id) { return root.querySelector('#' + id); }
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
        target.scrollIntoView({ block: 'nearest' });
    }
    function option(select, value, label) { var item = document.createElement('option'); item.value = value; item.textContent = label || value; select.append(item); }
    function button(label, action, disabled) {
        var node = document.createElement('button'); node.type = 'button'; node.textContent = label;
        node.disabled = Boolean(disabled); node.addEventListener('click', action); return node;
    }
    function scopeFromUrl() {
        var result = {};
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) { if (activeSource[key] !== undefined) result[key] = activeSource[key]; });
        if (!result.workspaceScopeKey && activeSource.workspaceId !== undefined) result.workspaceScopeKey = activeSource.workspaceId || '__shared__';
        return result;
    }
    function linkSelection(replace) {
        if (!active || !view) return;
        var url = new URL(location.href);
        ['repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) {
            if (context()[key]) url.searchParams.set(key, context()[key]); else url.searchParams.delete(key);
        });
        url.searchParams.set('workspaceId', context().workspaceScopeKey === '__shared__' ? '' : context().workspaceScopeKey);
        url.searchParams.delete('commit'); url.searchParams.delete('revision');
        if (view.document.source === 'GIT_CHECKPOINT') url.searchParams.set('commit', context().commit);
        else url.searchParams.set('revision', context().revision);
        if (selected) url.searchParams.set('element', selected); else url.searchParams.delete('element');
        if (selectedView) url.searchParams.set('viewId',selectedView); else url.searchParams.delete('viewId');
        url.searchParams.set('layout', layout);
        if (layoutRevision !== null && layoutRevision !== undefined) url.searchParams.set('layoutRevision', layoutRevision);
        else url.searchParams.delete('layoutRevision');
        if (replace) history.replaceState(null, '', url); else history.pushState(null, '', url);
    }
    function permissions() {
        var writable = active && view && view.mayEdit && !busy && !loading;
        el('architectureEditor').setAttribute('aria-busy', String(busy || loading));
        adoptionPanel.setLocked(busy || loading);
        planningPanel.setEnabled(writable);
        focusPanel.setLocked(!active || busy || loading);
        el('editorArrange').disabled = busy || loading || !view;
        el('editorCheckpoint').disabled = !writable;
        el('editorResumeCheckpoint').disabled = busy;
        el('editorRecoverVersion').disabled = busy;
        el('editorNew').disabled = !writable;
        var quick = view?.document.historyTargets || {undo:null, redo:null};
        ['undo', 'redo'].forEach(function (action) {
            var key = action === 'undo' ? 'Undo' : 'Redo';
            var control = el('editorQuick' + key), target = quick[action];
            control.disabled = !writable || !target;
            control.title = target ? t('editor.quickTarget', target.revision, target.rationale) : t('editor.quickUnavailable');
            el('editorQuick' + key + 'Target').textContent = control.title;
        });
        el('editorPackageFields').disabled = !writable;
        el('editorFields').disabled = !writable;
        el('editorRelationFields').disabled = !writable || !selectedElement();
        el('editorDelete').disabled = !writable || !selectedElement();
        el('editorAccept').disabled = busy || !pending;
    }
    async function load(scope, commit, preserveDraft) {
        if (!active) return false;
        var ticket = ++generation;
        loading = true;
        window.TaxonomyArchitectureLifecycle?.beginSelection(scope, commit);
        permissions();
        if (loader) loader.abort();
        loader = new AbortController();
        focusPanel.setLocked(true);
        setStatus(t('editor.loading'));
        el('editorError').hidden = true;
        try {
            if ((view && !sameScope(scope, context())) || /^[a-f0-9]{40}$/i.test(String(commit))) layoutRevision = null;
            var next = await api.load(scope, commit, loader.signal, layout, layoutRevision, selectedView);
            if (ticket !== generation || !active) return false;
            view = next;
            layout = view.schema.layoutStrategy || 'stable';
            layoutRevision = view.schema.layoutRevision === undefined ? null : view.schema.layoutRevision;
            Object.freeze(view.document.context);
            if (!selectedElement()) selected = null;
            render(preserveDraft);
            linkSelection(true);
            return true;
        } catch (error) {
            if (ticket === generation) { report(error); view = null; window.TaxonomyArchitectureLifecycle?.failedSelection(error); focusPanel.setContext(null, false); permissions(); }
            return false;
        } finally {
            if (ticket === generation) { loading = false; permissions(); }
        }
    }
    function publishContext() {
        if (!active || !view) return;
        var c = context();
        window.TaxonomyArchitectureLifecycle?.observe(view.document, view.mayEdit);
        adoptionPanel.update(view);
        window.TaxonomyArchitectureWorkflow?.showEditor(c,adoptionPanel.getSource());
        var contextLabel = view.document.source === 'GIT_CHECKPOINT' ? t('editor.selectedVersion', short(c.commit))
            : t('editor.revision', c.revision) + ' · ' + t('editor.lastCheckpoint', short(c.commit));
        window.TaxonomyAppChrome?.updateContext({architecture:{repositoryId:c.repositoryId, workspaceScopeKey:c.workspaceScopeKey, branch:c.branch, modelStand:contextLabel,commit:c.commit,revision:c.revision}, editable:Boolean(view.mayEdit), source:adoptionPanel.getSource()}, 'working');
        el('editorContext').hidden = Boolean(window.TaxonomyAppChrome);
        el('editorContext').textContent = c.repositoryId + ' / ' + c.workspaceScopeKey + ' / ' + c.branch + ' · ' + contextLabel + ' · ' + c.actor;
    }
    function render(preserveDraft) {
        renderPackages(preserveDraft);
        planningPanel.render(view, preserveDraft);
        var c = context();
        publishContext();
        var state = view.document.projectionState;
        setStatus(t('editor.projection.' + state));
        el('editorMode').textContent = view.mayEdit ? t('editor.writable') : t('editor.readOnly');
        el('editorWorkspace').hidden = c.writeMode !== 'READ_ONLY' || state === 'HISTORICAL';
        el('editorRebuild').hidden = !view.mayEdit || state === 'READY';
        el('editorResumeCheckpoint').hidden = state !== 'CHECKPOINT_PENDING';
        el('editorRecoverVersion').hidden = state !== 'VERSION_CHANGED';
        ['Svg', 'Pdf'].forEach(function (format) {
            var link = el('editor' + format); link.hidden = !view.model.elements.length;
            if (!link.hidden) link.href = api.exportUrl(c, format.toLowerCase(), view.document.source, layout, layoutRevision, selectedView);
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
        renderTree(); renderRelations(); renderHistory(); renderVersions();
        renderer.render(view.scene, selected);
        el('editorLayout').value = layout;
        focusPanel.setContext(c, view.mayEdit && state === 'READY' && view.document.source === 'WORKSPACE_REVISION');
        focusPanel.select(selected);
        permissions();
        if (!preserveDraft) draftBaseline = draftValue();
        document.dispatchEvent(new CustomEvent('taxonomy:architecture-diagram-updated'));
    }
    function renderTree() {
        var query = el('editorSearch').value.toLowerCase();
        var matchesIds = new Set(view.searchIndex.filter(function (entry) { return entry.text.includes(query); }).map(function (entry) { return entry.id; }));
        var matches = view.model.elements.filter(function (item) {
            return matchesIds.has(item.id);
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
        formBaseline = {};
        el('editorSelection').textContent = item ? item.id : t('editor.newDraft');
        el('editorType').value = item ? item.type : view.schema.elementTypes[0];
        Object.keys(fieldMap).forEach(function (key) {
            var value = item && (key.startsWith('x-') ? item.extensions[key] : item[key]);
            el(fieldMap[key]).value = value || (key === 'x-editor-status' ? 'draft' : '');
            formBaseline[key] = el(fieldMap[key]).value;
        });
        var parent = item && view.scene.nodes.find(function (node) { return node.id === item.id; });
        el('editorParent').value = parent && parent.parentId || '';
        el('editorDraft').hidden = true;
    }
    function selectElement(id, fromHistory) {
        if (!active || busy || loading || !view) return false;
        if (!fromHistory && id === selected) return true;
        if (!fromHistory && hasDraft() && !window.confirm(t('architecture.workspace.discardDraft'))) return false;
        selected = id; pending = null;
        renderForm(); renderRelations(); permissions();
        renderer.select(selected);
        focusPanel.select(selected);
        el('editorTree').querySelectorAll('button').forEach(function (item) {
            item.setAttribute('aria-current', String(item.querySelector('small').textContent.split(' › ').pop() === selected));
        });
        var offset = view.document.dsl.indexOf('element ' + selected + ' type ');
        if (offset >= 0) el('editorDsl').setSelectionRange(offset, view.document.dsl.indexOf('\n}', offset) + 2);
        if (!fromHistory) linkSelection(false);
        setStatus(selected ? t('editor.selected', selected) : t('editor.newDraft'));
        draftBaseline = draftValue();
        return true;
    }
    function selectRelation(edge) {
        var sameElement = edge.sourceId === selected;
        if (sameElement && hasDraft() && !window.confirm(t('architecture.workspace.discardDraft'))) return;
        if (!selectElement(edge.sourceId, sameElement)) return;
        el('editorRelationTarget').value = edge.targetId;
        el('editorRelationType').value = edge.relationType;
        var relation = view.model.relations.find(function (item) { return relationId(item) === edge.id; });
        el('editorRelationStatus').value = relation && relation.status || 'proposed';
        el('editorRelationType').focus();
        draftBaseline = draftValue();
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
        var relationEvidence = view.model.relations.filter(function (r) { return (r.sourceId === selected || r.targetId === selected) && r.provenance; })
            .map(function (r) { return { relation: relationId(r), provenance: r.provenance, details: r.extensions }; });
        el('editorEvidence').textContent = JSON.stringify({ taxonomy: item && item.taxonomy, extensions: item && item.extensions,
            evidence: evidence, relations: relationEvidence }, null, 2);
    }
    function renderPackages(preserveDraft) {
        var packages = view.model.packages || [], chosen = el('editorPackage').value,
            parent = el('editorPackageParent').value;
        el('editorPackage').replaceChildren(); option(el('editorPackage'), '', t('editor.packageNew'));
        el('editorPackageParent').replaceChildren(); option(el('editorPackageParent'), '', t('editor.packageRoot'));
        el('editorPackages').replaceChildren();
        packages.forEach(function (pkg) {
            option(el('editorPackage'), pkg.id, pkg.title + ' · ' + pkg.id);
            option(el('editorPackageParent'), pkg.id, pkg.title + ' · ' + pkg.id);
            var row = document.createElement('li');
            row.textContent = pkg.title + ' · ' + pkg.id + ' → ' + (pkg.parentId || t('editor.packageRoot')) + ' [' + pkg.position + ']';
            el('editorPackages').append(row);
        });
        if (preserveDraft) {
            // Keep stale identities visible: a concurrent deletion must not turn an update into a new package.
            if (chosen && !packages.some(function (p) { return p.id === chosen; }))
                option(el('editorPackage'), chosen, t('editor.packageUnavailable', chosen));
            if (parent && !packages.some(function (p) { return p.id === parent; }))
                option(el('editorPackageParent'), parent, t('editor.packageUnavailable', parent));
            el('editorPackage').value = chosen;
            el('editorPackageParent').value = parent;
        } else {
            el('editorPackage').value = packages.some(function (p) { return p.id === chosen; }) ? chosen : '';
            fillPackage();
        }
    }
    function fillPackage() {
        var pkg = (view.model.packages || []).find(function (p) { return p.id === el('editorPackage').value; });
        el('editorPackageTitle').value = pkg ? pkg.title : '';
        el('editorPackageDescription').value = pkg ? pkg.description || '' : '';
        el('editorPackageParent').value = pkg ? pkg.parentId : '';
        el('editorPackagePosition').value = pkg ? pkg.position : 0;
    }
    function packagePlacement(kind, id, detach) {
        if (!id) return;
        var members = (view.model.packages || []).map(function (p) { return { kind: 'PACKAGE', memberId: p.id, parentPackageId: p.parentId, position: p.position }; });
        view.model.elements.filter(function (e) { return e.packageId !== null && e.packageId !== undefined; }).forEach(function (e) {
            members.push({ kind: 'ELEMENT', memberId: e.id, parentPackageId: e.packageId, position: e.packagePosition });
        });
        var old = members.find(function (p) { return p.kind === kind && p.memberId === id; });
        var parent = detach ? null : el('editorPackageParent').value;
        var scopes = new Set(); if (old) scopes.add(old.parentPackageId); if (parent !== null) scopes.add(parent);
        var finalState = [];
        scopes.forEach(function (scope) {
            var siblings = members.filter(function (p) { return p.parentPackageId === scope && !(p.kind === kind && p.memberId === id); }).sort(function (a, b) { return a.position - b.position; });
            if (scope === parent) siblings.splice(Math.min(Number(el('editorPackagePosition').value), siblings.length), 0, { kind: kind, memberId: id, parentPackageId: parent });
            siblings.forEach(function (p, position) { finalState.push(Object.assign({}, p, { position: position })); });
        });
        if (detach) finalState.push({ kind: kind, memberId: id, parentPackageId: null, position: -1 });
        stage({ kind: 'SET_PACKAGE_PLACEMENTS', placements: finalState, completeParentScopes: Array.from(scopes) });
    }
    function renderHistory() {
        var historyList = el('editorHistory'); historyList.replaceChildren();
        view.document.history.forEach(function (entry) {
            var row = document.createElement('li');
            row.append(document.createTextNode(t('editor.revision', entry.revision) + ' · ' + entry.kind + ' · ' + entry.actor + ' · ' + entry.occurredAt + ' — ' + entry.rationale + ' '));
            row.append(button(t(entry.kind === 'UNDO' ? 'editor.redo' : 'editor.undo'), function () {
                stage({ kind: entry.kind === 'UNDO' ? 'REDO' : 'UNDO', targetOperationId: entry.operationId });
            }, !view.mayEdit || entry.actor !== context().actor));
            historyList.append(row);
        });
    }
    function relationPayload(relation) { return { sourceId: relation.sourceId, relationType: relation.relationType, targetId: relation.targetId }; }
    function renderVersions() {
        var list = el('editorVersions'); list.replaceChildren();
        view.document.versions.forEach(function (version) {
            var row = document.createElement('li');
            var url = new URL(location.href);
            url.searchParams.delete('revision'); url.searchParams.delete('layoutRevision'); url.searchParams.delete('viewId'); url.searchParams.set('commit', version.commitId);
            var link = document.createElement('a'); link.href = url.href;
            link.textContent = short(version.commitId) + ' · ' + version.author + ' · ' + version.message.split('\n')[0];
            row.append(link); list.append(row);
        });
    }
    function metadata(causation, correlation) {
        var id = crypto.randomUUID();
        return { commandId: id, correlationId: correlation || id,
            causationId: causation || id, rationale: el('editorRationale').value };
    }
    function sameScope(a, b) {
        return a && b && ['repositoryId', 'workspaceScopeKey', 'branch'].every(function (key) { return a[key] === b[key]; });
    }
    function canUndoFocus(item) {
        return view && view.mayEdit && item.accepted && sameScope(item.accepted.context, context()) && item.accepted.context.actor === context().actor;
    }
    async function executeFocus(item, undo) {
        if (busy || loading || !view || !view.mayEdit || (undo ? !canUndoFocus(item) : item.stale)) return;
        var key = undo ? 'undoCommand' : 'command', previewKey = undo ? 'undoPreviewed' : 'previewed';
        if (!item[key]) {
            var audit = metadata(); audit.rationale = t(undo ? 'editor.lens.undoAudit' : 'editor.lens.audit', item.sourceId, item.relationType, item.targetId).substring(0, 1000);
            item[key] = Object.assign({ context: context(), metadata: audit }, undo
                ? { kind: 'UNDO', targetOperationId: item.accepted.operationId }
                : { kind: 'CREATE_RELATION', sourceId: item.sourceId, relationType: item.relationType, targetId: item.targetId,
                    status: 'accepted', properties: item.properties });
        }
        var ticket = generation; busy = true; permissions(); el('editorLensError').hidden = true;
        try {
            if (!item[previewKey]) { await api.preview(item[key]); item[previewKey] = true; }
            if (ticket !== generation) return;
            // An uncertain transport result retains both the exact command ID and its successful preview.
            var result = await api.execute(item[key]);
            if (ticket !== generation) return;
            if (undo) focusPanel.undone(item.id); else focusPanel.accepted(item.id, result);
            var ok = await load(result.context, result.context.revision, true);
            if (ok) setStatus(t('editor.accepted', result.context.revision));
        } catch (error) {
            if (ticket !== generation) return;
            report(error, el('editorLensError'));
            if (error.status === 412) {
                // A stale hypothesis is never silently rebased. Undo may be recreated explicitly against the refreshed revision.
                if (undo) { delete item.undoCommand; delete item.undoPreviewed; }
                await load(context(), null, true);
            }
        } finally { busy = false; permissions(); focusPanel.refresh(); }
    }
    function acceptFocus(item) { return executeFocus(item, false); }
    function undoFocus(item) { return executeFocus(item, true); }
    async function stage(intent, rationaleInput, generatedRationale) {
        if (busy || !view || !view.mayEdit) return;
        rationaleInput = rationaleInput || el('editorRationale');
        if (!generatedRationale && !rationaleInput.reportValidity()) return;
        var audit = metadata(); audit.rationale = generatedRationale || rationaleInput.value;
        pending = Object.assign({}, intent, { context: context(), metadata: audit });
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
                + ' · ' + t('editor.revision', preview.context.revision) + ' · ' + pending.metadata.rationale;
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
        var existing = selectedElement();
        var properties = {};
        Object.keys(fieldMap).forEach(function (key) {
            var value = el(fieldMap[key]).value;
            // Track user edits to the form, including explicit clearing; displayed defaults are not semantic changes.
            if (existing ? value !== formBaseline[key] : value !== '' || key === 'title') properties[key] = value;
        });
        stage({ kind: existing ? 'UPDATE_ELEMENT' : 'CREATE_ELEMENT', id: selected, type: el('editorType').value, properties: properties });
    });
    el('editorForm').addEventListener('input', function () { el('editorDraft').hidden = false; pending = null; });
    el('editorRelationForm').addEventListener('submit', function (event) {
        event.preventDefault();
        var relation = { sourceId: selected, relationType: el('editorRelationType').value, targetId: el('editorRelationTarget').value.trim() };
        var exists = view.model.relations.some(function (item) { return relationId(item) === relationId(relation); });
        stage(Object.assign({ kind: exists ? 'UPDATE_RELATION' : 'CREATE_RELATION', status: el('editorRelationStatus').value }, relation));
    });
    el('editorDelete').onclick = function () { stage({ kind: 'DELETE_ELEMENT', id: selected }); };
    el('editorPackage').onchange = fillPackage;
    el('editorSavePackage').onclick = function () { stage({ kind: el('editorPackage').value ? 'UPDATE_PACKAGE' : 'CREATE_PACKAGE', id: el('editorPackage').value,
        properties: { title: el('editorPackageTitle').value, description: el('editorPackageDescription').value } }); };
    el('editorDeletePackage').onclick = function () { if (el('editorPackage').value) stage({ kind: 'DELETE_PACKAGE', id: el('editorPackage').value }); };
    el('editorMovePackage').onclick = function () { packagePlacement('PACKAGE', el('editorPackage').value, false); };
    el('editorPlaceElement').onclick = function () { packagePlacement('ELEMENT', selected, false); };
    el('editorDetachElement').onclick = function () { packagePlacement('ELEMENT', selected, true); };
    el('editorMove').onclick = function () { stage({ kind: 'MOVE_ELEMENT', id: selected, parentId: el('editorParent').value.trim() || null }); };
    el('editorDiscard').onclick = function () { pending = null; renderForm(); };
    el('editorNew').onclick = function () { selectElement(null); el('editorTitle').focus(); };
    function quickInverse(action) {
        if (busy || loading || !view || !view.mayEdit) return;
        var target = view.document.historyTargets?.[action];
        if (!target || (hasDraft() && !window.confirm(t('editor.quickDiscardDraft')))) return;
        stage({kind: action === 'undo' ? 'UNDO' : 'REDO', targetOperationId: target.operationId}, null,
            t(action === 'undo' ? 'editor.quickUndoReason' : 'editor.quickRedoReason', target.revision));
    }
    el('editorQuickUndo').onclick = function () { quickInverse('undo'); };
    el('editorQuickRedo').onclick = function () { quickInverse('redo'); };
    el('editorSearch').oninput = function () { if (view) { page = 0; renderTree(); } };
    el('editorPrevious').onclick = function () { page--; renderTree(); el('editorSearch').focus(); };
    el('editorNext').onclick = function () { page++; renderTree(); el('editorSearch').focus(); };
    el('editorZoomIn').onclick = function () { renderer.zoom(1.4); };
    el('editorZoomOut').onclick = function () { renderer.zoom(1 / 1.4); };
    el('editorFit').onclick = function () { renderer.fit(); };
    el('editorFocus').onclick = function () { renderer.focus(); };
    el('editorArrange').onclick = async function () {
        if (busy || !view) return;
        layout = el('editorLayout').value;
        layoutRevision = layout === 'reduce-crossings' && view.document.source === 'WORKSPACE_REVISION' ? context().revision : null;
        busy = true; permissions();
        try { if (await load(context(), view.document.source === 'GIT_CHECKPOINT' ? context().commit : context().revision, true)) renderer.fit(); }
        finally { busy = false; permissions(); }
    };
    el('editorRefresh').onclick = function () {
        if (busy || loading || (hasDraft() && !window.confirm(t('editor.refreshDiscardDraft')))) return;
        pending = null;
        load(view ? context() : scopeFromUrl(), null).then(function (ok) { if (ok) renderer.focus(); });
    };
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
            if (selectedView && accepted.change?.changes?.some(function (change) { return change.id === 'view:' + selectedView && change.after == null; })) selectedView = null;
            if (acceptedCommand.kind === 'CREATE_ELEMENT') selected = 'arch-' + acceptedCommand.metadata.commandId;
            var ok = await load(accepted.context, accepted.context.revision);
            if (ok) { setStatus(t('editor.accepted', accepted.context.revision) + ' · ' + t('editor.projection.' + accepted.projectionState)); renderer.focus(); }
        } catch (error) {
            if (ticket !== generation) return;
            report(error, el('editorPreviewError'));
            if (error.status === 412) el('editorReapply').hidden = false;
            // Keep this exact identity on ambiguous transport failure; never silently replay with a new ID.
        } finally {
            busy = false; permissions();
            if (dialog.open) el(!el('editorReapply').hidden ? 'editorReapply' : pending ? 'editorAccept' : 'editorCancel').focus();
            else el(view && view.mayEdit ? 'editorTitle' : 'editorRefresh').focus();
        }
    };
    el('editorReapply').onclick = async function () {
        if (!pending || busy) return;
        var original = pending; busy = true; permissions();
        var ok = await load(original.context, null, true);
        busy = false;
        if (!ok) { pending = null; permissions(); return; }
        // Re-review the frozen intent; neither form may replace its rationale after a conflict.
        var audit = { commandId: crypto.randomUUID(), correlationId: original.metadata.correlationId,
            causationId: original.metadata.commandId, rationale: original.metadata.rationale };
        pending = Object.assign({}, original, { context: context(), metadata: audit });
        await previewPending();
    };
    el('editorCheckpoint').onclick = async function () {
        if (busy || !view || !view.mayEdit || !el('editorRationale').reportValidity()) return;
        if (!pendingCheckpoint) pendingCheckpoint = { context: context(), metadata: metadata() };
        busy = true; permissions();
        try {
            await api.checkpoint(pendingCheckpoint); pendingCheckpoint = null;
            await load(context(), null);
        } catch (error) {
            report(error);
            // Definitive client errors reject this intent. Timeouts, throttling and server/transport
            // failures retain its identity because the result may still need an idempotent retry.
            if (error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429) {
                pendingCheckpoint = null;
            }
        }
        finally { busy = false; permissions(); }
    };
    el('editorResumeCheckpoint').onclick = async function () {
        if (busy) return; busy = true; permissions();
        try { await api.resumeCheckpoint(context()); pendingCheckpoint = null; await load(context(), null); }
        catch (error) { report(error); } finally { busy = false; permissions(); }
    };
    el('editorRecoverVersion').onclick = async function () {
        if (busy) return; busy = true; permissions();
        try { await api.recoverVersion(context()); await load(context(), null); }
        catch (error) { report(error); } finally { busy = false; permissions(); }
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
            renderer.fit({ initial: true });
        } catch (error) { report(error); }
        finally { busy = false; permissions(); el('editorWorkspace').disabled = false; }
    };
    document.addEventListener('taxonomy:architecture-stand-requested', function (event) {
        if (!active) return;
        if (dialog.open) dialog.close(); pending = null;
        var selection = event.detail?.selection || {};
        load(event.detail?.context || scopeFromUrl(), selection.commit || selection.revision || null, true);
    });
    function applyLifecycleChange(event) {
        if (!active) { refreshNeeded = true; return; }
        var result = event.detail?.publication || event.detail;
        var resultContext = result?.context;
        if (busy || loading || !view || !resultContext || !sameScope(resultContext, context())
                || view.document.source !== 'WORKSPACE_REVISION' || view.document.projectionState === 'HISTORICAL') return;
        // Accepted lifecycle work advances the editor journal. Keep the unrelated form draft.
        load(resultContext, null, true);
    }
    document.addEventListener('taxonomy:lifecycle-changed', applyLifecycleChange);
    document.addEventListener('taxonomy:architecture-reconciled', applyLifecycleChange);
    function draftValue() {
        return JSON.stringify(Array.from(root.querySelectorAll('#editorForm input, #editorForm select, #editorForm textarea, #editorRelationForm input, #editorRelationForm select, #editorPackageFields input, #editorPackageFields select, #editorPackageFields textarea, #editorPlanningForm input, #editorPlanningForm select, #editorPlanningForm textarea, #editorRationale')).map(function (node) { return [node.id,node.value,node.checked]; }));
    }
    function hasDraft() { return Boolean(view && draftBaseline && draftValue() !== draftBaseline); }
    function getSource() {
        var result = Object.assign({}, activeSource, view ? context() : null, {kind:'working',element:selected,viewId:selectedView,layout:layout,layoutRevision:layoutRevision});
        if (view) {
            result.workspaceId = context().workspaceScopeKey === '__shared__' ? '' : context().workspaceScopeKey;
            if (view.document.source === 'GIT_CHECKPOINT') result.revision = null;
            else result.commit = null;
        }
        return result;
    }
    function sameSelection(source) {
        var current = getSource();
        if (view && view.document.source === 'GIT_CHECKPOINT' && source.commit !== current.commit) return false;
        // An unpinned source requests the current branch head, which can have advanced.
        if (view && !source.commit && String(source.revision ?? '') !== String(current.revision ?? '')) return false;
        if ((source.commit || null) !== (current.commit || null)) return false;
        if ((source.viewId || null) !== (current.viewId || null)) return false;
        return ['repositoryId','workspaceScopeKey','branch','commit','revision','viewId','layout','layoutRevision'].every(function (key) {
            return source[key] === undefined || String(source[key] ?? '') === String(current[key] ?? '');
        });
    }
    function canLeave(next, settings) {
        if (busy || dialog.open) { if (dialog.open) dialog.focus(); return false; }
        if (settings?.retain || (sameSelection(next) && (next.element || null) === selected)) return true;
        return !hasDraft() || window.confirm(t('architecture.workspace.discardDraft'));
    }
    async function activate(source) {
        active = true;
        renderer.setActive(true);
        var reuse = view && sameSelection(source) && !refreshNeeded;
        activeSource = Object.assign({},source);
        await window.TaxonomyI18n.ready();
        root.querySelectorAll('[data-i18n]').forEach(function (node) { node.textContent = t(node.getAttribute('data-i18n')); });
        if (!active) return false;
        if (reuse) {
            var nextElement = source.element && view.model.elements.some(function (item) { return item.id === source.element; }) ? source.element : null;
            if (nextElement !== selected) selectElement(nextElement, true);
            publishContext(); permissions(); linkSelection(true);
            return true;
        }
        selected = source.element || null;
        selectedView = source.viewId || null;
        layout = source.layout || 'stable'; layoutRevision = source.layoutRevision ?? null;
        var ok = await load(scopeFromUrl(),source.commit || source.revision,refreshNeeded && hasDraft());
        refreshNeeded = false;
        if (ok) { renderer.fit({initial:true}); if(selected) renderer.focus(); }
        return ok;
    }
    var instance = {activate:activate, suspend:function () {
        active = false; renderer.setActive(false); generation++; loader?.abort(); loading = false; window.TaxonomyArchitectureLifecycle?.suspend?.(); focusPanel.setLocked(true); permissions();
    }, canLeave:canLeave, hasDraft:hasDraft, getSource:getSource, getData:function () { return view; },
        getDiagram:function () { return renderer; }, select:selectElement,restoreUrl:function(){linkSelection(true);},
        openLens:function () { el('editorLensTitle').scrollIntoView({block:'nearest'}); el('editorLensEnabled').focus(); }
    };
    root.__architectureEditor = instance;
    return instance;
    }
    window.TaxonomyArchitectureEditor = Object.freeze({mount:mount});
    // Compatibility for standalone templates; the application workspace mounts lazily.
    if (!document.getElementById('architectureWorkspace') && document.getElementById('architectureEditor')) {
        var standalone = mount(document.body);
        function readSelection() {
            var value = {kind:'working'}, params = new URLSearchParams(location.search);
            // Standalone links accept the same source pins as the application workspace.
            ['repositoryId', 'workspaceId', 'workspaceScopeKey', 'branch', 'projectId', 'requirementId',
                'requirementVersionId', 'snapshotId', 'commit', 'revision', 'element', 'viewId', 'layout', 'layoutRevision',
                'sourceRepositoryId', 'sourceWorkspaceId', 'sourceWorkspaceScopeKey', 'sourceBranch', 'sourceProjectId',
                'sourceRequirementId', 'sourceRequirementVersionId', 'sourceSnapshotId', 'adoption'].forEach(function (key) {
                    if (params.has(key)) value[key] = params.get(key);
                });
            return value;
        }
        standalone.activate(readSelection());
        window.addEventListener('popstate',function () { if (standalone.canLeave(readSelection(),{})) standalone.activate(readSelection()); else standalone.restoreUrl(); });
    }
}());
