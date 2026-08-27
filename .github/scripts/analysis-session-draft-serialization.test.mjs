import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/'
      + 'taxonomy-analysis-session-transport.js',
    import.meta.url
  ),
  'utf8'
);

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function conflictError(requestId = 'conflict-request-1') {
  return Object.assign(new Error('Analysis draft version conflict'), {
    status: 409,
    code: 'HTTP_ERROR',
    requestId
  });
}

function put(payload, expectedVersion = null) {
  return {
    method: 'PUT',
    body: JSON.stringify({ payload, expectedVersion })
  };
}

function createHarness(rawRequest, { basePath = '' } = {}) {
  const eventListeners = new Map();
  const dispatched = [];
  const runtime = {
    workspaceId: 'workspace-1',
    version: null,
    invalidating: false,
    restoredPayload: null
  };
  const C = {
    runtime,
    S: { interactiveMode: false },
    requestUrl(input) {
      return new URL(String(input), `https://taxonomy.example${basePath || ''}/`);
    },
    comparable(value) {
      return JSON.stringify(value);
    },
    jsonRequest: rawRequest,
    currentPayload() {
      return { businessText: '' };
    },
    installWorkspaceFetchRouting() {},
    installWorkspaceEventSourceRouting() {}
  };
  function nativeFetch() {
    return Promise.resolve({ ok: true });
  }
  function nativeSetInterval() {
    return 1;
  }
  function nativeClearInterval() {}
  const window = {
    __TaxonomyAnalysisSessionContext: C,
    location: { origin: 'https://taxonomy.example' },
    fetch: nativeFetch,
    setInterval: nativeSetInterval,
    clearInterval: nativeClearInterval,
    setTimeout(callback) {
      callback();
      return 1;
    },
    TaxonomyScoring: null
  };
  const document = {
    getElementById() {
      return null;
    },
    querySelectorAll() {
      return [];
    },
    addEventListener(type, listener) {
      if (!eventListeners.has(type)) eventListeners.set(type, []);
      eventListeners.get(type).push(listener);
    },
    dispatchEvent(event) {
      dispatched.push(event);
    }
  };

  vm.runInNewContext(source, {
    window,
    document,
    console,
    AbortController,
    Array,
    Boolean,
    CustomEvent: class CustomEvent {
      constructor(type, options = {}) {
        this.type = type;
        this.detail = options.detail;
      }
    },
    Date,
    JSON,
    Number,
    Object,
    Promise,
    Proxy,
    Reflect,
    Set,
    String,
    URL
  }, { filename: 'taxonomy-analysis-session-transport.js' });

  return { C, runtime, dispatched, eventListeners };
}

async function flush() {
  await new Promise(resolve => setImmediate(resolve));
}

test('serializes simultaneous draft writes and advances the optimistic version before the next write', async () => {
  const first = deferred();
  const second = deferred();
  const calls = [];
  const payloadA = { businessText: 'First draft' };
  const payloadB = { businessText: 'Second draft' };
  const harness = createHarness((url, options) => {
    calls.push({ url, options, body: options.body ? JSON.parse(options.body) : null });
    return calls.length === 1 ? first.promise : second.promise;
  });

  const writingA = harness.C.jsonRequest('/api/analysis-drafts/workspace-1', put(payloadA));
  const writingB = harness.C.jsonRequest('/api/analysis-drafts/workspace-1', put(payloadB));
  await flush();

  assert.equal(calls.length, 1, 'Second PUT must not start before the first settles');
  assert.equal(calls[0].body.expectedVersion, null);

  first.resolve({ version: 0, payload: payloadA });
  await flush();

  assert.equal(calls.length, 2);
  assert.equal(calls[1].body.expectedVersion, 0);
  second.resolve({ version: 1, payload: payloadB });
  await Promise.all([writingA, writingB]);

  assert.equal(harness.runtime.version, 1);
});

test('serializes draft reads with writes so a late reload cannot regress the acknowledged version', async () => {
  const loading = deferred();
  const calls = [];
  const loaded = { version: 0, payload: { businessText: 'Loaded draft' } };
  const updated = { version: 1, payload: { businessText: 'Updated draft' } };
  const harness = createHarness((url, options) => {
    const body = options.body ? JSON.parse(options.body) : null;
    calls.push({ method: options.method, body });
    if (calls.length === 1) return loading.promise;
    return Promise.resolve(updated);
  });

  const reading = harness.C.jsonRequest('/api/analysis-drafts/workspace-1', { method: 'GET' });
  const writing = harness.C.jsonRequest(
    '/api/analysis-drafts/workspace-1', put(updated.payload));
  await flush();

  assert.deepEqual(calls.map(call => call.method), ['GET']);
  loading.resolve(loaded);
  await flush();

  assert.deepEqual(calls.map(call => call.method), ['GET', 'PUT']);
  assert.equal(calls[1].body.expectedVersion, 0);
  await Promise.all([reading, writing]);
  assert.equal(harness.runtime.version, 1);
});

test('clears acknowledged write evidence when a draft read reports no current draft', async () => {
  const oldPayload = { businessText: 'Expired draft' };
  const newPayload = { businessText: 'New lifecycle' };
  const conflict = conflictError('new-lifecycle-conflict');
  let phase = 0;
  const harness = createHarness(async (url, options) => {
    phase += 1;
    if (phase === 1) return { version: 0, payload: oldPayload };
    if (phase === 2) return null;
    if (phase === 3) throw conflict;
    if (phase === 4) return { version: 0, payload: oldPayload };
    throw new Error(`Unexpected request phase ${phase}`);
  });

  await harness.C.jsonRequest('/api/analysis-drafts/workspace-1', put(oldPayload));
  await harness.C.jsonRequest('/api/analysis-drafts/workspace-1', { method: 'GET' });

  await assert.rejects(
    harness.C.jsonRequest('/api/analysis-drafts/workspace-1', put(newPayload)),
    error => error === conflict
  );
  assert.equal(harness.runtime.version, null);
});

test('repairs a stale same-browser version after a 409 only from an acknowledged remote revision', async () => {
  const payloadA = { businessText: 'Version zero' };
  const payloadB = { businessText: 'Version one' };
  const payloadC = { businessText: 'Version two' };
  const calls = [];
  let phase = 0;
  const harness = createHarness(async (url, options) => {
    const body = options.body ? JSON.parse(options.body) : null;
    calls.push({ url, method: options.method, body });
    phase += 1;
    if (phase === 1) return { version: 0, payload: payloadA };
    if (phase === 2) return { version: 1, payload: payloadB };
    if (phase === 3) throw conflictError();
    if (phase === 4) return { version: 1, payload: payloadB };
    if (phase === 5) return { version: 2, payload: payloadC };
    throw new Error(`Unexpected request phase ${phase}`);
  });

  await harness.C.jsonRequest('/api/analysis-drafts/workspace-1', put(payloadA));
  await harness.C.jsonRequest('/api/analysis-drafts/workspace-1', put(payloadB));
  harness.runtime.version = 0; // Simulate a stale local overwrite after v1 was acknowledged.

  const result = await harness.C.jsonRequest(
    '/api/analysis-drafts/workspace-1', put(payloadC));

  assert.deepEqual(result, { version: 2, payload: payloadC });
  assert.deepEqual(calls.map(call => call.method), ['PUT', 'PUT', 'PUT', 'GET', 'PUT']);
  assert.equal(calls[2].body.expectedVersion, 0);
  assert.equal(calls[4].body.expectedVersion, 1);
  assert.equal(harness.runtime.version, 2);
  const reconciled = harness.dispatched.find(event =>
    event.type === 'taxonomy:analysis-draft-write-reconciled');
  assert.ok(reconciled);
  assert.equal(reconciled.detail.reason, 'stale-local-version');
});

test('accepts an explicit 409 as already committed only when the remote payload is identical', async () => {
  const payload = { businessText: 'Committed despite response conflict' };
  const conflict = conflictError('same-payload-409');
  const calls = [];
  const harness = createHarness(async (url, options) => {
    calls.push({ method: options.method, body: options.body && JSON.parse(options.body) });
    if (options.method === 'PUT') throw conflict;
    return { version: 0, payload };
  });

  const result = await harness.C.jsonRequest(
    '/api/analysis-drafts/workspace-1', put(payload));

  assert.deepEqual(result, { version: 0, payload });
  assert.deepEqual(calls.map(call => call.method), ['PUT', 'GET']);
  assert.equal(harness.runtime.version, 0);
  const reconciled = harness.dispatched.find(event =>
    event.type === 'taxonomy:analysis-draft-write-reconciled');
  assert.equal(reconciled?.detail.reason, 'write-already-committed');
});

test('preserves genuine cross-tab conflicts when the remote revision was never acknowledged locally', async () => {
  const local = { businessText: 'Local tab' };
  const remote = { version: 0, payload: { businessText: 'Other device' } };
  const conflict = conflictError('foreign-conflict');
  const calls = [];
  const harness = createHarness(async (url, options) => {
    calls.push(options.method);
    if (options.method === 'PUT') throw conflict;
    return remote;
  });

  await assert.rejects(
    harness.C.jsonRequest('/api/analysis-drafts/workspace-1', put(local)),
    error => error === conflict
  );

  assert.deepEqual(calls, ['PUT', 'GET']);
  assert.equal(harness.runtime.version, null);
  assert.equal(
    harness.dispatched.some(event =>
      event.type === 'taxonomy:analysis-draft-write-reconciled'),
    false
  );
});

test('recognizes draft mutations below a servlet context path', async () => {
  const payload = { businessText: 'Context path draft' };
  const calls = [];
  const harness = createHarness(async (url, options) => {
    calls.push({ url, body: JSON.parse(options.body) });
    return { version: 0, payload };
  }, { basePath: '/taxonomy' });

  await harness.C.jsonRequest('/taxonomy/api/analysis-drafts/workspace-1', put(payload, 99));

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/taxonomy/api/analysis-drafts/workspace-1');
  assert.equal(calls[0].body.expectedVersion, null,
    'The queue must use its authoritative runtime version, not the caller value');
});

test('diagnostics remain bounded and omit draft payload contents', async () => {
  const secretText = 'private-requirement-payload';
  const payload = { businessText: secretText };
  const harness = createHarness(async () => ({ version: 0, payload }));

  await harness.C.jsonRequest('/api/analysis-drafts/workspace-1', put(payload));
  const diagnostics = harness.C.draftMutationDiagnostics();

  assert.ok(diagnostics.length > 0);
  assert.ok(diagnostics.length <= 48);
  assert.equal(JSON.stringify(diagnostics).includes(secretText), false);
});
