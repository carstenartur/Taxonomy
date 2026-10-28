import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const root = new URL('../../taxonomy-app/src/main/resources/static/js/core/', import.meta.url);
const clone = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));

function server() {
  let draft = null;
  const writes = [];
  const continuation = { request: { businessText: 'Hospital communications', provider: 'LOCAL' },
    recovery: { id: 'continuation-1', version: 1, state: 'RUNNING', completedCalls: 0, openQuestions: [] },
    clusterOperationId: 'operation-1' };
  return { writes, continuation, draft: () => clone(draft),
    async request(tab, path, options) {
      const method = options.method || 'GET';
      if (path.startsWith('/api/analysis-continuations/')) {
        assert.equal(method, 'GET');
        return { ok: true, json: async () => clone(continuation) };
      }
      assert.match(path, /^\/api\/analysis-drafts\/workspace-1(?:\/status)?$/);
      if (method === 'PUT') {
        const body = JSON.parse(options.body);
        writes.push({ tab, body });
        if (body.expectedVersion !== (draft?.version ?? null)) {
          return { ok: false, status: 409, json: async () => ({ detail: 'Draft version conflict' }) };
        }
        draft = { workspaceId: 'workspace-1', branch: 'draft', version: (draft?.version ?? -1) + 1,
          payload: body.payload };
      } else assert.equal(method, 'GET');
      return { ok: true, status: draft ? 200 : 204, json: async () => clone(draft) };
    }
  };
}

// Two independent JS realms share only the server, as two pages of the same user do.
// Run the production state, draft, transport, option restoration and recovery modules.
function tab(server, name, { recovery = false, renderReady = true } = {}) {
  const elements = new Map(), listeners = new Map(), timers = new Map(), alerts = [];
  let serial = 0;
  function element(id) {
    const attributes = new Map();
    return { id, value: '', checked: false, options: [{ value: '' }, { value: 'LOCAL' }],
      style: {}, dataset: {}, classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
      setAttribute: (key, value) => attributes.set(key, value), getAttribute: key => attributes.get(key),
      removeAttribute: key => attributes.delete(key), replaceChildren() {}, append() {}, addEventListener() {} };
  }
  for (const id of ['businessText', 'providerSelect', 'interactiveMode', 'includeArchitectureView', 'statusArea']) elements.set(id, element(id));
  const document = { readyState: 'loading', documentElement: { lang: 'en' },
    getElementById: id => elements.get(id) || null, querySelector: () => null, querySelectorAll: () => [],
    addEventListener(type, listener) { if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(listener); },
    dispatchEvent(event) { for (const listener of listeners.get(event.type) || []) listener(event); }
  };
  const schedule = (fn, delay) => { timers.set(++serial, { fn, delay }); return serial; };
  const window = { location: { href: 'https://taxonomy.example/', origin: 'https://taxonomy.example' }, console,
    setTimeout: schedule, clearTimeout: id => timers.delete(id), setInterval: schedule, clearInterval: id => timers.delete(id),
    fetch: async () => { throw new Error('Unexpected analysis request'); },
    TaxonomyAnalysisSessionApi: { request: (path, options) => server.request(name, path, options) },
    TaxonomyBrowse: { renderView() {}, updateExportGroupVisibility() {} },
    TaxonomyScoring: { renderArchitectureView() {}, renderSuggestedRelations() {} },
    TaxonomyRecoveryViewport: { mount: () => ({ update() {}, close() {}, isOpen: () => false, isDismissed: () => true }) }
  };
  const sandbox = vm.createContext({ window, document, console, URL, Headers, Request, AbortController,
    CustomEvent, setTimeout: schedule, clearTimeout: id => timers.delete(id),
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} } });
  const load = file => vm.runInContext(readFileSync(new URL(file, root), 'utf8'), sandbox, { filename: file });
  load('taxonomy-state.js');
  load('taxonomy-analysis-session-core.js');
  load('taxonomy-analysis-session-transport.js');
  const C = window.__TaxonomyAnalysisSessionContext, S = window.TaxonomyState;
  Object.assign(C.runtime, { workspaceId: 'workspace-1', workspaceResolved: true });
  C.showActionAlert = (kind, title, body, actions, marker) => alerts.push({ kind, marker });
  C.showStaleActions = () => {};
  load('taxonomy-analysis-session-draft.js');
  window.TaxonomyAnalysisSession = { state: () => ({ ready: !C.runtime.restoring && !C.runtime.conflict }), saveNow: C.saveDraft };
  if (recovery) load('taxonomy-analysis-recovery.js');
  if (renderReady) S.taxonomyData = [{ code: 'BP', children: [] }];
  return { C, S, window, alerts, timers, elements,
    edit(text) { const input = elements.get('businessText'); input.value = text; document.dispatchEvent({ type: 'input', target: input }); },
    async timer(delay) { const entry = [...timers].find(([, value]) => value.delay === delay); assert.ok(entry); timers.delete(entry[0]); await entry[1].fn(); await flush(); }
  };
}

async function running(server) {
  const first = tab(server, 'first');
  first.edit('Hospital communications');
  first.S.lastAnalysisStatus = 'IN_PROGRESS';
  first.S.analysisOperationId = 'operation-1';
  first.S.recoveryContext = { id: 'continuation-1', workspaceId: 'workspace-1',
    request: clone(server.continuation.request), followups: {} };
  first.S.analysisRecovery = clone(server.continuation.recovery);
  assert.equal(await first.C.saveDraft(), true);
  return first;
}

for (const recovery of [false, true]) test(`opening another tab during a running analysis does not write or conflict (recovery=${recovery})`, async () => {
  const backend = server(), first = await running(backend), second = tab(backend, 'second', { recovery });
  await second.C.loadDraft(); await flush();
  assert.equal(await second.C.saveDraft(), true);
  assert.deepEqual(backend.writes.map(write => write.tab), ['first'], 'restoration and status observation are read-only');
  first.S.currentScores = { BP: 80 };
  first.S.lastAnalysisStatus = 'SUCCESS';
  assert.equal(await first.C.saveDraft(), true, 'the original tab can still save its result');
  assert.equal(first.C.runtime.conflict, false);
  assert.equal(second.C.runtime.conflict, false);
});

test('two tabs that actually edit the requirement retain optimistic conflict protection', async () => {
  const backend = server(), first = await running(backend), second = tab(backend, 'second');
  await second.C.loadDraft();
  first.edit('First edit'); assert.equal(await first.C.saveDraft(), true);
  second.edit('Independent second edit'); assert.equal(await second.C.saveDraft(), false);
  assert.equal(second.C.runtime.conflict, true);
  assert.equal(second.elements.get('businessText').value, 'Independent second edit');
  assert.equal(backend.draft().payload.businessText, 'First edit');
});

for (const currentView of ['tabs', 'tree']) test(`restoring the saved ${currentView} view does not write another revision`, async () => {
  const backend = server(), first = await running(backend);
  first.S.currentView = currentView; await first.C.saveDraft();
  const second = tab(backend, 'second', { recovery: true });
  await second.C.loadDraft(); await flush(); await second.C.saveDraft();
  assert.equal(second.S.currentView, currentView);
  assert.ok(backend.writes.every(write => write.tab === 'first'));
  first.S.currentScores = { BP: 80 };
  assert.equal(await first.C.saveDraft(), true);
});

test('typing while restored renderers load is not acknowledged as already saved', async () => {
  const backend = server(); await running(backend);
  const second = tab(backend, 'second', { renderReady: false });
  await second.C.loadDraft();
  second.edit('Typed while rendering');
  second.S.taxonomyData = [{ code: 'BP', children: [] }];
  await second.timer(100);
  assert.equal(await second.C.saveDraft(), true);
  assert.equal(backend.draft().payload.businessText, 'Typed while rendering');
});

test('late provider option restoration stays read-only', async () => {
  const backend = server(), first = await running(backend);
  first.elements.get('providerSelect').value = 'LOCAL'; await first.C.saveDraft();
  const second = tab(backend, 'second');
  second.elements.get('providerSelect').options = [{ value: '' }];
  await second.C.loadDraft();
  second.elements.get('providerSelect').options.push({ value: 'LOCAL' });
  await second.timer(250);
  await second.C.saveDraft();
  assert.equal(second.elements.get('providerSelect').value, 'LOCAL');
  assert.ok(backend.writes.every(write => write.tab === 'first'));
});

test('terminal server status and its canonical result do not make a second tab a writer', async () => {
  const backend = server(); await running(backend);
  const second = tab(backend, 'second', { recovery: true });
  await second.C.loadDraft(); await flush();
  backend.continuation.recovery = { ...backend.continuation.recovery, version: 2, state: 'COMPLETED' };
  backend.continuation.result = { status: 'SUCCESS', scores: {}, rawScores: {}, reasons: {},
    recovery: backend.continuation.recovery, analysisOperationId: 'operation-1', provider: 'LOCAL' };
  await second.window.TaxonomyAnalysisRecovery.refresh();
  assert.equal(second.S.lastAnalysisStatus, 'SUCCESS');
  assert.equal(second.C.currentPayload().analysisReference.continuationId, 'continuation-1');
  assert.ok(backend.writes.every(write => write.tab === 'first'));
});

test('failed read and reconnect are local observation state, without competing draft writes', async () => {
  const backend = server(); await running(backend);
  const request = backend.request;
  backend.request = async (...args) => args[1].startsWith('/api/analysis-continuations/')
    ? { ok: false, status: 503, json: async () => ({ detail: 'Temporarily unavailable' }) } : request(...args);
  const second = tab(backend, 'second', { recovery: true });
  await second.C.loadDraft(); await flush();
  assert.equal(second.S.recoveryContext.syncState, 'UNAVAILABLE');
  await second.C.saveDraft();
  backend.request = request;
  await second.window.TaxonomyAnalysisRecovery.refresh();
  assert.equal(second.S.recoveryContext.syncState, null);
  await second.C.saveDraft();
  assert.ok(backend.writes.every(write => write.tab === 'first'));
});

test('status observation cannot acknowledge a manual score edit made during its read', async () => {
  const backend = server(); await running(backend);
  const second = tab(backend, 'second', { recovery: true });
  await second.C.loadDraft(); await flush();
  const request = backend.request;
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  backend.request = async (...args) => { await pending; return request(...args); };
  const reading = second.window.TaxonomyAnalysisRecovery.refresh();
  await flush();
  second.S.currentScores = { BP: 37 };
  release(); await reading;
  assert.equal(await second.C.saveDraft(), true);
  assert.equal(backend.writes.at(-1).tab, 'second');
  assert.equal(backend.draft().payload.scores.BP, 37);
});
