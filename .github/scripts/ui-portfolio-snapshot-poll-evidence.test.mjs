import assert from 'node:assert/strict';
import test from 'node:test';
import { classifyPortfolioSnapshotPollCancellation } from './ui-portfolio-snapshot-poll-evidence.mjs';

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

test('absent or malformed evidence fails closed without throwing', () => {
  for (const input of [undefined, null, {}, { browserName: 'webkit' }, { ...fixture(), events: null },
    { ...fixture(), reads: null }, { ...fixture(), baseUrl: 'not a URL' }]) {
    assert.equal(classifyPortfolioSnapshotPollCancellation(input), null);
  }
});
