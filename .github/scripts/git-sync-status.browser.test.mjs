/** Independent Git and workspace polls must retain both accepted statuses. */
import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {readFile, writeFile} from 'node:fs/promises';
import {createNavigationPollEvidence, createConsoleErrorLedger} from './ui-navigation-poll-evidence.mjs';

const {chromium} = createRequire(import.meta.url)('playwright');
const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async () => {
    browser = await chromium.launch({headless: true,
        ...(process.env.CHROMIUM_EXECUTABLE ? {executablePath: process.env.CHROMIUM_EXECUTABLE} : {}),
        args: ['--no-sandbox', '--disable-dev-shm-usage']});
});
after(async () => { await browser?.close(); });

for (const prefix of ['', '/taxonomy']) test(`root-shell reload pins original status reads before analysis hydration (${prefix || '/'})`, async () => {
    const context = await browser.newContext({viewport:{width:390, height:844}}), page = await context.newPage();
    const baseUrl = 'http://status.test' + prefix + '/', workspaceId = 'workspace-1';
    const query = new URLSearchParams({architectureSource:'working', lang:'en', repositoryId:'repository-1',
        workspaceId, workspaceScopeKey:workspaceId, branch:'draft', revision:'6', element:'CR-1000', layout:'stable'});
    const url = baseUrl + '?' + query + '#architecture';
    const errors = [], reconciled = [], ledger = createConsoleErrorLedger(errors, reconciled);
    const observer = createNavigationPollEvidence({browserName:'webkit', baseUrl});
    const headers = [], headerTasks = [], pageErrors = [], lifecycleLoads = [];
    const git = {currentBranch:'draft', headCommit:'a'.repeat(40), projectionStale:false, indexStale:false,
        branches:['draft'], totalCommits:1, operationInProgress:false};
    const current = {contextId:'context-1', branch:'draft', commitId:null, mode:'EDITABLE',
        timestamp:'2026-11-06T22:12:09Z', dirty:false};
    const paths = ['shared/taxonomy-utils.js', 'shared/architecture-workflow.js',
        'api/taxonomy-api-client.js', 'api/workspace-api.js', 'core/taxonomy-state.js', 'core/taxonomy-analysis-session-core.js',
        'core/taxonomy-analysis-session-api-routing.js'];
    const messages = Object.fromEntries((await readFile(path.join(resources, 'i18n/messages.properties'), 'utf8'))
        .split('\n').map(line => line.match(/^([^#!\s][^=]*)=(.*)$/)).filter(Boolean).map(match => [match[1], match[2]]));
    const template = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
    const statusMarkup = ['gitStatusBar', 'contextBar'].map(id => {
        const markup = template.match(new RegExp('<div id="' + id + '"[^>]*><\\/div>'))?.[0];
        assert.ok(markup, 'Retain the actual root-shell status container: ' + id);
        return markup;
    }).join('');
    try {
        page.on('pageerror', error => pageErrors.push(error.message));
        page.on('request', request => {
            if (new URL(request.url()).pathname.endsWith('/js/core/taxonomy-analysis-session.js'))
                lifecycleLoads.push(request.url());
            if (!/\/api\/(?:git\/state|context\/current)$/.test(new URL(request.url()).pathname)) return;
            const item = {url:request.url(), pageUrl:page.url(), immediate:request.headers()['x-taxonomy-workspace-id'] || null};
            headers.push(item);
            headerTasks.push(request.allHeaders().then(all => { item.all = all['x-taxonomy-workspace-id'] || null; }));
        });
        // Drive the two production poll intervals independently of the runner's wall clock.
        // Startup, reload, requests, response bodies and renderer adoption remain native.
        await context.addInitScript(() => {
            const schedule = window.setInterval.bind(window), cancel = window.clearInterval.bind(window);
            const statusIntervals = new Map();
            let next = -1;
            window.setInterval = (callback, delay, ...args) => {
                if (delay !== 10000) return schedule(callback, delay, ...args);
                const id = next--;
                statusIntervals.set(id, () => callback(...args));
                return id;
            };
            window.clearInterval = id => { if (!statusIntervals.delete(id)) cancel(id); };
            window.__fixturePollStatus = () => {
                for (const callback of statusIntervals.values()) callback();
                return statusIntervals.size;
            };
        });
        await observer.attach(page, context);
        // The engine signature is a controlled fixture at a real beforeunload.
        // Original HTTP, routing, DTO adoption and document epochs are native Chromium evidence.
        await context.exposeBinding('__fixtureStatusCancellation', () => {
            for (const endpoint of ['/api/git/state?branch=draft', '/api/context/current']) {
                const endpointUrl = baseUrl.slice(0, -1) + endpoint;
                const message = '/status.test' + prefix + endpoint + ' due to access control checks.';
                observer.recordError({name:'Fetch API cannot load http', message,
                    stack:'Fetch API cannot load ' + endpointUrl + ' due to access control checks.\n'
                        + '    at routedFetch (' + baseUrl + 'js/core/taxonomy-analysis-session-core.js:273:39)'}, ledger.record(message));
            }
        });
        await page.route('http://status.test/**', async route => {
            const target = new URL(route.request().url()), relative = target.pathname.slice(prefix.length);
            if (relative.startsWith('/js/')) return route.fulfill({contentType:'text/javascript',
                body:await readFile(path.join(resources, 'static', relative), 'utf8')});
            // This navigation proof requires both original reads to finish. Deliver Git first;
            // the converse ordering has a separate contract below for the superseded read.
            if (relative === '/api/context/current') {
                await page.waitForFunction(() => document.querySelector('.git-sha')?.textContent === 'aaaaaaa');
            }
            if (relative.startsWith('/api/')) return route.fulfill({contentType:'application/json', body:JSON.stringify(
                relative === '/api/i18n/en' ? messages : relative === '/api/git/state' ? git : relative === '/api/context/current' ? current : {})});
            return route.fulfill({contentType:'text/html', body:'<!doctype html><html lang="en"><head><meta charset="UTF-8">'
                + '<script src="' + prefix + '/js/taxonomy-i18n.js"></script></head><body>' + statusMarkup
                // This fixture owns only pre-hydration Core/Routing. Claim that
                // lifecycle before taxonomy-state could start the full loader.
                + '<script data-taxonomy-analysis-session></script>'
                + paths.map(file => '<script src="' + prefix + '/js/' + file + '"></script>').join('')
                + '<script>__TaxonomyAnalysisSessionContext.installWorkspaceFetchRouting();</script>'
                + '<script src="' + prefix + '/js/workspace/taxonomy-git-status.js"></script>'
                + '<script src="' + prefix + '/js/versioning/taxonomy-context-bar.js"></script>'
                + '<script>document.addEventListener("DOMContentLoaded",()=>TaxonomyContextBar.init("contextBar"));</script>'
                + '</body></html>'});
        });
        await page.goto(url);
        await page.waitForFunction(() => document.querySelector('.git-sha')?.textContent === 'aaaaaaa'
            && window.TaxonomyContextBar?.getCurrentContext()?.contextId === 'context-1');
        const initialEpoch = await page.evaluate(() => performance.timeOrigin);
        assert.equal(await page.evaluate(() => __TaxonomyAnalysisSessionContext.runtime.workspaceId), null);
        assert.equal(await page.evaluate(() => __TaxonomyAnalysisSessionContext.runtime.workspaceResolved), false);
        assert.equal(await page.evaluate(() => window.TaxonomyAnalysisSession), undefined);
        assert.deepEqual(lifecycleLoads, [], 'The owned pre-hydration fixture must not start the full lifecycle');
        await page.evaluate(() => window.addEventListener('beforeunload', () => window.__fixtureStatusCancellation()));
        await observer.begin(null, 'reload');
        await page.reload();
        await page.waitForFunction(() => document.querySelector('.git-sha')?.textContent === 'aaaaaaa'
            && window.TaxonomyContextBar?.getCurrentContext()?.contextId === 'context-1');
        await observer.end(null, true, 'reload');
        await observer.verifyDestination(null, 'reload');
        await Promise.all(headerTasks);
        assert.equal(await page.evaluate(() => __TaxonomyAnalysisSessionContext.runtime.workspaceId), null);
        assert.equal(await page.evaluate(() => __TaxonomyAnalysisSessionContext.runtime.workspaceResolved), false);
        assert.equal(await page.evaluate(() => window.TaxonomyAnalysisSession), undefined);
        assert.deepEqual(lifecycleLoads, [], 'Reload must retain the fixture’s pre-hydration lifecycle ownership');
        assert.equal(page.url(), url, 'Status reads must not rewrite any URL, scope or selection pin');
        assert.notEqual(await page.evaluate(() => performance.timeOrigin), initialEpoch);
        // Before analysis hydration, GitStatus and ContextBar each issue one
        // startup read. Confirm both physical requests in each document.
        const observed = observer.snapshot();
        const epochs = [initialEpoch, observed.navigations[0].newDocument.epoch];
        assert.deepEqual(headers.map(item => new URL(item.url).pathname.slice(prefix.length)),
            epochs.flatMap(() => ['/api/git/state', '/api/context/current']),
            'Both status transports must be exercised at startup and reload');
        assert.deepEqual(observed.reads.map(read => read.documentEpoch), epochs.flatMap(epoch => [epoch, epoch]));
        assert.deepEqual(headers.map(item => [item.immediate, item.all]),
            headers.map(() => [workspaceId, workspaceId]),
            'Immediate passive headers and allHeaders must both prove the exact original workspace: ' + JSON.stringify(headers));
        assert.deepEqual(observed.reads.map(read => read.workspaceId), headers.map(item => item.all));
        assert.ok(observed.reads.every(read => read.finished && read.jsonParsed && read.status === 200));
        assert.equal(observed.navigations[0].oldDocument.url, observed.navigations[0].newDocument.url);
        const proofs = observer.reconciliations();
        assert.deepEqual(pageErrors, [], 'Actual renderer and script errors remain blocking');
        ledger.reconcile(proofs);
        // A failed cancellation proof must expose the original browser/HTTP
        // evidence, not merely say that an artificial WebKit error remained.
        // Keep the strict ledger assertion: never silently waive an error.
        const diagnosis = errors.length ? JSON.stringify({
            lifecycle: observed.events.filter(event =>
                ['beforeunload', 'pagehide', 'poll-page-error', 'document-start'].includes(event.type)
                || event.type.includes('navigation')).map(event => ({
                    type:event.type, sequence:event.sequence, epoch:event.documentEpoch,
                    url:event.url, pageUrl:event.pageUrl, message:event.message})),
            requests: observed.reads.map(read => ({
                id:read.id, url:read.url, epoch:read.documentEpoch, pageUrl:read.pageUrl,
                workspaceId:read.workspaceId, sequence:read.sequence, status:read.status,
                finished:read.finished, jsonParsed:read.jsonParsed, failed:read.failed})),
            navigation: observed.navigations.map(item => ({
                completed:item.completed, startSequence:item.startSequence, endSequence:item.endSequence,
                oldDocument:item.oldDocument, newDocument:item.newDocument,
                adoption:item.observation})),
            pageErrors, reconciledCount:proofs.length
        }, null, 2) : '';
        assert.deepEqual(errors, [], 'Full native lifecycle and adoption evidence for an unreconciled cancellation: ' + diagnosis);
        assert.deepEqual(reconciled.map(proof => proof.request.payload), [git, current]);
        // Exercise one complete periodic cycle after both document-start pairs have been verified.
        const endpoints = ['/api/git/state', '/api/context/current'];
        const writes = await page.evaluate(() => [__taxonomyPollEvidence.gitWrites, __taxonomyPollEvidence.contextWrites]);
        const offset = headers.length;
        const cycle = await Promise.all([
            ...endpoints.map(endpoint => page.waitForEvent('requestfinished',
                request => new URL(request.url()).pathname === prefix + endpoint)),
            page.evaluate(() => window.__fixturePollStatus())
        ]);
        assert.equal(cycle.pop(), 2, 'Each status owner schedules exactly one ten-second poll');
        await page.waitForFunction(([gitBefore, contextBefore]) =>
            __taxonomyPollEvidence.gitWrites > gitBefore && __taxonomyPollEvidence.contextWrites > contextBefore, writes);
        await Promise.all(headerTasks);
        assert.deepEqual(headers.slice(offset).map(read => new URL(read.url).pathname.slice(prefix.length)), endpoints);
        assert.deepEqual(headers.slice(offset).map(read => [read.immediate, read.all]), endpoints.map(() => [workspaceId, workspaceId]));
        const responses = await Promise.all(cycle.map(request => request.response()));
        assert.deepEqual(responses.map(response => response.status()), [200, 200]);
        assert.deepEqual(await Promise.all(responses.map(response => response.json())), [git, current]);
        assert.deepEqual(observed.reads.slice(offset).map(read => read.documentEpoch), endpoints.map(() => epochs[1]));
        assert.deepEqual(pageErrors, []);
        await writeFile(path.resolve('target/native-status-reload-' + (prefix ? 'mounted' : 'root') + '.json'),
            JSON.stringify({browser:'chromium', nativeEngineCancellationReproduced:false, headers, observed, proofs, pageErrors}, null, 2) + '\n');

    } finally { await context.close(); }
});

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
    await page.addScriptTag({path: path.join(resources, 'static/js/api/taxonomy-api-client.js')});
    await page.addScriptTag({path: path.join(resources, 'static/js/api/workspace-api.js')});
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

test('initial context adoption immediately replaces a pending Git read for the default branch', async () => {
    const page = await fixture();
    try {
        await page.evaluate(() => {
            document.body.insertAdjacentHTML('beforeend', '<div id="contextBar"></div>');
            window.TaxonomyI18n.formatBranch = branch => branch;
        });
        await page.addScriptTag({path: path.join(resources, 'static/js/versioning/taxonomy-context-bar.js')});
        await page.evaluate(() => { window.initialContext = TaxonomyContextBar.fetchAndRender('contextBar'); });
        await answer(page, '/api/context/current', {contextId: 'review-1', branch: 'review', mode: 'EDITABLE'});
        await page.evaluate(() => window.initialContext);
        await page.waitForFunction(() => window.statusReads.some(read => read.url.includes('branch=review')),
            null, {timeout: 3000});
        await answer(page, '/api/git/state', {currentBranch: 'draft', headCommit: '1111111', branches: []});
        assert.equal(await page.locator('.git-sha').count(), 0);
        await answer(page, '/api/git/state', {currentBranch: 'review', headCommit: '2222222', branches: []});
        await page.waitForFunction(() => document.querySelector('.git-sha')?.textContent === '2222222');
        assert.equal(await page.locator('.git-branch').innerText(), 'review');
        assert.equal(await page.evaluate(() => window.statusReads.filter(read => read.url.startsWith('/api/git/state')).length), 2);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

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

test('context polling and keyboard navigation retain focus on the active branch', async () => {
    const page = await fixture();
    try {
        await page.evaluate(() => {
            document.body.insertAdjacentHTML('beforeend', '<div id="contextBar"></div><button id="outside">Outside</button>');
            window.TaxonomyI18n.formatBranch = branch => branch;
        });
        await page.addScriptTag({path: path.join(resources, 'static/js/versioning/taxonomy-context-bar.js')});
        const origin = {contextId: 'origin', branch: 'main', commitId: 'aaa', mode: 'EDITABLE'};
        const variant = {contextId: 'variant', branch: 'review', commitId: 'bbb', mode: 'READ_ONLY',
            originContextId: 'origin', originBranch: 'main'};
        async function refresh(data) {
            await page.evaluate(() => { window.refreshResult = TaxonomyContextBar.fetchAndRender('contextBar'); });
            await answer(page, '/api/context/current', data);
            await page.evaluate(() => window.refreshResult);
        }
        await refresh(variant);
        const back = page.getByRole('button', {name: /context\.back$/});
        await back.focus();
        await refresh({...variant, dirty: true});
        assert.equal(await back.evaluate(button => button === document.activeElement), true,
            'A background refresh must retain keyboard focus on Back');
        await page.keyboard.press('Enter');
        await answer(page, '/api/context/back', origin);
        await page.waitForFunction(() => TaxonomyContextBar.getCurrentContext()?.contextId === 'origin');
        assert.equal(await page.evaluate(() => document.activeElement.textContent), 'main',
            'Back disappears at the origin, so focus moves to the new branch name');
        await page.locator('#outside').focus();
        await refresh(origin);
        assert.equal(await page.evaluate(() => document.activeElement.id), 'outside');
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});
