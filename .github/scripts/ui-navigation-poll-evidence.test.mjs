import assert from 'node:assert/strict';
import test from 'node:test';
import { classifyWebKitPollCancellation, createConsoleErrorLedger, createNavigationPollEvidence } from './ui-navigation-poll-evidence.mjs';

test('the passive request observer captures the application workspace-routing header', async () => {
  const handlers = {};
  const page = { on: (name, fn) => { handlers[name] = fn; }, url: () => reloadUrl,
    evaluate: async () => ({ epoch: oldEpoch, url: reloadUrl }) };
  const context = { exposeBinding: async () => {}, addInitScript: async () => {} };
  const observer = createNavigationPollEvidence({ browserName: 'webkit', baseUrl });
  await observer.attach(page, context);
  handlers.request({ url: () => baseUrl + 'api/context/current', method: () => 'GET',
    headers: () => ({ 'x-taxonomy-workspace-id': 'workspace-1' }), redirectedFrom: () => null });
  assert.equal(observer.snapshot().reads[0].workspaceId, 'workspace-1');
});

test('a consumed reload proof cannot remove a later identical unproved error', () => {
  const errors = [], reconciled = [], ledger = createConsoleErrorLedger(errors, reconciled);
  const message = 'same native error text';
  const first = ledger.record(message), firstProof = { errorId: first, message, reason: 'proven-reload', epoch: 1 };
  ledger.reconcile([firstProof]);
  const second = ledger.record(message);
  ledger.reconcile([firstProof]);
  assert.deepEqual(errors, [message]);
  assert.deepEqual(reconciled, [firstProof]);
  assert.notEqual(first, second);
  ledger.reconcile([{ errorId: second, message, reason: 'proven-reload', epoch: 2 }]);
  assert.deepEqual(errors, []);
  assert.deepEqual(reconciled.map(proof => proof.epoch), [1, 2]);
});

test('removing one proven error preserves identities and ordering of other live errors', () => {
  const errors = [], reconciled = [], ledger = createConsoleErrorLedger(errors, reconciled);
  const first = ledger.record('unproved'), second = ledger.record('proved'), third = ledger.record('last');
  ledger.reconcile([{ errorId: second, message: 'proved', reason: 'proof' }]);
  assert.deepEqual(errors, ['unproved', 'last']);
  ledger.reconcile([{ errorId: third, message: 'wrong text', reason: 'proof' }]);
  assert.deepEqual(errors, ['unproved', 'last']);
  ledger.reconcile([{ errorId: third, message: 'last', reason: 'proof' }]);
  assert.deepEqual(errors, ['unproved']);
  assert.equal(reconciled.some(proof => proof.errorId === first), false);
});

const baseUrl = 'http://127.0.0.1:8091/';
const oldUrl = `${baseUrl}?architectureSource=analysis&repositoryId=f2edadfe-395b-4230-bbe8-62921500d68f&workspaceId=0c099050-b36e-4c38-8798-7b19b4cd6aef&workspaceScopeKey=0c099050-b36e-4c38-8798-7b19b4cd6aef&branch=draft#analyze`;
const destination = new URL(oldUrl);
destination.searchParams.set('lang', 'en');
const newUrl = destination.href;
const oldEpoch = 1793730500000;
const newEpoch = 1793730600900;
const endpoints = ['/api/status/startup', '/api/workspace/sync-state'];

// The native name/message/stack fields are copied from the unchanged application's
// full-passive/passive-trace.json reproduction on 2026-11-03. These unit tests
// replay that signature; their navigation/read/render proofs are controlled
// boundary fixtures, not a replacement for the separate real WebKit reproduction.
const nativeErrors = {
  '/api/status/startup': {
    name: 'Fetch API cannot load http',
    message: '/127.0.0.1:8091/api/status/startup due to access control checks.',
    stack: 'Fetch API cannot load http://127.0.0.1:8091/api/status/startup due to access control checks.\n'
      + '    at routedFetch (http://127.0.0.1:8091/js/core/taxonomy-analysis-session-core.js:273:39)\n'
      + '    at routedFetch (http://127.0.0.1:8091/js/core/taxonomy-analysis-session-core.js:273:39)\n'
      + '    at unknown (http://127.0.0.1:8091/#analyze:2736:60)'
  },
  '/api/workspace/sync-state': {
    name: 'Fetch API cannot load http',
    message: '/127.0.0.1:8091/api/workspace/sync-state due to access control checks.',
    stack: 'Fetch API cannot load http://127.0.0.1:8091/api/workspace/sync-state due to access control checks.\n'
      + '    at routedFetch (http://127.0.0.1:8091/js/core/taxonomy-analysis-session-core.js:273:39)\n'
      + '    at routedFetch (http://127.0.0.1:8091/js/core/taxonomy-analysis-session-core.js:273:39)\n'
      + '    at pollSyncState (http://127.0.0.1:8091/js/workspace/taxonomy-workspace-sync.js:49:14)'
  }
};

function fixture(endpoint) {
  const payload = endpoint === endpoints[0]
    ? { initialized: true, status: 'READY', memory: {
      heapUsagePercent: 38, heapUsedMB: 195, heapMaxMB: 512, threadCount: 30
    } }
    : { syncStatus: 'UP_TO_DATE', unpublishedCommitCount: 0,
      workspaceId: '0c099050-b36e-4c38-8798-7b19b4cd6aef', branch: 'draft',
      lastSyncTimestamp: null, lastPublishTimestamp: null };
  return {
    browserName: 'webkit', baseUrl,
    error: { kind: 'page-error', ...nativeErrors[endpoint], pageUrl: oldUrl,
      documentEpoch: oldEpoch, sequence: 30 },
    navigation: { locale: 'en', oldDocument: { url: oldUrl, epoch: oldEpoch },
      newDocument: { url: newUrl, epoch: newEpoch },
      startSequence: 10, endSequence: 60, completed: true },
    events: [
      { type: 'beforeunload', documentEpoch: oldEpoch, url: oldUrl, sequence: 20 },
      { type: 'pagehide', documentEpoch: oldEpoch, url: oldUrl, sequence: 40 }
    ],
    reads: [{ id: 'fresh-read', sequence: 50, url: new URL(endpoint, baseUrl).href, method: 'GET',
      documentEpoch: newEpoch, pageUrl: newUrl, finished: true, status: 200,
      jsonParsed: true, payload, redirected: false, failed: false }],
    observation: { documentEpoch: newEpoch, url: newUrl.replace('#analyze', '#admin'),
      startup: { text: '🟢 Ready | Heap: 38% (195/512 MB) | Threads: 30', writeCount: 1 },
      sync: { state: structuredClone(payload), indicatorText: '│ Sync Up to date',
        panelText: 'Up to date\nLast synced: —', writeCount: 1 },
      messages: { 'fresh-read': { ready: 'Ready', loading: 'Loading …', threads: 'Threads',
        syncIndicator: 'Up to date', syncPanel: 'Up to date' } } }
  };
}

function assertReconciled(input, endpoint) {
  const before = structuredClone(input);
  const result = classifyWebKitPollCancellation(input);
  assert.ok(result, 'the native cancellation needs complete, matching destination evidence');
  assert.equal(result.reason, 'webkit-provisional-navigation-poll-cancelled');
  assert.equal(result.endpoint, endpoint);
  assert.equal(result.locale, 'en');
  assert.equal(result.requestId, 'fresh-read');
  assert.deepEqual(result.oldDocument, input.navigation.oldDocument);
  assert.deepEqual(result.newDocument, input.navigation.newDocument);
  assert.deepEqual(result.rawError, input.error, 'preserve the original native error and attribution');
  assert.deepEqual(input, before, 'classification must not rewrite its source evidence');
}

for (const endpoint of endpoints) {
  test(`${endpoint}: real native signature reconciles only with fresh body and adopted render`, () => {
    assertReconciled(fixture(endpoint), endpoint);
  });
}

const rejectedBoundaries = [
  ['another browser', x => { x.browserName = 'chromium'; }],
  ['console text instead of a native page-error', x => { x.error.kind = 'console-error'; }],
  ['ordinary JavaScript error name', x => { x.error.name = 'TypeError'; }],
  ['incomplete native signature', x => { x.error.stack = ''; }],
  ['different error origin', x => {
    x.error.message = x.error.message.replace('127.0.0.1', 'other.invalid');
    x.error.stack = x.error.stack.replaceAll('127.0.0.1', 'other.invalid');
  }],
  ['different error port', x => {
    x.error.message = x.error.message.replace(':8091', ':8092');
    x.error.stack = x.error.stack.replaceAll(':8091', ':8092');
  }],
  ['additional endpoint query', x => {
    x.error.message = x.error.message.replace(' due to', '?extra=1 due to');
    x.error.stack = x.error.stack.replace(' due to', '?extra=1 due to');
  }],
  ['missing beforeunload', x => { x.events = x.events.filter(e => e.type !== 'beforeunload'); }],
  ['missing pagehide', x => { x.events = x.events.filter(e => e.type !== 'pagehide'); }],
  ['lifecycle from a different document', x => { x.events[0].documentEpoch = newEpoch; }],
  ['error from a different document', x => { x.error.documentEpoch = newEpoch; }],
  ['error from a different old URL', x => { x.error.pageUrl = newUrl; }],
  ['error before the navigation window', x => { x.error.sequence = 9; }],
  ['error before beforeunload', x => { x.error.sequence = 19; }],
  ['error after pagehide', x => { x.error.sequence = 41; }],
  ['error after navigation completion', x => { x.navigation.endSequence = 29; }],
  ['uncompleted or cancelled navigation', x => { x.navigation.completed = false; }],
  ['same document masquerading as a new page', x => { x.navigation.newDocument.epoch = oldEpoch; }],
  ['wrong destination locale', x => { x.navigation.newDocument.url = newUrl.replace('lang=en', 'lang=de'); }],
  ['changed source pins on destination', x => {
    const changed = new URL(newUrl); changed.searchParams.set('workspaceId', 'other-workspace');
    x.navigation.newDocument.url = changed.href;
  }],
  ['non-GET destination request', x => { x.reads[0].method = 'POST'; }],
  ['different destination endpoint', x => { x.reads[0].url = `${baseUrl}api/ai-status`; }],
  ['destination HTTP 403', x => { x.reads[0].status = 403; }],
  ['failed destination request', x => { x.reads[0].failed = true; }],
  ['headers received but response body unfinished', x => { x.reads[0].finished = false; }],
  ['body cannot be parsed as JSON', x => { x.reads[0].jsonParsed = false; }],
  ['missing JSON body', x => { x.reads[0].payload = null; }],
  ['redirected destination request', x => { x.reads[0].redirected = true; }],
  ['read from the old document', x => { x.reads[0].documentEpoch = oldEpoch; }],
  ['read started before the navigation', x => { x.reads[0].sequence = 9; }],
  ['read without a received start sequence', x => { delete x.reads[0].sequence; }],
  ['read from another page URL', x => { x.reads[0].pageUrl = oldUrl; }],
  ['observation from the old document', x => { x.observation.documentEpoch = oldEpoch; }],
  ['observation from another destination', x => { x.observation.url = oldUrl; }],
  ['missing translation evidence', x => { x.observation.messages = {}; }],
  ['no new DOM write', x => { x.observation.startup.writeCount = 0; x.observation.sync.writeCount = 0; }],
  ...['error', 'unhandledrejection', 'securitypolicyviolation'].map(type => [
    `native ${type} in the affected old document`, x => {
      x.events.push({ type, documentEpoch: oldEpoch, url: oldUrl, sequence: 31 });
    }
  ])
];

// The two native signatures were recorded in the mobile-admin-webkit artifact
// from run 42240324789, during the editor's revision-16 reload. Destination
// bodies/renders are controlled proofs here; the browser driver records its own.
const reloadUrl = `${baseUrl}architecture/editor?lang=en&repositoryId=repository-1&workspaceId=workspace-1&workspaceScopeKey=workspace-1&branch=draft&revision=16`;
const reloadEndpoints = ['/api/git/state?branch=draft', '/api/context/current'];
function reloadFixture(endpoint) {
  const input = fixture(endpoints[0]);
  const context = { contextId: 'context-1', branch: 'draft', commitId: 'a'.repeat(40),
    mode: 'EDITABLE', timestamp: '2026-11-06T15:18:30Z', dirty: false };
  const git = { currentBranch: 'draft', headCommit: 'a'.repeat(40), projectionStale: false,
    indexStale: false, branches: [{ name: 'draft' }], totalCommits: 7, operationInProgress: false };
  Object.assign(input.error, { pageUrl: reloadUrl, name: 'Fetch API cannot load http',
    message: `/127.0.0.1:8091${endpoint} due to access control checks.`,
    stack: `Fetch API cannot load http://127.0.0.1:8091${endpoint} due to access control checks.\n    at poll (http://127.0.0.1:8091/js/poll.js:51:14)` });
  input.navigation.kind = 'reload';
  input.navigation.oldDocument.url = reloadUrl;
  input.navigation.newDocument.url = reloadUrl;
  input.events.forEach(event => { event.url = reloadUrl; });
  Object.assign(input.reads[0], { url: new URL(endpoint, baseUrl).href, pageUrl: reloadUrl,
    workspaceId: 'workspace-1', payload: endpoint === reloadEndpoints[0] ? git : context });
  Object.assign(input.observation, { url: reloadUrl,
    git: { text: '🔀 draft @ aaaaaaa │ Projection: Fresh │ Index: Fresh │ 1 variant │ 7 versions', writeCount: 1 },
    context: { state: structuredClone(context), branchText: 'Draft', modeText: '🟢 Editable',
      rendered: true, writeCount: 1 } });
  Object.assign(input.observation.messages['fresh-read'], { gitProjection: 'Projection', gitIndex: 'Index',
    gitProjectionState: 'Fresh', gitIndexState: 'Fresh', gitVariants: '1 variant', gitVersions: '7 versions',
    gitOperation: 'Operation running', contextBranch: 'Draft', contextMode: '🟢 Editable' });
  return input;
}

for (const endpoint of reloadEndpoints) {
  test(`${endpoint}: an explicit editor reload requires a fresh matching adopted response`, () => {
    assertReconciled(reloadFixture(endpoint), endpoint);
  });
  const failures = [
    ...rejectedBoundaries.filter(([name]) => !['wrong destination locale', 'no new DOM write',
      'error from a different old URL', 'read from another page URL', 'observation from another destination'].includes(name)),
    ['undeclared navigation kind', x => { x.navigation.kind = 'other'; }],
    ['a language navigation cannot use reload proof', x => { x.navigation.kind = 'locale'; }],
    ['changed destination fragment', x => { x.navigation.newDocument.url += '#other'; }],
    ['changed destination language', x => { x.navigation.newDocument.url = reloadUrl.replace('lang=en', 'lang=de'); }],
    ['changed revision pin', x => { x.navigation.newDocument.url = reloadUrl.replace('revision=16', 'revision=17'); }],
    ['different error page', x => { x.error.pageUrl = `${baseUrl}projects`; }],
    ['different destination request page', x => { x.reads[0].pageUrl = `${baseUrl}projects`; }],
    ['different observed page', x => { x.observation.url += '#other'; }],
    ['missing workspace request header', x => { delete x.reads[0].workspaceId; }],
    ['different workspace request header', x => { x.reads[0].workspaceId = 'other-workspace'; }],
    ['changed adopted context branch', x => { x.observation.context.state.branch = 'other'; }],
    ['no fresh git/context DOM writes', x => { x.observation.git.writeCount = 0; x.observation.context.writeCount = 0; }],
    ['real error in the new document', x => { x.events.push({ type: 'error', documentEpoch: newEpoch, sequence: 61 }); }],
    ['real old request failure', x => { x.reads.push({ ...x.reads[0], documentEpoch: oldEpoch,
      pageUrl: reloadUrl, sequence: 25, failed: true, finished: false, jsonParsed: false }); }],
    ['later destination HTTP failure', x => { x.reads.push({ ...x.reads[0], sequence: 70, status: 403 }); }]
  ];
  for (const [boundary, change] of failures) {
    test(`${endpoint}: reload ${boundary} remains blocking`, () => {
      const input = reloadFixture(endpoint); change(input);
      assert.equal(classifyWebKitPollCancellation(input), null);
    });
  }
}

for (const [boundary, change] of [
  ['wrong response branch', x => { x.reads[0].payload.currentBranch = 'other'; }],
  ['unrendered HEAD', x => { x.reads[0].payload.headCommit = 'b'.repeat(40); }],
  ['unrendered projection state', x => { x.observation.messages['fresh-read'].gitProjectionState = 'Stale'; }],
  ['unrendered version count', x => { x.observation.messages['fresh-read'].gitVersions = '8 versions'; }],
  ['unrendered operation warning', x => { x.reads[0].payload.operationInProgress = true; }],
  ['missing translated label', x => { x.observation.messages['fresh-read'].gitProjection = 'git.projection'; }]
]) test(`git reload rejects ${boundary}`, () => {
  const input = reloadFixture(reloadEndpoints[0]); change(input);
  assert.equal(classifyWebKitPollCancellation(input), null);
});

for (const [boundary, change] of [
  ['unadopted context identity', x => { x.reads[0].payload.contextId = 'other'; }],
  ['unadopted commit', x => { x.reads[0].payload.commitId = 'b'.repeat(40); }],
  ['unrendered branch', x => { x.observation.context.branchText = 'Other'; }],
  ['unrendered mode', x => { x.observation.context.modeText = 'Read only'; }],
  ['missing rendered marker', x => { x.observation.context.rendered = false; }],
  ['missing translation', x => { x.observation.messages['fresh-read'].contextMode = 'context.mode.editable'; }]
]) test(`context reload rejects ${boundary}`, () => {
  const input = reloadFixture(reloadEndpoints[1]); change(input);
  assert.equal(classifyWebKitPollCancellation(input), null);
});

for (const endpoint of endpoints) {
  for (const [boundary, change] of rejectedBoundaries) {
    test(`${endpoint}: ${boundary} remains a blocking error`, () => {
      const input = fixture(endpoint);
      change(input);
      assert.equal(classifyWebKitPollCancellation(input), null);
    });
  }
}

test('a real failed request in the old navigation window cannot be hidden by a fresh success', () => {
  for (const endpoint of endpoints) {
    const input = fixture(endpoint);
    input.reads.push({ ...structuredClone(input.reads[0]), id: 'failed-old-read',
      documentEpoch: oldEpoch, pageUrl: oldUrl, sequence: 25,
      failed: true, finished: false, jsonParsed: false, status: null, payload: null });
    assert.equal(classifyWebKitPollCancellation(input), null);
  }
});

test('startup requires the entire freshly rendered payload, not merely a Ready label', () => {
  for (const text of ['Ready', '🟢 Ready | Heap: 37% (195/512 MB) | Threads: 30',
    '🟢 Ready | Heap: 38% (195/512 MB) | Threads: 29']) {
    const input = fixture(endpoints[0]);
    input.observation.startup.text = text;
    assert.equal(classifyWebKitPollCancellation(input), null);
  }
});

test('startup accepts a freshly rendered initializing phase with the matching yellow threshold', () => {
  const input = fixture(endpoints[0]);
  Object.assign(input.reads[0].payload, { initialized: false, phaseMessage: 'Loading taxonomy' });
  input.reads[0].payload.memory.heapUsagePercent = 85;
  input.observation.startup.text = '🟡 Loading taxonomy | Heap: 85% (195/512 MB) | Threads: 30';
  assertReconciled(input, endpoints[0]);
});

test('sync requires full state adoption even when the displayed summary is unchanged', () => {
  const input = fixture(endpoints[1]);
  input.observation.sync.state.workspaceId = 'stale-workspace';
  assert.equal(classifyWebKitPollCancellation(input), null);
});

test('sync requires both the indicator and panel to match the adopted status', () => {
  for (const field of ['indicatorText', 'panelText']) {
    const input = fixture(endpoints[1]);
    input.observation.sync[field] = 'Behind';
    assert.equal(classifyWebKitPollCancellation(input), null);
  }
});

test('a later unadopted body does not displace the actual freshly adopted response', () => {
  const input = fixture(endpoints[0]);
  const unadopted = structuredClone(input.reads[0]);
  unadopted.id = 'later-unadopted-read';
  unadopted.payload.memory.heapUsedMB = 196;
  input.reads.push(unadopted);
  input.observation.messages[unadopted.id] = structuredClone(input.observation.messages['fresh-read']);
  assertReconciled(input, endpoints[0]);
});
