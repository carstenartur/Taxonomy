import assert from 'node:assert/strict';
import test from 'node:test';
import { classifyWebKitPollCancellation } from './ui-navigation-poll-evidence.mjs';

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
