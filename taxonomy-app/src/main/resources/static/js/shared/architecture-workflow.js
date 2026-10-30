/* Source-aware navigation for standalone architecture and portfolio views.
 * The primary analysis page retains its own live session/transport controller.
 */
(function () {
    'use strict';
    var params = new URLSearchParams(window.location.search);
    var workspacePin = params.has('workspaceId') ? params.get('workspaceId') : null;
    var repositoryPin = params.has('repositoryId') ? params.get('repositoryId') : null;
    var branchPin = params.has('branch') ? params.get('branch') : null;
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
            || /^\/architecture\/(?:workbench|editor)$/.test(path)
            || /^\/api\/architecture\/(?:lifecycle|editor)(?:\/|$)/.test(path);
    }
    function getScope() {
        var current = new URLSearchParams(window.location.search);
        var value = {};
        if (repositoryPin !== null) value.repositoryId = repositoryPin;
        if (branchPin !== null) value.branch = branchPin;
        if (workspacePin !== null) {
            value.workspaceId = workspacePin;
            value.workspaceScopeKey = workspacePin || '__shared__';
        } else if (current.has('workspaceScopeKey')) {
            value.workspaceScopeKey = current.get('workspaceScopeKey');
            value.workspaceId = value.workspaceScopeKey === '__shared__' ? '' : value.workspaceScopeKey;
        }
        return value;
    }
    function setScope(context) {
        if (!context) return;
        if (context.repositoryId !== undefined) repositoryPin = context.repositoryId;
        if (context.branch !== undefined) branchPin = context.branch;
        if (context.workspaceScopeKey !== undefined) workspacePin = context.workspaceScopeKey === '__shared__' ? '' : context.workspaceScopeKey;
        else if (context.workspaceId !== undefined) workspacePin = context.workspaceId;
        decorateLinks();
    }
    function scopeUrl(value) {
        if ((workspacePin === null && repositoryPin === null && branchPin === null) || typeof value !== 'string' || value.startsWith('#')) return value;
        var target;
        try { target = new URL(value, window.location.href); } catch (_) { return value; }
        if (target.origin !== window.location.origin || !eligible(applicationPath(target))) return value;
        var pins = {workspaceId:workspacePin,repositoryId:repositoryPin,branch:branchPin};
        var keys = Object.keys(pins).filter(function (key) { return pins[key] !== null && pins[key] !== undefined; });
        if (keys.some(function (key) { return target.searchParams.has(key) && target.searchParams.get(key) !== pins[key]; })) return value;
        keys.forEach(function (key) { if (!target.searchParams.has(key)) target.searchParams.set(key,pins[key]); });
        return /^[a-z][a-z\d+.-]*:/i.test(value) ? target.href : target.pathname + target.search + target.hash;
    }
    function sourceLinks(project, requirement, snapshot, workspace, repository, branch) {
        var common = {workspaceId: workspace, repositoryId: repository || null, branch: branch || null};
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
            return {requirement: null, clarify: null, portfolio: null, snapshot: null, editor: null, adopt: null};
        }
        var workspace = data.workspaceId || '';
        var repository = data.exportProvenance?.repositoryId;
        var links = sourceLinks(data.projectId, data.requirementId, data.snapshotId, workspace, repository, data.branchName);
        links.editor = repository && data.branchName ? pathUrl('/architecture/editor', {
            repositoryId: repository, workspaceScopeKey: workspace || '__shared__', workspaceId: workspace,
            branch: data.branchName, element: selected || null,
            sourceProjectId: data.projectId, sourceRequirementId: data.requirementId,
            sourceSnapshotId: data.snapshotId, sourceWorkspaceId: workspace,
            sourceRepositoryId: repository, sourceBranch: data.branchName,
            sourceWorkspaceScopeKey: workspace || '__shared__', sourceRequirementVersionId: data.exportProvenance?.requirementVersionId || null
        }) : null;
        links.adopt = links.editor && positive(data.exportProvenance?.requirementVersionId)
            ? links.editor + '&adoption=preview' : null;
        return links;
    }
    function adoptionSource() {
        var current = new URLSearchParams(window.location.search);
        var repository = current.get('sourceRepositoryId'), branch = current.get('sourceBranch');
        var scope = current.get('sourceWorkspaceScopeKey');
        if (!repository || !branch || !scope || !positive(current.get('sourceProjectId'))
                || !positive(current.get('sourceRequirementId')) || !positive(current.get('sourceRequirementVersionId'))
                || !snapshotId(current.get('sourceSnapshotId'))) return null;
        return {repositoryId: repository, workspaceScopeKey: scope, branch: branch,
            projectId: Number(current.get('sourceProjectId')), requirementId: Number(current.get('sourceRequirementId')),
            requirementVersionId: Number(current.get('sourceRequirementVersionId')), snapshotId: current.get('sourceSnapshotId')};
    }
    function editorSourceLinks(source) {
        if (source) {
            if (!positive(source.projectId) || !positive(source.requirementId) || !snapshotId(source.snapshotId)
                    || !source.repositoryId || !source.branch || !source.workspaceScopeKey) return null;
            return sourceLinks(source.projectId,source.requirementId,source.snapshotId,
                source.workspaceScopeKey === '__shared__' ? '' : source.workspaceScopeKey,source.repositoryId,source.branch);
        }
        var current = new URLSearchParams(window.location.search);
        var project = current.get('sourceProjectId'), requirement = current.get('sourceRequirementId');
        var snapshot = current.get('sourceSnapshotId');
        if (!positive(project) || !positive(requirement) || !snapshotId(snapshot) || !current.has('sourceWorkspaceId')) return null;
        return sourceLinks(project, requirement, snapshot, current.get('sourceWorkspaceId'), current.get('sourceRepositoryId'), current.get('sourceBranch'));
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
        ['requirement', 'clarify', 'portfolio', 'snapshot', 'editor', 'adopt'].forEach(function (name) {
            link('workflow-' + name, links && links[name]);
        });
    }
    function showSnapshot(data, selected) {
        var root = element('architectureWorkflow');
        if (!root || !data) return;
        var links = snapshotLinks(data, selected);
        if (!links.requirement) { root.hidden = true; return; }
        workspacePin = data.workspaceId || '';
        repositoryPin = data.exportProvenance?.repositoryId || repositoryPin; branchPin = data.branchName || branchPin;
        decorateLinks();
        showLinks(links);
        link('workflow-snapshot', null); // Already viewing this snapshot.
        element('workflowIdentity').textContent = [data.projectKey || data.projectId,
            data.requirementKey || data.requirementId, data.requirementTitle].filter(Boolean).join(' · ');
        element('workflowState').textContent = t('workflow.snapshot') + ' ' + data.snapshotId;
        element('workflowExplanation').textContent = t(links.editor ? 'workflow.snapshot.help' : 'workflow.snapshot.missingContext');
        root.hidden = false;
    }
    function showEditor(context, source) {
        if (context && context.workspaceScopeKey !== undefined) {
            workspacePin = context.workspaceScopeKey === '__shared__' ? '' : context.workspaceScopeKey;
            repositoryPin = context.repositoryId; branchPin = context.branch;
        }
        decorateLinks();
        var root = element('architectureWorkflow');
        if (!root) return;
        var links = editorSourceLinks(source);
        showLinks(links);
        var current = new URLSearchParams(window.location.search);
        element('workflowIdentity').textContent = links
            ? t('workflow.source') + ' ' + (source ? source.projectId : current.get('sourceProjectId')) + ' / ' + (source ? source.requirementId : current.get('sourceRequirementId'))
            : t('workflow.editor');
        element('workflowState').textContent = links ? t('workflow.snapshot') + ' ' + (source ? source.snapshotId : current.get('sourceSnapshotId')) : '';
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
                if (/^\/api\/(?:projects|architecture\/(?:lifecycle|editor))(?:\/|$)/.test(applicationPath(target))) scoped = scopeUrl(input);
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
        getScope: getScope, setScope: setScope, adoptionSource: adoptionSource, scopeUrl: scopeUrl, snapshotLinks: snapshotLinks, editorSourceLinks: editorSourceLinks,
        showSnapshot: showSnapshot, showEditor: showEditor, preferredProject: preferredProject,
        portfolioUrl: portfolioUrl, workbenchUrl: workbenchUrl
    };
}());
