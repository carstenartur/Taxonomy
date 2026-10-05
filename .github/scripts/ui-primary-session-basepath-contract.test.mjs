import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL(
  './ui-primary-session-workflow.mjs', import.meta.url), 'utf8');
const preferencesSource = await readFile(new URL(
  './ui-primary-preferences-workflow.mjs', import.meta.url), 'utf8');
const i18nSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js', import.meta.url), 'utf8');

// Run the actual workflow helper without importing unrelated Playwright fixtures.
const workflowContext = vm.createContext({ URL });
vm.runInContext(preferencesSource.replace(/^import .*;\n/m, '').replace(/^export /mg, ''),
  workflowContext);
const selectAnalysisWorkspace = vm.runInContext('selectAnalysisWorkspace', workflowContext);

test('authoritative reset draft verification resolves the application base path', () => {
  assert.match(source,
    /const path = `\/api\/analysis-drafts\/\$\{encodeURIComponent\(state\.workspaceId\)\}`/);
  assert.match(source,
    /TaxonomyI18n\?\.resolveUrl\?\.\(path\) \|\| path/);
  assert.doesNotMatch(source,
    /fetch\(\s*`\/api\/analysis-drafts\//);
});

for (const { basePath, expectedEndpoint } of [
  { basePath: '', expectedEndpoint: 'https://qa.example/api/workspace/fixture-workspace/switch' },
  { basePath: '/taxonomy', expectedEndpoint: 'https://qa.example/taxonomy/api/workspace/fixture-workspace/switch' }
]) {
  test(`Preferences switch at ${basePath || 'root'} waits for its ready workspace while unrelated network activity continues`, async () => {
    const calls = [];
    const browserFetches = [];
    let selectedWorkspace = 'original-workspace';
    let sessionWorkspace = selectedWorkspace;
    let sessionReady = true;
    const pageUrl = `https://qa.example${basePath}/?lang=en#preferences`;
    const browser = vm.createContext({
      URL, console, CustomEvent: class {},
      location: { href: pageUrl },
      document: {
        currentScript: { src: `https://qa.example${basePath}/js/taxonomy-i18n.js` },
        cookie: '', documentElement: { lang: 'en' }, dispatchEvent() {},
        querySelector(selector) {
          if (selector === 'meta[name="_csrf"]') return { content: 'fixture-csrf-token' };
          if (selector === 'meta[name="_csrf_header"]') return { content: 'X-Fixture-CSRF' };
          return null;
        }
      },
      localStorage: { getItem() { return null; } },
      fetch: async url => {
        browserFetches.push(url);
        return { ok: true, json: async () => ({}) };
      },
      TaxonomyAnalysisSession: { state: () => ({ ready: sessionReady, workspaceId: sessionWorkspace }) },
      __TaxonomyAnalysisSessionContext: {
        rememberWorkspaceId(id) { calls.push(['remember', id]); }
      }
    });
    browser.window = browser;
    vm.runInContext(i18nSource, browser);
    await browser.TaxonomyI18n.ready();
    function evaluate(callback, argument) {
      browser.argument = argument;
      return vm.runInContext(`(${callback.toString()})(argument)`, browser);
    }
    const page = {
      url: () => pageUrl,
      evaluate: async (callback, argument) => evaluate(callback, argument),
      request: { post: async (url, options) => {
        calls.push(['post', url, JSON.parse(JSON.stringify(options))]);
        selectedWorkspace = 'fixture-workspace';
        return { status: () => 200 };
      } },
      reload: async options => {
        // A completed document can retain background network activity. Treat
        // networkidle as unavailable without a real browser timeout in this test.
        if (options.waitUntil === 'networkidle') throw new Error('Background network activity never becomes idle');
        calls.push(['reload', JSON.parse(JSON.stringify(options))]);
        sessionWorkspace = selectedWorkspace;
        sessionReady = false;
      },
      locator: selector => ({ waitFor: async options => {
        assert.equal(selector, '#mainContent');
        assert.deepEqual(JSON.parse(JSON.stringify(options)), { state: 'visible', timeout: 60_000 });
        calls.push(['main-visible']);
      } }),
      waitForFunction: async (callback, argument, options) => {
        assert.equal(options.timeout, 30_000);
        assert.equal(evaluate(callback, argument), false, 'An unresolved session must remain blocked');
        sessionReady = true;
        sessionWorkspace = 'original-workspace';
        assert.equal(evaluate(callback, argument), false, 'A ready session in another workspace must remain blocked');
        sessionWorkspace = selectedWorkspace;
        assert.equal(evaluate(callback, argument), true, 'Reload must expose the selected ready session');
        calls.push(['session-ready', argument]);
      }
    };

    await selectAnalysisWorkspace(page, 'fixture-workspace', assert);

    assert.deepEqual(calls, [
      ['post', expectedEndpoint, { headers: {
        'X-Taxonomy-Workspace-Id': 'original-workspace', 'X-Fixture-CSRF': 'fixture-csrf-token'
      } }],
      ['remember', 'fixture-workspace'],
      ['reload', { waitUntil: 'domcontentloaded' }],
      ['main-visible'],
      ['session-ready', 'fixture-workspace']
    ]);
    assert.deepEqual(browserFetches, [`${basePath}/api/i18n/en`],
      'The switch must use the native request context, avoiding automatic browser-fetch reloads');
  });
}

for (const { basePath, provisionStatus } of [
  { basePath: '', provisionStatus: 'READY' },
  { basePath: '/taxonomy', provisionStatus: 'READY' },
  { basePath: '/taxonomy', provisionStatus: 'PROVISIONING' },
  { basePath: '/taxonomy', provisionStatus: undefined }
]) {
  test(`Preferences fixture at ${basePath || 'root'} handles provisioning response ${provisionStatus || 'missing status'} before its first reload`, async () => {
    const calls = [];
    let selectedWorkspace = 'original-workspace';
    let fixtureReady = false;
    const pageUrl = `https://qa.example${basePath}/#versions`;
    const browser = vm.createContext({
      URL, console, CustomEvent: class {}, location: { href: pageUrl },
      document: {
        currentScript: { src: `https://qa.example${basePath}/js/taxonomy-i18n.js` },
        cookie: '', documentElement: { lang: 'en' }, dispatchEvent() {},
        querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-Fixture-CSRF' : 'fixture-csrf-token' })
      },
      localStorage: { getItem() { return null; } },
      fetch: async () => ({ ok: true, json: async () => ({}) }),
      TaxonomyAnalysisSession: { state: () => ({
        workspaceId: selectedWorkspace, ready: selectedWorkspace === 'original-workspace' || fixtureReady
      }) },
      __TaxonomyAnalysisSessionContext: { rememberWorkspaceId() {} },
      TaxonomyState: {}, TaxonomyScoring: { renderArchitectureView() {}, renderSuggestedRelations() {} }
    });
    browser.window = browser;
    vm.runInContext(i18nSource, browser);
    await browser.TaxonomyI18n.ready();
    const context = vm.createContext({ URL });
    vm.runInContext(preferencesSource.replace(/^import .*;\n/m, '').replace(/^export /mg, ''), context);
    // Boundaries unrelated to fixture lifecycle retain their own scenario coverage.
    Object.assign(context, {
      csrfJson: async (_page, endpoint) => {
        calls.push(endpoint);
        if (endpoint === '/api/workspace/create') return { status: 200, json: { workspaceId: 'fixture-workspace' } };
        if (endpoint === '/api/workspace/fixture-workspace') return { status: 200, json: { success: true } };
        throw new Error(`Unexpected browser-fetch lifecycle request: ${endpoint}`);
      },
      workingStateExpression: () => () => 'original-state',
      restoreWorkingStateExpression: () => () => {},
      saveDraftNow: async () => true,
      createPersistedHypotheses: async () => { calls.push('hypotheses'); return []; },
      runPreferencesPreservationWorkflow: async () => { calls.push('preservation'); }
    });
    const page = {
      url: () => pageUrl,
      on() {}, off() {},
      evaluate: async (callback, argument) => {
        browser.argument = argument;
        return vm.runInContext(`(${callback.toString()})(argument)`, browser);
      },
      request: { post: async (url, {headers}) => {
        const path = new URL(url).pathname;
        calls.push(path);
        assert.equal(headers['X-Fixture-CSRF'], 'fixture-csrf-token');
        if (path === `${basePath}/api/workspace/provision`) {
          assert.equal(headers['X-Taxonomy-Workspace-Id'], 'fixture-workspace',
            'Provisioning must pin the fixture while the browser still belongs to the original workspace');
          assert.equal(selectedWorkspace, 'original-workspace');
          fixtureReady = provisionStatus === 'READY';
          return { status: () => 200, json: async () => ({ status: provisionStatus }) };
        }
        assert.equal(headers['X-Taxonomy-Workspace-Id'], selectedWorkspace);
        const match = path.match(new RegExp(`^${basePath}/api/workspace/(fixture-workspace|original-workspace)/switch$`));
        assert.ok(match, `Unexpected native lifecycle endpoint: ${path}`);
        selectedWorkspace = match[1];
        return { status: () => 200 };
      } },
      reload: async () => {
        calls.push(`reload:${selectedWorkspace}`);
        if (selectedWorkspace === 'fixture-workspace' && !fixtureReady) {
          throw new Error('Pinned fixture draft GET returns 409 until workspace READY');
        }
      },
      locator: () => ({ waitFor: async () => {} }),
      waitForFunction: async (callback, argument) => {
        assert.equal(await page.evaluate(callback, argument), true, 'Selected workspace must be ready');
      }
    };
    const workflow = { page, evidence: { assert } };
    const run = vm.runInContext('runPreferencesWorkflow', context);
    if (provisionStatus === 'READY') {
      await run(workflow);
      assert.deepEqual(calls, [
        '/api/workspace/create', `${basePath}/api/workspace/provision`,
        `${basePath}/api/workspace/fixture-workspace/switch`, 'reload:fixture-workspace',
        'hypotheses', 'preservation', `${basePath}/api/workspace/original-workspace/switch`,
        'reload:original-workspace', '/api/workspace/fixture-workspace'
      ]);
    } else {
      await assert.rejects(run(workflow), /Preferences fixture workspace provisioning failed/);
      assert.deepEqual(calls, [
        '/api/workspace/create', `${basePath}/api/workspace/provision`,
        `${basePath}/api/workspace/original-workspace/switch`, 'reload:original-workspace',
        '/api/workspace/fixture-workspace'
      ]);
    }
  });
}
