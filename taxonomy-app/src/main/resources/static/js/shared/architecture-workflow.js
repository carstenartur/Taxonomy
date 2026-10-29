/* Source-aware navigation for standalone architecture and portfolio views.
 * The primary analysis page retains its own live session/transport controller.
 */
(function () {
    'use strict';
    var params = new URLSearchParams(window.location.search);
    var workspacePin = params.has('workspaceId') ? params.get('workspaceId') : null;
    var decoratedLinks = new WeakMap();
    function t(key) { return window.TaxonomyI18n.t(key); }
    function element(id) { return document.getElementById(id); }
    function positive(value) { return Number.isSafeInteger(Number(value)) && Number(value) > 0; }
    function snapshotId(value) { return typeof value === 'string' && /^[A-Za-z0-9._-]{1,128}$/.test(value); }
    function pathUrl(path, values, hash) {
        var resolved = window.TaxonomyI18n.resolveUrl(path);
        var target = new URL(resolved, window.location.href);
        Object.keys(values || {}).forEach(function (key) {
            if (values[key] !== null && values[key] !== undefined) target.searchParams.set(key, values[key]);
        });
        target.searchParams.set('lang', window.TaxonomyI18n.getLocale());
        if (hash) target.hash = hash;
        return target.pathname + target.search + target.hash;
    }
    function applicationPath(target) {
        var prefix = window.TaxonomyI18n.getBasePath?.() || '';
        if (prefix && target.pathname.startsWith(prefix + '/')) return target.pathname.slice(prefix.length);
        return target.pathname;
    }
    function eligible(path) {
        return path === '/' || /^\/(?:api\/)?projects(?:\/|$)/.test(path)
            || /^\/architecture\/(?:workbench|editor)$/.test(path);
    }
    function scopeUrl(value) {
        if (workspacePin === null || typeof value !== 'string' || value.startsWith('#')) return value;
        var target;
        try { target = new URL(value, window.location.href); } catch (_) { return value; }
        if (target.origin !== window.location.origin || !eligible(applicationPath(target))
                || target.searchParams.has('workspaceId')) return value;
        target.searchParams.set('workspaceId', workspacePin);
        return /^[a-z][a-z\d+.-]*:/i.test(value) ? target.href : target.pathname + target.search + target.hash;
    }
    function sourceLinks(project, requirement, snapshot, workspace) {
        var common = {workspaceId: workspace};
        var detail = '/projects/' + project + '/requirements/' + requirement;
        return {
            requirement: pathUrl(detail, Object.assign({snapshot: snapshot}, common)),
            clarify: pathUrl(detail, Object.assign({snapshot: snapshot}, common), 'reformulationOffers'),
            portfolio: pathUrl('/projects', Object.assign({projectId: project}, common)),
            snapshot: pathUrl('/architecture/workbench', Object.assign({projectId: project, snapshotId: snapshot}, common))
        };
    }
    function portfolioUrl(project) {
        return positive(project) ? scopeUrl(pathUrl('/projects', {projectId: project})) : '#';
    }
    function workbenchUrl(project, snapshot) {
        return positive(project) && snapshotId(snapshot)
            ? scopeUrl(pathUrl('/architecture/workbench', {projectId: project, snapshotId: snapshot})) : '#';
    }
    function snapshotLinks(data, selected) {
        if (!data || !positive(data.projectId) || !positive(data.requirementId) || !snapshotId(data.snapshotId)) {
            return {requirement: null, clarify: null, portfolio: null, snapshot: null, editor: null};
        }
        var workspace = data.workspaceId || '';
        var links = sourceLinks(data.projectId, data.requirementId, data.snapshotId, workspace);
        var repository = data.exportProvenance?.repositoryId;
        links.editor = repository && data.branchName ? pathUrl('/architecture/editor', {
            repositoryId: repository, workspaceScopeKey: workspace || '__shared__', workspaceId: workspace,
            branch: data.branchName, element: selected || null,
            sourceProjectId: data.projectId, sourceRequirementId: data.requirementId,
            sourceSnapshotId: data.snapshotId, sourceWorkspaceId: workspace
        }) : null;
        return links;
    }
    function editorSourceLinks() {
        var current = new URLSearchParams(window.location.search);
        var project = current.get('sourceProjectId'), requirement = current.get('sourceRequirementId');
        var snapshot = current.get('sourceSnapshotId');
        if (!positive(project) || !positive(requirement) || !snapshotId(snapshot) || !current.has('sourceWorkspaceId')) return null;
        return sourceLinks(project, requirement, snapshot, current.get('sourceWorkspaceId'));
    }
    function preferredProject(projects, preferredId, storedId) {
        var current = new URLSearchParams(window.location.search);
        var explicit = preferredId != null || current.has('projectId');
        var candidate = preferredId != null ? preferredId : current.has('projectId') ? current.get('projectId') : storedId;
        return projects.find(function (project) { return positive(candidate) && project.id === Number(candidate); })
            || (!explicit && projects[0]) || null;
    }
    function link(id, target) {
        var node = element(id);
        if (!node) return;
        node.hidden = !target;
        if (target) node.setAttribute('href', target);
        else node.removeAttribute('href');
    }
    function showLinks(links) {
        ['requirement', 'clarify', 'portfolio', 'snapshot', 'editor'].forEach(function (name) {
            link('workflow-' + name, links && links[name]);
        });
    }
    function showSnapshot(data, selected) {
        var root = element('architectureWorkflow');
        if (!root || !data) return;
        var links = snapshotLinks(data, selected);
        if (!links.requirement) { root.hidden = true; return; }
        workspacePin = data.workspaceId || '';
        decorateLinks();
        showLinks(links);
        link('workflow-snapshot', null); // Already viewing this snapshot.
        element('workflowIdentity').textContent = [data.projectKey || data.projectId,
            data.requirementKey || data.requirementId, data.requirementTitle].filter(Boolean).join(' · ');
        element('workflowState').textContent = t('workflow.snapshot') + ' ' + data.snapshotId;
        element('workflowExplanation').textContent = t(links.editor ? 'workflow.snapshot.help' : 'workflow.snapshot.missingContext');
        root.hidden = false;
    }
    function showEditor(context) {
        if (context && context.workspaceScopeKey !== undefined) {
            workspacePin = context.workspaceScopeKey === '__shared__' ? '' : context.workspaceScopeKey;
        }
        decorateLinks();
        var root = element('architectureWorkflow');
        if (!root) return;
        var links = editorSourceLinks();
        showLinks(links);
        var current = new URLSearchParams(window.location.search);
        element('workflowIdentity').textContent = links
            ? t('workflow.source') + ' ' + current.get('sourceProjectId') + ' / ' + current.get('sourceRequirementId')
            : t('workflow.editor');
        element('workflowState').textContent = links ? t('workflow.snapshot') + ' ' + current.get('sourceSnapshotId') : '';
        element('workflowExplanation').textContent = t(links ? 'workflow.editor.help' : 'workflow.editor.standalone');
        root.hidden = false;
    }

    // Only project APIs use this standalone pin. The editor passes its exact
    // context explicitly, and account/system APIs must remain unscoped.
    var nativeFetch = window.fetch;
    if (nativeFetch) window.fetch = function (input, options) {
        var scoped = input;
        if (typeof input === 'string') {
            try {
                var target = new URL(input, window.location.href);
                if (/^\/api\/projects(?:\/|$)/.test(applicationPath(target))) scoped = scopeUrl(input);
            } catch (_) { /* Let the normal transport report malformed URLs. */ }
        }
        return nativeFetch.call(window, scoped, options);
    };
    function decorateLinks() {
        document.querySelectorAll('a[href]').forEach(function (node) {
            var present = node.getAttribute('href'), previous = decoratedLinks.get(node);
            var original = previous && previous.scoped === present ? previous.original : present;
            var scoped = scopeUrl(original);
            decoratedLinks.set(node, {original: original, scoped: scoped});
            if (scoped !== present) node.setAttribute('href', scoped);
        });
    }
    document.addEventListener('click', decorateLinks, true);
    document.addEventListener('auxclick', decorateLinks, true);
    if (typeof MutationObserver !== 'undefined') {
        new MutationObserver(decorateLinks).observe(document.body, {childList: true, subtree: true, attributes: true, attributeFilter: ['href']});
    }
    decorateLinks();
    window.TaxonomyArchitectureWorkflow = {
        scopeUrl: scopeUrl, snapshotLinks: snapshotLinks, editorSourceLinks: editorSourceLinks,
        showSnapshot: showSnapshot, showEditor: showEditor, preferredProject: preferredProject,
        portfolioUrl: portfolioUrl, workbenchUrl: workbenchUrl
    };
}());
