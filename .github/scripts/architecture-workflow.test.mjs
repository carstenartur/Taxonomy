import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';

const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-workflow.js', import.meta.url), 'utf8').catch(() => '');
const snapshot = {
    projectId: 12, requirementId: 34, snapshotId: 'snapshot-v2',
    projectKey: 'CLINIC', projectTitle: 'Clinic', requirementKey: 'REQ-7',
    requirementTitle: 'Unified communication', workspaceId: 'private-source',
    branchName: 'draft/source', commitSha: 'a'.repeat(40),
    exportProvenance: {repositoryId: 'repo-source'}
};
function boot(search = '', prefix = '') {
    const requests = [];
    const elements = new Map();
    const context = vm.createContext({URL, URLSearchParams, Request, console,
        location: {href: 'https://example.test' + prefix + '/architecture/workbench' + search, search, origin: 'https://example.test'},
        document: {getElementById: id => elements.get(id) || null, querySelectorAll: () => [...elements.values()].filter(node => node.tagName === 'A'), addEventListener() {}},
        fetch: async (url, options) => {requests.push({url, options}); return {ok: true};},
        TaxonomyI18n: {getLocale: () => 'de', getBasePath: () => prefix, resolveUrl: path => prefix + path, t: key => key}
    });
    context.window = context;
    vm.runInContext(source, context);
    assert.ok(context.TaxonomyArchitectureWorkflow, 'The architecture workflow adapter is available');
    return {api: context.TaxonomyArchitectureWorkflow, context, requests, elements};
}
function url(value) {return new URL(value, 'https://example.test');}

for (const prefix of ['', '/taxonomy', '/teams/blue/taxonomy']) {
    test(`snapshot handoff retains requirement, source and selected element under ${prefix || '/'}`, () => {
        const {api} = boot('', prefix);
        const links = api.snapshotLinks(snapshot, 'UA-1574');
        const requirement = url(links.requirement), editor = url(links.editor);
        assert.equal(requirement.pathname, prefix + '/projects/12/requirements/34');
        assert.equal(requirement.searchParams.get('snapshot'), 'snapshot-v2');
        assert.equal(requirement.searchParams.get('workspaceId'), 'private-source');
        assert.equal(url(links.clarify).hash, '#reformulationOffers');
        assert.equal(editor.pathname, prefix + '/architecture/editor');
        assert.equal(editor.searchParams.get('workspaceScopeKey'), 'private-source');
        assert.equal(editor.searchParams.get('repositoryId'), 'repo-source');
        assert.equal(editor.searchParams.get('branch'), 'draft/source');
        assert.equal(editor.searchParams.get('element'), 'UA-1574');
        assert.equal(editor.searchParams.get('sourceSnapshotId'), 'snapshot-v2');
        assert.equal(editor.searchParams.get('lang'), 'de');
        assert.equal(editor.searchParams.has('commit'), false, 'Analysis source commit is not an adopted editor snapshot');
        assert.equal(editor.searchParams.has('revision'), false);
    });
}

for (const workspace of ['', 'private-source']) {
    test(`requirement page reopens its saved architecture with mounted path and ${workspace || 'shared'} scope`, async () => {
        const {context} = boot('?workspaceId=' + workspace, '/taxonomy');
        context.location.pathname = '/taxonomy/projects/12/requirements/34';
        context.document.documentElement = {lang: 'de'};
        const page = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js', import.meta.url), 'utf8');
        // Expose closure state without changing the production navigation body.
        vm.runInContext(page.replace(/\}\)\(\);\s*$/, 'window.detailProbe = {state, architectureWorkbenchUrl}; })();'), context);
        context.detailProbe.state.selectedSnapshot = {id: 'snapshot-v2'};
        const target = url(context.detailProbe.architectureWorkbenchUrl());
        assert.equal(target.pathname, '/taxonomy/architecture/workbench');
        assert.equal(target.searchParams.get('snapshotId'), 'snapshot-v2');
        assert.equal(target.searchParams.get('workspaceId'), workspace);
    });

    test(`successful import returns to its project and ${workspace || 'shared'} scope`, async () => {
        const {context} = boot('?workspaceId=' + workspace, '/taxonomy');
        context.location.pathname = '/taxonomy/projects/12/import';
        context.document.documentElement = {lang: 'de'};
        context.document.getElementById = () => ({checked: false, classList: {toggle() {}, remove() {}, add() {}}, focus() {}});
        context.localStorage = {removeItem() {}};
        context.setTimeout = callback => callback();
        context.TaxonomyPortfolioApi = {importReviewedRequirements: async () => ({json: async () => ({}), headers: {get: () => null}})};
        const page = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/portfolio/portfolio-import.js', import.meta.url), 'utf8');
        vm.runInContext(page.replace(/\}\)\(\);\s*$/, 'window.importProbe = {state, confirmImport}; })();'), context);
        context.importProbe.state.candidates = [{id: 1, decision: 'NEW', key: 'REQ-1', title: 'Requirement', text: 'Recorded requirement text', type: 'FUNCTIONAL', priority: 1, criticality: 'MEDIUM'}];
        await context.importProbe.confirmImport();
        const target = url(context.location.href);
        assert.equal(target.pathname, '/taxonomy/projects');
        assert.equal(target.searchParams.get('projectId'), '12');
        assert.equal(target.searchParams.get('workspaceId'), workspace);
    });
}

test('shared snapshot has an explicit central pin instead of falling back to the active workspace', () => {
    const {api} = boot('?workspaceId=other');
    const links = api.snapshotLinks({...snapshot, workspaceId: null});
    for (const key of ['requirement', 'clarify', 'portfolio', 'editor']) {
        assert.equal(url(links[key]).searchParams.has('workspaceId'), true);
        assert.equal(url(links[key]).searchParams.get('workspaceId'), '');
    }
    assert.equal(url(links.editor).searchParams.get('workspaceScopeKey'), '__shared__');
});

for (const patch of [{exportProvenance: null}, {branchName: null}, {snapshotId: ''}, {projectId: 0}, {requirementId: -1}]) {
    test(`incomplete provenance cannot open an unrelated editable model: ${JSON.stringify(patch)}`, () => {
        assert.equal(boot().api.snapshotLinks({...snapshot, ...patch}).editor, null);
    });
}

test('editor return uses original snapshot scope even after its current workspace changes', () => {
    const {api} = boot('?workspaceId=new-workspace&sourceProjectId=12&sourceRequirementId=34&sourceSnapshotId=snapshot-v2&sourceWorkspaceId=private-source');
    const links = api.editorSourceLinks();
    assert.equal(url(links.snapshot).pathname, '/architecture/workbench');
    assert.equal(url(links.snapshot).searchParams.get('snapshotId'), 'snapshot-v2');
    assert.equal(url(links.requirement).searchParams.get('workspaceId'), 'private-source');
});
test('editor return preserves an explicitly shared source', () => {
    const {api} = boot('?workspaceId=private&sourceProjectId=12&sourceRequirementId=34&sourceSnapshotId=snapshot-v2&sourceWorkspaceId=');
    assert.equal(url(api.editorSourceLinks().snapshot).searchParams.get('workspaceId'), '');
});
test('a partial editor source never manufactures a return target', () => {
    assert.equal(boot('?sourceProjectId=12&sourceRequirementId=34&sourceSnapshotId=snapshot-v2').api.editorSourceLinks(), null);
});

for (const workspace of ['private-one', '']) {
    test(`standalone project requests keep explicit workspace ${workspace || '(shared)'}`, async () => {
        const {context, requests} = boot('?workspaceId=' + workspace, '/taxonomy');
        await context.fetch('/api/projects/12/requirements', {method: 'POST', body: 'unchanged'});
        const request = requests[0];
        assert.equal(url(request.url).searchParams.has('workspaceId'), true);
        assert.equal(url(request.url).searchParams.get('workspaceId'), workspace);
        assert.equal(request.options.body, 'unchanged');
    });
}
for (const destination of ['https://elsewhere.test/api/projects/12', '/api/account', '/api/projects/12?workspaceId=explicit-other', '/taxonomy/api/projects/12?workspaceId=']) {
    test(`scope decoration leaves an external/global/explicit destination unchanged: ${destination}`, () => {
        assert.equal(boot('?workspaceId=current', '/taxonomy').api.scopeUrl(destination), destination);
    });
}
test('unscoped standalone pages do not invent a workspace pin', () => {
    assert.equal(boot().api.scopeUrl('/api/projects'), '/api/projects');
});
test('scoping preserves mounted path, fragment and existing query', () => {
    const result = url(boot('?workspaceId=private-one', '/taxonomy').api.scopeUrl('/taxonomy/projects/12/requirements/34?snapshot=v1#requirementReformulation'));
    assert.equal(result.pathname, '/taxonomy/projects/12/requirements/34');
    assert.equal(result.searchParams.get('snapshot'), 'v1');
    assert.equal(result.hash, '#requirementReformulation');
    assert.equal(result.searchParams.get('workspaceId'), 'private-one');
});

test('portfolio handoff selects the requested project instead of the last unrelated project', () => {
    const projects = [{id: 5}, {id: 12}];
    assert.equal(boot('?projectId=12').api.preferredProject(projects, null, 5).id, 12);
});
test('an inaccessible requested project does not silently display a different portfolio', () => {
    assert.equal(boot('?projectId=99').api.preferredProject([{id: 5}], null, 5), null);
});
test('creating a project overrides the older navigation target', () => {
    assert.equal(boot('?projectId=12').api.preferredProject([{id: 12}, {id: 17}], 17, 12).id, 17);
});
test('editor transport preserves an explicit shared pin', async () => {
    const editorSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/architecture-editor-api.js', import.meta.url), 'utf8');
    const {context} = boot();
    let requested;
    context.TaxonomyApiClient = {getJson: async value => {requested = value; return {};}};
    vm.runInContext(editorSource, context);
    await context.ArchitectureEditorApi.load({repositoryId: 'repo-source', workspaceScopeKey: '__shared__', branch: 'draft'}, null);
    assert.equal(url(requested).searchParams.has('workspaceId'), true);
    assert.equal(url(requested).searchParams.get('workspaceId'), '');
});
test('snapshot downloads retain the source workspace after the format suffix and preserve Word language', async () => {
    const apiSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/architecture-workbench-api.js', import.meta.url), 'utf8');
    const {context} = boot('?workspaceId=private-source', '/taxonomy');
    context.TaxonomyContentLanguage = {documentLanguage: () => 'de'};
    vm.runInContext(apiSource, context);
    for (const [method, suffix] of [['svgUrl', '.svg'], ['pdfUrl', '.pdf'], ['archiMateBundleUrl', '.archimate.zip'], ['visioBundleUrl', '.visio.zip']]) {
        const target = url(context.ArchitectureWorkbenchApi[method](12, 'snapshot-v2'));
        assert.equal(target.pathname, '/taxonomy/api/projects/12/architecture-workbench/snapshot-v2' + suffix);
        assert.equal(target.searchParams.get('workspaceId'), 'private-source');
    }
    const word = url(context.ArchitectureWorkbenchApi.architectureWordUrl(12, 'snapshot-v2'));
    assert.equal(word.searchParams.get('language'), 'de');
    assert.equal(word.searchParams.get('workspaceId'), 'private-source');
});
function anchor(href) {
    const attributes = new Map([['href', href]]);
    return {tagName: 'A', getAttribute: key => attributes.get(key),
        setAttribute: (key, value) => attributes.set(key, value), removeAttribute: key => attributes.delete(key)};
}
test('changing the editor workspace updates generic navigation while retaining an explicit source return', () => {
    const {api, elements} = boot('?workspaceId=old-workspace');
    const generic = anchor('/projects'), sourceReturn = anchor('/projects/12/requirements/34?workspaceId=source-workspace');
    elements.set('generic', generic); elements.set('sourceReturn', sourceReturn);
    api.showEditor({workspaceScopeKey: 'old-workspace'});
    assert.equal(url(generic.getAttribute('href')).searchParams.get('workspaceId'), 'old-workspace');
    api.showEditor({workspaceScopeKey: 'new-workspace'});
    assert.equal(url(generic.getAttribute('href')).searchParams.get('workspaceId'), 'new-workspace');
    assert.equal(url(sourceReturn.getAttribute('href')).searchParams.get('workspaceId'), 'source-workspace');
});

test('editor working-copy creation uses the always-available editor command with the exact central source', async () => {
    const editorSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/architecture-editor-api.js', import.meta.url), 'utf8');
    const {context} = boot('?workspaceId=');
    let request;
    context.TaxonomyApiClient = {sendJson: async (path, body) => {request = {path, body}; return {workspaceId: 'copy'};}};
    vm.runInContext(editorSource, context);
    const result = await context.ArchitectureEditorApi.createWorkspace({repositoryId: 'repo-source', workspaceScopeKey: '__shared__', branch: 'reviewed/draft'});
    assert.equal(url(request.path).pathname, '/api/architecture/editor/workspaces');
    assert.equal(url(request.path).searchParams.get('workspaceId'), '');
    assert.equal(url(request.path).searchParams.get('branch'), 'reviewed/draft');
    assert.equal(request.body.repositoryId, 'repo-source');
    assert.equal(request.body.branch, 'reviewed/draft');
    assert.equal(result.workspaceId, 'copy');
});

for (const prefix of ['', '/taxonomy', '/teams/blue/taxonomy']) {
    for (const workspace of ['', 'private-source']) {
        test(`portfolio redirect and saved-analysis re-entry preserve ${prefix || '/'} and ${workspace || 'shared'}`, () => {
            const {api} = boot('?workspaceId=' + workspace, prefix);
            const portfolio = url(api.portfolioUrl(12));
            const workbench = url(api.workbenchUrl(12, 'snapshot-v2'));
            assert.equal(portfolio.pathname, prefix + '/projects');
            assert.equal(portfolio.searchParams.get('projectId'), '12');
            assert.equal(workbench.pathname, prefix + '/architecture/workbench');
            assert.equal(workbench.searchParams.get('snapshotId'), 'snapshot-v2');
            for (const target of [portfolio, workbench]) {
                assert.equal(target.searchParams.has('workspaceId'), true);
                assert.equal(target.searchParams.get('workspaceId'), workspace);
                assert.equal(target.searchParams.get('lang'), 'de');
            }
        });
    }
}
