import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {chromium} from 'playwright';

const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async () => { browser = await chromium.launch({headless:true,
    ...(process.env.CHROMIUM_EXECUTABLE ? {executablePath:process.env.CHROMIUM_EXECUTABLE} : {}),
    args:['--no-sandbox','--disable-dev-shm-usage']}); });
after(async () => { await browser?.close(); });

async function pageFor(markup, script, setup = () => {}) {
    const page = await browser.newPage();
    page.errors = [];
    page.on('pageerror', error => page.errors.push(error.message));
    await page.route('http://localhost/**', route => route.fulfill({contentType:'text/html',
        body:new URL(route.request().url()).pathname === '/edit' ? '<!doctype html><html lang="en"><body><a id="leave" href="/next">Architecture</a>' + markup + '</body></html>' : '<!doctype html><html><body>Destination</body></html>'}));
    await page.goto('http://localhost/edit');
    await page.evaluate(setup);
    await page.addScriptTag({path:path.join(resources,'static/js',script)});
    return page;
}

async function leaveAndCancel(page) {
    const dialog = page.waitForEvent('dialog', {timeout:10000});
    const click = page.locator('#leave').click();
    const prompt = await dialog;
    assert.equal(prompt.type(), 'beforeunload');
    await prompt.dismiss();
    await click;
    assert.match(page.url(), /\/edit$/);
}

const credentialForm = `<form id="createWebDavCredentialForm" action="/saved">
    <input id="credentialDescription" name="description" value="">
    <input id="credentialLifetime" name="lifetimeDays" type="number" value="30">
    <input id="credentialRead" name="readAllowed" type="checkbox" checked>
    <input id="credentialWrite" name="writeAllowed" type="checkbox">
    <button type="submit">Create</button></form><div id="credentialLive"></div>`;
const secret = '<code id="webdavCredentialSecret">one-time-secret</code><button id="copyWebDavCredential">Copy</button>';

test('the one-time WebDAV secret is protected until a successful copy', async () => {
    const page = await pageFor(credentialForm + secret, 'security/webdav-credentials.js', () => {
        Object.defineProperty(navigator, 'clipboard', {configurable:true, value:{writeText:async value => { window.copied = value; }}});
    });
    try {
        await leaveAndCancel(page);
        await page.locator('#copyWebDavCredential').click();
        assert.equal(await page.evaluate(() => copied), 'one-time-secret');
        await page.locator('#leave').click();
        assert.match(page.url(), /\/next$/);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('a failed manual copy cleans up the temporary field and keeps the one-time secret protected', async () => {
    const page = await pageFor(credentialForm + secret, 'security/webdav-credentials.js', () => {
        Object.defineProperty(navigator, 'clipboard', {configurable:true, value:undefined});
        document.execCommand = () => false;
    });
    try {
        await page.locator('#copyWebDavCredential').click();
        assert.equal(await page.locator('textarea').count(), 0);
        await leaveAndCancel(page);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('credential form edits are protected, but submitting the edited form is allowed', async () => {
    const page = await pageFor(credentialForm, 'security/webdav-credentials.js');
    try {
        await page.locator('#credentialDescription').fill('Work laptop');
        await leaveAndCancel(page);
        await page.getByRole('button',{name:'Create'}).click();
        assert.match(page.url(), /\/saved\?/);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('submitting another credential does not discard an uncopied one-time secret', async () => {
    const page = await pageFor(credentialForm + secret, 'security/webdav-credentials.js');
    try {
        const pending = page.waitForEvent('dialog', {timeout:10000});
        const click = page.getByRole('button',{name:'Create'}).click();
        const prompt = await pending;
        assert.equal(prompt.type(), 'beforeunload');
        await prompt.dismiss();
        await click;
        assert.match(page.url(), /\/edit$/);
        await leaveAndCancel(page);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('a canceled credential submission continues to protect the edited form', async () => {
    const page = await pageFor(credentialForm, 'security/webdav-credentials.js');
    try {
        await page.evaluate(() => document.getElementById('createWebDavCredentialForm')
            .addEventListener('submit', event => event.preventDefault()));
        await page.locator('#credentialDescription').fill('Work laptop');
        await page.getByRole('button',{name:'Create'}).click();
        await leaveAndCancel(page);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

const list = `<div id="documentTemplateWorkspace" data-api-base="/api/templates" data-webdav-base="/dav/" data-direct-word-enabled="false">
    <table><tbody id="documentTemplateRows"></tbody></table><div id="documentTemplateEmpty"></div>
    <button id="documentTemplateRefresh">Refresh</button><div id="documentTemplateError"></div>
    <div id="documentTemplateSuccess"></div><div id="documentTemplateLive"></div>
    <div id="documentTemplateHistoryBody"></div><div id="documentTemplateHistoryTitle"></div><div id="documentTemplateHistoryModal"></div>
    <form id="documentTemplateUploadForm"><input id="documentTemplateId" required>
    <input id="documentTemplateDisplayName" required><input id="documentTemplateFile" type="file" required>
    <button id="documentTemplateUploadButton" type="submit">Upload</button><span id="documentTemplateUploadSpinner"></span></form></div>`;

test('a new template stays protected through its upload and clears after confirmation', async () => {
    const page = await pageFor(list, 'document-templates/document-templates.js', () => {
        window.TaxonomyDocumentTemplateLinks = {DOTX_MEDIA_TYPE:'application/template'};
        window.TaxonomyDocumentTemplatesApi = {list:async () => [], upload:() => new Promise(resolve => {window.completeUpload = resolve;})};
    });
    try {
        await page.locator('#documentTemplateId').fill('sample');
        await page.locator('#documentTemplateDisplayName').fill('Sample');
        await page.locator('#documentTemplateFile').setInputFiles({name:'sample.dotx',mimeType:'application/template',buffer:Buffer.from('fixture')});
        await leaveAndCancel(page);
        await page.getByRole('button',{name:'Upload'}).click();
        await page.waitForFunction(() => typeof completeUpload === 'function');
        await leaveAndCancel(page);
        await page.evaluate(() => completeUpload({displayName:'Sample'}));
        await page.waitForFunction(() => document.getElementById('documentTemplateUploadForm').getAttribute('aria-busy') === 'false');
        await page.locator('#leave').click();
        assert.match(page.url(), /\/next$/);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});
