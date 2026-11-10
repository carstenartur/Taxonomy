import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL(
  './ui-primary-session-workflow.mjs', import.meta.url), 'utf8');
const preferencesSource = await readFile(new URL(
  './ui-primary-preferences-workflow.mjs', import.meta.url), 'utf8');
const proposalSource = await readFile(new URL(
  './ui-primary-proposal-workflows.mjs', import.meta.url), 'utf8');
const i18nSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js', import.meta.url), 'utf8');

// Run the actual workflow helper without importing unrelated Playwright fixtures.
const workflowContext = vm.createContext({ URL });
vm.runInContext(preferencesSource.replace(/^import .*;\n/m, '').replace(/^export /mg, ''),
  workflowContext);
const selectAnalysisWorkspace = vm.runInContext('selectAnalysisWorkspace', workflowContext);

function primaryReloadHarness({ reset = false } = {}) {
  const calls = [];
  const empty = {
    ready: true, workspaceId: 'fixture-workspace', restoring: false,
    businessText: '', scores: null, architecture: null, legacyScores: null, describeCurrent: true
  };
  let state = { ...empty };
  let readyGateObserved = false;
  const browser = vm.createContext({
    window: {
      TaxonomyAnalysisSession: { state: () => state },
      TaxonomyState: {
        get currentScores() { return state.scores; },
        get currentArchView() { return state.architecture; }
      },
      get _taxonomyCurrentScores() { return state.legacyScores; },
      TaxonomyI18n: { ready: async () => calls.push('translations-ready') },
      TaxonomyRoleSurface: { ready: { then(resolve) { calls.push('roles-ready'); resolve(); } } }
    },
    document: {
      getElementById(id) {
        assert.equal(id, 'businessText');
        return { value: state.businessText };
      },
      querySelector(selector) {
        assert.equal(selector, '#taskStageDescribe[data-state="current"]');
        return state.describeCurrent ? {} : null;
      }
    }
  });
  function evaluate(callback, argument) {
    browser.argument = argument;
    return vm.runInContext(`(${callback.toString()})(argument)`, browser);
  }
  const page = {
    evaluate: async (callback, argument) => evaluate(callback, argument),
    reload: async options => {
      // Model a completed document whose allowed background polling never
      // supplies Playwright's separate network-idle navigation condition.
      assert.notEqual(options.waitUntil, 'networkidle',
        'A ready application must not wait for background polling to become idle');
      assert.equal(options.waitUntil, 'domcontentloaded');
      assert.equal(options.timeout ?? 30_000, 30_000);
      calls.push('document-reloaded');
      state = { ...empty, ready: false, workspaceId: 'loading-workspace', restoring: true };
    },
    locator: selector => ({
      waitFor: async options => {
        assert.equal(selector, reset ? '#analysisTaskProgress' : '#mainContent');
        assert.equal(options.state, 'visible');
        calls.push('required-dom-visible');
      },
      isVisible: async () => {
        assert.equal(selector, '#onboardingDismiss');
        return false;
      }
    }),
    waitForFunction: async (callback, argument, options) => {
      if (options.timeout === 20_000) {
        assert.equal(Boolean(evaluate(callback, argument)), true);
        return;
      }
      assert.equal(options.timeout, 30_000);
      const invalidStates = [
        ['unresolved session', { ready: false }],
        ['another ready workspace', { workspaceId: 'another-workspace' }]
      ];
      if (reset) invalidStates.push(
        ['restoring session', { restoring: true }],
        ['restored text', { businessText: 'previous analysis' }],
        ['restored scores', { scores: { BP: 91 } }],
        ['restored architecture', { architecture: { title: 'previous architecture' } }],
        ['restored legacy scores', { legacyScores: { BP: 91 } }],
        ['wrong task stage', { describeCurrent: false }]
      );
      for (const [label, invalid] of invalidStates) {
        state = { ...empty, ...invalid };
        assert.equal(Boolean(evaluate(callback, argument)), false,
          `Reload must remain blocked for ${label}`);
      }
      state = { ...empty };
      assert.equal(Boolean(evaluate(callback, argument)), true,
        'The original ready workspace must complete the reload gate');
      readyGateObserved = true;
      calls.push('original-session-ready');
    }
  };
  return { page, calls, readyGateObserved: () => readyGateObserved };
}

test('Primary reset reload waits for its empty ready workspace while background polling continues', async () => {
  const marker = "  passed('visible reset command clears browser and authoritative server draft');";
  const start = source.indexOf(marker);
  const end = source.indexOf("  await page.locator('.analysis-command-grid').scrollIntoViewIfNeeded();", start);
  assert.ok(start >= 0 && end > start, 'The real reset reload segment must exist');
  const harness = primaryReloadHarness({ reset: true });
  const context = vm.createContext({ page: harness.page });

  await vm.runInContext(`(async () => {${source.slice(start + marker.length, end)}})()`, context);

  assert.equal(harness.readyGateObserved(), true,
    'DOM visibility alone must not admit the reset workflow');
  assert.deepEqual(harness.calls, [
    'document-reloaded', 'translations-ready', 'required-dom-visible', 'original-session-ready'
  ]);
});

test('Primary proposal reload retains provisioning and role gates while background polling continues', async () => {
  const harness = primaryReloadHarness();
  const endpoints = [];
  const context = vm.createContext({
    csrfJson: async (_page, endpoint, options = {}) => {
      endpoints.push([endpoint, options.method || 'POST']);
      if (endpoint === '/api/taxonomy') return { status: 200 };
      if (endpoint === '/api/workspace/provision') return { status: 200, json: { status: 'READY' } };
      assert.equal(endpoint, '/api/architecture/relations/projection/readiness');
      return { status: 200, json: { readinessState: 'READY' } };
    }
  });
  vm.runInContext(proposalSource.replace(/^import .*;\n/m, '').replace(/^export /mg, ''), context);

  await vm.runInContext('ensureWorkspaceProjection', context)(harness.page, assert);

  assert.deepEqual(endpoints, [
    ['/api/taxonomy', 'GET'], ['/api/workspace/provision', 'POST'],
    ['/api/architecture/relations/projection/readiness', 'GET'],
    ['/api/architecture/relations/projection/readiness', 'GET']
  ]);
  assert.equal(harness.readyGateObserved(), true,
    'Visible version history and roles must not admit another or unresolved session');
  assert.deepEqual(harness.calls, [
    'document-reloaded', 'required-dom-visible', 'translations-ready',
    'original-session-ready', 'roles-ready'
  ]);
});

function preferencesNavigationHarness({ basePath = '', pendingRestore = false } = {}) {
  const calls = [];
  const workspaceId = 'fixture-workspace';
  let session = { workspaceId, ready: true, restoring: false };
  let readyObserved = false;
  let restoringObserved = false;
  let released = false;
  const browser = vm.createContext({
    window: {
      TaxonomyAnalysisSession: { state: () => session },
      TaxonomyI18n: { ready: async () => calls.push('translations-ready') },
      TaxonomyState: {
        lastAnalyzedText: 'QA preference preservation sentinel',
        lastAnalysisStatus: 'SUCCESS', lastAnalysisProvider: 'MOCK', currentArchView: { title: 'Retained' }
      }
    },
    document: { getElementById: id => id === 'pref-max-arch-nodes' ? { value: '50' }
      : id === 'prefResetBtn' ? { disabled: false } : null }
  });
  function evaluate(callback, argument) {
    browser.argument = argument;
    return vm.runInContext(`(${callback.toString()})(argument)`, browser);
  }
  function navigate(options) {
    assert.notEqual(options.waitUntil, 'networkidle',
      'Loaded Preferences must not wait for unrelated background traffic to become idle');
    assert.equal(options.waitUntil, 'domcontentloaded');
    assert.equal(options.timeout ?? 30_000, 30_000);
    calls.push('document-ready');
    session = { workspaceId, ready: false, restoring: true };
  }
  const page = {
    evaluate: async (callback, argument) => evaluate(callback, argument),
    goto: async (url, options) => {
      assert.equal(url, `https://qa.example${basePath}/#preferences`);
      navigate(options);
    },
    reload: async options => navigate(options),
    locator: selector => ({
      waitFor: async options => {
        assert.equal(options.state, 'visible');
        assert.equal(options.timeout, selector === '#mainContent' ? 60_000 : 20_000);
        assert.ok(['#mainContent', '#tab-preferences'].includes(selector));
        calls.push(`visible:${selector}`);
      },
      inputValue: async () => { assert.equal(selector, '#pref-max-arch-nodes'); return '50'; }
    }),
    waitForFunction: async (callback, argument, options) => {
      if (options.timeout === 20_000) {
        assert.equal(Boolean(evaluate(callback, argument)), true, 'The existing preference load gate still runs');
        calls.push('preferences-loaded');
        return;
      }
      assert.equal(options.timeout, 30_000);
      const awaitingRestore = pendingRestore && !released;
      const expected = { workspaceId, ready: !awaitingRestore, restoring: awaitingRestore };
      for (const [label, values] of [
        ['another workspace', { workspaceId: 'foreign-workspace' }],
        [awaitingRestore ? 'a draft that is not restoring' : 'an unready source/session',
          awaitingRestore ? { restoring: false } : { ready: false }]
      ]) {
        session = { ...expected, ...values };
        assert.equal(Boolean(evaluate(callback, argument)), false,
          `Preferences navigation must remain blocked for ${label}`);
      }
      session = expected;
      assert.equal(Boolean(evaluate(callback, argument)), true);
      if (awaitingRestore) restoringObserved = true;
      else readyObserved = true;
      calls.push(awaitingRestore ? 'same-workspace-restoring' : 'same-workspace-ready');
    }
  };
  const gate = {
    seen: Promise.resolve(),
    release() { released = true; calls.push('draft-released'); },
    async dispose() { calls.push('gate-disposed'); }
  };
  return { page, calls, gate, readyObserved: () => readyObserved, restoringObserved: () => restoringObserved };
}

for (const basePath of ['', '/taxonomy']) {
  test(`Preferences deep link at ${basePath || 'root'} waits for its original ready workspace without network idle`, async () => {
    const start = preferencesSource.indexOf('async function runPreferencesPreservationWorkflow(');
    const end = preferencesSource.indexOf('  const originalState =', start);
    assert.ok(start >= 0 && end > start, 'Run the actual direct-link prefix before any draft mutation');
    const harness = preferencesNavigationHarness({ basePath });
    const context = vm.createContext({});
    vm.runInContext(preferencesSource.replace(/^import .*;\n/m, '').replace(/^export /mg, ''), context);
    const run = vm.runInContext(`(${preferencesSource.slice(start, end)}\n})`, context);
    await run({ page: harness.page, baseUrl: `https://qa.example${basePath}`, evidence: {
      assert(condition, message) {
        assert.equal(harness.readyObserved(), true, 'The direct-link assertion requires the original ready workspace');
        assert(condition, message);
      },
      passed: value => harness.calls.push(value)
    } }, []);
    assert.deepEqual(harness.calls, [
      'document-ready', 'visible:#mainContent', 'translations-ready', 'same-workspace-ready',
      'visible:#tab-preferences', 'preferences-loaded', 'preferences direct-link load'
    ]);
  });
}

test('Preferences keep-local reload waits for the same ready workspace before comparing retained evidence', async () => {
  const marker = "    'Keeping newer input lost persisted score or manual-decision authority');";
  const start = preferencesSource.indexOf(marker) + marker.length;
  const end = preferencesSource.indexOf("    await page.locator('#businessText').fill(JSON.parse(before).businessText);", start);
  assert.ok(start >= marker.length && end > start, 'Run the actual persisted keep-local reload section');
  const harness = preferencesNavigationHarness();
  const context = vm.createContext({ page: harness.page, edited: 'retained-local-evidence',
    workingStateExpression: () => () => 'retained-local-evidence',
    assert(condition, message) {
      assert.equal(harness.readyObserved(), true, 'Retained evidence is compared only in the original ready workspace');
      assert(condition, message);
    }
  });
  await vm.runInContext(`(async () => {${preferencesSource.slice(start, end)}})()`, context);
  assert.deepEqual(harness.calls, ['document-ready', 'same-workspace-ready']);
});

test('Preferences pending-restore reload keeps its workspace fence before and after the held draft read', async () => {
  const start = preferencesSource.indexOf('    // Hold the initial draft GET open after reload.');
  const end = preferencesSource.indexOf('    const afterReload =', start);
  assert.ok(start >= 0 && end > start, 'Run the real pending-restoration navigation and preference mutation');
  const harness = preferencesNavigationHarness({ pendingRestore: true });
  const context = vm.createContext({ page: harness.page, changedLimit: 150, preferenceRestored: true,
    workspaceId: 'fixture-workspace', console: { log() {} }
  });
  vm.runInContext(preferencesSource.replace(/^import .*;\n/m, '').replace(/^export /mg, ''), context);
  // Keep the navigation and existing preference-load predicate real; only the
  // request gate and unrelated preference persistence are controlled here.
  context.installRequestGate = async (_page, pattern, method) => {
    assert.equal(pattern, '**/api/analysis-drafts/**'); assert.equal(method, 'GET'); return harness.gate;
  };
  context.saveArchitectureLimit = async (_page, value) => {
    assert.equal(value, 150);
    assert.equal(harness.restoringObserved(), true, 'Preferences must be exercised during this workspace restoration');
    assert.equal(harness.readyObserved(), false, 'The held draft must not finish before the preference save');
    harness.calls.push('preference-saved-during-restore');
  };
  await vm.runInContext(`(async () => {${preferencesSource.slice(start, end)}})()`, context);
  assert.deepEqual(harness.calls, ['document-ready', 'translations-ready', 'same-workspace-restoring',
    'visible:#tab-preferences', 'preferences-loaded', 'preference-saved-during-restore',
    'draft-released', 'same-workspace-ready', 'gate-disposed']);
});

async function applySessionCommandHarness(authority) {
  const calls = [];
  const context = vm.createContext({
    URL,
    csrfJson: async (_page, endpoint, options) => {
      calls.push([endpoint, options.method]);
      return authority;
    }
  });
  vm.runInContext(preferencesSource.replace(/^import .*;\n/m, '').replace(/^export /mg, ''), context);
  const response = {
    status: () => 200,
    json: async () => { throw new Error('Browser has not consumed the apply-session response body'); }
  };
  const page = {
    waitForResponse: async predicate => {
      const request = method => ({ method: () => method });
      const candidate = (id, method) => ({
        request: () => request(method),
        url: () => `https://qa.example/taxonomy/api/dsl/hypotheses/${id}/apply-session`
      });
      assert.equal(predicate(candidate(42, 'GET')), false);
      assert.equal(predicate(candidate(43, 'POST')), false);
      assert.equal(predicate(candidate(42, 'POST')), true);
      return response;
    }
  };
  await vm.runInContext('hypothesisCommand', context)(
    page, 42, 'apply-session', async () => { calls.push('click'); }, assert);
  return calls;
}

test('Preferences session application verifies the persisted identity without waiting on an unconsumed browser response', async () => {
  const calls = await applySessionCommandHarness({
    status: 200,
    json: [{ id: 42, appliedInCurrentAnalysis: true }]
  });
  assert.deepEqual(calls, ['click', ['/api/dsl/hypotheses', 'GET']]);
});

for (const [label, authority] of [
  ['failed authority read', { status: 503, json: null }],
  ['missing persisted identity', { status: 200, json: [] }],
  ['another persisted identity', { status: 200, json: [{ id: 43, appliedInCurrentAnalysis: true }] }],
  ['unapplied persisted identity', { status: 200, json: [{ id: 42, appliedInCurrentAnalysis: false }] }]
]) {
  test(`Preferences session application rejects ${label} despite HTTP 200 from the command`, async () => {
    await assert.rejects(applySessionCommandHarness(authority),
      /Apply-session did not persist the expected hypothesis decision/);
  });
}

async function requestGateHarness(method) {
  const timers = new Map();
  let nextTimer = 0;
  let handler;
  let continued = 0;
  let unrouted = false;
  const context = vm.createContext({
    URL,
    setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, {callback, delay}); return id; },
    clearTimeout(id) { timers.delete(id); }
  });
  vm.runInContext(preferencesSource.replace(/^import .*;\n/m, '').replace(/^export /mg, ''), context);
  const pattern = '**/api/analysis-drafts/**';
  const page = {
    route: async (routePattern, callback) => { assert.equal(routePattern, pattern); handler = callback; },
    unroute: async (routePattern, callback) => {
      assert.equal(routePattern, pattern); assert.equal(callback, handler); unrouted = true;
    }
  };
  const gate = await vm.runInContext('installRequestGate', context)(page, pattern, method);
  return {
    gate, timers, isUnrouted: () => unrouted, continued: () => continued,
    request: requestMethod => handler({
      request: () => ({ method: () => requestMethod }),
      continue: async () => { continued++; }
    })
  };
}

for (const method of ['PUT', 'GET']) {
  test(`Preferences ${method} gate fails clearly after 30s without a matching request and releases its timer`, async () => {
    const {gate, timers, isUnrouted} = await requestGateHarness(method);
    assert.equal(timers.size, 0, 'Installing a gate must not start a rejecting timer before observation is awaited');
    const observation = gate.seen;
    const rejected = assert.rejects(observation,
      new RegExp(`Timed out after 30000 ms waiting for ${method} .*analysis-drafts.* request`));
    assert.equal(timers.size, 1, 'A missing request needs a bounded observation');
    const timer = [...timers.values()][0];
    assert.equal(timer.delay, 30_000);
    timer.callback();
    await rejected;
    assert.equal(timers.size, 0, 'Timeout must clear its timer');
    await gate.dispose();
    assert.equal(isUnrouted(), true);
  });

  test(`Preferences ${method} gate remembers a request observed before awaiting and continues once released`, async () => {
    const {gate, timers, request, continued} = await requestGateHarness(method);
    const intercepted = request(method);
    assert.equal(timers.size, 0);
    assert.equal(continued(), 0, 'Matching request stays held before release');
    await gate.seen;
    assert.equal(timers.size, 0, 'Successful observation must clear its timer');
    gate.release();
    gate.release();
    await intercepted;
    assert.equal(continued(), 1);
    await gate.seen;
    assert.equal(timers.size, 0, 'Seen remains sticky after release');
    await gate.dispose();
  });
}

test('Preferences gate ignores other methods and disposal releases an intercepted request', async () => {
  const {gate, timers, request, continued, isUnrouted} = await requestGateHarness('PUT');
  await request('GET');
  assert.equal(continued(), 1);
  let seen = false;
  const observation = gate.seen.then(() => { seen = true; });
  await Promise.resolve();
  assert.equal(seen, false, 'GET must not satisfy a PUT gate');
  const intercepted = request('PUT');
  await observation;
  assert.equal(timers.size, 0);
  await gate.dispose();
  await intercepted;
  assert.equal(continued(), 2);
  assert.equal(isUnrouted(), true);
});

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


function routeRegistration(source, marker) {
  const end = source.indexOf(marker);
  assert.ok(end >= 0, 'The real scenario route must exist');
  const start = source.lastIndexOf('await page.route(', end);
  const finish = source.indexOf('{ times: 1 });', end) + '{ times: 1 });'.length;
  assert.ok(start >= 0 && finish > start);
  return source.slice(start, finish);
}

for (const [driver, marker] of [
  ['ui-role-state-flow.mjs', "body: JSON.stringify({ error: 'QA_PROVIDER_UNAVAILABLE'"],
  ['ui-primary-session-workflow.mjs', 'await new Promise(resolve => setTimeout(resolve, 3_000))'],
  ['ui-special-modes-acceptance.mjs', "status: 'PARTIAL'"]
]) {
  test(`${driver} intercepts the exact mounted analysis endpoint with canonical workspace queries`, async () => {
    const driverSource = await readFile(new URL(driver, import.meta.url), 'utf8');
    let registered;
    const context = vm.createContext({page:{route:async (matcher,handler,options)=>{registered={matcher,handler,options};}}});
    await vm.runInContext(`(async()=>{${routeRegistration(driverSource,marker)}})()`, context);
    const matches = value => typeof registered.matcher === 'function'
      ? registered.matcher(new URL(value)) : value.endsWith(registered.matcher.slice(2));
    for(const prefix of ['', '/taxonomy', '/teams/blue/taxonomy']) {
      for(const query of ['', '?workspaceId=exact-source', '?workspaceId=&language=en']) {
        assert.equal(matches(`https://qa.example${prefix}/api/analyze${query}`),true,
          'The injected scenario response/delay must receive canonical pinned requests');
      }
      for(const path of ['/api/analyze-node','/api/analyze/extra','/api/other?next=/api/analyze']) {
        assert.equal(matches(`https://qa.example${prefix}${path}`),false,'Unrelated requests must remain real');
      }
    }
    assert.equal(registered.options.times,1,'Only the intended first analysis is intercepted');
  });
}

test('special-mode AI status interception accepts workspace queries and retains identical cleanup matcher',async()=>{
  const driverSource=await readFile(new URL('ui-special-modes-acceptance.mjs',import.meta.url),'utf8');
  const start=driverSource.indexOf('await page.route(',driverSource.indexOf('const stableAiStatus'));
  const finish=driverSource.indexOf(';',start)+1;
  let registered;
  const context=vm.createContext({stableAiStatus(){},page:{route:async matcher=>{registered=matcher;}}});
  const declaration=driverSource.match(/const aiStatusEndpoint = [^;]+;/)?.[0]||'';
  await vm.runInContext(`(async()=>{${declaration}${driverSource.slice(start,finish)}})()`,context);
  const matches=value=>typeof registered==='function'?registered(new URL(value)):value.endsWith(registered.slice(2));
  assert.equal(matches('https://qa.example/taxonomy/api/ai-status?workspaceId=exact-source'),true);
  assert.equal(matches('https://qa.example/taxonomy/api/ai-status/other'),false);
  assert.equal(matches('https://qa.example/taxonomy/api/other?next=/api/ai-status'),false);
  if(typeof registered==='function')assert.match(driverSource,/page\.unroute\(aiStatusEndpoint, stableAiStatus\)/);
});
