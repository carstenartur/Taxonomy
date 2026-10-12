import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const workflowSource = readFileSync(new URL('./ui-primary-account-workflows.mjs', import.meta.url), 'utf8');
const syncSource = readFileSync(new URL(
  '../../taxonomy-app/src/main/resources/static/js/workspace/taxonomy-workspace-sync.js', import.meta.url), 'utf8');
const roleSource = readFileSync(new URL(
  '../../taxonomy-app/src/main/resources/static/js/security/taxonomy-role-surface.js', import.meta.url), 'utf8');

// Execute the real workflow and renderers. Only the Playwright/DOM boundary is
// replaced: the test schedules the real tab connectivity callback before the
// explicit refresh, without starting a browser or waiting for a wall-clock race.
function createHarness({ lateConnectivity = false, extraReads = 0,
  incidentalActionReads = false, failEvidence = null,
  delayPublishRequest = false, delayPublishResponse = false, delayPublishPoll = false } = {}) {
  const routes = [];
  const requests = [];
  const passed = [];
  const listeners = new Map();
  const queued = [];
  const pending = new Set();
  const panel = { innerHTML: '' };
  let toast = '';
  let toastKind = '';
  let publishResponseReceived = false;
  const deferred = () => {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return { promise, resolve };
  };
  const publishRequested = deferred();
  const publishResponseReady = deferred();
  const publishPollRequested = deferred();
  const publishRequestGate = deferred();
  const publishResponseGate = deferred();
  const publishPollGate = deferred();

  function matches(pattern, url) {
    if (typeof pattern === 'function') return pattern(new URL(url));
    if (pattern instanceof RegExp) return pattern.test(url);
    const escaped = pattern.split('*').map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*');
    return new RegExp(`^${escaped}$`).test(url);
  }

  async function request(path, init = {}) {
    const url = new URL(path, 'https://taxonomy.example.invalid').href;
    const method = init.method || 'GET';
    const pathname = new URL(url).pathname;
    const publishing = method === 'POST' && pathname.endsWith('/publish');
    if (publishing) {
      publishRequested.resolve();
      if (delayPublishRequest) await publishRequestGate.promise;
    }
    if (publishResponseReceived && method === 'GET' && pathname.endsWith('/sync-state')) {
      publishPollRequested.resolve();
      if (delayPublishPoll) await publishPollGate.promise;
    }
    let response;
    const fallback = () => {
      const pathname = new URL(url).pathname;
      response = { status: 200, body: JSON.stringify(pathname.endsWith('/sync-state')
        ? { syncStatus: 'UP_TO_DATE', unpublishedCommitCount: 0 }
        : { changeCount: 0 }) };
    };
    const entry = [...routes].reverse().find(candidate => matches(candidate.pattern, url));
    if (entry) {
      if (--entry.remaining === 0) routes.splice(routes.indexOf(entry), 1);
      await entry.handler({
        request: () => ({ method: () => method, url: () => url }),
        fulfill: async result => { response = result; },
        fallback: async () => fallback(),
        continue: async () => fallback()
      });
    } else fallback();
    assert.ok(response, `No response for ${method} ${url}`);
    const body = JSON.parse(response.body);
    requests.push({ method, path: pathname, body });
    if (publishing) {
      publishResponseReady.resolve();
      if (delayPublishResponse) await publishResponseGate.promise;
      publishResponseReceived = true;
    }
    return { ok: response.status >= 200 && response.status < 300,
      status: response.status, json: async () => body };
  }

  const fetch = (path, init) => {
    const task = request(path, init);
    pending.add(task);
    task.then(() => pending.delete(task), () => pending.delete(task));
    return task;
  };
  async function settle() {
    do {
      await Promise.all([...pending]);
      await new Promise(resolve => setImmediate(resolve));
    } while (pending.size);
  }
  async function waitForCondition(predicate, label) {
    // A visible condition must not also await unrelated in-flight requests.
    // Bound deterministic event-loop turns so a missing condition fails promptly.
    for (let turn = 0; turn < 100; turn += 1) {
      if (predicate()) return;
      await new Promise(resolve => setImmediate(resolve));
    }
    assert.ok(predicate(), label);
  }
  const window = {
    setTimeout: callback => queued.push(callback),
    TaxonomyContextBar: { getCurrentContext: () => ({ branch: 'draft' }) },
    TaxonomyApiClient: { getJson: async path => (await fetch(path)).json() },
    TaxonomyOperationResult: {
      showError: (_title, detail) => { toast = detail; toastKind = 'error'; },
      showSuccess: title => { toast = title; toastKind = 'success'; }
    }
  };
  const context = vm.createContext({
    window, fetch, setInterval() {},
    document: {
      addEventListener: (type, callback) => listeners.set(type, callback),
      getElementById: id => id === 'syncStatePanel' ? panel : null
    },
    TaxonomyI18n: { t: key => key },
    TaxonomyUtils: { escapeHtml: String },
    navigateToPage: async () => undefined
  });
  vm.runInContext(syncSource, context, { filename: 'taxonomy-workspace-sync.js' });
  const guardStart = roleSource.indexOf('    function renderWorkspaceOffline(error)');
  const guardEnd = roleSource.indexOf('    function refresh()', guardStart);
  assert.ok(guardStart >= 0 && guardEnd > guardStart);
  vm.runInContext(`${roleSource.slice(guardStart, guardEnd)}\ninstallWorkspaceOfflineGuard();`,
    context, { filename: 'taxonomy-role-surface.js' });
  const importLine = "import { csrfJson, navigateToPage } from './ui-role-fixtures.mjs';";
  assert.ok(workflowSource.startsWith(importLine));
  vm.runInContext(workflowSource.slice(importLine.length).replace(/^export /gm, ''),
    context, { filename: 'ui-primary-account-workflows.mjs' });

  async function drainConnectivity() {
    while (queued.length) queued.shift()();
    await settle();
  }
  async function readState({ render = true } = {}) {
    if (render) window.TaxonomyWorkspaceSync.refresh();
    else await window.TaxonomySyncOfflineGuard.refresh();
    await settle();
  }
  function assertConflict(label) {
    assert.match(panel.innerHTML, /<button\b/, `${label}: conflict button absent; panel=${panel.innerHTML}`);
    assert.equal(window.TaxonomyWorkspaceSync.getSyncState().syncStatus, 'DIVERGED');
  }
  const page = {
    async route(pattern, handler, options = {}) {
      routes.push({ pattern, handler, remaining: options.times ?? Infinity });
      if (incidentalActionReads && typeof pattern === 'string'
          && /\/(?:sync-from-shared|publish)\?/.test(pattern)) {
        await fetch(pattern.replace('**', 'https://taxonomy.example.invalid')
          .replace('?*', '?userBranch=draft'));
        await readState({ render: false });
      }
    },
    async unroute(pattern, handler) {
      for (let index = routes.length - 1; index >= 0; index -= 1) {
        if (routes[index].pattern === pattern && routes[index].handler === handler) routes.splice(index, 1);
      }
    },
    locator(selector) {
      return {
        async click() {
          assert.equal(selector, '#versionsSubTabs [data-versions-tab="sync"]');
          listeners.get('click')({ target: { closest: () => ({}) } });
          if (!lateConnectivity) await drainConnectivity();
        },
        async waitFor() {
          if (selector === '#syncStatePanel button') {
            await waitForCondition(() => /<button\b/.test(panel.innerHTML),
              'locator wait: conflict button absent');
            assertConflict('locator wait');
          }
          if (selector === '#operationToast') await waitForCondition(() => Boolean(toast), 'toast absent');
          if (selector === '#operationToast .toast-header.bg-success') {
            await waitForCondition(() => toastKind === 'success', 'success toast absent');
          }
        }
      };
    },
    async evaluate(callback) {
      if (lateConnectivity && queued.length) await drainConnectivity();
      // Playwright adopts the callback's returned Promise; undefined does not
      // wait for fetches started inside a fire-and-forget application function.
      return callback();
    },
    async waitForFunction(predicate, argument) {
      await waitForCondition(() => predicate(argument), 'published sync state not reached');
    }
  };
  const evidence = {
    passed: message => passed.push(message),
    async axeState(state) {
      if (failEvidence === state) throw new Error('intentional evidence rejection');
      for (let index = 0; index < extraReads; index += 1) {
        await readState({ render: index % 2 === 0 });
      }
      if (state !== 'workspace-publish-success') assertConflict(state);
    },
    async saveState(state) { if (state === 'workspace-conflict') assertConflict('saved conflict'); },
    async waitForText(_selector, predicate) {
      await waitForCondition(() => predicate(toast), `Unexpected toast: ${toast}`);
    }
  };
  return {
    page, routes, requests, passed, panel, readState, settle,
    publishRequested: publishRequested.promise,
    publishResponseReady: publishResponseReady.promise,
    publishPollRequested: publishPollRequested.promise,
    releasePublishRequest: publishRequestGate.resolve,
    releasePublishResponse: publishResponseGate.resolve,
    releasePublishPoll: publishPollGate.resolve,
    run: () => context.runWorkspaceSyncWorkflows({ page, evidence })
  };
}

test('workspace workflow succeeds with an immediately completed connectivity read', async () => {
  const harness = createHarness();
  await harness.run();
  assert.deepEqual(harness.passed, ['workspace diverged conflict state',
    'workspace remote sync error', 'workspace publish success']);
  assert.equal(harness.routes.length, 0);
});

test('a delayed connectivity GET cannot consume the conflict needed by the rendering GET', async () => {
  const harness = createHarness({ lateConnectivity: true });
  await harness.run();
  assert.equal(harness.passed.length, 3);
});

test('repeated polling preserves divergence through a failed sync and advances only after publish', async () => {
  const harness = createHarness({ extraReads: 9 });
  await harness.run();
  const publication = harness.requests.findIndex(item => item.method === 'POST' && item.path.endsWith('/publish'));
  assert.ok(publication > 0);
  const reads = harness.requests.filter(item => item.path.endsWith('/sync-state'));
  assert.ok(reads.length >= 20);
  for (const item of harness.requests.slice(0, publication).filter(item => item.path.endsWith('/sync-state'))) {
    assert.equal(item.body.syncStatus, 'DIVERGED');
  }
  for (const item of harness.requests.slice(publication + 1).filter(item => item.path.endsWith('/sync-state'))) {
    assert.equal(item.body.syncStatus, 'UP_TO_DATE');
  }
});

test('GET requests to action URLs neither consume POST responses nor advance sync state', async () => {
  const harness = createHarness({ incidentalActionReads: true });
  await harness.run();
  const publication = harness.requests.findIndex(item => item.method === 'POST' && item.path.endsWith('/publish'));
  assert.ok(publication > 0);
  const before = harness.requests.slice(0, publication).filter(item => item.path.endsWith('/sync-state'));
  assert.ok(before.length >= 4);
  assert.ok(before.every(item => item.body.syncStatus === 'DIVERGED'));
  const syncPost = harness.requests.find(item => item.method === 'POST' && item.path.endsWith('/sync-from-shared'));
  assert.equal(syncPost.body.error, 'REMOTE_UNAVAILABLE');
  assert.deepEqual(harness.requests[publication].body, {});
});

for (const state of ['workspace-conflict', 'workspace-sync-error', 'workspace-publish-success']) {
  test(`workspace fixture cleans its own routes after failure at ${state}`, async () => {
    const harness = createHarness({ failEvidence: state });
    const unrelated = async route => route.fulfill({ status: 200, body: '{}' });
    await harness.page.route('**/api/unrelated', unrelated);
    await assert.rejects(harness.run, /intentional evidence rejection/);
    assert.equal(harness.routes.length, 1);
    assert.equal(harness.routes[0].handler, unrelated);
    await harness.readState();
    assert.doesNotMatch(harness.panel.innerHTML, /<button\b/);
  });
}

test('an already visible error toast cannot finish the workflow before the publish POST is handled', async () => {
  const harness = createHarness({ delayPublishRequest: true });
  let finished = false;
  const outcome = harness.run().finally(() => { finished = true; });
  try {
    await harness.publishRequested;
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(finished, false, 'workflow completed while its publish POST was still pending');
    assert.ok(harness.routes.some(route => String(route.pattern).includes('/publish?')),
      'publish route was removed before the POST reached it');
    harness.releasePublishRequest();
    await outcome;
    const publication = harness.requests.find(item => item.method === 'POST' && item.path.endsWith('/publish'));
    assert.deepEqual(publication.body, {});
    assert.equal(harness.routes.length, 0);
  } finally {
    harness.releasePublishRequest();
    await outcome.catch(() => undefined);
    await harness.settle();
  }
});

test('cleanup waits for both the publish response and its rendered sync-state poll', async () => {
  const harness = createHarness({ delayPublishResponse: true, delayPublishPoll: true });
  let finished = false;
  const outcome = harness.run().finally(() => { finished = true; });
  try {
    await harness.publishResponseReady;
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(finished, false, 'workflow completed before the publish response arrived');
    harness.releasePublishResponse();
    await harness.publishPollRequested;
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(finished, false, 'workflow completed before the successful sync-state poll rendered');
    harness.releasePublishPoll();
    await outcome;
    assert.equal(harness.passed.at(-1), 'workspace publish success');
    assert.equal(harness.routes.length, 0);
  } finally {
    harness.releasePublishResponse();
    harness.releasePublishPoll();
    await outcome.catch(() => undefined);
    await harness.settle();
  }
});
