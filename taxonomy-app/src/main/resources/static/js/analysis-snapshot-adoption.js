/* Deliberate frozen-source handoff. The server owns graph retrieval, preview and mutation. */
window.AnalysisSnapshotAdoption = function (options) {
    'use strict';
    var view = null, pending = null, busy = false, locked = false;
    var source = window.TaxonomyArchitectureWorkflow?.adoptionSource();
    function el(id) { return document.getElementById(id); }
    function t(key) { return options.t.apply(null, arguments); }
    function setBusy(value) { busy = value; if (options.onBusy) options.onBusy(value); render(); }
    function render() {
        el('snapshotAdoption').hidden = !source && !(view?.model.views || []).some(function (item) { return item.extensions?.['x-analysis-snapshot-id']; });
        el('adoptionPreview').disabled = !source || !view?.mayEdit || locked || busy;
        el('adoptionAccept').disabled = !pending || locked || busy;
        el('adoptionCancel').disabled = busy;
        el('adoptionAccept').hidden = !pending;
        el('adoptionCancel').hidden = !pending;
        el('adoptionSource').textContent = source ? [source.repositoryId, source.workspaceScopeKey, source.branch,
            source.projectId + '/' + source.requirementId + '/v' + source.requirementVersionId, source.snapshotId].join(' · ') : '';
        var context = view?.document.context;
        el('adoptionTarget').textContent = context ? [context.repositoryId,context.workspaceScopeKey,context.branch,
            t('editor.revision',context.revision)].join(' · ') : '';
        el('adoptionHelp').textContent = t(view?.mayEdit ? 'adoption.help' : 'adoption.createCopy');
    }
    function sourceFromView(item) {
        var p = item?.extensions;
        return p?.['x-analysis-snapshot-id'] ? {repositoryId:p['x-analysis-repository-id'],workspaceScopeKey:p['x-analysis-workspace-scope'],
            branch:p['x-analysis-branch'],projectId:Number(p['x-analysis-project-id']),requirementId:Number(p['x-analysis-requirement-id']),
            requirementVersionId:Number(p['x-analysis-requirement-version-id']),snapshotId:p['x-analysis-snapshot-id']} : null;
    }
    function showSummary(result) {
        var summary = result.summary;
        var text = [t('adoption.summary',summary.addedElements.length,summary.reusedElements.length,
            summary.addedRelations.length,summary.reusedRelations.length,summary.conflicts.length)];
        if (result.existing) text.push(t('adoption.existing'));
        summary.differences.forEach(function (item) {
            text.push(item.id + ' · ' + item.field + '\n' + t('adoption.preserve') + ': ' + (item.existingValue || '')
                + '\n' + t('adoption.recorded') + ': ' + (item.sourceValue || ''));
        });
        summary.conflicts.forEach(function (conflict) { text.push(conflict); });
        el('adoptionSummary').textContent = text.join('\n\n');
        el('adoptionSummary').hidden = false;
    }
    async function preview() {
        if (busy || locked || !view?.mayEdit || !source) return;
        pending = null; setBusy(true);
        try {
            var request = {context:view.document.context,metadata:options.metadata(),source:Object.assign({},source)};
            var result = await options.api.previewAdoption(request);
            pending = result.summary.conflicts.length ? null : Object.freeze(Object.assign({},request,{previewFingerprint:result.fingerprint}));
            showSummary(result);
        } catch (error) { options.report(error); }
        finally { setBusy(false); el(pending ? 'adoptionAccept' : 'adoptionPreview').focus(); }
    }
    async function accept() {
        if (busy || locked || !pending) return;
        setBusy(true);
        try {
            var result = await options.api.adoptSnapshot(pending);
            pending = null;
            if (options.onAccepted) await options.onAccepted(result);
        } catch (error) {
            options.report(error);
            // Timeouts and transport/server failures may follow durable acceptance: retain this exact intent.
            if (error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429) pending = null;
        } finally { setBusy(false); el(pending ? 'adoptionAccept' : 'adoptionPreview').focus(); }
    }
    el('adoptionPreview').addEventListener('click',preview);
    el('adoptionAccept').addEventListener('click',accept);
    el('adoptionCancel').addEventListener('click',function () { if (!busy) { pending = null; render(); el('adoptionPreview').focus(); } });
    el('adoptionView').addEventListener('change',function () { if (!busy && options.onSelectView) options.onSelectView(el('adoptionView').value || null); });
    return {
        getSource:function () { return source ? Object.assign({},source) : null; },
        update:function (next) {
            if (view && JSON.stringify(view.document.context) !== JSON.stringify(next.document.context)) pending = null;
            view = next;
            var selectedId = options.selectedView ? options.selectedView() : null;
            var selected = view.model.views.find(function (item) { return item.id === selectedId; });
            source = sourceFromView(selected) || window.TaxonomyArchitectureWorkflow?.adoptionSource();
            var selector = el('adoptionView'); selector.replaceChildren();
            var full = document.createElement('option'); full.value = ''; full.textContent = t('adoption.fullModel'); selector.append(full);
            view.model.views.forEach(function (item) { var option = document.createElement('option'); option.value = item.id; option.textContent = item.title || item.id; selector.append(option); });
            selector.value = selectedId || '';
            var provenance = selected?.extensions || {};
            el('adoptionProvenance').textContent = Object.keys(provenance).filter(function (key) { return key.startsWith('x-analysis-') && key !== 'x-analysis-relation-members'; })
                .sort().map(function (key) { return t('adoption.provenance.' + key.substring('x-analysis-'.length)) + ': ' + provenance[key]; }).join('\n');
            var link = el('adoptionSourceLink'); link.hidden = !source;
            if (source) {
                var url = new URL(window.TaxonomyI18n?.resolveUrl('/architecture/workbench') || '/architecture/workbench',window.location.href || 'https://example.test');
                var values = {repositoryId:source.repositoryId,workspaceId:source.workspaceScopeKey === '__shared__' ? '' : source.workspaceScopeKey,
                    branch:source.branch,projectId:source.projectId,snapshotId:source.snapshotId};
                Object.keys(values).forEach(function (key) { url.searchParams.set(key,values[key]); });
                if (window.TaxonomyI18n?.getLocale) url.searchParams.set('lang',window.TaxonomyI18n.getLocale());
                link.href = url.pathname + url.search;
            }
            render();
        },
        setLocked:function (value) { locked = value; render(); }
    };
};
