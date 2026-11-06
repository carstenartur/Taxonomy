/** Production native-file controls in Chromium; transport fixtures are separate from packaged-host acceptance. */
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const resources = path.resolve('taxonomy-app/src/main/resources');
let browser, main;
before(async () => {
    const template = await readFile(path.join(resources, 'templates/integrations.html'), 'utf8');
    main = template.match(/<main\b[^>]*>[\s\S]*?<\/main>/)?.[0];
    assert.ok(main, 'Use the actual integrations template');
    browser = await chromium.launch({ headless: true,
        ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}),
        args: ['--no-sandbox', '--disable-dev-shm-usage'] });
});
after(async () => { await browser?.close(); });

async function fixture(locale, mode, width) {
    const page = await browser.newPage({ viewport: { width, height: 900 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.route('http://localhost/**', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><html></html>' }));
    await page.goto('http://localhost/integrations?repositoryId=repo&workspaceId=ws&branch=draft&connection=conn');
    await page.setContent(`<html lang="${locale}"><body data-may-write="true">${main}</body></html>`);
    await page.addStyleTag({ path: path.join(resources, 'static/css/integrations.css') });
    const dictionary = {};
    for (const file of ['messages_integrations.properties', ...(locale === 'de' ? ['messages_integrations_de.properties'] : [])])
        for (const line of (await readFile(path.join(resources, 'i18n', file), 'utf8')).split('\n')) {
            const pair = line.match(/^([^#!\s][^=]*)=(.*)$/); if (pair) dictionary[pair[1]] = pair[2];
        }
    await page.evaluate(({ dictionary, mode }) => {
        window.calls = [];
        const profile = { id: 'fixture-native', version: '2', title: 'Native file fixture',
            capabilities: ['FILE_IMPORT', 'FILE_EXPORT', 'BASED_FILE_EXPORT_V1'], mediaTypes: ['application/vnd.sparx.qea'] };
        const overview = { connection: { id: 'conn', connectorId: profile.id, profileVersion: '2', authority: 'BIDIRECTIONAL', externalScope: { systemType: 'Fixture', repository: 'model' } },
            current: { semanticRevision: 7, branch: 'draft' }, checkpoint: mode === 'UPDATE_COPY' ? { externalVersion: 'imported-basis' } : null, history: [],
            fileExportModes: [mode], fileBases: [{ id: 'retained', mediaType: profile.mediaTypes[0], sha256: 'a'.repeat(64), bytes: 8192 }],
            publicationAvailability: { available: false, modes: [], reasonCode: 'PUBLICATION_GUARANTEES_UNVERIFIED' }, oslcCatalogPath: '/oslc' };
        let operation;
        window.TaxonomyI18n = { ready: async () => {}, t: key => dictionary[key] || key, resolveUrl: value => value };
        window.TaxonomyApiClient = {
            async getJson(url) {
                const pathname = new URL(url, location.href).pathname;
                if (pathname.endsWith('/profiles')) return [profile];
                if (pathname === '/api/integrations') return [{ id: 'conn', displayName: 'Native fixture', connectorId: profile.id, profileVersion: '2' }];
                if (pathname === '/api/integrations/conn') return overview;
                if (pathname.endsWith('/events')) return [];
                if (pathname.endsWith('/operations/op')) return operation;
                throw new Error('Unexpected read: ' + url);
            },
            async sendFormData(url, form) {
                const request = JSON.parse(await form.get('request').text()), file = form.get('file');
                calls.push({ url, request, bytes: file ? Array.from(new Uint8Array(await file.arrayBuffer())) : null });
                operation = { id: 'op', direction: 'OUTBOUND', status: 'PREVIEWED', fingerprint: 'preview',
                    context: { profile: profile.id, profileVersion: '2', authority: 'BIDIRECTIONAL', internalState: overview.current },
                    document: { metadata: {}, losses: [] }, fileExport: { mode: request.mode, baseSha256: 'a'.repeat(64), modelIdentifier: 'model-guid' },
                    changes: [{ id: 'change', externalId: 'ELEMENT:guid', kind: 'ADD', conflicts: [], after: { kind: 'ELEMENT', title: 'Reviewed component', attributes: {}, extensions: {} } }] };
                return operation;
            },
            async sendJson(url, request) {
                calls.push({ url, request });
                if (!new URL(url, location.href).pathname.endsWith('/files')) throw new Error('Unexpected write: ' + url);
                operation = { ...operation, status: 'COMPLETED', review: request };
                return operation;
            }
        };
    }, { dictionary, mode });
    await page.addScriptTag({ path: path.join(resources, 'static/js/api/integration-api.js') });
    await page.addScriptTag({ path: path.join(resources, 'static/js/integrations.js') });
    await page.waitForFunction(() => !document.getElementById('integrationRefresh').disabled);
    return { page, errors };
}

for (const [locale, width] of [['en', 1366], ['de', 390]]) test(`native-file upload, review and download controls work in ${locale} at ${width}px`, async () => {
    const { page, errors } = await fixture(locale, 'UPDATE_COPY', width);
    try {
        assert.equal(await page.locator('#integrationExport').isDisabled(), true);
        assert.deepEqual(await page.locator('#integrationExportMode option').evaluateAll(nodes => nodes.map(node => node.value)), ['UPDATE_COPY']);
        await page.locator('#integrationExportBaseFile').setInputFiles({ name: 'model.qea', mimeType: 'application/octet-stream', buffer: Buffer.from([0, 255, 32, 1]) });
        await page.locator('#integrationExport').click();
        await page.waitForFunction(() => document.getElementById('integrationOperation').textContent.startsWith('op'));
        const preview = await page.evaluate(() => calls[0]);
        assert.equal(preview.request.mode, 'UPDATE_COPY'); assert.equal(preview.request.expected.semanticRevision, 7);
        assert.equal(preview.request.baseFileId, null); assert.deepEqual(preview.bytes, [0, 255, 32, 1]);
        assert.match(preview.url, /repositoryId=repo&workspaceId=ws&branch=draft/);
        assert.equal(await page.locator('#integrationDownload').isHidden(), true);
        await page.locator('#integrationAccept').click();
        await page.locator('#integrationRationale').fill('Reviewed the native copy');
        await page.locator('#integrationApply').click();
        await page.locator('#integrationDownload:not([hidden])').waitFor();
        assert.match(await page.locator('#integrationDownload').getAttribute('href'), /\/operations\/op\/file\?/);
        assert.equal(await page.evaluate(() => calls[1].request.decisions.change), 'ACCEPT');
        assert.equal(await page.evaluate(() => calls.some(call => /publish|publication-previews/.test(call.url))), false);
        assert.equal(await page.locator('#integrationFileExportSummary').isVisible(), true);
        const output = path.resolve('target/sparx-completion/browser'); await mkdir(output, { recursive: true });
        await page.screenshot({ path: path.join(output, `native-file-${locale}-${width}.png`), fullPage: true });
        const overflowing = await page.locator('main *').evaluateAll(nodes => nodes.filter(node => node.getBoundingClientRect().right > innerWidth)
            .map(node => node.tagName + '#' + node.id).slice(0, 12));
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true,
            'The native-file form must fit the viewport: ' + overflowing.join(', '));
        assert.deepEqual(errors, []);
    } finally { await page.close(); }
});

test('native-file selection reuses a retained basis without a binary upload', async () => {
    const { page, errors } = await fixture('en', 'NEW_PROJECT', 1366);
    try {
        await page.locator('#integrationExportBase').selectOption('retained');
        assert.equal(await page.locator('#integrationExportBaseFile').isDisabled(), true);
        await page.locator('#integrationExport').click();
        await page.waitForFunction(() => calls.length === 1);
        const call = await page.evaluate(() => calls[0]);
        assert.equal(call.request.mode, 'NEW_PROJECT'); assert.equal(call.request.baseFileId, 'retained');
        assert.equal(call.bytes, null); assert.deepEqual(errors, []);
    } finally { await page.close(); }
});
