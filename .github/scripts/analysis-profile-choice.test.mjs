import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const js = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const [client, choice] = await Promise.all(['api/architecture-editor-api.js','core/analysis-profile-choice.js']
    .map(file => readFile(new URL(file, js), 'utf8')));
const drain = () => new Promise(resolve => setImmediate(resolve));
const scope = {repositoryId:'public-service-project', workspaceScopeKey:'workspace A', branch:'planning/design'};

function fixture(features = ['architecture', 'analysis']) {
    const nodes = new Map(), events = new Map(), pending = [], opened = [];
    const element = id => {
        if (!nodes.has(id)) nodes.set(id, {hidden:true, handlers:new Map(), addEventListener(name, cb) { this.handlers.set(name, cb); },
            focus() { this.focused = true; }, scrollIntoView() {}});
        return nodes.get(id);
    };
    const document = {getElementById:element, addEventListener:(name, cb) => events.set(name, cb)};
    let model = {expectedSemanticRevision:4}, ready = true, notify;
    const location = new URL('https://example.test/app/?workspaceId=workspace+A#analyze');
    const window = {TaxonomyCapabilities:{load:async () => ({features})}, TaxonomyAnalysisSession:{state:() => ({ready, workspaceId:scope.workspaceScopeKey})},
        TaxonomyArchitectureLifecycle:{ready:async () => null, selectedContext:() => scope,
            selectedAnalysisModel:() => model, onStandChange:cb => { notify = cb; }},
        TaxonomyArchitectureWorkspace:{analysisScope:async () => scope,
            open:async (kind, {source}) => { opened.push({kind, source:JSON.parse(JSON.stringify(source))}); return true; }},
        TaxonomyArchitectureWorkspaceController:{urlFor(url, source, path) {
            url.pathname = path; url.hash = 'architecture'; url.search = new URLSearchParams(source); return url.href; }},
        TaxonomyApiClient:{getJson:(path, options) => new Promise(resolve => pending.push({resolve, path, options}))},
        TaxonomyI18n:{resolveUrl:path => '/app' + path, t:key => key}};
    const ctx = {document, window, location, URL, URLSearchParams};
    vm.runInNewContext(client, ctx);
    vm.runInNewContext(choice, ctx);
    return {element, events, pending, opened, refresh:() => notify(), setModel:next => { model = next; },
        setReady:next => { ready = next; }, response(overrides = {}) {
            return {...scope, commit:'a'.repeat(40), semanticRevision:4, editable:true, decision:'OPEN', ...overrides};
        }};
}

test('an assembly without architecture does not request a profile', async () => {
    const f = fixture(['analysis']); await drain();
    assert.equal(f.pending.length, 0);
    assert.equal(f.element('analysisProfileChoice').hidden, true);
});

test('one known profile shows no extra control or confirmation', async () => {
    const f = fixture(); await drain();
    const first = f.pending[0];
    assert.ok(first.path.startsWith('/api/architecture/editor/analysis-brief?'));
    const query = new URL(first.path, 'https://example.test').searchParams;
    assert.equal(query.get('expectedSemanticRevision'), '4');
    assert.equal(query.get('repositoryId'), scope.repositoryId);
    assert.equal(query.get('workspaceId'), scope.workspaceScopeKey);
    assert.equal(first.options.pinnedWorkspaceId, scope.workspaceScopeKey);
    first.resolve(f.response({decision:'ASSIGNED'})); await drain();
    assert.equal(f.element('analysisProfileChoice').hidden, true);
    assert.equal(f.opened.length, 0);
});

test('an editable open profile opens its exact semantic revision in the existing editor', async () => {
    const f = fixture(); await drain();
    f.pending[0].resolve(f.response()); await drain();
    const panel = f.element('analysisProfileChoice'), link = f.element('analysisProfileEditor');
    assert.equal(panel.hidden, false);
    assert.equal(link.textContent, 'analysis.profile.edit');
    assert.equal(new URL(link.href).searchParams.get('revision'), '4');
    assert.equal(new URL(link.href).searchParams.get('architecturePanel'), 'brief');
    await link.handlers.get('click')({button:0, preventDefault() {}});
    assert.equal(f.opened[0].kind, 'working');
    assert.deepEqual(f.opened[0].source, {...scope, kind:'working', workspaceId:scope.workspaceScopeKey, revision:'4'});
    assert.equal(f.element('editorBriefTitle').focused, true);
});

test('a stale response cannot restore an editor link after changing version, tab or source', async () => {
    const f = fixture(); await drain();
    const commit = 'f'.repeat(40);
    f.setModel({expectedCommit:commit}); f.refresh(); await drain();
    f.pending[0].resolve(f.response()); await drain();
    assert.equal(f.element('analysisProfileChoice').hidden, true);
    f.pending[1].resolve(f.response({semanticRevision:null, commit, decision:'CONFLICTING_EDITIONS', editable:false})); await drain();
    assert.equal(f.element('analysisProfileChoice').hidden, false);
    const link = f.element('analysisProfileEditor');
    assert.equal(link.textContent, 'analysis.profile.view');
    assert.equal(new URL(link.href).searchParams.get('commit'), commit);
    f.events.get('taxonomy:page-activated')({detail:{page:'architecture'}});
    assert.equal(f.element('analysisProfileChoice').hidden, true);
    f.setReady(false);
    f.events.get('taxonomy:page-activated')({detail:{page:'analyze'}}); await drain();
    assert.equal(f.pending.length, 2);
    f.setReady(true); f.refresh(); await drain();
    f.pending[2].resolve(f.response({repositoryId:'other', semanticRevision:null, commit})); await drain();
    assert.equal(f.element('analysisProfileChoice').hidden, true);
});
