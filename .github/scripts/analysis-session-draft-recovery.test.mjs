import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const loaderSource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/'
      + 'taxonomy-analysis-session.js',
    import.meta.url
  ),
  'utf8'
);
const draftSource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/'
      + 'taxonomy-analysis-session-draft.js',
    import.meta.url
  ),
  'utf8'
);
const recoverySource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/'
      + 'taxonomy-analysis-session-draft-recovery.js',
    import.meta.url
  ),
  'utf8'
);

function timeoutError() {
  return Object.assign(new Error('Request timed out after 30000 ms'), {
    status: 0,
    code: 'TIMEOUT',
    requestId: 'request-timeout-1'
  });
}

function createHarness(request) {
  const events = [];
  const scheduled = [];
  const context = {
    runtime: { workspaceId: 'workspace-1' },
    comparable: value => JSON.stringify(value),
    requestUrl: input => new URL(input, 'https://taxonomy.example/'),
    jsonRequest: request
  };
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    location: { origin: 'https://taxonomy.example' },
    console,
    setTimeout(callback, delay) {
      scheduled.push({ callback, delay });
      return scheduled.length;
    }
  };
  const document = {
    dispatchEvent(event) {
      events.push(event);
    }
  };

  vm.runInNewContext(recoverySource, {
    window,
    document,
    console,
    CustomEvent: class CustomEvent {
      constructor(type, options = {}) {
        this.type = type;
        this.detail = options.detail;
      }
    },
    Error,
    JSON,
    Number,
    Object,
    Promise,
    String,
    URL
  }, { filename: 'taxonomy-analysis-session-draft-recovery.js' });

  return { context, events, scheduled };
}

function createComposedHarness(request, payload) {
  const events = [];
  const controls = [];
  const input = { value: payload.current.businessText, classList: { add() {}, remove() {} } };
  const runtime = {
    workspaceId: 'workspace-1',
    version: null,
    restoring: false,
    invalidating: false,
    conflict: false,
    saveTimer: null,
    saveInFlight: null,
    saveQueued: false,
    lastSavedComparable: null,
    lastObservedComparable: null,
    restoredPayload: null
  };
  const context = {
    S: {
      currentScores: null,
      currentReasons: {},
      currentDiscrepancies: [],
      currentArchView: null,
      storedBusinessText: null,
      lastAnalyzedText: null,
      evaluatedNodes: new Set(),
      taxonomyData: []
    },
    runtime,
    AUTOSAVE_DELAY_MS: 900,
    MAX_RESTORE_ATTEMPTS: 1,
    text: key => key,
    businessTextElement: () => input,
    currentPayload: () => payload.current,
    comparable: value => JSON.stringify(value),
    meaningful: value => Boolean(String(value?.businessText || '').trim()),
    draftEndpoint: () => '/api/analysis-drafts/workspace-1',
    requestUrl: value => new URL(value, 'https://taxonomy.example/'),
    jsonRequest: request,
    hasDerivedAnalysis: () => false,
    isStale: () => false,
    showActionAlert() {},
    showStaleActions() {},
    openRequirementDialog() {}
  };
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    location: { origin: 'https://taxonomy.example' },
    TaxonomyBrowse: null,
    TaxonomyScoring: null,
    _taxonomyCurrentScores: null,
    _currentProvisionalRelations: [],
    console,
    clearTimeout() {},
    setTimeout(callback) {
      callback();
      return 1;
    }
  };
  const document = {
    dispatchEvent(event) {
      events.push(event);
    },
    addEventListener() {},
    getElementById() {
      return null;
    },
    querySelector() {
      return null;
    },
    querySelectorAll() {
      return controls;
    }
  };
  const globals = {
    window,
    document,
    console,
    CustomEvent: class CustomEvent {
      constructor(type, options = {}) {
        this.type = type;
        this.detail = options.detail;
      }
    },
    Array,
    Boolean,
    Error,
    JSON,
    Number,
    Object,
    Promise,
    Set,
    String,
    URL,
    encodeURIComponent
  };

  vm.runInNewContext(recoverySource, globals, {
    filename: 'taxonomy-analysis-session-draft-recovery.js'
  });
  vm.runInNewContext(draftSource, globals, {
    filename: 'taxonomy-analysis-session-draft.js'
  });
  return { context, runtime, input, events };
}

function put(payload, expectedVersion = null) {
  return {
    method: 'PUT',
    body: JSON.stringify({ payload, expectedVersion })
  };
}

test('loads write recovery after mutation serialization and before draft autosave', () => {
  const transport = loaderSource.indexOf('taxonomy-analysis-session-transport.js');
  const recovery = loaderSource.indexOf('taxonomy-analysis-session-draft-recovery.js');
  const draft = loaderSource.indexOf('taxonomy-analysis-session-draft.js');
  assert.ok(transport >= 0 && recovery > transport && draft > recovery,
    'Draft write recovery must wrap the serialized transport before autosave captures it');
});

test('recovers a committed initial draft when its PUT response was lost', async () => {
  const payload = { businessText: 'Committed draft', scores: { BP: 100 } };
  const remote = { version: 0, payload };
  const calls = [];
  const harness = createHarness(async (url, options) => {
    calls.push({ url, method: options.method });
    if (options.method === 'PUT') throw timeoutError();
    if (options.method === 'GET') return remote;
    throw new Error(`Unexpected method ${options.method}`);
  });

  const result = await harness.context.jsonRequest(
    '/api/analysis-drafts/workspace-1', put(payload));

  assert.deepEqual(result, remote);
  assert.deepEqual(calls.map(call => call.method), ['PUT', 'GET']);
  assert.equal(harness.events.length, 1);
  assert.equal(harness.events[0].type,
    'taxonomy:analysis-draft-write-reconciled');
  assert.equal(harness.events[0].detail.expectedVersion, null);
  assert.equal(harness.events[0].detail.version, 0);
  assert.equal(harness.events[0].detail.requestId, 'request-timeout-1');
});

test('feeds the recovered version into the next serialized autosave', async () => {
  const payload = { current: { businessText: 'First draft' } };
  let remote = null;
  let putCount = 0;
  const bodies = [];
  const harness = createComposedHarness(async (url, options) => {
    if (options.method === 'GET') return remote;
    if (options.method !== 'PUT') throw new Error(`Unexpected method ${options.method}`);
    const body = JSON.parse(options.body);
    bodies.push(body);
    putCount += 1;
    if (putCount === 1) {
      remote = { version: 0, payload: body.payload };
      throw timeoutError();
    }
    remote = { version: 1, payload: body.payload };
    return remote;
  }, payload);

  await harness.context.saveDraft();
  assert.equal(harness.runtime.version, 0);
  assert.equal(harness.runtime.conflict, false);

  payload.current = { businessText: 'Second draft' };
  harness.input.value = payload.current.businessText;
  await harness.context.saveDraft();

  assert.equal(bodies.length, 2);
  assert.equal(bodies[0].expectedVersion, null);
  assert.equal(bodies[1].expectedVersion, 0,
    'The write after a recovered response must not repeat expectedVersion=null');
  assert.equal(harness.runtime.version, 1);
  assert.equal(harness.runtime.conflict, false);
});

test('waits for a late commit before declaring an initial write lost', async () => {
  const payload = { businessText: 'Late commit' };
  let remote = null;
  let reads = 0;
  const harness = createHarness(async (url, options) => {
    if (options.method === 'PUT') throw timeoutError();
    if (options.method === 'GET') {
      reads += 1;
      return remote;
    }
    throw new Error(`Unexpected method ${options.method}`);
  });

  const writing = harness.context.jsonRequest(
    '/api/analysis-drafts/workspace-1', put(payload));
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(reads, 1);
  assert.equal(harness.scheduled.length, 1);
  assert.equal(harness.scheduled[0].delay, 100);
  remote = { version: 0, payload };
  harness.scheduled.shift().callback();

  const result = await writing;
  assert.deepEqual(result, remote);
  assert.equal(reads, 2);
  assert.equal(harness.events[0].detail.attempts, 2);
});

test('converts a divergent concurrent initial draft into a real conflict', async () => {
  const local = { businessText: 'Local draft' };
  const remote = { version: 0, payload: { businessText: 'Other tab draft' } };
  const original = timeoutError();
  const harness = createHarness(async (url, options) => {
    if (options.method === 'PUT') throw original;
    if (options.method === 'GET') return remote;
    throw new Error(`Unexpected method ${options.method}`);
  });

  await assert.rejects(
    harness.context.jsonRequest(
      '/api/analysis-drafts/workspace-1', put(local)),
    error => error.status === 409
      && error.code === 'DRAFT_RECOVERY_CONFLICT'
      && error.expectedVersion === null
      && error.currentVersion === 0
      && error.cause === original
  );
  assert.equal(harness.events.length, 0);
});

test('does not reinterpret an ordinary optimistic-lock conflict', async () => {
  const conflict = Object.assign(new Error('Conflict'), {
    status: 409,
    code: 'HTTP_ERROR'
  });
  let reads = 0;
  const harness = createHarness(async (url, options) => {
    if (options.method === 'GET') reads += 1;
    throw conflict;
  });

  await assert.rejects(
    harness.context.jsonRequest(
      '/api/analysis-drafts/workspace-1', put({ businessText: 'Local' })),
    error => error === conflict
  );
  assert.equal(reads, 0);
  assert.equal(harness.events.length, 0);
});
