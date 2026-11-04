/** Exercise the actual database dialog and script; replace only the remote API boundary. */
import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile, mkdir} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
const {chromium} = createRequire(import.meta.url)('playwright');
const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser, fragment, bootstrap;
before(async () => {
    const html = await readFile(path.join(resources, 'templates/fragments/database-setup.html'), 'utf8');
    fragment = html.match(/<dialog\b[\s\S]*?<\/dialog>/)?.[0];
    assert.ok(fragment, 'The production dialog is present');
    bootstrap = process.env.BOOTSTRAP_CSS ? await readFile(process.env.BOOTSTRAP_CSS, 'utf8') : execFileSync('unzip',
        ['-p', path.join(process.env.MAVEN_REPO_LOCAL || path.join(os.homedir(), '.m2/repository'),
            'org/webjars/npm/bootstrap/5.3.8/bootstrap-5.3.8.jar'),
            'META-INF/resources/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css'], {encoding: 'utf8'});
    browser = await chromium.launch({headless: true,
        ...(process.env.CHROMIUM_EXECUTABLE ? {executablePath: process.env.CHROMIUM_EXECUTABLE} : {}),
        args: ['--no-sandbox', '--disable-dev-shm-usage']});
});
after(async () => { await browser?.close(); });

const success = {findings: [
    {key: 'database.schema', status: 'NOT_CHECKED', message: 'Schema scope'},
    {key: 'database.tls', status: 'WARNING', message: 'TLS is disabled'},
    {key: 'database.connection', status: 'OK', message: 'Connection validated'}
]};
const tlsFailure = {findings: [
    {key: 'database.tls', status: 'NOT_CHECKED', message: 'TLS is selected'},
    {key: 'database.connection.tls', status: 'ERROR', message: 'TLS connection failed'}
]};
const ready = {outcome: 'READY', role: 'CREATE', database: 'CREATE',
    finding: {key: 'database.provision.ready', status: 'OK', message: 'Ready to create'}};
const complete = {outcome: 'COMPLETE', role: 'CREATED', database: 'CREATED',
    finding: {key: 'database.provision.complete', status: 'OK', message: 'Application login verified'}};

async function fixture({locale = 'en', width = 1000, responses = [success], realTransport = false} = {}) {
    const page = await browser.newPage({viewport: {width, height: 850}});
    page.errors = [];
    page.on('pageerror', error => page.errors.push(error.message));
    await page.route('http://database-setup.test/**', route => route.fulfill({body: '', contentType: 'text/html'}));
    await page.goto('http://database-setup.test/');
    await page.setContent(`<html lang="${locale}"><head></head><body>
        <details id="applicationSettings"><summary>Settings</summary><button id="databaseSetupMenuAction">Database setup</button></details>
        <input id="businessText"><div id="databaseSetupNotice" hidden></div>${fragment}</body></html>`);
    const translations = {};
    for (const filename of ['messages_database_setup.properties', ...(locale === 'de' ? ['messages_database_setup_de.properties'] : [])]) {
        for (const line of (await readFile(path.join(resources, 'i18n', filename), 'utf8')).split('\n')) {
            const match = line.match(/^([^#!\s][^=]*)=(.*)$/);
            if (match) translations[match[1]] = match[2].replace(/\\u([0-9a-fA-F]{4})/g, (_, value) => String.fromCharCode(parseInt(value, 16)));
        }
    }
    await page.evaluate(({translations, responses, realTransport}) => {
        window.TaxonomyI18n = {t: (key, ...args) => (translations[key] || key).replace(/\{(\d+)\}/g, (_, index) => args[index] ?? '')};
        document.querySelectorAll('[th\\:text], [th\\:aria-label]').forEach(node => {
            for (const attribute of ['text', 'aria-label']) {
                const key = node.getAttribute('th:' + attribute)?.match(/^#\{(.+)\}$/)?.[1];
                if (key && attribute === 'text') node.textContent = TaxonomyI18n.t(key);
                if (key && attribute === 'aria-label') node.setAttribute('aria-label', TaxonomyI18n.t(key));
            }
        });
        window.databaseRequests = [];
        window.databaseResponses = responses;
        window.TaxonomyApiClient = {
            getAccountContext: async () => ({administrator: true}),
            getJson: async () => ({database: 'hsqldb', inMemory: true, promptRequired: false, deploymentManaged: true}),
            sendJson: async (url, body, options) => {
                window.databaseRequests.push({url, body});
                if (url.endsWith('/dismiss')) return {dismissed: true};
                if (url.endsWith('/preview')) return {properties: 'spring.datasource.username=' + body.username,
                    helmValues: '# application user ' + body.username, findings: []};
                const result = window.databaseResponses.shift();
                if (result?.pending) return new Promise((resolve, reject) => {
                    window.finishDatabaseRequest = () => resolve(result.value);
                    options?.signal?.addEventListener('abort', () => reject({name: 'AbortError'}));
                });
                if (result?.reject) throw result.reject;
                return result;
            }
        };
        if (realTransport) {
            // Exercise the production API client with real Response/ReadableStream bodies.
            window.fetch = async (url, init = {}) => {
                const headers = {'Content-Type': 'application/json'};
                const json = value => new Response(JSON.stringify(value), {status: 200, headers});
                if (url.endsWith('/api/account/me')) return json({administrator: true});
                if ((init.method || 'GET') === 'GET') return json({database: 'hsqldb', inMemory: true, promptRequired: false});
                const body = JSON.parse(init.body || '{}');
                window.databaseRequests.push({url, body});
                const result = window.databaseResponses.shift();
                if (result?.pending) {
                    const stream = new ReadableStream({start(controller) {
                        window.finishDatabaseRequest = () => {
                            controller.enqueue(new TextEncoder().encode(JSON.stringify(result.value)));
                            controller.close();
                        };
                        init.signal?.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), {once: true});
                    }});
                    window.databaseHeadersReceived = true;
                    return new Response(stream, {status: 200, headers});
                }
                if (result?.reject) throw result.reject;
                return json(result);
            };
        }
    }, {translations, responses, realTransport});
    // The actual component styles are used, including its mobile layout and result hierarchy.
    await page.addStyleTag({content: bootstrap});
    await page.addStyleTag({path: path.join(resources, 'static/css/taxonomy-database-setup.css')});
    if (realTransport) await page.addScriptTag({path: path.join(resources, 'static/js/api/taxonomy-api-client.js')});
    await page.addScriptTag({path: path.join(resources, 'static/js/shared/taxonomy-database-setup.js')});
    await page.evaluate(() => window.TaxonomyDatabaseSetup.open());
    return page;
}

async function connection(page, {create = false} = {}) {
    if (create) await page.locator('#databaseSetupModeCreate').check();
    await page.locator('#databaseSetupNext').click();
    await page.locator('#databaseSetupHost').fill('postgres.database.svc.cluster.local');
    await page.locator('#databaseSetupPassword').fill('APP_SECRET_SENTINEL');
    if (create) await page.locator('#databaseSetupAdminPassword').fill('ADMIN_SECRET_SENTINEL');
}
async function check(page) {
    await page.locator('#databaseSetupCheck').click();
    await page.waitForFunction(() => !document.getElementById('databaseSetupCheck').disabled);
}

test('a successful plaintext check is the primary result and TLS is a single separate warning', async () => {
    const page = await fixture();
    try {
        await connection(page);
        await page.locator('#databaseSetupTls').uncheck();
        await check(page);
        assert.match(await page.locator('#databaseSetupStatus').innerText(), /Connection successful/i);
        assert.equal(await page.locator('#databaseSetupResult').getAttribute('data-status'), 'success');
        assert.match(await page.locator('#databaseSetupResultNext').innerText(), /configuration/i);
        assert.equal(await page.locator('#databaseSetupTlsWarning').isVisible(), true);
        assert.equal(await page.locator('#databaseSetupDetails').evaluate(node => node.open), false);
        assert.equal(await page.locator('#databaseSetupResults').innerText().then(value => /TLS is disabled/.test(value)), false);
        await page.locator('#databaseSetupNext').click();
        await page.waitForFunction(() => !document.getElementById('databaseSetupProperties').closest('[data-database-step]').hidden);
        assert.equal(await page.locator('#databaseSetupTlsWarning').isVisible(), true, 'Transport remains visible when applying the export');
        assert.equal(await page.locator('#databaseSetupTlsWarning').count(), 1);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('TLS failure has a diagnosis and a second click tests again using the changed setting', async () => {
    const page = await fixture({responses: [tlsFailure, success]});
    try {
        await connection(page);
        await check(page);
        assert.equal(await page.locator('#databaseSetupResult').getAttribute('data-status'), 'error');
        assert.match(await page.locator('#databaseSetupResultMessage').innerText(), /TLS|certificate/i);
        assert.equal(await page.locator('#databaseSetupResults .bg-success').count(), 0);
        await page.locator('#databaseSetupTls').uncheck();
        assert.equal(await page.locator('#databaseSetupResult').isVisible(), false);
        await check(page);
        assert.match(await page.locator('#databaseSetupStatus').innerText(), /Connection successful/i);
        const requests = await page.evaluate(() => window.databaseRequests.filter(item => item.url.endsWith('/check')));
        assert.equal(requests.length, 2);
        assert.deepEqual(requests.map(item => item.body.tls), [true, false]);
        await page.locator('#databaseSetupHost').fill('other.database.svc.cluster.local');
        assert.equal(await page.locator('#databaseSetupResult').isVisible(), false);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('reopening retains server and TLS choices but clears both passwords', async () => {
    const page = await fixture();
    try {
        await connection(page);
        await page.locator('#databaseSetupTls').uncheck();
        await page.locator('#databaseSetupClose').click();
        await page.evaluate(() => window.TaxonomyDatabaseSetup.open());
        await page.locator('#databaseSetupNext').click();
        assert.equal(await page.locator('#databaseSetupHost').inputValue(), 'postgres.database.svc.cluster.local');
        assert.equal(await page.locator('#databaseSetupTls').isChecked(), false);
        assert.equal(await page.locator('#databaseSetupPassword').inputValue(), '');
        assert.equal(await page.locator('#databaseSetupAdminPassword').inputValue(), '');
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('creating requires an inspected plan and a separate explicit confirmation; exports use only the app user', async () => {
    const page = await fixture({responses: [ready, complete]});
    try {
        await connection(page, {create: true});
        assert.equal(await page.locator('#databaseSetupProvision').isVisible(), false);
        await check(page);
        assert.deepEqual(await page.evaluate(() => window.databaseRequests.map(item => item.url)), ['/api/admin/database-setup/postgres/inspect']);
        assert.equal(await page.locator('#databaseSetupReview').isVisible(), true);
        assert.match(await page.locator('#databaseSetupReview').innerText(), /taxonomy_app/);
        assert.match(await page.locator('#databaseSetupReview').innerText(), /taxonomy/);
        assert.equal(await page.locator('#databaseSetupNext').isVisible(), false);
        await page.locator('#databaseSetupProvision').click();
        await page.waitForFunction(() => !document.getElementById('databaseSetupCheck').disabled);
        assert.match(await page.locator('#databaseSetupStatus').innerText(), /Database ready/i);
        assert.equal(await page.locator('#databaseSetupAdminPassword').inputValue(), '');
        const creation = await page.evaluate(() => window.databaseRequests.find(item => item.url.endsWith('/provision')));
        assert.equal(creation.body.confirmed, true);
        assert.equal(creation.body.expectedRole, 'CREATE');
        assert.equal(creation.body.expectedDatabase, 'CREATE');
        assert.equal(creation.body.target.username, 'taxonomy_app');
        assert.equal(creation.body.adminUsername, 'postgres');
        await page.locator('#databaseSetupNext').click();
        await page.waitForFunction(() => !document.getElementById('databaseSetupProperties').closest('[data-database-step]').hidden);
        const exported = await page.locator('#databaseSetupProperties').innerText();
        assert.match(exported, /taxonomy_app/);
        assert.doesNotMatch(exported, /ADMIN_SECRET_SENTINEL|APP_SECRET_SENTINEL/);
        const preview = await page.evaluate(() => window.databaseRequests.find(item => item.url.endsWith('/preview')));
        assert.equal(preview.body.password, '');
        assert.equal(Object.hasOwn(preview.body, 'adminPassword'), false);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('creation locks navigation and a lost response requires inspection before another write', async () => {
    const page = await fixture({responses: [ready, {pending: true, value: complete}]});
    try {
        await connection(page, {create: true});
        await check(page);
        await page.locator('#databaseSetupProvision').click();
        assert.equal(await page.locator('#databaseSetupHost').isDisabled(), true);
        assert.equal(await page.locator('#databaseSetupClose').isDisabled(), true);
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('#databaseSetupDialog').evaluate(node => node.open), true);
        await page.evaluate(() => window.finishDatabaseRequest());
        await page.waitForFunction(() => !document.getElementById('databaseSetupCheck').disabled);
        assert.equal(await page.locator('#databaseSetupHost').isDisabled(), false);
    } finally { await page.close(); }
    const lost = await fixture({responses: [ready, {reject: {name: 'TimeoutError'}}, {...ready, role: 'REUSE', database: 'REUSE'}]});
    try {
        await connection(lost, {create: true});
        await check(lost);
        await lost.locator('#databaseSetupProvision').click();
        await lost.waitForFunction(() => !document.getElementById('databaseSetupCheck').disabled);
        assert.match(await lost.locator('#databaseSetupStatus').innerText(), /outcome.*unknown|could not be confirmed/i);
        assert.equal(await lost.locator('#databaseSetupProvision').isVisible(), false);
        assert.equal(await lost.locator('#databaseSetupNext').isVisible(), false);
        await check(lost);
        const urls = await lost.evaluate(() => window.databaseRequests.map(item => item.url));
        assert.deepEqual(urls, ['/api/admin/database-setup/postgres/inspect', '/api/admin/database-setup/postgres/provision', '/api/admin/database-setup/postgres/inspect']);
        assert.match(await lost.locator('#databaseSetupReview').innerText(), /reuse|existing/i);
        assert.deepEqual(lost.errors, []);
    } finally { await lost.close(); }
});

test('a stalled JSON body releases creation controls into UNKNOWN and ignores late success', async () => {
    const page = await fixture({realTransport: true,
        responses: [ready, {pending: true, value: complete}, {...ready, role: 'REUSE', database: 'REUSE'}]});
    try {
        await page.clock.install();
        await connection(page, {create: true});
        await check(page);
        await page.locator('#databaseSetupProvision').click();
        await page.waitForFunction(() => window.databaseHeadersReceived);
        assert.equal(await page.locator('#databaseSetupClose').isDisabled(), true);
        await page.clock.fastForward(75001);
        await page.waitForFunction(() => !document.getElementById('databaseSetupCheck').disabled);
        assert.match(await page.locator('#databaseSetupStatus').innerText(), /outcome.*unknown|could not be confirmed/i);
        assert.equal(await page.locator('#databaseSetupClose').isDisabled(), false);
        assert.equal(await page.locator('#databaseSetupHost').isDisabled(), false);
        assert.equal(await page.locator('#databaseSetupProvision').isVisible(), false);
        assert.equal(await page.locator('#databaseSetupNext').isVisible(), false);
        await page.evaluate(() => window.finishDatabaseRequest());
        assert.match(await page.locator('#databaseSetupStatus').innerText(), /outcome.*unknown|could not be confirmed/i);
        assert.equal(await page.locator('#databaseSetupAdminPassword').inputValue(), 'ADMIN_SECRET_SENTINEL');
        await check(page);
        assert.deepEqual(await page.evaluate(() => window.databaseRequests.map(item => item.url)),
            ['/api/admin/database-setup/postgres/inspect', '/api/admin/database-setup/postgres/provision', '/api/admin/database-setup/postgres/inspect']);
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('#databaseSetupDialog').evaluate(node => node.open), false);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

for (const locale of ['en', 'de']) test(`${locale}: mobile result and review are localized, keyboard accessible and fit the viewport`, async () => {
    const page = await fixture({locale, width: 390, responses: [ready]});
    try {
        await connection(page, {create: true});
        await page.locator('#databaseSetupTls').uncheck();
        await check(page);
        const text = await page.locator('#databaseSetupDialog').innerText();
        assert.doesNotMatch(text, /database\.setup\./);
        const bounds = await page.locator('#databaseSetupDialog').boundingBox();
        assert.ok(bounds.width <= 390 && bounds.x >= 0);
        assert.equal(await page.locator('#databaseSetupReview').isVisible(), true);
        await page.locator('#databaseSetupProvision').focus();
        assert.equal(await page.locator('#databaseSetupProvision').evaluate(node => document.activeElement === node), true);
        await mkdir('target/ui-verification/database-setup-guided', {recursive: true});
        await page.screenshot({path: `target/ui-verification/database-setup-guided/review-${locale}-mobile.png`});
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});
