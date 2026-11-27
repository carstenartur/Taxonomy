import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import {chromium} from 'playwright';

const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
const origin = 'http://about.test';
const longModel = 'operator-model-' + 'x'.repeat(110) + '<img src=x onerror=alert(1)>';
const upstream = {
    modelId: 'sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2',
    origin: 'https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2',
    license: 'Apache-2.0',
    revision: 'e8f8c211226b894fcb81acc59f3b34ba3efd5f42'
};
const loaded = {
    embedding: {
        state: 'READY',
        configuredProfile: {id: 'MULTILINGUAL_MINILM_L12', source: 'PROFILE_DEFAULT', upstream},
        loadedArtifact: {
            provenance: 'VERIFIED_UPSTREAM', upstream,
            modelSha256: 'a'.repeat(64), tokenizerSha256: 'b'.repeat(64),
            inferenceVersion: 'djl-declared-onnx-runtime', inferenceIdentity: 'c'.repeat(64)
        }
    },
    analysis: {state: 'CONFIGURED', mode: 'OPERATOR_ENDPOINT', provider: 'CUSTOM_OPENAI', model: longModel}
};

let browser, modal, bootstrapCss, bootstrapScript, apiScript;
before(async () => {
    const index = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
    const markup = index.match(/<!-- About Modal -->([\s\S]*?)<!-- Create Relation Modal -->/);
    assert.ok(markup, 'Use the production About dialog');
    modal = markup[1];
    const jar = path.join(process.env.MAVEN_REPO_LOCAL || path.join(os.homedir(), '.m2/repository'),
        'org/webjars/npm/bootstrap/5.3.8/bootstrap-5.3.8.jar');
    const bootstrapAsset = name => execFileSync('unzip',
        ['-p', jar, 'META-INF/resources/webjars/bootstrap/5.3.8/dist/' + name], {encoding: 'utf8'});
    bootstrapCss = process.env.BOOTSTRAP_CSS
        ? await readFile(process.env.BOOTSTRAP_CSS, 'utf8') : bootstrapAsset('css/bootstrap.min.css');
    bootstrapScript = bootstrapAsset('js/bootstrap.bundle.min.js');
    apiScript = await readFile(path.join(resources, 'static/js/api/taxonomy-api-client.js'), 'utf8');
    apiScript = apiScript.slice(0, apiScript.indexOf('(function loadAuthenticatedUiSurfaces'));
    browser = await chromium.launch({
        headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined,
        args: ['--no-sandbox', '--disable-dev-shm-usage']
    });
});
after(async () => { await browser?.close(); });

async function fixture(locale, width, prefix) {
    const page = await browser.newPage({viewport: {width, height: 800}});
    page.setDefaultTimeout(5000);
    const responses = [];
    const requests = [];
    const translations = new Map();
    const bundles = locale === 'de' ? ['messages.properties', 'messages_de.properties'] : ['messages.properties'];
    for (const bundle of bundles) {
        const text = await readFile(path.join(resources, 'i18n', bundle), 'utf8');
        for (const line of text.split('\n')) {
            const entry = line.match(/^([^#!\s][^=]*)=(.*)$/);
            if (entry) translations.set(entry[1], entry[2].replace(/\\u([0-9a-f]{4})/gi,
                (_, code) => String.fromCharCode(parseInt(code, 16))));
        }
    }
    const localizedModal = modal.replace(/th:text="#\{([^}]+)\}">([^<]*)</g,
        (original, key, fallback) => original.replace(fallback + '<', (translations.get(key) || fallback) + '<'));
    const scripts = [
        'taxonomy-i18n.js', 'shared/taxonomy-utils.js', 'shared/application-chrome.js',
        'api/taxonomy-api-client.js', 'api/reference-api.js', 'shared/taxonomy-about.js'
    ];
    await page.route(origin + '/**', async route => {
        const pathname = new URL(route.request().url()).pathname;
        if (pathname === prefix + '/') return route.fulfill({
            contentType: 'text/html; charset=utf-8',
            body: `<!doctype html><html lang="${locale}"><head><style>${bootstrapCss}</style></head>
                <body><button id="aboutBtn">About</button>${localizedModal}
                <script>${bootstrapScript}</script>
                ${scripts.map(script => `<script src="${prefix}/js/${script}"></script>`).join('')}
                </body></html>`
        });
        if (pathname === prefix + '/api/about') {
            requests.push(pathname);
            const response = responses.shift() || {body: {}};
            if (response.wait) await response.wait;
            return route.fulfill({status: response.status || 200, json: response.body || {}});
        }
        if (pathname === prefix + '/api/i18n/' + locale)
            return route.fulfill({json: Object.fromEntries(translations)});
        const script = scripts.find(candidate => pathname === prefix + '/js/' + candidate);
        if (script) return route.fulfill({
            contentType: 'text/javascript',
            body: script === 'api/taxonomy-api-client.js'
                ? apiScript : await readFile(path.join(resources, 'static/js', script), 'utf8')
        });
        return route.fulfill({status: 404, json: {}});
    });
    return {page, responses, requests, url: origin + prefix + '/?lang=' + locale};
}

for (const [locale, width, prefix, heading, analysis, verified] of [
    ['en', 1366, '', 'AI and models', 'Server-configured analysis provider', 'Verified upstream bundle'],
    ['de', 390, '/team/taxonomy', 'KI und Modelle', 'Serverseitig konfigurierter Analyseanbieter', 'Verifiziertes Upstream-Paket']
]) {
    test(`About shows model provenance and safe model text in ${locale} at ${width}px`, async () => {
        const {page, responses, requests, url} = await fixture(locale, width, prefix);
        try {
            responses.push({body: {}}, {body: {ai: loaded}});
            await page.goto(url);
            await page.locator('#aboutBtn').click();
            const pane = page.locator('#about-info-pane');
            await pane.getByRole('heading', {name: heading, exact: true}).waitFor({state: 'visible'});
            await pane.getByText(verified, {exact: true}).waitFor();
            assert.equal(await pane.getByRole('heading', {name: analysis, exact: true}).isVisible(), true);
            assert.equal(await pane.getByText(longModel, {exact: true}).count(), 1);
            assert.equal(await pane.locator('img').count(), 0);
            assert.match(await pane.innerText(), /Apache-2\.0/);
            assert.match(await pane.innerText(), /a{64}/);
            assert.match(await pane.innerText(), /b{64}/);
            assert.match(await pane.innerText(), /c{64}/);
            const widths = await pane.evaluate(node => ({
                content: node.scrollWidth, available: node.clientWidth
            }));
            assert.ok(widths.content <= widths.available + 1, JSON.stringify(widths));
            assert.deepEqual(requests, [prefix + '/api/about', prefix + '/api/about']);
        } finally {
            await page.close();
        }
    });
}

test('reopening About clears stale runtime information before a failed request and tolerates old responses', async () => {
    const {page, responses, requests, url} = await fixture('en', 390, '/taxonomy');
    let finishFailure;
    const wait = new Promise(resolve => { finishFailure = resolve; });
    try {
        responses.push({body: {}}, {body: {ai: loaded}}, {status: 503, wait}, {body: {version: 'old-build'}});
        await page.goto(url);
        const pane = page.locator('#about-info-pane');
        await page.locator('#aboutBtn').click();
        await pane.getByText('Verified upstream bundle', {exact: true}).waitFor();
        await page.locator('#aboutModal[data-modal-visible="true"]').waitFor();
        await page.keyboard.press('Escape');
        await page.locator('#aboutModal').waitFor({state: 'hidden'});

        await page.locator('#aboutBtn').click();
        await pane.getByText('Loading AI information…', {exact: true}).waitFor();
        assert.equal(await pane.getByText(longModel, {exact: true}).count(), 0);
        finishFailure();
        await pane.getByText('Could not load AI information.', {exact: true}).waitFor();
        assert.equal(await pane.getByText('Verified upstream bundle', {exact: true}).count(), 0);

        await page.locator('#aboutModal[data-modal-visible="true"]').waitFor();
        await page.keyboard.press('Escape');
        await page.locator('#aboutModal').waitFor({state: 'hidden'});
        await page.locator('#aboutBtn').click();
        await pane.getByText('AI information is unavailable.', {exact: true}).waitFor();
        assert.deepEqual(requests, Array(4).fill('/taxonomy/api/about'));
    } finally {
        finishFailure();
        await page.close();
    }
});
