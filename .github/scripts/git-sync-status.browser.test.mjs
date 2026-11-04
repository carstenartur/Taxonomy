/** Independent Git and workspace polls must retain both accepted statuses. */
import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';

const {chromium} = createRequire(import.meta.url)('playwright');
const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async () => {
    browser = await chromium.launch({headless: true,
        ...(process.env.CHROMIUM_EXECUTABLE ? {executablePath: process.env.CHROMIUM_EXECUTABLE} : {}),
        args: ['--no-sandbox', '--disable-dev-shm-usage']});
});
after(async () => { await browser?.close(); });

const initial = {syncStatus: 'UP_TO_DATE', unpublishedCommitCount: 0};
async function fixture() {
    const page = await browser.newPage();
    page.errors = [];
    page.on('pageerror', error => page.errors.push(error.message));
    await page.setContent('<html lang="en"><body><div id="gitStatusBar"></div><div id="syncStatePanel"></div></body></html>');
    await page.evaluate(() => {
        window.TaxonomyI18n = {t: (key, count) => ({
            'workspace.sync.status.synced': 'synced', 'workspace.sync.status.ahead': `${count} ahead`,
            'workspace.sync.panel.uptodate': 'Up to date', 'workspace.sync.panel.ahead': `${count} unpublished`,
            'workspace.sync.indicator': 'Sync'
        }[key] || key)};
        window.TaxonomyUtils = {escapeHtml: value => String(value)};
        // Control only clock and HTTP boundaries; both production renderers use the real DOM.
        window.statusTimers = new Map();
        let nextTimer = 0;
        window.setInterval = (callback, delay) => {
            const id = ++nextTimer;
            window.statusTimers.set(id, {callback, delay});
            return id;
        };
        window.clearInterval = id => window.statusTimers.delete(id);
        window.statusReads = [];
        window.fetch = url => new Promise(resolve => {
            window.statusReads.push({url: String(url), pending: true,
                answer(value) {
                    this.pending = false;
                    resolve(new Response(JSON.stringify(value), {status: 200,
                        headers: {'Content-Type': 'application/json'}}));
                }});
        });
    });
    await page.addScriptTag({path: path.join(resources, 'static/js/workspace/taxonomy-git-status.js')});
    await page.addScriptTag({path: path.join(resources, 'static/js/workspace/taxonomy-workspace-sync.js')});
    await page.evaluate(() => document.dispatchEvent(new Event('DOMContentLoaded')));
    return page;
}

async function answer(page, endpoint, value) {
    await page.evaluate(({endpoint, value}) => {
        const request = window.statusReads.find(read => read.pending && read.url.split('?')[0] === endpoint);
        if (!request) throw new Error('Expected a pending production poll: ' + endpoint);
        request.answer(value);
    }, {endpoint, value});
}
async function syncResponse(page, value = initial) {
    await answer(page, '/api/workspace/sync-state', value);
    await page.waitForFunction(expected => {
        const state = window.TaxonomyWorkspaceSync.getSyncState();
        return state?.syncStatus === expected.syncStatus && state?.unpublishedCommitCount === expected.unpublishedCommitCount;
    }, value);
}
async function gitResponse(page, headCommit = 'abcdef012345') {
    await answer(page, '/api/git/state', {currentBranch: 'draft', headCommit, branches: []});
    await page.waitForFunction(head => document.querySelector('.git-sha')?.textContent === head.slice(0, 7), headCommit);
}

for (const order of [['sync', 'git'], ['git', 'sync']]) {
    test('both statuses remain visible when responses arrive: ' + order.join(' then '), async () => {
        const page = await fixture();
        try {
            for (const kind of order) {
                if (kind === 'sync') await syncResponse(page);
                else await gitResponse(page);
            }
            assert.equal(await page.locator('#syncStatusIndicator').count(), 1);
            assert.match(await page.locator('#syncStatusIndicator').innerText(), /synced/);
            assert.equal(await page.locator('.git-branch').innerText(), 'draft');
            assert.deepEqual(await page.evaluate(() => window.TaxonomyWorkspaceSync.getSyncState()), initial);
            assert.equal(await page.evaluate(() => window.statusReads.length), 2, 'Rendering must not start another poll');
            assert.deepEqual(page.errors, []);
        } finally { await page.close(); }
    });
}

test('repeated Git refreshes retain the latest sync node without duplicates or extra HTTP reads', async () => {
    const page = await fixture();
    try {
        await gitResponse(page);
        await syncResponse(page);
        await page.evaluate(() => { window.acceptedSyncNode = document.getElementById('syncStatusIndicator'); });
        for (const head of ['123456789abc', '23456789abcd']) {
            await page.evaluate(() => window.TaxonomyGitStatus.refresh());
            await gitResponse(page, head);
            assert.equal(await page.evaluate(() => document.getElementById('syncStatusIndicator') === window.acceptedSyncNode), true);
            assert.equal(await page.locator('#syncStatusIndicator').count(), 1);
        }
        const latest = {syncStatus: 'AHEAD', unpublishedCommitCount: 3};
        await page.evaluate(() => window.TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, latest);
        assert.equal(await page.evaluate(() => document.getElementById('syncStatusIndicator') === window.acceptedSyncNode), false);
        await page.evaluate(() => {
            window.acceptedSyncNode = document.getElementById('syncStatusIndicator');
            window.TaxonomyGitStatus.refresh();
        });
        await gitResponse(page, '3456789abcde');
        assert.equal(await page.evaluate(() => document.getElementById('syncStatusIndicator') === window.acceptedSyncNode), true);
        assert.equal(await page.locator('#syncStatusIndicator').count(), 1);
        assert.match(await page.locator('#syncStatusIndicator').innerText(), /3 ahead/);
        assert.match(await page.locator('#syncStatePanel').innerText(), /3 unpublished/);
        assert.deepEqual(await page.evaluate(() => window.TaxonomyWorkspaceSync.getSyncState()), latest);
        const urls = await page.evaluate(() => window.statusReads.map(read => read.url));
        assert.equal(urls.filter(url => url.startsWith('/api/git/state')).length, 4);
        assert.equal(urls.filter(url => url === '/api/workspace/sync-state').length, 2);
        assert.deepEqual(await page.evaluate(() => [...window.statusTimers.values()].map(timer => timer.delay)), [10000, 15000]);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});
