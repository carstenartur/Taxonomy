import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { classifyWebKitPollCancellation, classifyPortfolioSnapshotPollCancellation, createConsoleErrorLedger, createNavigationPollEvidence } from './ui-navigation-poll-evidence.mjs';

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

test('the passive observer retains original project job and snapshot response evidence', async () => {
  const handlers = {};
  const page = { on: (name, fn) => { handlers[name] = fn; }, url: () => reloadUrl,
    evaluate: async () => ({ epoch: oldEpoch, url: reloadUrl }) };
  const context = { exposeBinding: async () => {}, addInitScript: async () => {} };
  const observer = createNavigationPollEvidence({ browserName: 'webkit', baseUrl });
  await observer.attach(page, context);
  for (const endpoint of ['analysis-jobs', 'analysis-jobs/job-1', 'architecture-workbench/snapshot-1']) {
    const url = `${baseUrl}api/projects/1/${endpoint}?repositoryId=repo-1&workspaceId=workspace-1&workspaceScopeKey=workspace-1&branch=draft`;
    const payload = { endpoint };
    const request = { url: () => url, method: () => 'GET',
      headers: () => ({ 'x-taxonomy-workspace-id': 'workspace-1' }), redirectedFrom: () => null,
      response: async () => response };
    const response = { request: () => request, status: () => 200,
      headers: () => ({ 'content-type': 'application/json' }), json: async () => payload };
    handlers.request(request);
    handlers.response(response);
    handlers.requestfinished(request);
  }
  await new Promise(resolve => setImmediate(resolve));
  const reads = observer.snapshot().reads;
  assert.equal(reads.length, 3, 'The existing observer must capture the original transports without replacement requests');
  for (const read of reads) {
    assert.equal(read.documentEpoch, oldEpoch);
    assert.equal(read.workspaceId, 'workspace-1');
    assert.equal(read.finished, true);
    assert.equal(read.jsonParsed, true);
    assert.ok(read.sequence < read.finishedSequence && read.finishedSequence < read.parsedSequence,
      'A source proof must distinguish a started read from its completed original response body');
    assert.equal(new URL(read.url).pathname.endsWith(read.payload.endpoint), true);
  }
});

test('an explicitly declared snapshot transition retains its two document observations', async () => {
  const declaration = { locale: 'en', projectId: 1, jobId: 'job-1', snapshotId: 'snapshot-1' };
  const oldDocument = { epoch: oldEpoch, url: baseUrl + 'projects?projectId=1' };
  const newDocument = { epoch: newEpoch, url: baseUrl + '?architectureSource=snapshot&snapshotId=snapshot-1#architecture' };
  const sourceObservation = { documentEpoch: oldEpoch, url: oldDocument.url, job: { heading: 'Completed job' } };
  const observation = { documentEpoch: newEpoch, url: newDocument.url, snapshot: { rendered: true } };
  const results = [oldDocument, oldDocument, sourceObservation, newDocument, observation];
  const page = { on() {}, url: () => oldDocument.url, evaluate: async () => results.shift() };
  const observer = createNavigationPollEvidence({ browserName: 'webkit', baseUrl });
  await observer.attach(page, { exposeBinding: async () => {}, addInitScript: async () => {} });
  await observer.begin('en', 'portfolio-snapshot', declaration);
  await observer.end('en', true, 'portfolio-snapshot');
  await observer.verifyDestination('en', 'portfolio-snapshot');
  const navigation = observer.snapshot().navigations[0];
  assert.deepEqual(navigation.declaration, declaration);
  assert.deepEqual(navigation.sourceObservation, sourceObservation);
  assert.deepEqual(navigation.newDocument, newDocument);
  assert.deepEqual(navigation.observation, observation);
  assert.equal(navigation.completed, true);
  assert.equal(results.length, 0, 'Both observations must come from the actual selected document');
});

test('snapshot render evidence follows the visible canvas inside its display-contents wrapper', async () => {
  let epoch = oldEpoch, url = baseUrl + 'projects?projectId=1';
  const canvas = { getClientRects: () => [{}] };
  const surface = { hidden: false, getClientRects: () => [],
    querySelector: selector => selector === '#architectureCanvas' ? canvas : null,
    querySelectorAll: () => [{}] };
  const nodes = { architectureSnapshotSource: surface, requirementText: { textContent: 'Frozen source' },
    workflowState: { textContent: 'Saved analysis snapshot-1' } };
  const page = { on() {}, url: () => url, evaluate: async (fn, input) => {
    if (fn.name === 'installDocumentObserver') return { epoch, url };
    return vm.runInNewContext('(' + fn.toString() + ')(input)', {
      input, performance: { timeOrigin: epoch }, location: { href: url },
      document: { getElementById: id => nodes[id] },
      window: { localStorage: { getItem: () => '1' },
        TaxonomyArchitectureWorkflow: { getScope: () => ({}) },
        TaxonomyArchitectureWorkspace: { isActive: () => true,
          getSource: () => ({ kind: 'snapshot' }), getData: () => ({ snapshotId: 'snapshot-1' }) } }
    });
  } };
  const observer = createNavigationPollEvidence({ browserName: 'webkit', baseUrl });
  await observer.attach(page, { exposeBinding: async () => {}, addInitScript: async () => {} });
  await observer.begin('en', 'portfolio-snapshot', { jobId: 'job-1', requirementKey: 'REQ-1' });
  epoch = newEpoch; url = baseUrl + '?architectureSource=snapshot&snapshotId=snapshot-1#architecture';
  await observer.end('en', true, 'portfolio-snapshot');
  await observer.verifyDestination('en', 'portfolio-snapshot');
  const snapshot = observer.snapshot().navigations[0].observation.snapshot;
  assert.equal(snapshot.rendered, true, 'The production display:contents wrapper has no box even while its snapshot is rendered');
  assert.equal(snapshot.nodeCount, 1);
  assert.equal(snapshot.data.snapshotId, 'snapshot-1');
  canvas.getClientRects = () => [];
  await observer.verifyDestination('en', 'portfolio-snapshot');
  assert.equal(observer.snapshot().navigations[0].observation.snapshot.rendered, false,
    'A hidden canvas cannot supply rendered-snapshot evidence');
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

const retainedWorkspace = destination.searchParams.get('workspaceId');
const scopedSync = `${endpoints[1]}?workspaceId=${retainedWorkspace}`;

function scopedSyncFixture() {
  const input = fixture(endpoints[1]);
  input.error.message = input.error.message.replace(endpoints[1], scopedSync);
  input.error.stack = input.error.stack.replace(endpoints[1], scopedSync);
  return input;
}

test('a cancelled scoped sync poll reconciles after the new document adopts its bootstrap read', () => {
  assertReconciled(scopedSyncFixture(), scopedSync);
});

test('a scoped sync replacement must carry the retained workspace pin in both URL and header', () => {
  const input = scopedSyncFixture();
  input.reads[0].url = new URL(scopedSync, baseUrl).href;
  input.reads[0].workspaceId = retainedWorkspace;
  assertReconciled(input, scopedSync);
  input.reads[0].workspaceId = 'other-workspace';
  assert.equal(classifyWebKitPollCancellation(input), null);
});

test('a foreign workspace or extra query in the native error cannot reconcile', () => {
  for (const badEndpoint of [
    `${endpoints[1]}?workspaceId=other-workspace`,
    `${scopedSync}&workspaceId=${retainedWorkspace}`,
    `${scopedSync}&other=1`
  ]) {
    const input = scopedSyncFixture();
    input.error.message = input.error.message.replace(scopedSync, badEndpoint);
    input.error.stack = input.error.stack.replace(scopedSync, badEndpoint);
    assert.equal(classifyWebKitPollCancellation(input), null);
  }
});

test('an actual HTTP denial on either sync URL remains an audit failure', () => {
  for (const endpoint of [endpoints[1], scopedSync]) {
    const input = scopedSyncFixture();
    input.reads.push({ ...input.reads[0], url: new URL(endpoint, baseUrl).href, status: 403 });
    assert.equal(classifyWebKitPollCancellation(input), null);
  }
});

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
  test(`${endpoint}: a retained workspace pin still permits an exactly adopted canonical response`, () => {
    const input = reloadFixture(endpoint);
    const url = new URL(input.reads[0].url);
    url.searchParams.set('workspaceId', 'workspace-1');
    input.reads[0].url = url.href;
    assertReconciled(input, endpoint);
  });
  for (const [boundary, mutate] of [
    ['foreign workspace query', url => url.searchParams.set('workspaceId', 'foreign-workspace')],
    ['duplicate workspace query', url => url.searchParams.append('workspaceId', 'workspace-1')],
    ['unexpected query', url => url.searchParams.set('insecure', 'true')],
    ['missing branch query', url => url.searchParams.delete('branch')]
  ]) {
    test(`${endpoint}: a pinned reload rejects ${boundary}`, () => {
      const input = reloadFixture(endpoint);
      const url = new URL(input.reads[0].url);
      url.searchParams.set('workspaceId', 'workspace-1');
      mutate(url);
      input.reads[0].url = url.href;
      if (boundary === 'missing branch query' && endpoint === reloadEndpoints[1]) return;
      assert.equal(classifyWebKitPollCancellation(input), null);
    });
  }
  test(`${endpoint}: a failed pinned request vetoes a later correct response`, () => {
    const input = reloadFixture(endpoint);
    const url = new URL(input.reads[0].url);
    url.searchParams.set('workspaceId', 'workspace-1');
    input.reads[0].url = url.href;
    input.reads.push({...structuredClone(input.reads[0]), id:'failed-pinned-response',
      documentEpoch:oldEpoch, sequence:25, failed:'net::ERR_ABORTED', status:null,
      finished:false, jsonParsed:false, payload:null});
    assert.equal(classifyWebKitPollCancellation(input), null);
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

// The existing Maven-owned navigation contract also owns snapshot departures.
{
  const baseUrl = 'http://127.0.0.1:8080/';
  const repository = '16f78e7f-1375-4fa1-8c71-6b6470edc63f';
  const workspace = 'd4cbca86-5cbd-428e-9ae6-9cba6a51a780';
  const snapshotId = '48777a08-9626-41ca-9fb1-99ae0f7887f3';
  const jobId = 'f41d287c-6534-4aae-a238-f3e65f445a1d';
  const oldEpoch = 1793991858497, newEpoch = 1793991862901;
  const sourceUrl = `${baseUrl}projects?projectId=1&repositoryId=${repository}&workspaceId=${workspace}&branch=draft&lang=en`;
  const destinationUrl = `${baseUrl}?architectureSource=snapshot&projectId=1&snapshotId=${snapshotId}&repositoryId=${repository}&workspaceId=${workspace}&branch=draft&lang=en#architecture`;
  const discoveryUrl = `${baseUrl}api/projects/1/analysis-jobs?workspaceId=${workspace}&repositoryId=${repository}&branch=draft&workspaceScopeKey=${workspace}`;
  const jobUrl = `${baseUrl}api/projects/1/analysis-jobs/${jobId}`;
  const sourceReadUrl = `${jobUrl}?workspaceId=${workspace}&repositoryId=${repository}&branch=draft&workspaceScopeKey=${workspace}`;
  const destinationReadUrl = `${baseUrl}api/projects/1/architecture-workbench/${snapshotId}?repositoryId=${repository}&workspaceScopeKey=${workspace}&workspaceId=${workspace}&branch=draft`;
  const requirementText = 'Provide secure resilient command information exchange with auditable decisions.';

  // The native error is copied from the unchanged 055e83f mobile-admin WebKit
  // report. The job, response bodies, DOM observations and sequence bounds below
  // are controlled boundary fixtures. They do not reclassify that failed report
  // or stand in for a fresh browser run with its original requests observed.
  const nativeError = {
    kind: 'page-error', name: 'Fetch API cannot load http',
    message: `/127.0.0.1:8080/api/projects/1/analysis-jobs?workspaceId=${workspace}&repositoryId=${repository}&branch=draft&workspaceScopeKey=${workspace} due to access control checks.`,
    stack: `Fetch API cannot load ${discoveryUrl} due to access control checks.\n`
      + '    at portfolioAwareFetch (http://127.0.0.1:8080/js/portfolio/taxonomy-portfolio-async.js:167:49)\n'
      + '    at getResponse (http://127.0.0.1:8080/js/api/portfolio-api.js:59:37)\n'
      + '    at getJson (http://127.0.0.1:8080/js/api/portfolio-api.js:68:49)\n'
      + '    at synchronizeCurrentProjectJobs (http://127.0.0.1:8080/js/portfolio/portfolio-analysis-job-synchronizer.js:77:52)',
    documentEpoch: oldEpoch, pageUrl: sourceUrl, sequence: 30
  };

  function fixture() {
    const job = {
      id: jobId, projectId: 1, status: 'SUCCESS', idempotencyKey: 'adoption-fixture',
      provider: 'GEMINI', maxArchitectureNodes: 20, requestedBy: 'admin', workspaceId: workspace,
      createdAt: '2026-11-06T17:17:39Z', startedAt: '2026-11-06T17:17:39Z', completedAt: '2026-11-06T17:17:41Z',
      totalItems: 1, successfulItems: 1, partialItems: 0, failedItems: 0, errorSummary: null,
      items: [{ id: 1, requirementId: 1, requirementKey: 'REQ-ADOPT', requirementVersionId: 1,
        requirementVersionNumber: 1, status: 'SUCCESS', snapshotId, attempt: 1,
        startedAt: '2026-11-06T17:17:39Z', completedAt: '2026-11-06T17:17:41Z', errorMessage: null }],
      providerBinding: null, analysisReasonLength: null, analysisLanguage: null
    };
    const projection = {
      projectId: 1, projectKey: 'ADOPT-FIXTURE', projectTitle: 'Frozen snapshot adoption',
      requirementId: 1, requirementKey: 'REQ-ADOPT', requirementTitle: 'Secure command exchange', requirementText,
      snapshotId, snapshotStatus: 'SUCCESS', snapshotCreatedAt: '2026-11-06T17:17:41Z',
      provider: 'GEMINI', modelName: 'mock', workspaceId: workspace, branchName: 'draft', commitSha: 'a'.repeat(40),
      diagram: { title: 'Secure command exchange', nodes: [{ id: 'C1', label: 'Command information' }], edges: [] },
      scene: { nodes: [{ id: 'C1', label: 'Command information', x: 0, y: 0, width: 200, height: 70 }], edges: [] },
      elements: {}, relations: {}, warnings: [],
      exportProvenance: { requirementVersionId: 1, taxonomyFingerprint: 'b'.repeat(64), repositoryId: repository,
        requirementVersionCreatedBy: 'admin', requirementVersionCreatedAt: '2026-11-06T17:17:38Z' },
      policyTitleKey: null, persistedViewTitle: null, nodeReasons: {}, relationReasons: {}
    };
    return {
      browserName: 'webkit', baseUrl, error: structuredClone(nativeError),
      navigation: {
        kind: 'portfolio-snapshot', locale: 'en', completed: true, startSequence: 10, endSequence: 60,
        oldDocument: { epoch: oldEpoch, url: sourceUrl }, newDocument: { epoch: newEpoch, url: destinationUrl },
        declaration: { projectId: 1, requirementId: 1, requirementKey: 'REQ-ADOPT', requirementText,
          jobId, jobStatus: 'SUCCESS', itemStatus: 'SUCCESS', snapshotId,
          repositoryId: repository, workspaceId: workspace, branch: 'draft', locale: 'en' },
        sourceObservation: { documentEpoch: oldEpoch, url: sourceUrl,
          scope: { repositoryId: repository, workspaceId: workspace, workspaceScopeKey: workspace, branch: 'draft' },
          projectId: '1', job: { url: jobUrl, heading: 'f41d287c-653 · Successful',
            item: { key: 'REQ-ADOPT', status: 'Successful', snapshot: '48777a08-962' }, warnings: 0 } }
      },
      events: [
        { type: 'beforeunload', documentEpoch: oldEpoch, url: sourceUrl, sequence: 20 },
        { type: 'pagehide', documentEpoch: oldEpoch, url: sourceUrl, sequence: 40 },
        { type: 'document-start', documentEpoch: newEpoch, url: destinationUrl, sequence: 45 }
      ],
      reads: [
        { id: 'source-job', sequence: 5, finishedSequence: 6, parsedSequence: 7,
          url: sourceReadUrl, method: 'GET', documentEpoch: oldEpoch, pageUrl: sourceUrl,
          workspaceId: null, status: 200, finished: true, jsonParsed: true, failed: false, redirected: false, payload: job },
        { id: 'destination-snapshot', sequence: 50, finishedSequence: 55, parsedSequence: 56,
          url: destinationReadUrl, method: 'GET', documentEpoch: newEpoch, pageUrl: destinationUrl,
          workspaceId: workspace, status: 200, finished: true, jsonParsed: true, failed: false, redirected: false,
          payload: projection }
      ],
      observation: { documentEpoch: newEpoch, url: destinationUrl,
        snapshot: { source: { kind: 'snapshot', projectId: '1', requirementId: 1, requirementVersionId: 1,
          repositoryId: repository, workspaceId: workspace, workspaceScopeKey: workspace,
          branch: 'draft', snapshotId, element: null }, data: structuredClone(projection),
        rendered: true, nodeCount: 1, requirementText, workflowState: `Saved analysis ${snapshotId}` } }
    };
  }

  function assertProven(input) {
    const before = structuredClone(input);
    const proof = classifyPortfolioSnapshotPollCancellation(input);
    assert.ok(proof, 'the declared source-to-snapshot transition needs its original read and adopted render proof');
    assert.equal(proof.reason, 'webkit-provisional-navigation-poll-cancelled');
    assert.equal(proof.kind, 'portfolio-snapshot');
    assert.equal(proof.locale, 'en');
    assert.equal(proof.sourceRequestId, 'source-job');
    assert.equal(proof.requestId, 'destination-snapshot');
    assert.deepEqual(proof.rawError, input.error);
    assert.deepEqual(proof.oldDocument, input.navigation.oldDocument);
    assert.deepEqual(proof.newDocument, input.navigation.newDocument);
    assert.deepEqual(proof.sourceRequest, input.reads.find(read => read.id === 'source-job'));
    assert.deepEqual(proof.request, input.reads.find(read => read.id === 'destination-snapshot'));
    assert.deepEqual(proof.sourceRender, input.navigation.sourceObservation);
    assert.deepEqual(proof.render, input.observation.snapshot);
    assert.deepEqual(input, before, 'classification must preserve the immutable evidence it receives');
    return proof;
  }

  test('the canonical JSON transport retains the same completed job and destination proof', () => {
    const input = fixture();
    const oldFrame = 'getResponse (http://127.0.0.1:8080/js/api/portfolio-api.js:59:37)';
    const currentFrame = 'attempt (http://127.0.0.1:8080/js/api/taxonomy-api-client.js:555:41)';
    input.error.stack = input.error.stack.replace(oldFrame, currentFrame);
    assertProven(input);
    for (const unexpected of [currentFrame.replace('attempt', 'request'), currentFrame.replace('taxonomy-api-client', 'portfolio-api'),
        currentFrame.replace('127.0.0.1:8080', '127.0.0.1:8081')]) {
      assert.equal(classifyPortfolioSnapshotPollCancellation({...input, error:{...input.error,
        stack:input.error.stack.replace(currentFrame, unexpected)}}), null);
    }
    input.observation.snapshot.rendered = false;
    assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
  });

  test('the captured native signature needs a completed original source job and adopted snapshot', () => {
    const proof = assertProven(fixture());
    assert.equal(proof.endpoint,
      `/api/projects/1/analysis-jobs?workspaceId=${workspace}&repositoryId=${repository}&branch=draft&workspaceScopeKey=${workspace}`);
  });

  test('a completed partial job and partial snapshot retain their actual displayed statuses', () => {
    const input = fixture();
    input.navigation.declaration.jobStatus = 'PARTIAL'; input.navigation.declaration.itemStatus = 'PARTIAL';
    const job = input.reads[0].payload;
    job.status = 'PARTIAL'; job.items[0].status = 'PARTIAL'; job.successfulItems = 0; job.partialItems = 1;
    input.navigation.sourceObservation.job.heading = 'f41d287c-653 · Partial';
    input.navigation.sourceObservation.job.item.status = 'Partial';
    input.reads[1].payload.snapshotStatus = 'PARTIAL'; input.observation.snapshot.data.snapshotStatus = 'PARTIAL';
    assertProven(input);
  });

  test('destination JSON may finish after navigation commits when the verified render adopts that same body', () => {
    const input = fixture();
    input.reads[1].finishedSequence = 65; input.reads[1].parsedSequence = 66;
    assertProven(input);
  });

  test('optional derived snapshot URL pins must correspond to the original payload', () => {
    const input = fixture();
    const target = new URL(destinationUrl);
    target.searchParams.set('workspaceScopeKey', workspace);
    target.searchParams.set('requirementId', '1'); target.searchParams.set('requirementVersionId', '1');
    input.observation.url = target.href;
    assertProven(input);
  });

  test('a correctly scoped source job header is accepted when the transport provides it', () => {
    const input = fixture(); input.reads[0].workspaceId = workspace; assertProven(input);
  });

  test('scope query order does not weaken the required unique scope values', () => {
    const input = fixture();
    for (const read of input.reads) {
      const url = new URL(read.url); url.searchParams.sort(); read.url = url.href;
    }
    assertProven(input);
  });

  test('the same proof respects a deployed application context path', () => {
    const input = JSON.parse(JSON.stringify(fixture()).replaceAll('127.0.0.1:8080/', '127.0.0.1:8080/taxonomy/'));
    assertProven(input);
  });

  test('an unrelated document cannot supply or invalidate this transition proof', () => {
    const input = fixture();
    input.events.push({ type: 'error', documentEpoch: oldEpoch - 1, sequence: 31, url: sourceUrl });
    input.reads.push({ ...structuredClone(input.reads[0]), id: 'unrelated-failure',
      documentEpoch: oldEpoch - 1, failed: true, status: 403 });
    assertProven(input);
  });

  const rejectedBoundaries = [
    ['another browser', x => { x.browserName = 'chromium'; }],
    ['console text instead of a native page error', x => { x.error.kind = 'console-error'; }],
    ['ordinary JavaScript Error', x => { x.error.name = 'Error'; }],
    ['ordinary TypeError', x => { x.error.name = 'TypeError'; }],
    ['missing native stack', x => { delete x.error.stack; }],
    ['wrong first native stack line', x => { x.error.stack = 'Error: ' + x.error.stack; }],
    ['message and stack disagree', x => { x.error.message = x.error.message.replace('branch=draft', 'branch=other'); }],
    ['a different fetch caller', x => { x.error.stack = x.error.stack.replace('synchronizeCurrentProjectJobs', 'unrelatedFetch'); }],
    ['a foreign synchronizer script', x => { x.error.stack = x.error.stack.replace('/js/portfolio/portfolio-analysis-job-synchronizer.js', '/foreign/portfolio-analysis-job-synchronizer.js'); }],
    ['an undeclared transition', x => { x.navigation.kind = 'other'; }],
    ['a locale transition', x => { x.navigation.kind = 'locale'; }],
    ['an editor reload', x => { x.navigation.kind = 'reload'; }],
    ['an incomplete or cancelled navigation', x => { x.navigation.completed = false; }],
    ['missing declaration', x => { delete x.navigation.declaration; }],
    ['a declaration for another locale', x => { x.navigation.declaration.locale = 'de'; }],
    ['an invalid project identity', x => { x.navigation.declaration.projectId = 0; }],
    ['an invalid requirement identity', x => { x.navigation.declaration.requirementId = 0; }],
    ['a nonterminal declared job', x => { x.navigation.declaration.jobStatus = 'RUNNING'; }],
    ['a failed declared item', x => { x.navigation.declaration.itemStatus = 'FAILED'; }],
    ['missing exact scope', x => { delete x.navigation.declaration.workspaceId; }],
    ['a missing old document epoch', x => { delete x.navigation.oldDocument.epoch; }],
    ['a same-document transition', x => { x.navigation.newDocument.epoch = oldEpoch; }],
    ['an error attributed to the destination', x => { x.error.documentEpoch = newEpoch; }],
    ['an error attributed to another source URL', x => { x.error.pageUrl = destinationUrl; }],
    ['missing beforeunload', x => { x.events = x.events.filter(event => event.type !== 'beforeunload'); }],
    ['missing pagehide', x => { x.events = x.events.filter(event => event.type !== 'pagehide'); }],
    ['missing new-document start', x => { x.events = x.events.filter(event => event.type !== 'document-start'); }],
    ['lifecycle from another source document', x => { x.events[0].documentEpoch = newEpoch; }],
    ['lifecycle from another source URL', x => { x.events[0].url = destinationUrl; }],
    ['an error before the navigation declaration', x => { x.error.sequence = 9; }],
    ['an error before beforeunload', x => { x.error.sequence = 19; }],
    ['an error after pagehide', x => { x.error.sequence = 41; }],
    ['an error after navigation completion', x => { x.navigation.endSequence = 29; }],
    ['a new document started before pagehide', x => { x.events[2].sequence = 39; }],
    ['a missing navigation sequence', x => { delete x.navigation.startSequence; }],
    ['missing source observation', x => { delete x.navigation.sourceObservation; }],
    ['a stale source observation', x => { x.navigation.sourceObservation.documentEpoch = oldEpoch - 1; }],
    ['another source observation URL', x => { x.navigation.sourceObservation.url = destinationUrl; }],
    ['another selected project', x => { x.navigation.sourceObservation.projectId = '2'; }],
    ['another rendered source job', x => { x.navigation.sourceObservation.job.url += '-other'; }],
    ['a rendered queued job', x => { x.navigation.sourceObservation.job.heading = 'f41d287c-653 · Queued'; }],
    ['a different rendered requirement', x => { x.navigation.sourceObservation.job.item.key = 'REQ-OTHER'; }],
    ['a different rendered item status', x => { x.navigation.sourceObservation.job.item.status = 'Partial'; }],
    ['a different rendered snapshot', x => { x.navigation.sourceObservation.job.item.snapshot = 'other'; }],
    ['a source polling warning', x => { x.navigation.sourceObservation.job.warnings = 1; }],
    ['missing source job body', x => { x.reads.shift(); }],
    ['source body parsed after navigation began', x => { x.reads[0].parsedSequence = 11; }],
    ['source body parsed at the navigation boundary', x => { x.reads[0].parsedSequence = 10; }],
    ['source request starts after navigation begins', x => { x.reads[0].sequence = 11; }],
    ['another source body job', x => { x.reads[0].payload.id = 'another-job'; }],
    ['another source body project', x => { x.reads[0].payload.projectId = 2; }],
    ['another source body workspace', x => { x.reads[0].payload.workspaceId = 'other'; }],
    ['a source job still running', x => { x.reads[0].payload.status = 'RUNNING'; }],
    ['a source item still running', x => { x.reads[0].payload.items[0].status = 'RUNNING'; }],
    ['a different source item snapshot', x => { x.reads[0].payload.items[0].snapshotId = 'other'; }],
    ['a different source item requirement', x => { x.reads[0].payload.items[0].requirementId = 2; }],
    ['a different source item key', x => { x.reads[0].payload.items[0].requirementKey = 'REQ-OTHER'; }],
    ['a different frozen requirement version', x => { x.reads[0].payload.items[0].requirementVersionId = 2; }],
    ['duplicate source items for the same requirement', x => { x.reads[0].payload.items.push(structuredClone(x.reads[0].payload.items[0])); }],
    ['a missing source item', x => { x.reads[0].payload.items = []; }],
    ['a mismatched source workspace header', x => { x.reads[0].workspaceId = 'other'; }],
    ['missing destination body', x => { x.reads.pop(); }],
    ['a destination request from the source document', x => { x.reads[1].documentEpoch = oldEpoch; }],
    ['a destination request that predates navigation', x => { x.reads[1].sequence = 9; }],
    ['a destination request that predates its new document', x => { x.reads[1].sequence = 44; }],
    ['a destination body that predates navigation', x => { x.reads[1].parsedSequence = 9; }],
    ['a missing destination workspace header', x => { delete x.reads[1].workspaceId; }],
    ['a different destination workspace header', x => { x.reads[1].workspaceId = 'other'; }],
    ['a missing snapshot observation', x => { delete x.observation.snapshot; }],
    ['an observation from the source document', x => { x.observation.documentEpoch = oldEpoch; }],
    ['an observation from the source URL', x => { x.observation.url = sourceUrl; }],
    ['a different adopted snapshot payload', x => { x.observation.snapshot.data.commitSha = 'c'.repeat(40); }],
    ['a snapshot that has not rendered', x => { x.observation.snapshot.rendered = false; }],
    ['a snapshot without visible nodes', x => { x.observation.snapshot.nodeCount = 0; }],
    ['missing visible-node evidence', x => { delete x.observation.snapshot.nodeCount; }],
    ['a rendered edited requirement instead of the frozen text', x => { x.observation.snapshot.requirementText = 'Later edited text'; }],
    ['an untranslated workflow state', x => { x.observation.snapshot.workflowState = `workflow.snapshot ${snapshotId}`; }],
    ['a different workflow snapshot', x => { x.observation.snapshot.workflowState = 'Saved analysis other'; }],
    ['a working architecture source', x => { x.observation.snapshot.source.kind = 'working'; }],
    ['a wrong adopted requirement version', x => { x.observation.snapshot.source.requirementVersionId = 2; }]
  ];

  for (const field of ['repositoryId', 'workspaceId', 'workspaceScopeKey', 'branch']) {
    rejectedBoundaries.push([`different source observation ${field}`, x => { x.navigation.sourceObservation.scope[field] = 'other'; }]);
  }
  for (const field of ['projectId', 'requirementId', 'snapshotId', 'repositoryId', 'workspaceId', 'workspaceScopeKey', 'branch']) {
    rejectedBoundaries.push([`different adopted source ${field}`, x => { x.observation.snapshot.source[field] = 'other'; }]);
  }
  for (const [boundary, change] of rejectedBoundaries) {
    test(`${boundary} preserves the audit blocker`, () => {
      const input = fixture(); change(input);
      assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
    });
  }

  for (const index of [0, 1]) {
    const label = index === 0 ? 'source' : 'destination';
    for (const [boundary, change] of [
      ['a POST instead of a GET', read => { read.method = 'POST'; }],
      ['an HTTP failure', read => { read.status = 403; }],
      ['a real request failure', read => { read.failed = 'Load request cancelled'; }],
      ['an unfinished response', read => { read.finished = false; }],
      ['an unparsed response', read => { read.jsonParsed = false; }],
      ['missing JSON', read => { delete read.payload; }],
      ['a redirected request', read => { read.redirected = true; }],
      ['a missing request sequence', read => { delete read.sequence; }],
      ['a missing completion sequence', read => { delete read.finishedSequence; }],
      ['a missing parsed-body sequence', read => { delete read.parsedSequence; }],
      ['a body predating completion', read => { read.parsedSequence = read.finishedSequence - 1; }],
      ['another document', read => { read.documentEpoch = 1; }],
      ['another page URL', read => { read.pageUrl = baseUrl + 'other'; }]
    ]) test(`${label}: ${boundary} cannot supply proof`, () => {
      const input = fixture(); change(input.reads[index]);
      assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
    });
  }

  function changeErrorUrl(input, edit) {
    const url = new URL(discoveryUrl); edit(url);
    input.error.message = `/${url.host}${url.pathname}${url.search}${url.hash} due to access control checks.`;
    input.error.stack = input.error.stack.replace(discoveryUrl, url.href);
  }

  for (const [boundary, edit] of [
    ['foreign origin', url => { url.hostname = 'other.invalid'; }],
    ['another project', url => { url.pathname = '/api/projects/2/analysis-jobs'; }],
    ['an individual job endpoint', url => { url.pathname += '/' + jobId; }],
    ['an unrelated context endpoint', url => { url.pathname = '/api/context/current'; }],
    ['an extra query', url => { url.searchParams.set('extra', '1'); }],
    ['duplicate workspace query', url => { url.searchParams.append('workspaceId', workspace); }],
    ['missing scope query', url => { url.searchParams.delete('workspaceScopeKey'); }],
    ['foreign repository', url => { url.searchParams.set('repositoryId', 'other'); }],
    ['foreign workspace', url => { url.searchParams.set('workspaceId', 'other'); }],
    ['foreign workspace scope', url => { url.searchParams.set('workspaceScopeKey', 'other'); }],
    ['foreign branch', url => { url.searchParams.set('branch', 'other'); }],
    ['fragment', url => { url.hash = 'other'; }],
    ['credentials', url => { url.username = 'other'; }]
  ]) test(`native error ${boundary} is not this exact discovery cancellation`, () => {
    const input = fixture(); changeErrorUrl(input, edit);
    assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
  });

  for (const index of [0, 1]) {
    for (const [boundary, edit] of [
      ['foreign endpoint', url => { url.pathname = '/api/context/current'; }],
      ['foreign origin', url => { url.hostname = 'other.invalid'; }],
      ['missing scope', url => { url.searchParams.delete('workspaceId'); }],
      ['wrong scope', url => { url.searchParams.set('workspaceScopeKey', 'other'); }],
      ['extra query', url => { url.searchParams.set('extra', '1'); }],
      ['duplicate query', url => { url.searchParams.append('branch', 'draft'); }],
      ['fragment', url => { url.hash = 'fragment'; }],
      ['credentials', url => { url.username = 'other'; }]
    ]) test(`${index === 0 ? 'source' : 'destination'} read with ${boundary} cannot supply scoped evidence`, () => {
      const input = fixture(), url = new URL(input.reads[index].url); edit(url); input.reads[index].url = url.href;
      assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
    });
  }

  for (const [field, value] of [['projectId', 2], ['requirementId', 2], ['requirementKey', 'REQ-OTHER'],
    ['requirementText', 'Later edited requirement'], ['snapshotId', 'other'], ['snapshotStatus', 'FAILED'],
    ['workspaceId', 'other'], ['branchName', 'other']]) {
    test(`matching destination bodies with foreign ${field} cannot prove this frozen snapshot`, () => {
      const input = fixture(); input.reads[1].payload[field] = value; input.observation.snapshot.data[field] = value;
      assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
    });
  }
  for (const [field, value] of [['repositoryId', 'other'], ['requirementVersionId', 2]]) {
    test(`matching destination bodies with foreign provenance ${field} cannot prove this source`, () => {
      const input = fixture();
      input.reads[1].payload.exportProvenance[field] = value; input.observation.snapshot.data.exportProvenance[field] = value;
      assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
    });
  }

  for (const target of ['oldDocument', 'newDocument']) {
    for (const [boundary, edit] of [
      ['foreign origin', url => { url.hostname = 'other.invalid'; }],
      ['wrong project', url => { url.searchParams.set('projectId', '2'); }],
      ['wrong repository', url => { url.searchParams.set('repositoryId', 'other'); }],
      ['wrong workspace', url => { url.searchParams.set('workspaceId', 'other'); }],
      ['wrong branch', url => { url.searchParams.set('branch', 'other'); }],
      ['wrong locale', url => { url.searchParams.set('lang', 'de'); }],
      ['duplicate pin', url => { url.searchParams.append('projectId', '1'); }],
      ['unexpected query', url => { url.searchParams.set('unexpected', '1'); }],
      ['wrong route', url => { url.pathname = '/admin'; }]
    ]) test(`${target} ${boundary} cannot identify the declared transition`, () => {
      const input = fixture(), url = new URL(input.navigation[target].url); edit(url);
      input.navigation[target].url = url.href;
      assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
    });
  }

  for (const [field, value] of [['workspaceScopeKey', 'other'], ['requirementId', '2'], ['requirementVersionId', '2']]) {
    test(`a derived destination ${field} cannot contradict the adopted body`, () => {
      const input = fixture(), url = new URL(input.observation.url); url.searchParams.set(field, value);
      input.observation.url = url.href;
      assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
    });
  }

  for (const type of ['error', 'unhandledrejection', 'securitypolicyviolation']) {
    for (const [documentEpoch, sequence] of [[oldEpoch, 8], [oldEpoch, 31], [newEpoch, 61]]) {
      test(`a real ${type} in the affected document at ${sequence} remains blocking`, () => {
        const input = fixture(); input.events.push({ type, documentEpoch, sequence,
          url: documentEpoch === oldEpoch ? sourceUrl : destinationUrl });
        assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
      });
    }
  }

  for (const url of [discoveryUrl, sourceReadUrl]) {
    for (const sequence of [4, 25]) {
      for (const failure of [{ failed: 'Load request cancelled' }, { status: 503 }]) {
        test(`source failure at ${sequence} on ${new URL(url).pathname} cannot be concealed by a completed job`, () => {
          const input = fixture(); input.reads.push({ ...structuredClone(input.reads[0]), ...failure,
            id: 'failed-source', sequence, url, finished: false, jsonParsed: false });
          assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
        });
      }
    }
  }

  test('a later failed destination read cannot be concealed by an earlier adopted success', () => {
    const input = fixture(); input.reads.push({ ...structuredClone(input.reads[1]), id: 'failed-destination',
      sequence: 70, status: 503 });
    assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
  });

  test('an unrelated earlier context cancellation cannot replace the required source job read', () => {
    const input = fixture(); input.reads[0] = { id: 52, sequence: 288, documentEpoch: 1793991816775,
      url: baseUrl + 'api/context/current', failed: 'Load request cancelled', method: 'GET' };
    assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
  });

  test('a later unadopted successful body does not displace the actual adopted destination response', () => {
    const input = fixture(), later = structuredClone(input.reads[1]);
    later.id = 'unadopted-destination'; later.sequence = 70; later.finishedSequence = 71; later.parsedSequence = 72;
    later.payload.commitSha = 'c'.repeat(40); input.reads.push(later);
    assertProven(input);
  });

  for (const [boundary, read] of [
    ['a pending original list GET', { sequence: 9 }],
    ['HTTP 200 headers without the list body', { sequence: 9, status: 200, finished: false, jsonParsed: false }],
    ['a list body not consumed as JSON', { sequence: 9, status: 200, finished: true, finishedSequence: 15, jsonParsed: false }],
    ['a missing list completion timestamp', { sequence: 9, status: 200, finished: true, jsonParsed: true, parsedSequence: 16 }],
    ['a missing list JSON-consumption timestamp', { sequence: 9, status: 200, finished: true, finishedSequence: 15, jsonParsed: true }]
  ]) test(`${boundary} preserves the console ledger before any delayed failure arrives`, () => {
    const input = fixture(), messages = [], reconciled = [];
    const ledger = createConsoleErrorLedger(messages, reconciled), errorId = ledger.record(input.error.message);
    input.reads.push({ id: 'original-list', url: discoveryUrl, method: 'GET', documentEpoch: oldEpoch,
      redirected: false, payload: [], ...read });
    const proof = classifyPortfolioSnapshotPollCancellation(input);
    ledger.reconcile(proof ? [{ ...proof, errorId, message: input.error.message }] : []);
    assert.deepEqual(messages, [input.error.message], 'A pending original request can still report a real failure after this proof attempt');
    assert.deepEqual(reconciled, []);
  });

  for (const [label, url, documentEpoch] of [
    ['another source job read', sourceReadUrl, oldEpoch],
    ['another destination snapshot read', destinationReadUrl, newEpoch]
  ]) test(`pending ${label} cannot be hidden by the earlier adopted bodies`, () => {
    const input = fixture();
    input.reads.push({ id: 'pending-read', url, method: 'GET', documentEpoch, sequence: 70, finished: false, jsonParsed: false });
    assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
  });

  for (const payload of [[], [fixture().reads[0].payload]]) test(`a fully consumed original list array with ${payload.length} jobs is settled`, () => {
    const input = fixture();
    input.reads.push({ ...structuredClone(input.reads[0]), id: 'settled-list', url: discoveryUrl, payload,
      sequence: 9, finishedSequence: 70, parsedSequence: 71 });
    assertProven(input);
  });

  test('an old requestfailed callback after navigation completion still preserves the console ledger', () => {
    const input = fixture(), messages = [], reconciled = [];
    const ledger = createConsoleErrorLedger(messages, reconciled), errorId = ledger.record(input.error.message);
    input.reads.push({ id: 'failed-original', url: discoveryUrl, method: 'GET', documentEpoch: oldEpoch,
      sequence: 9, failed: 'access denied' });
    input.events.push({ type: 'poll-request-failed', requestId: 'failed-original', documentEpoch: oldEpoch,
      url: discoveryUrl, sequence: 70, failure: 'access denied' });
    const proof = classifyPortfolioSnapshotPollCancellation(input);
    ledger.reconcile(proof ? [{ ...proof, errorId, message: input.error.message }] : []);
    assert.deepEqual(messages, [input.error.message]);
    assert.deepEqual(reconciled, []);
  });

  test('the passive producer keeps the ledger blocked before and after a delayed original requestfailed callback', async () => {
    const input = fixture(), handlers = {}, frame = {}, bindings = {}, messages = [], reconciled = [];
    const ledger = createConsoleErrorLedger(messages, reconciled), errorId = ledger.record(input.error.message);
    let document = input.navigation.oldDocument;
    const page = { on: (name, fn) => { handlers[name] = fn; }, mainFrame: () => frame, url: () => document.url,
      evaluate: async (fn, argument) => {
        if (fn.name === 'documentIdentity' || fn.name === 'installDocumentObserver') return document;
        return argument?.jobId ? input.navigation.sourceObservation : input.observation;
      } };
    const context = { exposeBinding: async (name, fn) => { bindings[name] = fn; }, addInitScript: async () => {} };
    const observer = createNavigationPollEvidence({ browserName: 'webkit', baseUrl });
    await observer.attach(page, context);
    function response(read) {
      const reply = { status: () => 200, headers: () => ({ 'content-type': 'application/json' }), json: async () => read.payload };
      const request = { url: () => read.url, method: () => 'GET', headers: () => read.workspaceId ? { 'x-taxonomy-workspace-id': read.workspaceId } : {},
        redirectedFrom: () => null, response: async () => reply };
      reply.request = () => request;
      handlers.request(request); handlers.response(reply); handlers.requestfinished(request);
    }
    const lifecycle = type => bindings.__taxonomyQaPollLifecycle({ page, frame }, { type, documentEpoch: document.epoch, url: document.url });
    response(input.reads[0]);
    const original = { url: () => discoveryUrl, method: () => 'GET', headers: () => ({}), redirectedFrom: () => null,
      failure: () => ({ errorText: 'access denied' }) };
    handlers.request(original);
    await observer.begin('en', 'portfolio-snapshot', input.navigation.declaration);
    await lifecycle('beforeunload'); observer.recordError(input.error, errorId); await lifecycle('pagehide');
    document = input.navigation.newDocument; await lifecycle('document-start'); response(input.reads[1]);
    await observer.end('en', true, 'portfolio-snapshot'); await observer.verifyDestination('en', 'portfolio-snapshot');
    ledger.reconcile(observer.reconciliations());
    assert.deepEqual(messages, [input.error.message], 'The first reconciliation must retain a still-open original GET');
    handlers.requestfailed(original);
    const snapshot = observer.snapshot(), failed = snapshot.events.find(event => event.type === 'poll-request-failed');
    assert.ok(failed.sequence > snapshot.navigations[0].endSequence, 'This is an actual late observer event');
    ledger.reconcile(observer.reconciliations());
    assert.deepEqual(messages, [input.error.message]); assert.deepEqual(reconciled, []);
  });

  test('absent or malformed evidence fails closed without throwing', () => {
    for (const input of [undefined, null, {}, { browserName: 'webkit' }, { ...fixture(), events: null },
      { ...fixture(), reads: null }, { ...fixture(), baseUrl: 'not a URL' }]) {
      assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
    }
  });
}
