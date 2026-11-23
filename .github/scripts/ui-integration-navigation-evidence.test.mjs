import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { classifyIntegrationNavigationCancellation } from './ui-integration-navigation-evidence.mjs';
import { runIntegrationAcceptance } from './integration-acceptance.mjs';
import { createConsoleErrorLedger, createNavigationPollEvidence } from './ui-navigation-poll-evidence.mjs';

const baseUrl = 'http://127.0.0.1:8080';
const previewUrl = baseUrl + '/?architectureSource=working&workspaceScopeKey=__shared__&workspaceId=&adoption=preview#architecture';
const sourceUrl = baseUrl + '/?architectureSource=working&repositoryId=repo&workspaceScopeKey=adopted&workspaceId=adopted&branch=draft'
  + '&sourceProjectId=1&sourceRequirementId=2&sourceSnapshotId=snapshot&sourceWorkspaceId=historical&sourceRepositoryId=repo'
  + '&sourceBranch=draft&sourceWorkspaceScopeKey=historical&sourceRequirementVersionId=3&lang=en&layout=stable&viewId=view&revision=3#architecture';
const destinationUrl = baseUrl + '/integrations?repositoryId=repo&branch=draft&workspaceId=adopted&lang=en';
const startupUrl = baseUrl + '/api/status/startup';
const profilesUrl = baseUrl + '/api/integrations/profiles?repositoryId=repo&workspaceId=adopted&branch=draft';
const profile = { id: 'archimate-3.1', version: '1', title: 'ArchiMate 3.1' };
const option = { value: 'archimate-3.1@1', textContent: 'ArchiMate 3.1 · archimate-3.1@1' };
const initialized = { initialized: true, phase: 'READY', status: 'ready', memory: {
  heapUsagePercent: 29, heapUsedMB: 49, heapMaxMB: 170, threadCount: 41
} };
const startupText = '🟢 Available | Heap: 29% (49/170 MB) | Worker threads: 41';
const nativeError = {
  name: 'Fetch API cannot load http',
  message: '/127.0.0.1:8080/api/status/startup due to access control checks.',
  stack: 'Fetch API cannot load ' + startupUrl + ' due to access control checks.\n'
    + '    at attempt (' + baseUrl + '/js/api/taxonomy-api-client.js:629:41)\n'
    + '    at unknown (' + baseUrl + '/js/core/taxonomy-browse.js:732:48)'
};
const kind = 'integrations-entry';

async function replay({
  pendingUrl, duplicateDeparture = false, duplicateAfter = false, childFrame = false,
  startup = initialized, startupHeaders, earlyStartup, laterStartup, profilesContentType,
  profilesBeforeBinding, observe = async () => {}
} = {}) {
  const handlers = {}, bindings = {}, frame = {}, messages = [], reconciled = [];
  const ledger = createConsoleErrorLedger(messages, reconciled);
  let url = previewUrl, epoch = 1795448944652;
  const nodes = {
    systemStatusBar: { textContent: startupText },
    connectionProfile: { disabled: false, options: [option] },
    integrationConnection: { disabled: false },
    integrationRefresh: { disabled: false },
    integrationError: { hidden: true }
  };
  const page = { on: (name, fn) => { handlers[name] = fn; }, mainFrame: () => frame,
    frames: () => childFrame ? [frame, {}] : [frame], url: () => url,
    evaluate: async (fn, argument) => {
      if (fn.name === 'installDocumentObserver') return { epoch, url };
      return structuredClone(vm.runInNewContext('(' + fn.toString() + ')(argument)', {
        argument, performance: { timeOrigin: epoch }, location: { href: url },
        document: { getElementById: id => nodes[id], querySelector: selector =>
          selector === '#integrationCreate button[type=submit]' ? { disabled: false } : null },
        window: {
          __taxonomyPollEvidence: { epoch, startupWrites: 1 },
          TaxonomyI18n: { t: key => ({ 'startup.ui.ready': 'Available', 'startup.ui.threads': 'Worker threads' })[key] },
          TaxonomyArchitectureWorkspace: { isActive: () => true, getSource: () => ({ kind: 'working' }) }
        }
      }));
    } };
  const context = { exposeBinding: async (name, fn) => { bindings[name] = fn; }, addInitScript: async () => {} };
  const observer = createNavigationPollEvidence({ browserName: 'webkit', baseUrl });
  await observer.attach(page, context);
  const lifecycle = (type, detail) => bindings.__taxonomyQaPollLifecycle({ page, frame },
    { type, documentEpoch: epoch, url, ...detail });
  function originalRead(requestUrl, payload, {
    headers = requestUrl === profilesUrl ? { 'x-taxonomy-workspace-id': 'adopted' } : {},
    contentType = 'application/json'
  } = {}) {
    const request = { url: () => requestUrl, method: () => 'GET', frame: () => frame, headers: () => headers,
      response: async () => reply, redirectedFrom: () => null, failure: () => ({ errorText: 'cancelled at transport' }) };
    const reply = { status: () => 200, request: () => request, headers: () => ({ 'content-type': contentType }),
      json: async () => JSON.parse(JSON.stringify(payload)) };
    handlers.request(request);
    if (payload !== undefined) {
      handlers.response(reply);
      handlers.requestfinished(request);
    }
    return request;
  }
  lifecycle('document-start');
  if (earlyStartup) originalRead(startupUrl, earlyStartup);
  originalRead(startupUrl, startup, { headers: startupHeaders });
  lifecycle('poll-render', { id: 'systemStatusBar', writes: 1, text: startupText });
  if (laterStartup) originalRead(startupUrl, laterStartup);
  let pending = pendingUrl === startupUrl ? originalRead(pendingUrl) : null;
  url = sourceUrl;
  await observer.begin('en', kind, { sourceUrl, destinationUrl });
  lifecycle('beforeunload');
  const errorId = ledger.record(nativeError.message);
  observer.recordError(nativeError, errorId);
  if (duplicateDeparture) observer.recordError(nativeError, ledger.record(nativeError.message));
  lifecycle('pagehide');
  epoch = 1795448950408; url = destinationUrl;
  if (profilesBeforeBinding) pending = originalRead(profilesUrl);
  lifecycle('document-start');
  const profiles = originalRead(profilesUrl, [profile], { contentType: profilesContentType });
  if (pendingUrl === profilesUrl) pending = originalRead(pendingUrl);
  await observer.end('en', true, kind);
  await observer.verifyDestination('en', kind);
  await observe({ observer, failPending: () => handlers.requestfailed(pending),
    failProfiles: () => handlers.requestfailed(profiles),
    reload: async () => {
      await observer.begin('en', kind, { sourceUrl: destinationUrl, destinationUrl });
      epoch++;
      lifecycle('document-start');
      await observer.end('en', true, kind);
      await observer.verifyDestination('en', kind);
    } });
  if (duplicateAfter) observer.recordError(nativeError, ledger.record(nativeError.message));
  ledger.reconcile(observer.reconciliations());
  return { observer, messages, reconciled, errorId };
}

test('the passive observer reconciles the declared initial integrations departure with both original renders', async () => {
  const { messages, reconciled, errorId } = await replay({ duplicateAfter: true });
  assert.equal(reconciled.length, 1, 'The recorded startup cancellation has both original reads and renders');
  const [proof] = reconciled;
  assert.equal(proof.errorId, errorId);
  assert.deepEqual(messages, [nativeError.message], 'The identical later error has its own ledger ID');
  assert.equal(proof.oldDocument.url, sourceUrl);
  assert.equal(proof.newDocument.url, destinationUrl);
  assert.equal(proof.sourceRequest.pageUrl, previewUrl, 'Global startup endpoint health preceded private adoption');
  assert.equal(proof.sourceRender.startup.text, startupText);
  assert.equal(proof.request.url, profilesUrl);
  assert.equal(proof.request.workspaceId, 'adopted');
  assert.deepEqual(proof.profile, profile);
  assert.deepEqual(proof.render.options.map(item => [item.value, item.text]), [[option.value, option.textContent]]);
});

function helperPage(status, navigateError) {
  const calls = [];
  let url = sourceUrl;
  const boundary = new Error('stop at first integration action');
  const response = { status: () => status, url: () => profilesUrl, text: async () => 'original profiles',
    headers: () => ({ 'x-request-id': 'user-profile-request' }), request: () => ({ headers: () => ({}) }),
    json: async () => {
      calls.push(['profile-body', status]);
      return status === 200 ? [profile] : { code: 'PRIVATE_WORKSPACE_REQUIRED' };
    } };
  const page = {
    url: () => url,
    waitForResponse: predicate => {
      assert.ok(predicate(response));
      calls.push(['arm-original-response']);
      return Promise.resolve(response);
    },
    goto: async target => {
      calls.push(['goto', target]);
      if (navigateError) throw navigateError;
      url = target;
    },
    locator: selector => ({
      focus: async () => {},
      evaluate: async () => true,
      count: async () => 1,
      getAttribute: async () => '/architecture/editor?repositoryId=repo&workspaceId=adopted&branch=draft&lang=en',
      isDisabled: async () => selector === '#integrationApply',
      waitFor: async () => calls.push(['read-only-error-render', selector]),
      locator: () => ({ locator: () => ({ click: async () => { calls.push(['create']); throw boundary; } }) })
    }),
    waitForFunction: async predicate => {
      assert.equal(vm.runInNewContext('(' + predicate.toString() + ')()', {
        document: { getElementById: id => id === 'integrationRefresh' ? { disabled: false } : null }
      }), true);
      calls.push(['ready']);
    },
    viewportSize: () => { throw boundary; }
  };
  return { calls, boundary, page, callbacks: {
    onInitialNavigationStart: async declaration => calls.push(['declare', declaration]),
    onInitialNavigationEnd: async succeeded => calls.push(['end', succeeded]),
    onInitialNavigationVerified: async () => calls.push(['verify', page.url()])
  } };
}

test('the integration helper declares the real first navigation and verifies readiness before create', async () => {
  const { page, calls, boundary, callbacks } = helperPage(200);
  await assert.rejects(runIntegrationAcceptance({ page, role: 'ADMIN', baseUrl, httpFailures: [], ...callbacks }), boundary);
  assert.deepEqual(calls, [
    ['arm-original-response'], ['declare', { sourceUrl, destinationUrl }], ['goto', destinationUrl], ['end', true],
    ['profile-body', 200], ['ready'], ['verify', destinationUrl], ['create']
  ]);
});

test('a failed initial goto closes the declared navigation with no destination verification', async () => {
  const failure = new Error('integration navigation failed');
  const { page, calls, callbacks } = helperPage(200, failure);
  await assert.rejects(runIntegrationAcceptance({ page, role: 'ADMIN', baseUrl, httpFailures: [], ...callbacks }), failure);
  assert.deepEqual(calls, [
    ['arm-original-response'], ['declare', { sourceUrl, destinationUrl }], ['goto', destinationUrl], ['end', false]
  ]);
});

test('expected USER profiles 403 stays read-only with no successful destination evidence', async () => {
  const { page, calls, boundary, callbacks } = helperPage(403);
  await assert.rejects(runIntegrationAcceptance({ page, role: 'USER', baseUrl, httpFailures: [], ...callbacks }), boundary);
  assert.deepEqual(calls, [
    ['arm-original-response'], ['declare', { sourceUrl, destinationUrl }], ['goto', destinationUrl], ['end', true],
    ['profile-body', 403], ['read-only-error-render', '#integrationError:not([hidden])']
  ]);
});

for (const pendingUrl of [startupUrl, profilesUrl]) {
  test(`the original ${new URL(pendingUrl).pathname} blocks before and after a late requestfailed`, async () => {
    const { messages, reconciled } = await replay({ pendingUrl, observe: async ({ observer, failPending }) => {
      assert.deepEqual(observer.reconciliations(), [], 'An open original request must remain blocking');
      failPending();
      const { events, navigations } = observer.snapshot();
      assert.ok(events.find(event => event.type === 'poll-request-failed').sequence > navigations[0].endSequence);
    } });
    assert.deepEqual(messages, [nativeError.message]);
    assert.deepEqual(reconciled, []);
  });
}

test('two pageerrors in the same departure cannot share one proof', async () => {
  const { messages, reconciled } = await replay({ duplicateDeparture: true });
  assert.deepEqual(messages, [nativeError.message, nativeError.message]);
  assert.deepEqual(reconciled, []);
});

test('reconciliation rechecks a previously observed read if a late failure arrives', async () => {
  await replay({ observe: async ({ observer, failProfiles }) => {
    assert.equal(observer.reconciliations().length, 1);
    failProfiles();
    assert.deepEqual(observer.reconciliations(), []);
  } });
});

test('a pageerror with a child frame present cannot be bound to the departing main document', async () => {
  const { messages, reconciled } = await replay({ childFrame: true });
  assert.deepEqual(messages, [nativeError.message]);
  assert.deepEqual(reconciled, []);
});

test('a later attempt to declare or verify the reload cannot replace the first destination capture', async () => {
  const { observer, reconciled } = await replay({ observe: async ({ reload }) => reload() });
  const { navigations } = observer.snapshot();
  assert.equal(navigations.length, 1);
  assert.equal(navigations[0].observation.documentEpoch, 1795448950408);
  assert.equal(reconciled.length, 1);
});

function editUrl(value, edit) {
  const url = new URL(value);
  edit(url);
  return url.href;
}

const replayed = await replay();
const snapshot = replayed.observer.snapshot();
function fixture() {
  const { navigations: [navigation], events, reads, errors: [error] } = structuredClone(snapshot);
  return { browserName: 'webkit', baseUrl, error, navigation, events, reads, observation: navigation.observation };
}

// Change every reference to the same URL where appropriate. Otherwise a broken
// scope predicate could still pass a rejection test via an unrelated identity mismatch.
function changeDestination(input, edit) {
  const changed = editUrl(input.navigation.newDocument.url, edit);
  input.navigation.declaration.destinationUrl = changed;
  input.navigation.newDocument.url = changed;
  input.observation.url = changed;
  input.reads[1].pageUrl = changed;
  for (const event of input.events) if (event.url === destinationUrl) event.url = changed;
}

function changeSource(input, edit) {
  const changed = editUrl(input.navigation.oldDocument.url, edit);
  input.navigation.declaration.sourceUrl = changed;
  input.navigation.oldDocument.url = changed;
  input.navigation.sourceObservation.url = changed;
  input.error.pageUrl = changed;
  for (const event of input.events) if (event.url === sourceUrl) event.url = changed;
}

for (const [boundary, edit] of [
  ['a different origin', url => { url.host = 'example.invalid'; }],
  ['a different path', url => { url.pathname = '/other'; }],
  ['a nonempty fragment', url => { url.hash = '#other'; }],
  ['even a bare fragment delimiter', url => { url.hash = '#'; }],
  ['URL credentials', url => { url.username = 'wrong'; }],
  ['historical workspace instead of the adopted workspace', url => { url.searchParams.set('workspaceId', 'historical'); }],
  ['the shared preview workspace', url => { url.searchParams.set('workspaceId', '__shared__'); }],
  ['a different repository', url => { url.searchParams.set('repositoryId', 'other'); }],
  ['a different branch', url => { url.searchParams.set('branch', 'other'); }],
  ['a different locale', url => { url.searchParams.set('lang', 'de'); }],
  ['no workspace', url => { url.searchParams.delete('workspaceId'); }],
  ['an empty workspace', url => { url.searchParams.set('workspaceId', ''); }],
  ['a duplicate workspace', url => { url.searchParams.append('workspaceId', 'adopted'); }],
  ['extra connection state', url => { url.searchParams.set('connection', 'conn'); }]
]) test(`destination with ${boundary} cannot prove the exact entry`, () => {
  const input = fixture();
  changeDestination(input, edit);
  assert.equal(classifyIntegrationNavigationCancellation(input), null);
});

for (const [boundary, edit] of [
  ['a different origin', url => { url.host = 'example.invalid'; }],
  ['the editor route instead of the application root', url => { url.pathname = '/architecture/editor'; }],
  ['an inactive architecture route', url => { url.hash = 'analyze'; }],
  ['a snapshot as the current architecture', url => { url.searchParams.set('architectureSource', 'snapshot'); }],
  ['duplicate current repository pins', url => { url.searchParams.append('repositoryId', 'repo'); }],
  ['preview pins in the actual departure', url => { url.searchParams.set('adoption', 'preview'); }],
  ['a missing view pin', url => { url.searchParams.delete('viewId'); }],
  ['workspace scope disagreeing with workspace identity', url => { url.searchParams.set('workspaceScopeKey', 'other'); }],
  ['historical workspace confusion', url => {
    url.searchParams.set('sourceWorkspaceId', 'adopted'); url.searchParams.set('sourceWorkspaceScopeKey', 'adopted');
  }],
  ['the nonprivate workspace', url => {
    url.searchParams.set('workspaceId', '__shared__'); url.searchParams.set('workspaceScopeKey', '__shared__');
  }]
]) test(`departure with ${boundary} cannot prove the exact entry`, () => {
  const input = fixture();
  changeSource(input, edit);
  assert.equal(classifyIntegrationNavigationCancellation(input), null);
});

for (const [boundary, edit] of [
  ['a foreign origin', url => { url.hostname = 'other.invalid'; }],
  ['a different path', url => { url.pathname = '/api/integrations'; }],
  ['a different branch', url => { url.searchParams.set('branch', 'other'); }],
  ['an extra query key', url => { url.searchParams.set('lang', 'en'); }],
  ['a duplicate query key', url => { url.searchParams.append('branch', 'draft'); }],
  ['a different repository', url => { url.searchParams.set('repositoryId', 'other'); }],
  ['a historical workspace', url => { url.searchParams.set('workspaceId', 'historical'); }],
  ['a missing workspace', url => { url.searchParams.delete('workspaceId'); }],
  ['the unscoped profiles endpoint', url => { url.search = ''; }]
]) test(`profile response read from ${boundary} cannot supply adopted profiles`, () => {
  const input = fixture();
  input.reads[1].url = editUrl(profilesUrl, edit);
  assert.equal(classifyIntegrationNavigationCancellation(input), null);
});

const failingEvidence = [
  ['a non-WebKit browser', input => { input.browserName = 'chromium'; }],
  ['a console-only error', input => { input.error.kind = 'console-error'; }],
  ['another native error kind', input => { input.error.name = 'TypeError'; }],
  ['a scoped startup cancellation', input => { input.error.message = nativeError.message.replace('/startup', '/startup?workspaceId=adopted'); }],
  ['a foreign native URL', input => { input.error.stack = nativeError.stack.replace(startupUrl, 'http://other.invalid/api/status/startup'); }],
  ['a forged fetch frame', input => { input.error.stack = nativeError.stack.replace('at attempt', 'at wrapper'); }],
  ['a forged caller path', input => { input.error.stack = nativeError.stack.replace('/core/taxonomy-browse.js', '/core/other.js'); }],
  ['an appended caller-created stack frame', input => { input.error.stack += '\n    at wrapper (http://127.0.0.1:8080/fake.js:9:1)'; }],
  ['an undeclared navigation', input => { delete input.navigation.declaration; }],
  ['an integrations reload', input => { input.navigation.kind = 'reload'; }],
  ['a canceled navigation', input => { input.navigation.completed = false; }],
  ['a stale declaration from preview', input => { input.navigation.declaration.sourceUrl = previewUrl; }],
  ['a stale error URL', input => { input.error.pageUrl = previewUrl; }],
  ['a same-document navigation', input => { input.navigation.newDocument.epoch = input.navigation.oldDocument.epoch; }],
  ['an error associated with another document', input => { input.error.documentEpoch = input.navigation.newDocument.epoch; }],
  ['an error before beforeunload', input => { input.error.sequence = input.navigation.startSequence; }],
  ['a missing old pagehide', input => { input.events = input.events.filter(event => event.type !== 'pagehide'); }],
  ['a mismatched beforeunload URL', input => { input.events.find(event => event.type === 'beforeunload').url = previewUrl; }],
  ['a pagehide following document start', input => { input.events.find(event => event.type === 'pagehide').sequence = input.navigation.endSequence; }],
  ['a different first destination document', input => {
    const first = input.events.findLast(event => event.type === 'document-start');
    input.events.splice(input.events.indexOf(first), 0, { ...first, sequence: first.sequence - 1, url: baseUrl + '/other' });
  }],
  ['another document start within the declared window', input => {
    const first = input.events.findLast(event => event.type === 'document-start');
    input.events.push({ ...first, sequence: first.sequence + 1, url: baseUrl + '/other' });
  }],
  ['a missing source observation', input => { delete input.navigation.sourceObservation; }],
  ['a stale source observation', input => { input.navigation.sourceObservation.url = previewUrl; }],
  ['an unverified working architecture', input => { input.navigation.sourceObservation.workingArchitecture = false; }],
  ['a missing startup DOM mutation', input => { input.events = input.events.filter(event => event.type !== 'poll-render'); }],
  ['a startup DOM mutation from another document', input => { input.events.find(event => event.type === 'poll-render').documentEpoch = 5; }],
  ['an out-of-order startup response', input => { input.reads[0].responseSequence = input.events.find(event => event.type === 'poll-render').sequence; }],
  ['a mismatched startup render', input => { input.navigation.sourceObservation.startup.text = '🟢 Available'; }],
  ['an unrelated startup payload', input => { input.reads[0].payload.memory.heapUsagePercent = 30; }],
  ['a failed startup payload', input => { input.reads[0].payload.phase = 'FAILED'; }],
  ['a startup response from another root', input => { input.reads[0].pageUrl = baseUrl + '/projects'; }],
  ['a non-global startup endpoint', input => { input.reads[0].url += '?workspaceId=adopted'; }],
  ['a destination profile request before document start', input => {
    input.reads[1].sequence = input.events.findLast(event => event.type === 'document-start').sequence;
  }],
  ['profile JSON consumed only after observation', input => { input.reads[1].parsedSequence = input.observation.sequence + 1; }],
  ['profiles read from an earlier epoch', input => { input.reads[1].documentEpoch = input.navigation.oldDocument.epoch; }],
  ['profiles read from a different page URL', input => { input.reads[1].pageUrl += '&connection=conn'; }],
  ['a missing workspace header', input => { delete input.reads[1].workspaceId; }],
  ['a historical workspace header', input => { input.reads[1].workspaceId = 'historical'; }],
  ['a missing destination observation', input => { delete input.observation; }],
  ['a render in a later epoch', input => { input.observation.documentEpoch++; }],
  ['a stale destination URL at observation', input => { input.observation.url += '&connection=conn'; }],
  ['destination observation before end of goto', input => { input.observation.sequence = input.navigation.endSequence - 1; }],
  ['a missing profile option', input => { input.observation.profiles.options = []; }],
  ['a mismatched profile option', input => { input.observation.profiles.options[0].text = 'Different title · archimate-3.1@1'; }],
  ['a duplicate rendered option', input => { input.observation.profiles.options.push(input.observation.profiles.options[0]); }],
  ['a duplicate returned profile identity', input => { input.reads[1].payload.push({ ...profile, title: 'Other title' }); }],
  ['a different profile version', input => { input.reads[1].payload[0].version = '2'; }],
  ['the refresh control still disabled', input => { input.observation.profiles.refreshEnabled = false; }],
  ['the profiles control still disabled', input => { input.observation.profiles.profileEnabled = false; }],
  ['the create control still disabled', input => { input.observation.profiles.createEnabled = false; }],
  ['a shown initial integration error', input => { input.observation.profiles.errorHidden = false; }]
];

for (const [boundary, change] of failingEvidence) test(`${boundary} leaves the startup error blocking`, () => {
  const input = fixture();
  change(input);
  assert.equal(classifyIntegrationNavigationCancellation(input), null);
});

for (const [readIndex, label] of [[0, 'source startup'], [1, 'destination profiles']]) {
  for (const [boundary, change] of [
    ['a non-200 response', { status: 204 }],
    ['an HTTP error', { status: 403 }],
    ['a real request failure', { failed: 'network error' }],
    ['an unfinished request', { finished: false }],
    ['an unparsed body', { jsonParsed: false }],
    ['a non-JSON body', { payload: null }],
    ['a redirected request', { redirected: true }],
    ['a different method', { method: 'POST' }],
    ['another frame', { mainFrame: false }]
  ]) test(`${label} with ${boundary} preserves the error`, () => {
    const input = fixture();
    Object.assign(input.reads[readIndex], change);
    assert.equal(classifyIntegrationNavigationCancellation(input), null);
  });
  for (const [boundary, change] of [
    ['a failed second request', { failed: 'network error' }],
    ['a pending second request', { finished: false, jsonParsed: false }],
    ['an unparsed second body', { jsonParsed: false }],
    ['a redirect on a second request', { redirected: true }],
    ['a second request with HTTP 304', { status: 304 }]
  ]) test(`${label} with ${boundary} cannot borrow the first render`, () => {
    const input = fixture();
    input.reads.push({ ...input.reads[readIndex], id: 3, ...change });
    assert.equal(classifyIntegrationNavigationCancellation(input), null);
  });
}

for (const type of ['error', 'unhandledrejection', 'securitypolicyviolation']) {
  for (const [document, eventSequence] of [['oldDocument', 2], ['newDocument', 50]]) {
    test(`a native ${type} in the ${document === 'oldDocument' ? 'source' : 'first destination'} vetoes proof`, () => {
      const input = fixture();
      const { epoch: documentEpoch, url } = input.navigation[document];
      input.events.push({ type, documentEpoch, url, sequence: eventSequence });
      assert.equal(classifyIntegrationNavigationCancellation(input), null);
    });
  }
}

test('the classifier fails closed on missing or malformed evidence', () => {
  for (const input of [null, {}, { ...fixture(), events: null }, { ...fixture(), reads: null }, { ...fixture(), baseUrl: 'broken' }]) {
    assert.equal(classifyIntegrationNavigationCancellation(input), null);
  }
});

test('a terminal HTTP 200 startup failure after a ready bar preserves the ledger error', async () => {
  const laterStartup = { ...initialized, initialized: false, phase: 'FAILED', status: 'error' };
  const { observer, messages, reconciled } = await replay({ laterStartup });
  const { reads, events } = observer.snapshot();
  const render = events.find(event => event.type === 'poll-render');
  assert.equal(reads[1].jsonParsed, true);
  assert.equal(reads[1].status, 200);
  assert.ok(reads[1].sequence > render.sequence);
  assert.deepEqual(messages, [nativeError.message]);
  assert.deepEqual(reconciled, []);
});

for (const [label, startup] of [
  ['missing phase', { ...initialized, phase: undefined }],
  ['contradictory phase', { ...initialized, phase: 'LOADING_TAXONOMY' }],
  ['missing status', { ...initialized, status: undefined }],
  ['contradictory status', { ...initialized, status: 'loading' }]
]) test(`an initialized startup witness with ${label} preserves the ledger error`, async () => {
  const { messages, reconciled } = await replay({ startup });
  assert.deepEqual(messages, [nativeError.message]);
  assert.deepEqual(reconciled, []);
});

const starting = { ...initialized, initialized: false, phase: 'STARTING', status: 'pending' };
test('ordinary initialization before the adopted ready startup state still permits proof', async () => {
  const { messages, reconciled } = await replay({ earlyStartup: starting });
  assert.deepEqual(messages, []);
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].sourceRequest.payload.phase, 'READY');
});

test('a pending semantic startup state after the adopted ready bar preserves the ledger error', async () => {
  const { messages, reconciled } = await replay({ laterStartup: starting });
  assert.deepEqual(messages, [nativeError.message]);
  assert.deepEqual(reconciled, []);
});

for (const [label, value] of [['historical', 'historical'], ['current private', 'adopted'], ['explicitly empty', '']]) {
  test(`a global startup witness carrying the ${label} routing header preserves the ledger error`, async () => {
    const { messages, reconciled } = await replay({ startupHeaders: { 'x-taxonomy-workspace-id': value } });
    assert.deepEqual(messages, [nativeError.message]);
    assert.deepEqual(reconciled, []);
  });
}

for (const lateFailure of [false, true]) {
  test(`a pre-binding original profiles request ${lateFailure ? 'with a late requestfailed' : 'still pending'} preserves the ledger error`, async () => {
    const { messages, reconciled } = await replay({ profilesBeforeBinding: true, observe: async ({ observer, failPending }) => {
      const { reads, events, navigations } = observer.snapshot();
      const earlier = reads[1], later = reads[2];
      const pageHide = events.find(event => event.type === 'pagehide');
      const documentStart = events.findLast(event => event.type === 'document-start');
      assert.equal(earlier.pageUrl, destinationUrl);
      assert.equal(earlier.documentEpoch, navigations[0].oldDocument.epoch);
      assert.ok(pageHide.sequence < earlier.sequence && earlier.sequence < documentStart.sequence);
      assert.equal(later.documentEpoch, navigations[0].newDocument.epoch);
      assert.equal(later.jsonParsed, true);
      if (lateFailure) {
        failPending();
        const failed = events.find(event => event.type === 'poll-request-failed');
        assert.equal(failed.requestId, earlier.id);
        assert.ok(failed.sequence > navigations[0].endSequence);
      }
    } });
    assert.deepEqual(messages, [nativeError.message]);
    assert.deepEqual(reconciled, []);
  });
}

test('profiles with identical JSON under a non-JSON media type preserve the ledger error', async () => {
  const { observer, messages, reconciled } = await replay({ profilesContentType: 'text/plain; note="application/json"' });
  assert.deepEqual(observer.snapshot().reads[1].payload, [profile], 'The existing parser successfully consumed these bytes');
  assert.deepEqual(messages, [nativeError.message]);
  assert.deepEqual(reconciled, []);
});

test('profiles allow ordinary charset parameters on the application/json media type', async () => {
  const { messages, reconciled } = await replay({ profilesContentType: 'Application/JSON; charset=utf-8' });
  assert.deepEqual(messages, []);
  assert.equal(reconciled.length, 1);
});
