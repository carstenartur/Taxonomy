import canonicalFixture from './canonical-api-fixture.cjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const [i18nSource, apiSource, downloadSource] = await Promise.all([
    'taxonomy-i18n.js', 'api/architecture-workbench-api.js', 'architecture-workbench-export.js'
].map(path => readFile(new URL(path, resources), 'utf8')));

test('the workbench page loads the shared URL bootstrap before its API and download adapters', async () => {
    const template = await readFile(new URL('../../taxonomy-app/src/main/resources/templates/architecture-workbench.html', import.meta.url), 'utf8');
    const bootstrap = template.indexOf('src="/js/taxonomy-i18n.js"');
    assert.ok(bootstrap >= 0);
    for (const script of ['api/architecture-workbench-api.js', 'architecture-workbench.js', 'architecture-workbench-export.js']) {
        assert.ok(template.indexOf(`src="/js/${script}"`) > bootstrap, script);
    }
});

async function boot(prefix, withI18n = true, documentLanguage = null) {
    const clicks = new Map();
    const downloads = [];
    const requests = [];
    const requestOptions = [];
    const controller = new AbortController();
    const anchors = [];
    const byteRequests = [];
    const alert = {classList:{ add() {}, remove() {} }};
    let disposition = "attachment; filename=report.zip; filename*=UTF-8''Architektur%20%C3%84.zip";
    let received = async () => new Blob(['snapshot']);
    const context = vm.createContext({
        URL: class extends URL {
            static createObjectURL() { return 'blob:received'; }
            static revokeObjectURL() {}
        }, console, CustomEvent: class {},
        document: {
            currentScript: { src: `https://example.test${prefix}/js/taxonomy-i18n.js` },
            documentElement: { lang: 'en' }, cookie: '', dispatchEvent() {},
            body: {
                dataset: { projectId: '42', snapshotId: 'snapshot_7-v1' },
                __architectureSnapshot: { getExportSignal() { return controller.signal; } },
                appendChild() {}
            },
            createElement() {
                return {click() { anchors.push(this.download); }, remove() {}};
            },
            getElementById(id) {
                if (id === 'architectureAlert') return alert;
                return { addEventListener(event, callback) { clicks.set(id, callback); } };
            }
        },
        localStorage: { getItem() { return null; } },
        location: {
            href: `https://example.test${prefix}/projects/42/architecture-workbench/snapshot_7-v1`,
            assign(url) { downloads.push(url); }
        },
        async fetch(url, options) {
            if (/\.(zip|svg|pdf)(\?|$)|\/docx(\?|$)/.test(url)) {
                byteRequests.push({url, options});
                return { ok:true, headers:new Headers({'Content-Disposition':disposition}),
                    blob:received };
            }
            requests.push(url); requestOptions.push(options);
            return { ok: true, async json() { return {}; } };
        }
    });
    context.window = context;
    context.TaxonomyContentLanguage = { documentLanguage: () => documentLanguage };
    if (withI18n) {
        vm.runInContext(i18nSource, context);
        await context.TaxonomyI18n.ready();
        requests.length = 0;
    }
    canonicalFixture.install(context);
    vm.runInContext(apiSource, context);
    vm.runInContext(downloadSource, context);
    return { api: context.ArchitectureWorkbenchApi, clicks, downloads, requests, requestOptions,
        anchors, byteRequests, controller, context, alert, setDisposition(value) { disposition = value; }, setBodyReader(reader) { received = reader; } };
}

for (const prefix of ['', '/taxonomy', '/teams/blue/taxonomy']) {
    test(`workbench navigation and fetch retain the actual application prefix ${prefix || '/'}`, async () => {
        const app = await boot(prefix);
        const path = `${prefix}/api/projects/42/architecture-workbench/snapshot_7-v1`;
        const formats = {
            svgUrl: '.svg', pdfUrl: '.pdf', archiMateUrl: '.archimate.xml', visioUrl: '.vsdx',
            archiMateBundleUrl: '.archimate.zip'
        };
        if (app.api.visioBundleUrl) formats.visioBundleUrl = '.visio.zip';
        for (const [method, extension] of Object.entries(formats)) {
            assert.equal(app.api[method]('42', 'snapshot_7-v1'), path + extension);
        }
        await app.clicks.get('downloadArchitectureArchiMate')();
        await app.clicks.get('downloadArchitectureVisio')();
        assert.deepEqual(app.anchors, ['Architektur Ä.zip', 'Architektur Ä.zip']);
        assert.deepEqual(app.byteRequests.map(request => request.url), [path + '.archimate.zip',
            path + (app.api.visioBundleUrl ? '.visio.zip' : '.vsdx')]);
        await app.api.load('42', 'snapshot_7-v1');
        assert.deepEqual(app.requests, [path], 'fetch must not duplicate the resolved prefix');
    });
}

test('root deployment remains usable without the optional i18n URL resolver', async () => {
    const app = await boot('', false);
    await app.clicks.get('downloadArchitectureArchiMate')();
    assert.deepEqual(app.byteRequests.map(request => request.url), ['/api/projects/42/architecture-workbench/snapshot_7-v1.archimate.zip']);
});


for (const workspace of ['private-A','__shared__']) test(`workbench exact snapshot load pins URL and header (${workspace})`,async()=>{
    const app=await boot('/taxonomy',false);
    const scope={repositoryId:'repository-A',workspaceScopeKey:workspace,branch:'review/A'};
    await app.api.load('42','snapshot-A',scope);
    const url=new URL(app.requests.at(-1),'https://example.test');
    assert.equal(url.searchParams.get('repositoryId'),'repository-A');
    assert.equal(url.searchParams.get('workspaceId'),workspace==='__shared__'?'':workspace);
    assert.equal(url.searchParams.get('branch'),'review/A');
    assert.equal(new Headers(app.requestOptions.at(-1).headers).get('X-Taxonomy-Workspace-Id'),workspace==='__shared__'?'':workspace);
    for(const method of ['svgUrl','pdfUrl','architectureWordUrl','decisionWordUrl','archiMateBundleUrl','visioBundleUrl']){
        const download=new URL(app.api[method]('42','snapshot-A',scope),'https://example.test');
        assert.equal(download.searchParams.get('workspaceScopeKey'),workspace);
        assert.equal(download.searchParams.get('repositoryId'),'repository-A');
        assert.equal(download.searchParams.get('branch'),'review/A');
    }
});

test('document language follows both diagram and Word downloads while exchange files keep their source scope', async () => {
    const app = await boot('/teams/blue/taxonomy', true, 'de-CH');
    const scope = {repositoryId: 'repo / A', workspaceScopeKey: 'workspace-1', branch: 'analysis/a'};
    for (const [method, expected] of [
        ['svgUrl', 'de-CH'], ['pdfUrl', 'de-CH'], ['architectureWordUrl', 'de-CH'],
        ['decisionWordUrl', 'de-CH'], ['archiMateBundleUrl', null], ['visioBundleUrl', null]
    ]) {
        const url = new URL(app.api[method](5, 'frozen-A0', scope), 'https://example.test');
        assert.ok(url.pathname.startsWith('/teams/blue/taxonomy/api/projects/5/'), method);
        assert.equal(url.searchParams.get('language'), expected, method);
        assert.equal(url.searchParams.get('repositoryId'), scope.repositoryId, method);
        assert.equal(url.searchParams.get('branch'), scope.branch, method);
    }
});


for (const button of ['downloadArchitectureWord', 'downloadArchitectureSvg', 'downloadArchitecturePdf',
    'downloadArchitectureArchiMate', 'downloadArchitectureVisio']) {
    test(button + ' discards an unfinished attachment when its source is suspended', async () => {
        const app = await boot('', false);
        app.context.TaxonomyArchitectureWorkspace = {getSource: () => ({
            kind: 'snapshot', projectId: 42, snapshotId: 'original', repositoryId: 'A',
            workspaceScopeKey: 'private-A', branch: 'draft'
        })};
        let started, release;
        const reading = new Promise(resolve => { started = resolve; });
        app.setBodyReader(() => { started(); return new Promise(resolve => { release = resolve; }); });
        const pending = app.clicks.get(button)();
        assert.equal(app.downloads.length, 0, 'no direct navigation outside the source lifetime');
        await reading;
        const {url, options} = app.byteRequests[0];
        assert.equal(new URL(url, 'https://example.test').searchParams.get('workspaceId'), 'private-A');
        assert.equal(new Headers(options.headers).get('X-Taxonomy-Workspace-Id'), 'private-A');
        app.controller.abort();
        assert.equal(options.signal.aborted, true);
        release(new Blob(['old attachment']));
        await pending;
        assert.deepEqual(app.anchors, []);
    });
}

test('missing attachments leave the view usable and the next named report can be downloaded', async () => {
    const app = await boot('', false);
    app.setDisposition('inline');
    await app.clicks.get('downloadArchitectureWord')();
    assert.match(app.alert.textContent, /named architecture attachment/);
    assert.deepEqual(app.anchors, []);
    app.setDisposition('attachment; filename="DEMO-architecture-report-v1.docx"');
    await app.clicks.get('downloadArchitectureWord')();
    assert.deepEqual(app.anchors, ['DEMO-architecture-report-v1.docx']);
    assert.equal(app.alert.textContent, '');
});
