/* Deliberate frozen-source handoff. The server owns graph retrieval, preview and mutation. */
window.AnalysisSnapshotAdoption = function (options) {
    'use strict';
    var view = null, pending = null, pendingCheckpoint = null, attempted = null;
    var busy = false, locked = false, generation = 0;
    var source = window.TaxonomyArchitectureWorkflow?.adoptionSource();
    function el(id) { return document.getElementById(id); }
    function t(key) { return options.t.apply(null, arguments); }
    function setBusy(value) { busy = value; if (options.onBusy) options.onBusy(value); render(); }
    function workspaceId(value) {
        return value.workspaceId !== undefined ? (value.workspaceId ?? '')
            : value.workspaceScopeKey === '__shared__' ? '' : value.workspaceScopeKey;
    }
    function sourceKey(value) {
        return JSON.stringify(['repositoryId','workspaceScopeKey','branch','projectId','requirementId','requirementVersionId','snapshotId']
            .map(function (key) { return value?.[key] == null ? null : String(value[key]); }).concat(value && workspaceId(value)));
    }
    function sameBranch(left, right) {
        return left && right && left.repositoryId === right.repositoryId && left.branch === right.branch
            && left.workspaceScopeKey === right.workspaceScopeKey && workspaceId(left) === workspaceId(right);
    }
    function canAccept(request) { return view?.mayEdit && !busy && !locked && request && (!attempted || attempted === request); }
    function viewTitle(item) {
        var title = item.title || item.id, prefix = 'archview.policy.title.';
        if (typeof title !== 'string' || !title.startsWith(prefix)) return title;
        var translated = t(title);
        if (translated && translated !== title) return translated;
        return title.substring(prefix.length).replace(/([a-z])([A-Z])/g, '$1 $2')
            .replace(/^./, function (letter) { return letter.toUpperCase(); }) || item.id;
    }
    function render() {
        el('snapshotAdoption').hidden = !source && !(view?.model.views || []).some(function (item) { return item.extensions?.['x-analysis-snapshot-id']; });
        el('adoptionPreview').disabled = !source || !view?.mayEdit || locked || busy;
        el('adoptionAccept').disabled = !canAccept(pending);
        el('adoptionAcceptCheckpoint').disabled = !canAccept(pendingCheckpoint);
        el('adoptionAcceptCheckpoint').hidden = !pendingCheckpoint;
        el('adoptionCancel').disabled = busy;
        el('adoptionAccept').hidden = !pending;
        el('adoptionCancel').hidden = !pending;
        el('adoptionSource').textContent = source ? [t('adoption.requirementVersion', source.requirementId,
            source.requirementVersionId), source.branch].join(' · ') : '';
        var context = view?.document.context;
        el('adoptionTarget').textContent = context ? [context.branch,t('editor.revision',context.revision)].join(' · ') : '';
        el('adoptionIdentity').textContent = [source && [source.repositoryId, source.workspaceScopeKey, source.snapshotId].join(' / '),
            context && [context.repositoryId, context.workspaceScopeKey].join(' / ')].filter(Boolean).join('\n');
        el('adoptionHelp').textContent = t(view?.mayEdit ? 'adoption.help' : 'adoption.createCopy');
    }
    function sourceFromView(item) {
        var p = item?.extensions;
        return p?.['x-analysis-snapshot-id'] ? {repositoryId:p['x-analysis-repository-id'],workspaceScopeKey:p['x-analysis-workspace-scope'],
            workspaceId:workspaceId({workspaceId:p['x-analysis-workspace-id'],workspaceScopeKey:p['x-analysis-workspace-scope']}),
            branch:p['x-analysis-branch'],projectId:Number(p['x-analysis-project-id']),requirementId:Number(p['x-analysis-requirement-id']),
            requirementVersionId:Number(p['x-analysis-requirement-version-id']),snapshotId:p['x-analysis-snapshot-id']} : null;
    }
    function showSummary(result) {
        var summary = result.summary;
        var text = [t('adoption.summary',summary.addedElements.length,summary.reusedElements.length,
            summary.addedRelations.length,summary.reusedRelations.length,summary.conflicts.length)];
        if (result.existing) text.push(t('adoption.existing'));
        var differences = summary.differences.map(function (item) {
            return item.id + ' · ' + (item.field === 'responsibility' ? t('responsibility.unit') : item.field)
                + '\n' + t('adoption.preserve') + ': ' + (item.existingValue || '')
                + '\n' + t('adoption.recorded') + ': ' + (item.sourceValue || (item.field === 'responsibility'
                    ? t('editor.organizationUnassigned') : ''));
        });
        summary.conflicts.forEach(function (conflict) { text.push(conflict); });
        el('adoptionSummary').textContent = text.join('\n\n');
        el('adoptionSummary').hidden = false;
        el('adoptionDifferenceDetails').hidden = !differences.length;
        el('adoptionDifferences').textContent = differences.join('\n\n');
        el('adoptionPreview').hidden = Boolean(pending);
    }
    function showCheckpointReview(review, changes) {
        el('adoptionCheckpointTarget').textContent = t('adoption.checkpointTarget',
            review.context.repositoryId, review.context.branch, review.context.commit);
        el('adoptionCheckpointChanges').textContent = changes.map(function (change) {
            return [change.id, t('editor.before'), change.before ?? t('editor.absent'),
                t('editor.after'), change.after ?? t('editor.absent')].join('\n');
        }).join('\n\n');
        el('adoptionCheckpointReview').hidden = false;
    }
    function showCheckpointReceipt(commit) {
        var receipt = el('adoptionCheckpointReceipt');
        receipt.textContent = commit ? t('adoption.checkpointSaved', commit) : '';
        receipt.hidden = !commit;
    }
    function clearPreview() {
        pending = pendingCheckpoint = attempted = null;
        el('adoptionSummary').hidden = true;
        el('adoptionSummary').textContent = '';
        el('adoptionDifferenceDetails').hidden = true;
        el('adoptionDifferenceDetails').open = false;
        el('adoptionDifferences').textContent = '';
        el('adoptionPreview').hidden = false;
        el('adoptionCheckpointReview').hidden = true;
        el('adoptionCheckpointDetails').open = false;
        el('adoptionCheckpointTarget').textContent = '';
        el('adoptionCheckpointChanges').textContent = '';
    }
    async function preview(focusSummary) {
        if (busy || locked || !view?.mayEdit || !source) return;
        clearPreview(); showCheckpointReceipt(); setBusy(true); var ticket = generation;
        try {
            var request = {context:view.document.context,metadata:options.metadata(),source:Object.assign({},source)};
            var result = await options.api.previewAdoption(request);
            if (ticket !== generation) return;
            pending = result.summary.conflicts.length ? null : Object.freeze(Object.assign({},request,{previewFingerprint:result.fingerprint}));
            showSummary(result);
            var review = result.checkpointReview;
            var changes = result.preview?.change?.changes;
            var sameTarget = review?.context?.commit && ['repositoryId', 'workspaceScopeKey', 'branch', 'commit', 'revision', 'actor', 'writeMode']
                .every(function (key) { return review.context[key] === request.context[key]; });
            if (pending && review?.fingerprint && sameTarget && changes?.length) {
                pendingCheckpoint = Object.freeze(Object.assign({}, pending, {checkpointFingerprint:review.fingerprint}));
                showCheckpointReview(review, changes);
            }
        } catch (error) { options.report(error); }
        finally {
            setBusy(false);
            if (ticket === generation) el(focusSummary === true && !el('adoptionSummary').hidden
                ? 'adoptionSummary' : pending ? 'adoptionAccept' : 'adoptionPreview').focus();
        }
    }
    async function accept(request) {
        if (!canAccept(request)) return;
        attempted = request;
        var ticket = generation, accepted = false, checkpoint = null;
        setBusy(true);
        try {
            var result = await options.api.adoptSnapshot(request);
            if (ticket !== generation) return;
            checkpoint = result?.checkpoint?.commitId;
            if (request.checkpointFingerprint && !checkpoint) throw new Error(t('adoption.checkpointUnexpected'));
            clearPreview();
            accepted = true;
            if (checkpoint) showCheckpointReceipt(checkpoint);
            if (options.onAccepted) await options.onAccepted(result, request.context);
        } catch (error) {
            if (ticket !== generation) return;
            options.report(error);
            // Timeouts and transport/server failures may follow durable acceptance: retain this exact intent.
            if (error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429) {
                clearPreview();
            }
        } finally {
            setBusy(false);
            if (checkpoint && !el('adoptionCheckpointReceipt').hidden) el('adoptionCheckpointReceipt').focus();
            else if (accepted || ticket === generation) el(pendingCheckpoint && attempted === pendingCheckpoint
                ? 'adoptionAcceptCheckpoint' : pending ? 'adoptionAccept' : 'adoptionPreview').focus();
        }
    }
    el('adoptionPreview').addEventListener('click',preview);
    el('adoptionAccept').addEventListener('click',function () { return accept(pending); });
    el('adoptionAcceptCheckpoint').addEventListener('click',function () { return accept(pendingCheckpoint); });
    el('adoptionCancel').addEventListener('click',function () { if (!busy) { clearPreview(); render(); el('adoptionPreview').focus(); } });
    el('adoptionView').addEventListener('change',function () { if (!busy && options.onSelectView) options.onSelectView(el('adoptionView').value || null); });
    return {
        getSource:function () { return source ? Object.assign({},source) : null; },
        update:function (next) {
            var selectedId = options.selectedView ? options.selectedView() : null;
            var selected = next.model.views.find(function (item) { return item.id === selectedId; });
            var nextSource = sourceFromView(selected) || window.TaxonomyArchitectureWorkflow?.adoptionSource();
            var changedSource = sourceKey(source) !== sourceKey(nextSource);
            var changedBranch = view && !sameBranch(view.document.context, next.document.context);
            if (changedBranch || changedSource) showCheckpointReceipt();
            if (changedBranch || changedSource || (view && JSON.stringify(view.document.context) !== JSON.stringify(next.document.context))) {
                generation++; clearPreview();
            }
            view = next;
            source = nextSource ? Object.assign({},nextSource) : null;
            var selector = el('adoptionView'); selector.replaceChildren();
            var full = document.createElement('option'); full.value = ''; full.textContent = t('adoption.fullModel'); selector.append(full);
            view.model.views.forEach(function (item) { var option = document.createElement('option'); option.value = item.id; option.textContent = viewTitle(item); selector.append(option); });
            selector.value = selectedId || '';
            var provenance = selected?.extensions || {};
            el('adoptionProvenance').textContent = Object.keys(provenance).filter(function (key) { return key.startsWith('x-analysis-') && key !== 'x-analysis-relation-members'; })
                .sort().map(function (key) { return t('adoption.provenance.' + key.substring('x-analysis-'.length)) + ': ' + provenance[key]; }).join('\n');
            var link = el('adoptionSourceLink'); link.hidden = !source;
            if (source) {
                var url = new URL(window.TaxonomyI18n?.resolveUrl('/architecture/workbench') || '/architecture/workbench',window.location.href || 'https://example.test');
                var values = {repositoryId:source.repositoryId,workspaceId:workspaceId(source),
                    branch:source.branch,projectId:source.projectId,snapshotId:source.snapshotId};
                Object.keys(values).forEach(function (key) { url.searchParams.set(key,values[key]); });
                if (window.TaxonomyI18n?.getLocale) url.searchParams.set('lang',window.TaxonomyI18n.getLocale());
                link.href = url.pathname + url.search;
            }
            render();
        },
        setLocked:function (value) { locked = value; render(); },
        showPreview:function () {
            if (!source) return;
            if (view?.mayEdit) return preview(true);
            el('adoptionHeading').focus();
        }
    };
};
