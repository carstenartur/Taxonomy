// Native-browser acceptance of saved-run controls. HTTP/SSE responses are authored
// fixtures; API routing, progress observation and result hydration are production code.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const { chromium } = await import(process.env.TAXONOMY_PLAYWRIGHT_MODULE || '@playwright/test');
const repository = new URL('../../', import.meta.url);
const resources = new URL('taxonomy-app/src/main/resources/static/js/', repository);
const output = process.env.TAXONOMY_QA_OUTPUT || fileURLToPath(new URL('target/analysis-cluster-recovery-browser/', repository));
const scripts = ['api/taxonomy-api-client.js', 'api/analysis-session-api.js',
    'core/taxonomy-analysis-session-api-routing.js', 'core/taxonomy-analysis-progress.js', 'core/taxonomy-scoring.js'];
const source = new Map(await Promise.all(scripts.map(async name => [name, await readFile(new URL(name, resources), 'utf8')])));
const operationId = 'cb2a3d71-e849-4a50-9855-1f9cb8f81402';
const authority = { workspaceId: 'workspace-a', repositoryId: 'repo-a', branch: 'draft', sourceCommit: 'commit-a' };
const originalInput = { operationId, businessText: 'Original durable requirement', provider: 'MOCK',
    analysisScope: { taxonomyRoots: ['CP'], mode: 'TAXONOMIES_ONLY' }, scope: authority };
const result = { status: 'SUCCESS', scores: { CP: 80 }, rawScores: { CP: 80 }, effectiveScores: { CP: 80 },
    reasons: { CP: 'Persisted assessment' }, warnings: [], tree: [{ code: 'CP', children: [] }],
    analysisScope: originalInput.analysisScope, provider: 'MOCK', analysisDurationMillis: 1400 };
function snapshot(status = 'RUNNING', sequence = 2, score = 0) {
    return { operationId, sequence, transport: 'artemis', scope: authority, status,
        phase: status === 'COMPLETED' ? 'FINISHED' : 'SCORING', rawScores: score ? { CP: score } : {}, calls: [],
        startedAt: 1000, lastActivityAt: 2000, serverTime: 2400, elapsedMillis: 1400,
        evaluatedNodes: score ? 1 : 0, queueWaitMillis: 0, executionMillis: 1400,
        databaseStorage: 'EXTERNAL_OR_UNKNOWN', indexStorage: 'EXTERNAL_OR_UNKNOWN',
        cluster: { completedRoots: status === 'COMPLETED' ? 1 : 0, totalRoots: 1,
            tasks: [{ taskType: 'SUBTAXONOMY_ANALYSIS', root: { code: 'CP' }, queued: 0,
                running: status === 'RUNNING' ? 1 : 0, completed: status === 'COMPLETED' ? 1 : 0, failed: 0 }] } };
}
function fixturePage() {
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Cluster recovery acceptance fixture</title>
    <style>body{font:16px system-ui;margin:24px;max-width:1000px}textarea{display:block;width:95%;height:70px}button{padding:8px;margin:8px}.d-none,[hidden]{display:none!important}.alert{padding:12px;border:1px solid #789;margin:12px 0}pre{white-space:pre-wrap}</style></head>
    <body><h1>Saved analysis recovery</h1><p>Loopback HTTP/SSE fixture · production recovery scripts</p>
    <label>Requirement<textarea id="businessText">Unsaved current draft</textarea></label>
    <select id="providerSelect"><option>MOCK</option></select><input id="includeArchitectureView" type="checkbox">
    <button id="analyzeBtn">Analyze</button><div id="statusArea"></div><pre id="scoreOutput"></pre>
    <script>
    window.__pageErrors=[];window.__progressEvents=[];
    addEventListener('error',event=>window.__pageErrors.push(event.message));
    addEventListener('unhandledrejection',event=>window.__pageErrors.push(String(event.reason)));
    document.addEventListener('taxonomy:analysis-progress',event=>window.__progressEvents.push(event.detail));
    window.TaxonomyI18n={t:key=>key,getLocale:()=> 'en'};
    window.TaxonomyUtils={escapeHtml:value=>{const span=document.createElement('span');span.textContent=String(value??'');return span.innerHTML;}};
    window.TaxonomyRoleSurface={};window.TaxonomyUiSemantics={};
    window.TaxonomyState={taxonomyData:[{code:'CP',children:[]}],currentScores:{},currentRawScores:{},currentScoreDetails:{},
        currentReasons:{},currentView:'tree',currentDiscrepancies:[],currentProductCoverageGaps:[]};
    window.__TaxonomyAnalysisSessionContext={runtime:{workspaceId:'workspace-a',analysisGeneration:1,invalidating:false},clearDerivedUi(){}};
    window.TaxonomyAnalysisSession={state:()=>({workspaceId:window.__TaxonomyAnalysisSessionContext.runtime.workspaceId,
        ready:!window.__TaxonomyAnalysisSessionContext.runtime.invalidating})};
    window.TaxonomyAnalysisSessionReady=Promise.resolve(true);
    window.TaxonomyBrowse={clearStatus(){},ensureNodeRendered(){},
        showStatus:(_,message)=>{document.getElementById('statusArea').textContent=message;},
        renderView:(_,scores)=>{document.getElementById('scoreOutput').textContent=JSON.stringify(scores);}};
    </script>${scripts.map(name => `<script src="/static/js/${name}"></script>`).join('')}</body></html>`;
}
let current;
function json(response, body, status = 200) {
    response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(body));
}
function emit(response, value, name = 'progress') {
    response.write(`id: ${value.sequence}\nevent: ${name}\ndata: ${JSON.stringify(value)}\n\n`);
}
const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (url.pathname === '/') {
        response.writeHead(200, { 'Content-Type': 'text/html', 'Cache-Control': 'no-store' });
        response.end(fixturePage()); return;
    }
    if (url.pathname.startsWith('/static/js/')) {
        const text = source.get(url.pathname.slice('/static/js/'.length));
        response.writeHead(text ? 200 : 404, { 'Content-Type': 'text/javascript' });
        response.end(text || ''); return;
    }
    if (!url.pathname.startsWith('/api/')) { response.writeHead(404); response.end(); return; }
    const state = current;
    state.requests.push({ method: request.method, path: url.pathname, workspace: url.searchParams.get('workspaceId'),
        workspaceHeader: request.headers['x-taxonomy-workspace-id'], afterSequence: url.searchParams.get('afterSequence'),
        lastEventId: request.headers['last-event-id'] });
    if (request.method !== 'GET') { json(response, { detail: 'Unexpected mutation in recovery acceptance' }, 405); return; }
    if (url.pathname === '/api/analysis-runs') { json(response, [state.snapshot]); return; }
    if (url.pathname.endsWith('/request')) {
        if (state.holdInput) state.inputResponse = response;
        else json(response, originalInput);
        return;
    }
    if (url.pathname.endsWith('/result')) {
        if (state.holdResult) state.resultResponse = response;
        else if (state.failResultOnce) { state.failResultOnce = false; json(response, { detail: 'Fixture transient failure' }, 503); }
        else json(response, result);
        return;
    }
    if (url.pathname.endsWith('/events')) {
        response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
        response.write('retry: 50\n\n');
        state.clients.add(response);
        response.on('close', () => state.clients.delete(response));
        const after = Math.max(Number(url.searchParams.get('afterSequence')), Number(request.headers['last-event-id'] || 0));
        if (state.snapshot.sequence > after) emit(response, state.snapshot, 'snapshot');
        return;
    }
    if (url.pathname === '/api/analysis-runs/' + operationId) { json(response, state.snapshot); return; }
    json(response, { detail: 'Unknown fixture route' }, 404);
});

await mkdir(output, { recursive: true });
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'],
    ...(process.env.TAXONOMY_CHROME ? { executablePath: process.env.TAXONOMY_CHROME } : {}) });
const evidence = { sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8' }).trim(),
    capturedAt: new Date().toISOString(), browser: browser.version(), kind: 'production scripts with loopback HTTP and native EventSource fixtures',
    fullApplication: false, springAuthorization: false, artemisBroker: false, providerCalls: false,
    captures: ['saved-runs.png', 'recovered-complete.png', 'recovered-live.png'],
    sources: Object.fromEntries([...source].map(([name, content]) => [name, createHash('sha256').update(content).digest('hex')])), tests: [] };
async function until(predicate, description) {
    const deadline = Date.now() + 5000;
    while (!predicate()) { assert(Date.now() < deadline, description); await delay(10); }
}
async function settled(page) {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}
function publish(state, value, persist = true) {
    if (persist) state.snapshot = value;
    for (const response of state.clients) emit(response, value);
}
async function switchWorkspace(page) {
    await page.evaluate(() => {
        const runtime = window.__TaxonomyAnalysisSessionContext.runtime;
        runtime.workspaceId = 'workspace-b'; runtime.analysisGeneration++;
        document.getElementById('businessText').value = 'New workspace draft';
        window.TaxonomyState.currentRawScores = { fresh: 1 };
        document.getElementById('scoreOutput').textContent = 'New workspace result';
        document.dispatchEvent(new CustomEvent('taxonomy:analysis-invalidated'));
    });
}
async function scenario(name, settings, run) {
    const state = current = { snapshot: snapshot(), clients: new Set(), requests: [], ...settings };
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    context.setDefaultTimeout(5000);
    const page = await context.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    try {
        await page.goto(base);
        await page.locator('#analysisRecentRuns').waitFor();
        await run(page, state);
        assert.deepEqual(await page.evaluate(() => window.__pageErrors), [], 'Uncaught browser exceptions');
        assert.deepEqual(errors, [], 'Browser page errors');
        assert(state.requests.every(request => request.method === 'GET'), 'Recovery must not submit work or cancel another run');
        assert(state.requests.every(request => request.workspace === 'workspace-a'), 'Every recovery read retains the original workspace');
        assert(state.requests.filter(request => !request.path.endsWith('/events')).every(request =>
            request.workspaceHeader === 'workspace-a'), 'JSON reads carry the matching workspace header');
        evidence.tests.push({ name, result: 'PASS', requests: state.requests });
        console.log('PASS', name);
    } catch (error) {
        evidence.tests.push({ name, result: 'FAIL', error: error.stack, requests: state.requests, errors });
        await page.screenshot({ path: `${output}/failure.png` }).catch(() => {});
        throw error;
    } finally {
        await context.close();
        for (const client of state.clients) client.end();
        state.inputResponse?.end(); state.resultResponse?.end();
    }
}
try {
    await scenario('Reload discovers completed runs and explicit retrieval restores the original requirement',
        { snapshot: snapshot('COMPLETED') }, async (page, state) => {
            assert.equal(await page.locator('#businessText').inputValue(), 'Unsaved current draft');
            await page.reload();
            await page.getByRole('button', { name: 'Retrieve result', exact: true }).waitFor();
            await page.screenshot({ path: `${output}/saved-runs.png` });
            await page.getByRole('button', { name: 'Retrieve result', exact: true }).click();
            await page.waitForFunction(() => window.TaxonomyState.lastAnalysisStatus === 'SUCCESS');
            assert.equal(await page.locator('#businessText').inputValue(), originalInput.businessText);
            assert.equal(await page.evaluate(() => window.TaxonomyState.lastAnalyzedText), originalInput.businessText);
            assert.match(await page.locator('#scoreOutput').textContent(), /"CP":80/);
            assert.equal(await page.locator('#analyzeBtn').isEnabled(), true);
            assert.equal(state.requests.filter(request => request.path === '/api/analysis-runs').length, 2);
            assert.equal(state.requests.filter(request => request.path.endsWith('/result')).length, 1);
            await page.screenshot({ path: `${output}/recovered-complete.png` });
        });
    await scenario('Live recovery rejects foreign events and reconnects through native Last-Event-ID without status polling',
        {}, async (page, state) => {
            await page.getByRole('button', { name: 'Resume observation', exact: true }).click();
            await until(() => state.clients.size === 1, 'Native SSE connection did not open');
            assert.equal(await page.locator('#businessText').inputValue(), originalInput.businessText);
            publish(state, snapshot('RUNNING', 3, 35));
            await page.waitForFunction(() => window.TaxonomyState.currentRawScores.CP === 35);
            const accepted = await page.evaluate(() => window.__progressEvents.length);
            publish(state, snapshot('RUNNING', 3, 99), false);
            publish(state, snapshot('RUNNING', 2, 99), false);
            publish(state, { ...snapshot('RUNNING', 4, 99), operationId: 'foreign-operation' }, false);
            publish(state, { ...snapshot('RUNNING', 4, 99), scope: { ...authority, sourceCommit: 'foreign-commit' } }, false);
            await delay(100); await settled(page);
            assert.equal(await page.evaluate(() => window.TaxonomyState.currentRawScores.CP), 35);
            assert.equal(await page.evaluate(() => window.__progressEvents.length), accepted);
            publish(state, snapshot('RUNNING', 5, 40));
            await page.waitForFunction(() => window.TaxonomyState.currentRawScores.CP === 40);
            state.snapshot = snapshot('RUNNING', 6, 50);
            for (const client of state.clients) client.end();
            await page.waitForFunction(() => window.TaxonomyState.currentRawScores.CP === 50);
            const streams = state.requests.filter(request => request.path.endsWith('/events'));
            assert.equal(streams.length, 2);
            assert.equal(streams[1].lastEventId, '5');
            assert.equal(streams[1].afterSequence, '2');
            // Span the local monitor's one-second polling interval while SSE is live.
            await delay(1150);
            assert.equal(state.requests.filter(request => request.path === '/api/analysis-runs/' + operationId).length, 1);
            await page.screenshot({ path: `${output}/recovered-live.png` });
            publish(state, snapshot('COMPLETED', 7, 80));
            await page.waitForFunction(() => window.TaxonomyState.lastAnalysisStatus === 'SUCCESS');
            assert.equal(await page.evaluate(() => window.TaxonomyState.currentReasons.CP), 'Persisted assessment');
            assert.equal(await page.locator('#analyzeBtn').isEnabled(), true);
            await until(() => state.clients.size === 0, 'Terminal monitor did not close the native SSE connection');
            assert.equal(state.requests.filter(request => request.path === '/api/analysis-runs/' + operationId).length, 1);
            assert.equal(state.requests.filter(request => request.path.endsWith('/result')).length, 1);
        });
    for (const change of ['workspace', 'source']) {
        await scenario(`Late original-input response cannot repaint after a ${change} change`,
            { holdInput: true }, async (page, state) => {
                await page.getByRole('button', { name: 'Resume observation', exact: true }).click();
                await until(() => state.inputResponse, 'Original input request did not arrive');
                if (change === 'workspace') await switchWorkspace(page);
                const response = page.waitForResponse(value => value.url().includes('/request?'));
                json(state.inputResponse, change === 'source'
                    ? { ...originalInput, scope: { ...authority, sourceCommit: 'new-source' } } : originalInput);
                await (await response).finished(); await settled(page);
                assert.equal(await page.locator('#businessText').inputValue(), change === 'workspace'
                    ? 'New workspace draft' : 'Unsaved current draft');
                assert.equal(await page.locator('#analysisLiveProgress').count(), 0);
                assert.equal(state.requests.filter(request => request.path.endsWith('/events') || request.path.endsWith('/result')).length, 0);
                if (change === 'workspace') assert.equal(await page.locator('#scoreOutput').textContent(), 'New workspace result');
            });
    }
    await scenario('Late terminal result cannot repaint a replacement workspace',
        { snapshot: snapshot('COMPLETED'), holdResult: true }, async (page, state) => {
            await page.getByRole('button', { name: 'Retrieve result', exact: true }).click();
            await until(() => state.resultResponse, 'Persisted result request did not arrive');
            await switchWorkspace(page);
            const response = page.waitForResponse(value => value.url().includes('/result?'));
            json(state.resultResponse, result);
            await (await response).finished(); await settled(page);
            assert.equal(await page.locator('#businessText').inputValue(), 'New workspace draft');
            assert.equal(await page.locator('#scoreOutput').textContent(), 'New workspace result');
            assert.deepEqual(await page.evaluate(() => window.TaxonomyState.currentRawScores), { fresh: 1 });
        });
    await scenario('A visible result retry repeats only the persisted-result read',
        { snapshot: snapshot('COMPLETED'), failResultOnce: true }, async (page, state) => {
            await page.getByRole('button', { name: 'Retrieve result', exact: true }).click();
            await page.getByRole('button', { name: 'Retry result', exact: true }).click();
            await page.waitForFunction(() => window.TaxonomyState.lastAnalysisStatus === 'SUCCESS');
            assert.match(await page.locator('#scoreOutput').textContent(), /"CP":80/);
            assert.equal(await page.locator('#analyzeBtn').isEnabled(), true);
            assert.equal(state.requests.filter(request => request.path.endsWith('/result')).length, 2);
            assert.equal(state.requests.filter(request => request.path === '/api/analysis-runs/' + operationId).length, 1);
        });
    evidence.result = 'PASS';
} catch (error) {
    evidence.result = 'FAIL'; evidence.failure = error.stack; console.error(error); process.exitCode = 1;
} finally {
    await writeFile(`${output}/result.json`, JSON.stringify(evidence, null, 2) + '\n');
    await browser.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
}
