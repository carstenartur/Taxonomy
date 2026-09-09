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

async function boot(prefix, withI18n = true) {
    const clicks = new Map();
    const downloads = [];
    const requests = [];
    const context = vm.createContext({
        URL, console, CustomEvent: class {},
        document: {
            currentScript: { src: `https://example.test${prefix}/js/taxonomy-i18n.js` },
            documentElement: { lang: 'en' }, cookie: '', dispatchEvent() {},
            body: { dataset: { projectId: '42', snapshotId: 'snapshot_7-v1' } },
            getElementById(id) {
                return { addEventListener(event, callback) { clicks.set(id, callback); } };
            }
        },
        localStorage: { getItem() { return null; } },
        location: {
            href: `https://example.test${prefix}/projects/42/architecture-workbench/snapshot_7-v1`,
            assign(url) { downloads.push(url); }
        },
        async fetch(url) {
            requests.push(url);
            return { ok: true, async json() { return {}; } };
        }
    });
    context.window = context;
    if (withI18n) {
        vm.runInContext(i18nSource, context);
        await context.TaxonomyI18n.ready();
        requests.length = 0;
    }
    vm.runInContext(apiSource, context);
    vm.runInContext(downloadSource, context);
    return { api: context.ArchitectureWorkbenchApi, clicks, downloads, requests };
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
        app.clicks.get('downloadArchitectureArchiMate')();
        app.clicks.get('downloadArchitectureVisio')();
        assert.deepEqual(app.downloads, [path + '.archimate.zip',
            path + (app.api.visioBundleUrl ? '.visio.zip' : '.vsdx')]);
        await app.api.load('42', 'snapshot_7-v1');
        assert.deepEqual(app.requests, [path], 'fetch must not duplicate the resolved prefix');
    });
}

test('root deployment remains usable without the optional i18n URL resolver', async () => {
    const app = await boot('', false);
    app.clicks.get('downloadArchitectureArchiMate')();
    assert.deepEqual(app.downloads, ['/api/projects/42/architecture-workbench/snapshot_7-v1.archimate.zip']);
});
