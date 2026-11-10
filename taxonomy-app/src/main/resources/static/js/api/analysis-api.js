/* Named analysis requests. Graph and obligation reads require an explicit frozen source. */
window.AnalysisApi = (function () {
    'use strict';
    var client = window.TaxonomyApiClient;

    function snapshot(context) {
        var scope = context || {};
        var workspace = scope.workspaceScopeKey;
        if (workspace === undefined && typeof scope.workspaceId === 'string') workspace = scope.workspaceId || '__shared__';
        var commit = scope.commit || scope.commitId;
        if (typeof scope.repositoryId !== 'string' || !scope.repositoryId.trim()
                || typeof scope.branch !== 'string' || !scope.branch.trim()
                || typeof workspace !== 'string' || !workspace
                || typeof commit !== 'string' || !/^[0-9a-f]{40}$/.test(commit)) {
            throw new Error(window.TaxonomyI18n?.t('analysis.evidence.selectVersion')
                || 'Select a retained architecture version before evaluating its graph.');
        }
        return Object.freeze({repositoryId:scope.repositoryId, workspaceScopeKey:workspace,
            branch:scope.branch, commit:commit});
    }

    async function captureContext() {
        var lifecycle = window.TaxonomyArchitectureLifecycle;
        await lifecycle?.ready();
        // Chrome is the explicitly visible selection. A retained suspended overview
        // cannot supply missing identity or silently replace that selection.
        var visible = snapshot(window.TaxonomyAppChrome?.getContext()?.architecture);
        var active = lifecycle?.selectedContext?.();
        if (active && JSON.stringify(snapshot(active)) !== JSON.stringify(visible)) snapshot(null);
        var workspace = window.TaxonomyArchitectureWorkspace;
        if (workspace?.isActive() && workspace.getSource().kind === 'working'
                && JSON.stringify(snapshot(workspace.getSource())) !== JSON.stringify(visible)) snapshot(null);
        return visible;
    }

    function scoped(path, context, values) {
        var source = snapshot(context), query = new URLSearchParams(values || {});
        var workspace = source.workspaceScopeKey === '__shared__' ? '' : source.workspaceScopeKey;
        query.set('repositoryId', source.repositoryId); query.set('branch', source.branch);
        query.set('commitId', source.commit); query.set('workspaceId', workspace);
        return {url:path + '?' + query, policy:{pinnedWorkspaceId:workspace, retries:0}};
    }
    function read(path, context, values) {
        var request = scoped(path, context, values);
        return client.getJson(request.url, request.policy);
    }
    function assess(path, payload, context) {
        var request = scoped(path, context);
        return client.sendJson(request.url, payload, 'POST', request.policy);
    }
    function neighborhood(direction, nodeCode, maxHops, context) {
        return read('/api/graph/node/' + encodeURIComponent(nodeCode) + '/' + direction, context, {maxHops:maxHops});
    }

    return Object.freeze({
        captureContext:captureContext,
        gapProfiles:function () { return client.getJson('/api/gap/profiles'); },
        analyzeGap:function (payload, context) { return assess('/api/gap/analyze', payload, context); },
        recommend:function (payload, context) { return assess('/api/recommend', payload, context); },
        detectPatterns:function (payload, context) { return assess('/api/patterns/detect', payload, context); },
        requirementImpact:function (payload, context) { return assess('/api/graph/impact', payload, context); },
        enrichedFailureImpact:function (code, hops, context) { return neighborhood('enriched-failure-impact', code, hops, context); },
        graphUpstream:function (code, hops, context) { return neighborhood('upstream', code, hops, context); },
        graphDownstream:function (code, hops, context) { return neighborhood('downstream', code, hops, context); },
        graphFailureImpact:function (code, hops, context) { return neighborhood('failure-impact', code, hops, context); },
        apqcHierarchy:function (context) { return read('/api/graph/apqc-hierarchy', context); },
        apqcCoverage:function (context) { return read('/api/gap/apqc-coverage', context); }
    });
}());
