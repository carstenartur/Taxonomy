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
async function fixture(messages = {}) {
    const page = await browser.newPage();
    page.errors = [];
    page.on('pageerror', error => page.errors.push(error.message));
    await page.setContent('<html lang="en"><body><div id="gitStatusBar"></div><div id="syncStatePanel"></div></body></html>');
    await page.evaluate(messages => {
        window.TaxonomyI18n = {t: (key, count) => messages[key] || ({
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
        window.fetch = (url, init) => new Promise(resolve => {
            window.statusReads.push({url: String(url), method: init?.method || 'GET', body: init?.body, pending: true,
                answer(value, status = 200, headers) {
                    this.pending = false;
                    resolve(new Response(this.method === 'HEAD' ? null : JSON.stringify(value), {status,
                        headers: {'Content-Type': 'application/json', ...headers}}));
                }});
        });
    }, messages);
    await page.addScriptTag({path: path.join(resources, 'static/js/api/taxonomy-api-client.js')});
    await page.addScriptTag({path: path.join(resources, 'static/js/api/workspace-api.js')});
    await page.addScriptTag({path: path.join(resources, 'static/js/workspace/taxonomy-git-status.js')});
    await page.addScriptTag({path: path.join(resources, 'static/js/workspace/taxonomy-workspace-sync.js')});
    await page.evaluate(() => document.dispatchEvent(new Event('DOMContentLoaded')));
    return page;
}

async function answer(page, endpoint, value, status = 200, {method, headers} = {}) {
    await page.evaluate(({endpoint, value, status, method, headers}) => {
        const request = window.statusReads.find(read => read.pending && read.url.split('?')[0] === endpoint
            && (!method || read.method === method));
        if (!request) throw new Error('Expected a pending production poll: ' + endpoint);
        request.answer(value, status, headers);
    }, {endpoint, value, status, method, headers});
}
async function answerPlanHead(page, endpoint, tag) {
    await page.waitForFunction(path => window.statusReads.some(read =>
        read.pending && read.method === 'HEAD' && read.url.split('?')[0] === path), endpoint);
    await answer(page, endpoint, null, 200, {method:'HEAD', headers:{ETag:tag}});
}
async function answerCurrentPlan(page, endpoint, plan, tag = 'W/"current-plan"') {
    await answer(page, endpoint, plan, 200, {method:'GET', headers:{ETag:tag}});
    await answerPlanHead(page, endpoint, tag);
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

for (const [language, projection, index, fresh, missing, stale, failed, unverified] of [
    ['en', 'Projection', 'Index', 'fresh', 'no checkpoint', 'STALE', 'unavailable', 'unverified'],
    ['de', 'Projektion', 'Index', 'aktuell', 'kein Prüfpunkt', 'VERALTET', 'nicht verfügbar', 'nicht bestätigt']
]) {
    test('Git and view badges report selected-source readiness (' + language + ')', async () => {
        const bundle = language === 'de' ? 'messages_de.properties' : 'messages.properties';
        const lines = await readFile(path.join(resources, 'i18n', bundle), 'utf8');
        const messages = Object.fromEntries(lines.split('\n').map(line => line.match(/^([^#!\s][^=]*)=(.*)$/))
            .filter(Boolean).map(([, key, value]) => [key, value.replace(/\\u([a-f0-9]{4})/gi,
                (_, code) => String.fromCharCode(parseInt(code, 16)))]));
        const page = await fixture(messages);
        const head = 'a'.repeat(40);
        try {
            await page.evaluate(() => document.body.insertAdjacentHTML('beforeend', '<div id="viewContext"></div>'));
            await page.addScriptTag({path: path.join(resources, 'static/js/versioning/taxonomy-viewcontext.js')});
            const state = {currentBranch: 'draft', headCommit: head, projectionStale: false, indexStale: false};
            for (const [position, [source, projectionStatus, indexStatus]] of [
                [{...state, projectionReadiness: 'FRESH', indexReadiness: 'MISSING'}, fresh, missing],
                [{...state, projectionReadiness: 'STALE', indexReadiness: 'MISSING'}, stale, missing],
                [{...state, headCommit: null, projectionReadiness: 'FAILED', indexReadiness: 'FAILED'}, failed, failed],
                [state, unverified, unverified]
            ].entries()) {
                if (position > 0) await page.evaluate(() => { TaxonomyGitStatus.refresh(); });
                await page.evaluate(() => {
                    window.viewRead = TaxonomyViewContext.fetchAndRender('viewContext', 'draft');
                });
                await answer(page, '/api/git/state', source);
                await answer(page, '/api/git/state', source);
                await page.evaluate(() => window.viewRead);
                const expectedProjection = projection + ': ' + projectionStatus;
                const expectedIndex = index + ': ' + indexStatus;
                for (const id of ['gitStatusBar', 'viewContext']) {
                    const badge = await page.locator('#' + id).innerText();
                    assert.ok(badge.includes(expectedProjection), badge);
                    assert.ok(badge.includes(expectedIndex), badge);
                }
            }
            assert.deepEqual(page.errors, []);
        } finally { await page.close(); }
    });
}

test('a superseded view request cannot replace a direct render or restore a hidden badge', async () => {
    const page = await fixture();
    try {
        await page.evaluate(() => document.body.insertAdjacentHTML('beforeend', '<div id="viewContext"></div>'));
        await page.addScriptTag({path: path.join(resources, 'static/js/versioning/taxonomy-viewcontext.js')});
        await gitResponse(page);
        for (const replace of ['render', 'hide']) {
            await page.evaluate(replace => {
                window.viewRead = TaxonomyViewContext.fetchAndRender('viewContext', 'old');
                if (replace === 'render') TaxonomyViewContext.render('viewContext', {basedOnBranch: 'new'});
                else TaxonomyViewContext.hide('viewContext');
            }, replace);
            await answer(page, '/api/git/state', {currentBranch: 'old', headCommit: 'abcdef012345'});
            await page.evaluate(() => window.viewRead);
            const badge = page.locator('#viewContext');
            if (replace === 'render') assert.equal(await badge.locator('strong').innerText(), 'new');
            else {
                assert.equal(await badge.innerHTML(), '');
                assert.ok((await badge.getAttribute('class')).split(' ').includes('d-none'));
            }
        }
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

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

test('changing the selected Git context clears previous sync state and actions before another response', async () => {
    const page = await fixture();
    const index = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
    const fixedPull = page.locator('#fixedActionsFixture button[onclick="TaxonomyWorkspaceSync.syncFromShared()"]');
    const draft = {contextId:'a', branch:'draft-A', commitId:'aaaaaaaa', mode:'EDITABLE'};
    const snapshot = {contextId:'b', branch:'snapshot-B', commitId:'bbbbbbbb', mode:'READ_ONLY'};
    try {
        await page.evaluate(() => {
            document.body.insertAdjacentHTML('beforeend', '<div id="contextBar"></div><div id="localChangesPanel"></div>');
            window.TaxonomyI18n.formatBranch = branch => branch;
            window.TaxonomyArchitectureWorkflow = {getScope: () => ({repositoryId:'previous-repo'})};
        });
        await page.evaluate(html => {
            const template = new DOMParser().parseFromString(html, 'text/html');
            const group = template.querySelector('#versions-sync button[onclick="TaxonomyWorkspaceSync.syncFromShared()"]').parentElement;
            const section = document.createElement('div');
            section.id = 'fixedActionsFixture';
            section.append(document.importNode(group, true));
            document.body.append(section);
        }, index);
        assert.equal(await fixedPull.isDisabled(), true, 'Wait for the first confirmed Git selection');
        await page.addScriptTag({path: path.join(resources, 'static/js/versioning/taxonomy-context-bar.js')});
        async function select(context) {
            await page.evaluate(() => { window.currentRead = TaxonomyContextBar.fetchAndRender('contextBar'); });
            await answer(page, '/api/context/current', context);
            await page.evaluate(() => window.currentRead);
        }
        async function settle() {
            await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 0)));
        }
        await select(draft);
        await answer(page, '/api/workspace/sync-state', initial);
        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, {branch:draft.branch, syncStatus:'AHEAD', unpublishedCommitCount:3, lastSyncedCommitId:draft.commitId});
        await select(draft);
        assert.equal(await fixedPull.isEnabled(), true);
        await page.locator('#contextBarPublish').focus();
        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, {branch:draft.branch, syncStatus:'AHEAD', unpublishedCommitCount:4});
        assert.equal(await page.evaluate(() => document.activeElement.id), 'contextBarPublish');
        await page.evaluate(() => TaxonomyWorkspaceSync.showLocalChangesPanel());
        await answer(page, '/api/workspace/local-changes', {changeCount:3});
        await page.locator('#localChangesPanel button.btn-primary').waitFor();

        await page.evaluate(() => {
            window.notices = [];
            window.TaxonomyOperationResult = {
                showError: (...args) => notices.push(args),
                showWarning: (...args) => notices.push(args),
                showSuccess: (...args) => notices.push(args)
            };
            TaxonomyWorkspaceSync.showLocalChangesPanel();
            window.pendingPublication = TaxonomyWorkspaceSync.publish();
        });
        await fixedPull.focus();
        await select(snapshot);
        assert.equal(await fixedPull.isDisabled(), true, 'The fixed Sync tab must block writes at the same switch boundary');
        assert.equal(await page.evaluate(() => document.activeElement.id), 'contextBarBranch');
        assert.equal(await page.evaluate(() => TaxonomyWorkspaceSync.getSyncState()), null);
        assert.equal(await page.locator('#contextBarPublish, #syncStatusIndicator, #localChangesPanel button').count(), 0);
        assert.equal(await page.locator('#syncStatePanel').innerText(), '');
        await answer(page, '/api/workspace/local-changes', {changeCount:19});
        await answer(page, '/api/workspace/publish', {error:'previous draft', message:'previous draft'}, 409);
        await page.evaluate(() => pendingPublication);
        await settle();
        assert.equal(await page.locator('#localChangesPanel').innerText(), '');
        assert.deepEqual(await page.evaluate(() => notices), []);
        await answer(page, '/api/workspace/sync-state', {message:'temporarily unavailable'}, 503);
        await settle();
        assert.equal(await page.locator('#contextBarPublish, #syncStatusIndicator').count(), 0);
        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        // The workspace's current branch is still A when looking at B's historical state.
        await answer(page, '/api/workspace/sync-state', {branch:draft.branch, syncStatus:'AHEAD', unpublishedCommitCount:3});
        await settle();
        assert.equal(await page.evaluate(() => TaxonomyWorkspaceSync.getSyncState()), null);

        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, {branch:snapshot.branch, syncStatus:'DIVERGED', unpublishedCommitCount:1});
        await page.evaluate(() => { TaxonomyWorkspaceSync.publish(); TaxonomyWorkspaceSync.syncFromShared(); });
        assert.equal(await page.locator('#syncStatePanel button, #contextBarPublish, #contextBarSync').count(), 0);
        assert.deepEqual(await page.evaluate(() => statusReads.filter(read =>
            /workspace\/(publish|sync-from-shared)/.test(read.url)).map(read => read.url)),
            ['/api/workspace/publish?userBranch=draft-A']);

        await select(draft);
        assert.equal(await page.evaluate(() => TaxonomyWorkspaceSync.getSyncState()), null);
        await syncResponse(page, {branch:draft.branch, syncStatus:'AHEAD', unpublishedCommitCount:2});
        await page.locator('#contextBarPublish').waitFor();
        assert.match(await page.locator('#syncStatePanel').innerText(), /2 unpublished/);
        assert.equal(await fixedPull.isEnabled(), true);

        // ContextRef has no repository field. Pinned source navigation owns that identity.
        await page.evaluate(() => { window.TaxonomyArchitectureWorkflow = {getScope: () => ({repositoryId:'other-repo'})}; });
        await select(draft);
        assert.equal(await page.evaluate(() => TaxonomyWorkspaceSync.getSyncState()), null);
        await answer(page, '/api/workspace/sync-state', {repositoryId:'previous-repo', branch:draft.branch,
            syncStatus:'AHEAD', unpublishedCommitCount:3});
        await settle();
        assert.equal(await page.locator('#contextBarPublish').count(), 0);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('initial repository discovery preserves a pending read and later repository switches invalidate it', async () => {
    const page = await fixture();
    const current = {contextId:'context-1', branch:'draft', commitId:null, mode:'EDITABLE'};
    try {
        await page.addScriptTag({path: path.join(resources, 'static/js/shared/architecture-workflow.js')});
        await page.addScriptTag({path: path.join(resources, 'static/js/versioning/taxonomy-context-bar.js')});
        async function refresh() {
            await page.evaluate(() => { window.contextRead = TaxonomyContextBar.fetchAndRender('contextBar'); });
            await answer(page, '/api/context/current', current);
            await page.evaluate(() => window.contextRead);
            return page.evaluate(() => TaxonomyContextBar.getSelectionGeneration());
        }

        assert.equal(await refresh(), 0);
        await page.evaluate(() => {
            TaxonomyArchitectureWorkflow.setScope({repositoryId:'repository-a'});
            window.firstRepositoryRead = TaxonomyApiClient.captureContext();
        });
        assert.equal(await refresh(), 0);
        assert.equal(await page.evaluate(() => firstRepositoryRead()), true);

        await page.evaluate(() => TaxonomyArchitectureWorkflow.setScope({repositoryId:'repository-b'}));
        assert.equal(await refresh(), 1);
        await page.evaluate(() => TaxonomyArchitectureWorkflow.setScope({repositoryId:'repository-a'}));
        assert.equal(await refresh(), 2);
        assert.equal(await page.evaluate(() => firstRepositoryRead()), false);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});



test('Rebase plan shows exact fetched and live upstream commits and clears a superseded branch', async () => {
    const page = await fixture();
    try {
        const template = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
        const buttonAndPanel = template.match(/<button id="workspaceRebasePlanButton"[^>]*>[\s\S]*?<\/button>\s*<div id="workspaceRebasePlan"[^>]*><\/div>/)?.[0];
        assert.ok(buttonAndPanel);
        await page.evaluate(html => document.body.insertAdjacentHTML('beforeend', html), buttonAndPanel);
        const state = {syncStatus:'DIVERGED', repositoryId:'origin', workspaceId:'work', branch:'feature'};
        await syncResponse(page, state);
        const button = page.locator('#workspaceRebasePlanButton');
        assert.equal(await button.isVisible(), true);
        await button.click();
        const endpoint = '/api/workspace/upstream/rebase-plan';
        const plan = {status:'REPLAY', repositoryId:'origin', workspaceId:'work', branch:'feature', localCommit:'3'.repeat(40),
            mergeBase:'1'.repeat(40), upstream:{sourceRepositoryId:'origin',sourceBranch:'main',sourceCommit:'5'.repeat(40),
                trackedCommit:'4'.repeat(40)},commits:[{id:'2'.repeat(40), subject:'<form>first</form>'},{id:'3'.repeat(40), subject:'second'}]};
        await page.waitForFunction(path => window.statusReads.some(read => read.pending && read.url.startsWith(path)), endpoint);
        const url = new URL(await page.evaluate(path => window.statusReads.find(read => read.url.startsWith(path)).url, endpoint), 'http://status.test');
        assert.equal(url.searchParams.get('branch'), 'feature');
        assert.equal(url.searchParams.get('repositoryId'), 'origin');
        assert.equal(url.searchParams.get('workspaceId'), 'work');
        await answerCurrentPlan(page, endpoint, plan);
        await page.waitForFunction(() => document.querySelectorAll('#workspaceRebasePlan li').length === 2);
        const panel = page.locator('#workspaceRebasePlan');
        assert.match(await panel.innerText(), /444444444444/);
        assert.match(await panel.innerText(), /555555555555/);
        assert.match(await panel.innerText(), /<form>first<\/form>/);
        assert.equal(await panel.locator('form').count(), 0);
        assert.match(await panel.innerText(), /workspace.rebase.conflicts.unchecked/);
        await button.click();
        await page.evaluate(() => document.dispatchEvent(new CustomEvent('taxonomy:repository-context-changed',
            {detail:{selectionChanged:true}})));
        assert.equal(await panel.innerText(), '');
        await answer(page, endpoint, plan);
        assert.equal(await panel.innerText(), '');
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});


test('A central fork plan uses the explicit repository and branch without adopting a working copy', async () => {
    const page = await fixture();
    try {
        const template = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
        const markup = template.match(/<button id="workspaceRebasePlanButton"[^>]*>[\s\S]*?<\/button>\s*<div id="workspaceRebasePlan"[^>]*><\/div>/)[0];
        await page.evaluate(html => {
            document.body.insertAdjacentHTML('beforeend', html);
            window.TaxonomyContextBar = {getCurrentContext:() => ({mode:'READ_ONLY',branch:'main',commitId:null})};
        }, markup);
        await answer(page, '/api/workspace/sync-state', initial);
        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, {syncStatus:'UP_TO_DATE',repositoryId:'central-fork',workspaceId:null,branch:'main'});
        await page.locator('#workspaceRebasePlanButton').click();
        const endpoint = '/api/repositories/central-fork/upstream/rebase-plan';
        await page.waitForFunction(path => window.statusReads.some(read => read.pending && read.url.startsWith(path)), endpoint);
        const url = new URL(await page.evaluate(path => window.statusReads.find(read => read.url.startsWith(path)).url, endpoint), 'http://status.test');
        assert.equal(url.searchParams.get('workspaceScopeKey'), '__shared__');
        assert.equal(url.searchParams.get('workspaceId'), '');
        assert.equal(url.searchParams.get('branch'), 'main');
        await answerCurrentPlan(page, endpoint, {status:'FAST_FORWARD',repositoryId:'central-fork',workspaceId:null,branch:'main',
            localCommit:'1'.repeat(40),mergeBase:'1'.repeat(40),upstream:{sourceRepositoryId:'central',sourceBranch:'main',
                sourceCommit:'2'.repeat(40),trackedCommit:'2'.repeat(40)},commits:[]});
        await page.waitForFunction(() => document.querySelector('#workspaceRebasePlan').textContent.includes('FAST_FORWARD'));
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});


for (const outcome of ['applied', 'conflict']) test(`Reviewed central rebase shows its ${outcome} receipt`, async () => {
    const page = await fixture();
    try {
        await page.evaluate(() => {
            document.body.insertAdjacentHTML('beforeend', '<button id="workspaceRebasePlanButton">Plan</button><div id="workspaceRebasePlan"></div>');
            window.TaxonomyContextBar = {getCurrentContext: () => ({mode:'EDITABLE', branch:'draft/scope'})};
        });
        const repository = 'central-fork';
        await answer(page, '/api/workspace/sync-state', initial);
        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, {syncStatus:'DIVERGED', repositoryId:repository, workspaceId:null, branch:'draft/scope'});
        await page.locator('#workspaceRebasePlanButton').click();
        const endpoint = '/api/repositories/central-fork/upstream';
        const localCommit = '3'.repeat(40), trackedCommit = '4'.repeat(40), firstCommit = '2'.repeat(40);
        const tag = 'W/"' + 'e'.repeat(64) + '"';
        await answerCurrentPlan(page, endpoint + '/rebase-plan', {status:'REPLAY', repositoryId:repository,
            workspaceId:null, branch:'draft/scope', localCommit, mergeBase:'1'.repeat(40),
            upstream:{sourceRepositoryId:'central', sourceBranch:'main', sourceCommit:trackedCommit, trackedCommit},
            commits:[{id:firstCommit, subject:'First'},{id:localCommit, subject:'Second'}]}, tag);
        const apply = page.locator('#workspaceApplyRebase');
        await apply.waitFor();
        const writes = () => page.evaluate(path => statusReads.filter(read => read.method === 'POST' && read.url.startsWith(path))
            .map(read => ({url:read.url, body:JSON.parse(read.body)})), endpoint + '/rebase?');
        assert.deepEqual(await writes(), []);
        await apply.click();
        await page.waitForFunction(path => statusReads.some(read => read.method === 'POST' && read.url.startsWith(path)), endpoint + '/rebase?');
        assert.deepEqual(await writes(), [{url:endpoint + '/rebase?branch=draft%2Fscope',
            body:{expectedRefTag:tag, expectedLocalCommit:localCommit, expectedTrackedCommit:trackedCommit,
                orderedCommits:[firstCommit, localCommit]}}]);
        const newHead = '6'.repeat(40);
        await answer(page, endpoint + '/rebase', outcome === 'conflict' ? {originalHead:localCommit,
            newHead:localCommit, conflictCommit:firstCommit, conflictingPaths:['model/<branch>.taxdsl'], rewritten:[]}
            : {originalHead:localCommit, newHead, conflictCommit:null, conflictingPaths:[],
                rewritten:[{originalCommit:localCommit, replacementCommit:newHead}]}, 200, {method:'POST'});
        const panel = page.locator('#workspaceRebasePlan');
        await apply.waitFor({state:'detached'});
        assert.ok((await panel.locator('p').innerText()).startsWith('workspace.rebase.' + outcome));
        assert.equal(await panel.locator('li').innerText(), outcome === 'conflict'
            ? 'model/<branch>.taxdsl' : localCommit + ' → ' + newHead);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('A delayed rebase plan is rejected after the same branch advances, and a fresh plan is retained', async () => {
    const page = await fixture();
    try {
        const template = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
        const markup = template.match(/<button id="workspaceRebasePlanButton"[^>]*>[\s\S]*?<\/button>\s*<div id="workspaceRebasePlan"[^>]*><\/div>/)?.[0];
        assert.ok(markup);
        await page.evaluate(html => document.body.insertAdjacentHTML('beforeend', html), markup);
        const state = {syncStatus:'DIVERGED', repositoryId:'origin', workspaceId:'work', branch:'feature',
            unpublishedCommitCount:1, lastSyncedCommitId:'1'.repeat(40)};
        const endpoint = '/api/workspace/upstream/rebase-plan';
        const plan = {status:'FAST_FORWARD', repositoryId:'origin', workspaceId:'work', branch:'feature',
            localCommit:'2'.repeat(40), mergeBase:'2'.repeat(40),
            upstream:{sourceRepositoryId:'origin', sourceBranch:'main', sourceCommit:'3'.repeat(40),
                trackedCommit:'3'.repeat(40)}, commits:[]};
        const panel = page.locator('#workspaceRebasePlan');
        await syncResponse(page, state);
        await page.locator('#workspaceRebasePlanButton').click();
        await page.waitForFunction(path => window.statusReads.some(read => read.pending && read.url.startsWith(path)), endpoint);

        // A fetch or a competing commit advances the same selected branch while the old GET is pending.
        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        const newer = {...state, unpublishedCommitCount:2, lastSyncedCommitId:'2'.repeat(40)};
        await syncResponse(page, newer);
        assert.equal(await panel.innerText(), '');
        await answer(page, endpoint, {...plan, localCommit:'1'.repeat(40)});
        await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 0)));
        assert.equal(await panel.innerText(), '', 'A late plan may not repaint the newer observation');

        await page.locator('#workspaceRebasePlanButton').click();
        await page.waitForFunction(path => window.statusReads.some(read => read.pending && read.url.startsWith(path)), endpoint);
        await answerCurrentPlan(page, endpoint, plan);
        await page.waitForFunction(() => document.querySelector('#workspaceRebasePlan')?.textContent.includes('FAST_FORWARD'));
        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, {...newer});
        await answerPlanHead(page, endpoint, 'W/"current-plan"');
        assert.match(await panel.innerText(), /FAST_FORWARD/, 'An identical refresh retains the valid plan');

        // A new commit with the same branch and count also invalidates an already displayed plan.
        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, {...newer, lastSyncedCommitId:'4'.repeat(40)});
        assert.equal(await panel.innerText(), '');
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});

test('Live ref changes invalidate a pending or visible plan with an unchanged sync row', async () => {
    const page = await fixture();
    try {
        const template = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
        const markup = template.match(/<button id="workspaceRebasePlanButton"[^>]*>[\s\S]*?<\/button>\s*<div id="workspaceRebasePlan"[^>]*><\/div>/)[0];
        await page.evaluate(html => document.body.insertAdjacentHTML('beforeend', html), markup);
        const state = {syncStatus:'DIVERGED', repositoryId:'origin', workspaceId:'work', branch:'feature',
            unpublishedCommitCount:1, lastSyncedCommitId:'1'.repeat(40)};
        const endpoint = '/api/workspace/upstream/rebase-plan';
        const plan = {status:'FAST_FORWARD', repositoryId:'origin', workspaceId:'work', branch:'feature',
            localCommit:'2'.repeat(40), mergeBase:'2'.repeat(40),
            upstream:{sourceRepositoryId:'origin', sourceBranch:'main', sourceCommit:'3'.repeat(40),
                trackedCommit:'3'.repeat(40)}, commits:[]};
        const button = page.locator('#workspaceRebasePlanButton'), panel = page.locator('#workspaceRebasePlan');
        await syncResponse(page, state);

        await button.click();
        await answer(page, endpoint, plan, 200, {method:'GET', headers:{ETag:'W/"old-local"'}});
        await answerPlanHead(page, endpoint, 'W/"new-local"');
        await page.waitForFunction(() => document.querySelector('#workspaceRebasePlan').textContent === '');

        await button.click();
        await answerCurrentPlan(page, endpoint, {...plan, localCommit:'4'.repeat(40)}, 'W/"new-local"');
        await page.waitForFunction(() => document.querySelector('#workspaceRebasePlan').textContent.includes('4'.repeat(40)));

        await page.evaluate(() => TaxonomyWorkspaceSync.refresh());
        await syncResponse(page, {...state});
        await answerPlanHead(page, endpoint, 'W/"new-tracking"');
        await page.waitForFunction(() => document.querySelector('#workspaceRebasePlan').textContent === '');

        const planReads = await page.evaluate(path => statusReads.filter(read => read.url.startsWith(path))
            .map(read => new URL(read.url, 'http://status.test').search), endpoint);
        assert.equal(new Set(planReads).size, 1, 'GET and HEAD use the same exact pinned selection');
        assert.deepEqual(await page.evaluate(() => TaxonomyWorkspaceSync.getSyncState()), state);
        assert.deepEqual(page.errors, []);
    } finally { await page.close(); }
});
