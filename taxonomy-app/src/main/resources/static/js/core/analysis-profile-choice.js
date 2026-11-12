(function () {
    'use strict';
    var panel = document.getElementById('analysisProfileChoice');
    var workspace = window.TaxonomyArchitectureWorkspace;
    var lifecycle = window.TaxonomyArchitectureLifecycle;
    if (!panel || !workspace || !lifecycle || !window.TaxonomyCapabilities) return;
    var link = document.getElementById('analysisProfileEditor');
    var reason = document.getElementById('analysisProfileReason');
    var page = location.hash.slice(1) || 'analyze';
    var generation = 0, current = null, pending = null, available = null;

    async function refresh() {
        var ticket = ++generation;
        panel.hidden = true;
        current = null;
        if (page !== 'analyze') return;
        try {
            if (!available) available = window.TaxonomyCapabilities.load().then(function (value) {
                return value.features.includes('analysis') && value.features.includes('architecture');
            }).catch(function (failure) { available = null; throw failure; });
            if (!await available || ticket !== generation) return;
            await lifecycle.ready();
            if (ticket !== generation) return;
            var scope = await workspace.analysisScope();
            var session = window.TaxonomyAnalysisSession?.state?.();
            if (ticket !== generation || !session?.ready || (session.workspaceId || '__shared__') !== scope.workspaceScopeKey) return;
            var model = lifecycle.selectedAnalysisModel();
            var selected = lifecycle.selectedContext();
            if (model && (!selected || ['repositoryId','workspaceScopeKey','branch'].some(function (key) {
                return scope[key] !== selected[key];
            }))) return;
            var key = JSON.stringify([scope.repositoryId, scope.workspaceScopeKey, scope.branch, model]);
            if (!pending || pending.key !== key) {
                var request = {key:key, result:window.ArchitectureEditorApi.analysisBrief(scope, model)};
                pending = request;
                request.result.finally(function () { if (pending === request) pending = null; }).catch(function () {});
            }
            var result = await pending.result;
            if (ticket !== generation || page !== 'analyze' || result.decision === 'ASSIGNED'
                    || !['UNASSIGNED','OPEN','CONFLICTING_EDITIONS','UNRESOLVED'].includes(result.decision)
                    || ['repositoryId','workspaceScopeKey','branch'].some(function (name) { return result[name] !== scope[name]; })
                    || (model?.expectedCommit && model.expectedCommit !== result.commit)
                    || (model?.expectedSemanticRevision != null && model.expectedSemanticRevision !== result.semanticRevision)
                    || JSON.stringify(model) !== JSON.stringify(lifecycle.selectedAnalysisModel())) return;
            var source = {kind:'working', repositoryId:scope.repositoryId, workspaceScopeKey:scope.workspaceScopeKey,
                workspaceId:scope.workspaceScopeKey === '__shared__' ? '' : scope.workspaceScopeKey, branch:scope.branch};
            if (result.semanticRevision != null) source.revision = String(result.semanticRevision);
            else if (result.commit) source.commit = result.commit;
            else return;
            var editor = new URL(window.TaxonomyArchitectureWorkspaceController.urlFor(new URL(location.href), source,
                    window.TaxonomyI18n.resolveUrl('/')));
            editor.searchParams.set('architecturePanel', 'brief');
            link.href = editor.href;
            var t = window.TaxonomyI18n.t;
            reason.textContent = t('analysis.profile.' + result.decision);
            link.textContent = t('analysis.profile.' + (result.editable ? 'edit' : 'view'));
            current = source;
            panel.hidden = false;
        } catch (_) {
            // The optional profile action can only be offered for a server-confirmed selection.
        }
    }

    function focusBrief() {
        var heading = document.getElementById('editorBriefTitle');
        if (!heading) return;
        heading.tabIndex = -1;
        heading.focus({preventScroll:true});
        heading.scrollIntoView({block:'center'});
    }

    link.addEventListener('click', async function (event) {
        if (!current || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        var source = current;
        if (await workspace.open('working', {source:source})) focusBrief();
    });
    document.addEventListener('taxonomy:page-activated', function (event) { page = event.detail.page; refresh(); });
    document.addEventListener('taxonomy:analysis-source-ready', refresh);
    lifecycle.onStandChange(refresh);
    if (page === 'architecture' && new URL(location.href).searchParams.get('architecturePanel') === 'brief') {
        workspace.ready().then(function (ready) { if (ready && workspace.getSource().kind === 'working') focusBrief(); });
    }
    refresh();
}());
