import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { writeFile } from 'node:fs/promises';
import { chromium, expect } from '@playwright/test';
import { navigateToPage } from './ui-role-fixtures.mjs';

const [baseUrl, observationFile, evidenceFile] = process.argv.slice(2);
const observed = JSON.parse(readFileSync(observationFile, 'utf8'));
assert.equal(observed.transport, 'artemis');
assert.equal(observed.status, 'COMPLETED');
const operation = observed.operationId;
const originalWorkspace = observed.scope.workspaceId;
assert(baseUrl && originalWorkspace && operation && evidenceFile);
const password = readFileSync(0, 'utf8').trim();
assert(password);
const browser = await chromium.launch();
const page = await browser.newPage();
page.setDefaultTimeout(45_000);
const apiPath = '/api/analysis-runs/' + encodeURIComponent(operation);
const release = Promise.withResolvers();
let analysisPosts = 0;
let analysisStreams = 0;
page.on('request', request => {
    const pathname = new URL(request.url()).pathname;
    if (request.method() === 'POST' && pathname === '/api/analyze') analysisPosts++;
    if (request.method() === 'GET' && pathname === '/api/analyze-stream') analysisStreams++;
});

async function ready() {
    await page.waitForFunction(() => window.TaxonomyAnalysisSession?.state().ready === true);
    await page.evaluate(() => window.TaxonomyI18n.ready());
    const dismiss = page.locator('#onboardingDismiss');
    if (await dismiss.isVisible()) await dismiss.press('Enter');
}

async function selectWorkspace(id) {
    await navigateToPage(page, 'versions');
    const manager = page.locator('#workspaceManagerPanel');
    // Opening the panel loads the list. A second concurrent refresh replaces
    // the focused button and can discard its native Enter activation.
    if (await manager.getAttribute('open') === null) await manager.locator('summary').press('Enter');
    const activate = manager.locator('.ws-switch-btn').and(page.locator('[data-ws-id="' + id + '"]'));
    await expect(activate).toBeVisible({timeout:45_000});
    const switched = page.waitForResponse(response => new URL(response.url()).pathname === '/api/workspace/' + id + '/switch'
        && response.request().method() === 'POST');
    await Promise.all([page.waitForEvent('domcontentloaded'), activate.press('Enter')]);
    assert.equal((await switched).status(), 200);
    await ready();
    const current = await page.evaluate(() => window.TaxonomyWorkspaceApi.current());
    assert.equal(current.workspaceId, id);
    return current;
}

async function savedRunButton() {
    await navigateToPage(page, 'analyze');
    await page.evaluate(() => window.TaxonomyAnalysisProgress.refreshRecent(true));
    const control = page.locator('#analysisRecentRuns > div').filter({hasText:operation}).getByRole('button');
    await expect(control).toBeVisible({timeout:45_000});
    return control;
}

try {
    await page.goto(baseUrl + '/login?lang=en');
    await page.locator('input[name="username"]').fill('admin');
    await page.locator('input[name="password"]').fill(password);
    await Promise.all([page.waitForURL(url => !url.pathname.endsWith('/login')), page.keyboard.press('Enter')]);
    await ready();
    const first = await selectWorkspace(originalWorkspace);
    const second = await page.evaluate(async operationId => {
        const post = (path, body) => window.TaxonomyApiClient.requestJson(path, {
            method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)
        });
        const repo = await post('/api/repositories', {displayName:'Recovery isolation',
            slug:'recovery-' + operationId, visibility:'PRIVATE', defaultBranch:'draft'});
        return post('/api/repositories/' + repo.repositoryId + '/workspaces', {
            displayName:'Separate recovery workspace', sourceBranch:'draft'
        });
    }, operation);
    assert.notEqual(first.workspaceId, second.workspaceId);
    assert(second.sourceRepositoryId);
    assert.notEqual(observed.scope.repositoryId, second.sourceRepositoryId);

    const received = Promise.withResolvers();
    const delivered = Promise.withResolvers();
    const pattern = url => url.pathname === apiPath + '/request';
    await page.route(pattern, async route => {
        try {
            const response = await route.fetch();
            assert.equal(response.status(), 200);
            const input = await response.json();
            assert.equal(input.operationId, operation);
            for (const field of ['workspaceId', 'repositoryId', 'branch', 'sourceCommit']) {
                assert.equal(input.scope[field], observed.scope[field], field);
            }
            assert.match(input.scope.sourceCommit, /^[0-9a-f]{40}$/);
            assert(input.businessText);
            received.resolve(input);
            await release.promise;
            // The real response may already have lost its browser request at navigation.
            try { await route.fulfill({response}); } catch (error) {
                if (!/aborted|intercepted|closed|cancel/i.test(error.message)) throw error;
            }
            delivered.resolve();
        } catch (error) {
            received.reject(error);
            delivered.resolve(error);
        }
    }, {times:1});
    const firstButton = await savedRunButton();
    const [input] = await Promise.all([
        received.promise,
        page.waitForRequest(request => pattern(new URL(request.url()))),
        firstButton.press('Enter')
    ]);
    // The new document may start and complete the recent-runs GET during the
    // workspace switch. Its explicit refresh can join that same physical read.
    const [recent] = await Promise.all([
        page.waitForResponse(response => {
            const url = new URL(response.url());
            return url.pathname === '/api/analysis-runs' && url.searchParams.get('workspaceId') === second.workspaceId
                && response.request().method() === 'GET';
        }),
        (async () => {
            const confirmed = await selectWorkspace(second.workspaceId);
            assert.equal(confirmed.sourceRepositoryId, second.sourceRepositoryId);
            release.resolve();
            const deliveryError = await delivered.promise;
            if (deliveryError) throw deliveryError;
            await navigateToPage(page, 'analyze');
            await page.evaluate(() => window.TaxonomyAnalysisProgress.refreshRecent(true));
        })()
    ]);
    assert.equal(recent.status(), 200);
    assert.equal(await recent.request().headerValue('x-taxonomy-workspace-id'), second.workspaceId);
    const runs = await recent.json();
    assert(Array.isArray(runs));
    assert(!runs.some(run => run.operationId === operation));
    assert.equal(await page.locator('#analysisRecentRuns').getByText(operation, {exact:false}).count(), 0);
    assert.notEqual(await page.locator('#businessText').inputValue(), input.businessText);
    assert.notEqual(await page.evaluate(() => window.TaxonomyState.analysisOperationId), operation);

    const returned = await selectWorkspace(originalWorkspace);
    assert.equal(returned.sourceRepositoryId, first.sourceRepositoryId);
    const retry = await savedRunButton();
    const resultResponse = page.waitForResponse(response => new URL(response.url()).pathname === apiPath + '/result');
    await retry.press('Enter');
    const response = await resultResponse;
    assert.equal(response.status(), 200);
    assert.equal(new URL(response.url()).searchParams.get('workspaceId'), originalWorkspace);
    const result = await response.json();
    assert.equal(result.status, 'SUCCESS');
    assert(Object.keys(result.rawScores).length > 0);
    await expect(page.locator('#businessText')).toHaveValue(input.businessText);
    await page.waitForFunction(({operation, status}) => window.TaxonomyState.analysisOperationId === operation
        && window.TaxonomyState.lastAnalysisStatus === status, {operation,status:result.status});
    const scores = await page.evaluate(() => window.TaxonomyState.currentRawScores);
    assert.deepEqual(scores, result.rawScores);
    assert.equal(analysisPosts, 0, 'Recovery submitted a new analysis');
    assert.equal(analysisStreams, 0, 'Recovery started a new analysis stream');
    await writeFile(evidenceFile, JSON.stringify({browser:browser.version(), operation, originalWorkspace,
        originalRepository:input.scope.repositoryId, secondWorkspace:second.workspaceId, secondRepository:second.sourceRepositoryId,
        sourceCommit:input.scope.sourceCommit, branch:input.scope.branch, recoveredStatus:result.status,
        oldRunAbsentInSecondWorkspace:true, originalInputRestored:true, restoredRawScores:Object.keys(scores).length,
        analysisPosts, analysisStreams, analysisSubmissions:analysisPosts + analysisStreams}, null, 2) + '\n');
} finally {
    release.resolve();
    await browser.close();
}
