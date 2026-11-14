import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser;

before(async () => {
    browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'],
        ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}) });
});
after(async () => { await browser?.close(); });

async function fixture(t, writable) {
    const page = await browser.newPage();
    t.after(() => page.close());
    const html = await readFile(path.join(resources, 'templates/fragments/architecture-organization-directory.html'), 'utf8');
    await page.route('http://localhost/directory', route => route.fulfill({ contentType: 'text/html', body: html }));
    await page.goto('http://localhost/directory');
    await page.addScriptTag({ path: path.join(resources, 'static/js/architecture-organization-directory.js') });
    await page.evaluate(mayEdit => {
        window.calls = [];
        window.units = [{ id: 'parent', title: 'Operations' }, { id: 'child', title: 'Network', parentId: 'parent' },
            { id: 'other', title: 'Procurement' }];
        window.directory = TaxonomyOrganizationDirectory.create({
            root: document.querySelector('#editorOrganizationDirectory'), t: key => key,
            stage: (intent, reason) => window.calls.push({ ...intent, reason: reason.value })
        });
        window.directory.render(window.units, mayEdit);
    }, writable);
    return page;
}

test('directory new-unit previews retain one ID until the accepted model supplies the unit', async t => {
    const page = await fixture(t, true);
    await page.locator('#editorDirectoryNew').click();
    await page.locator('#editorDirectoryName').fill('Networks');
    await page.locator('#editorDirectoryParent').selectOption('parent');
    await page.locator('#editorDirectoryReason').fill('Create the operating team');
    assert.equal(await page.evaluate(() => directory.hasDraft()), true);
    await page.locator('#editorDirectorySave').click();
    await page.evaluate(() => { directory.setEnabled(false); directory.setEnabled(true); });
    await page.locator('#editorDirectorySave').click();
    const [first, retry] = await page.evaluate(() => window.calls);
    assert.match(first.id, /^[a-f0-9-]{36}$/i);
    assert.deepEqual(retry, first);
    assert.deepEqual(first, { kind: 'UPSERT_ORGANIZATION', id: first.id,
        properties: { title: 'Networks', parentId: 'parent' }, reason: 'Create the operating team' });
    await page.evaluate(id => directory.render([...window.units, { id, title: 'Networks', parentId: 'parent' }], true), first.id);
    assert.equal(await page.locator('#editorDirectoryUnits').inputValue(), first.id);
    assert.equal(await page.evaluate(() => directory.hasDraft()), false);
});

test('directory edits keep the stable identity and offer only valid parents', async t => {
    const page = await fixture(t, true);
    await page.locator('#editorDirectoryUnits').selectOption('parent');
    assert.deepEqual(await page.locator('#editorDirectoryParent option').evaluateAll(options => options.map(o => o.value)), ['', 'other']);
    await page.locator('#editorDirectoryName').fill('Infrastructure');
    await page.locator('#editorDirectoryParent').selectOption('other');
    await page.locator('#editorDirectoryReason').fill('Reorganization');
    await page.locator('#editorDirectorySave').click();
    await page.locator('#editorDirectoryDelete').click();
    assert.deepEqual(await page.evaluate(() => window.calls), [
        { kind: 'UPSERT_ORGANIZATION', id: 'parent', properties: { title: 'Infrastructure', parentId: 'other' }, reason: 'Reorganization' },
        { kind: 'DELETE_ORGANIZATION', id: 'parent', reason: 'Reorganization' }
    ]);
});

test('read-only directory exposes unit paths and reset removes prior workspace records', async t => {
    const page = await fixture(t, false);
    assert.ok((await page.locator('#editorDirectoryUnits option').allTextContents()).includes('Operations / Network'));
    assert.equal(await page.locator('#editorDirectoryNew').isVisible(), false);
    assert.equal(await page.locator('#editorDirectoryForm').isVisible(), false);
    await page.evaluate(() => {
        document.getElementById('editorDirectoryForm').dispatchEvent(new Event('submit', { cancelable: true }));
        document.getElementById('editorDirectoryDelete').click();
        directory.reset();
    });
    assert.deepEqual(await page.evaluate(() => window.calls), []);
    assert.equal(await page.locator('#editorDirectoryUnits option').count(), 0);
});
