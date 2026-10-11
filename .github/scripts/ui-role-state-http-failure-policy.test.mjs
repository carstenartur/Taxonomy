import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(
  new URL('./ui-role-state-acceptance.mjs', import.meta.url),
  'utf8'
);
const systemInformationSource = await readFile(
  new URL('./system-information-acceptance.mjs', import.meta.url),
  'utf8'
);

test('only request-correlated successful draft reconciliation removes a 409 from blockers', () => {
  assert.match(source, /failure\?\.status === 409/);
  assert.match(source, /\^\\\/api\\\/analysis-drafts\\\/\[\^\/\]\+\$/);
  assert.match(source, /Boolean\(failure\.requestId\)/);
  assert.match(source, /failure\.requestId === reconciliation\?\.requestId/);
  assert.match(
    source,
    /\['stale-local-version', 'write-already-committed'\]\.includes\(reconciliation\?\.reason\)/
  );
  assert.match(source, /reconciledHttpFailures\.push\(\{ \.\.\.failure, reconciliation \}\)/);
  assert.match(source, /else \{\s*httpFailures\.push\(failure\);\s*\}/);
});

test('reconciled failures remain explicit report evidence', () => {
  assert.match(source, /draftReconciliations, reconciledHttpFailures, consoleErrors/);
  assert.match(source, /reconciledConsoleErrors/);
  assert.match(source, /httpFailures,/);
});

test('system-information audit attributes only errors introduced by that flow', () => {
  assert.match(source, /const previousSystemHttpFailures = httpFailures\.length;/);
  assert.match(source, /const previousSystemConsoleErrors = consoleErrors\.length;/);
  assert.match(source, /const previousSystemExternalRequests = externalRequests\.length;/);
  assert.match(source,
    /httpFailures\.length !== previousSystemHttpFailures\s*\|\|\s*consoleErrors\.length !== previousSystemConsoleErrors\s*\|\|\s*externalRequests\.length !== previousSystemExternalRequests/);
  assert.doesNotMatch(source,
    /httpFailures\.length !== previousFailures \|\| consoleErrors\.length \|\| externalRequests\.length/);
});

test('webkit reconciliation is bound to each locale navigation window', () => {
  assert.match(source, /let systemInformationNavigationLocale = null;/);
  assert.match(source, /const navigationConsoleCandidates = \[\];/);
  assert.match(source, /locale: systemInformationNavigationLocale/);
  assert.match(source, /onLocaleNavigationStart: locale => \{/);
  assert.match(source, /onLocaleNavigationEnd: locale => \{/);
  assert.match(source, /webkit-locale-navigation-ai-status-cancelled/);
  assert.doesNotMatch(source,
    /for \(let index = consoleErrors\.length - 1; index >= previousSystemConsoleErrors; index -= 1\)/);
});

test('AI bootstrap never treats the untranslated unknown key as settled', () => {
  assert.match(systemInformationSource, /browse\.ai\.badge\.unknown/);
  assert.match(systemInformationSource, /value !== 'browse\.ai\.badge\.unknown'/);
  assert.match(systemInformationSource, /AI status bootstrap must settle after locale navigation/);
  assert.match(systemInformationSource, /onLocaleNavigationStart\?\.\(locale\)/);
  assert.match(systemInformationSource, /onLocaleNavigationEnd\?\.\(locale\)/);
  assert.match(systemInformationSource, /aiStatusSettled: true/);
});


// Exercise the real navigation precondition with a controlled draft promise.
async function navigationHarness({ initial = {}, after, outcome = true, pending } = {}) {
  const vm = await import('node:vm');
  const mod = await import('./system-information-acceptance.mjs');
  assert.equal(typeof mod.settleDraftBeforeLocaleNavigation, 'function');
  let saves = 0;
  let state = { workspaceId: 'workspace', ready: true, restoring: false, conflict: false, ...initial };
  const context = vm.createContext({ window: { TaxonomyAnalysisSession: {
    state: () => state,
    saveNow: async () => { saves++; const result = pending ? await pending : outcome; if (after) state = { ...state, ...after }; return result; }
  } } });
  const page = { evaluate: fn => vm.runInContext('(' + fn.toString() + ')()', context) };
  return { run: () => mod.settleDraftBeforeLocaleNavigation(page), saves: () => saves };
}

test('locale navigation awaits the authoritative draft write', async () => {
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const h = await navigationHarness({ pending });
  let settled = false;
  const work = h.run().then(() => { settled = true; });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.saves(), 1);
  assert.equal(settled, false, 'Navigation must not overtake an in-flight write');
  release(true); await work; assert.equal(settled, true);
});

for (const [label, initial] of Object.entries({ conflict: { conflict: true }, restoring: { restoring: true }, missingWorkspace: { workspaceId: '' } })) {
  test('locale navigation stops before saving an unsafe draft: ' + label, async () => {
    const h = await navigationHarness({ initial });
    await assert.rejects(h.run(), /draft.*ready/i); assert.equal(h.saves(), 0);
  });
}

test('failed autosave blocks locale navigation instead of filtering its error', async () => {
  const h = await navigationHarness({ outcome: false });
  await assert.rejects(h.run(), /draft.*saved/i);
});

test('a workspace switch during saving invalidates locale navigation', async () => {
  const h = await navigationHarness({ after: { workspaceId: 'different' } });
  await assert.rejects(h.run(), /workspace|draft.*changed/i);
});

test('system-information locale reload flushes drafts before its navigation window', async () => {
  assert.match(systemInformationSource, /await settleDraftBeforeLocaleNavigation\(page\);[\s\S]*onLocaleNavigationStart\?\.\(locale\)/);
});

// Run the actual acceptance observer/reconciliation code. Only the Playwright
// event boundary is simulated; the classification is never duplicated here.
function consoleAudit(browserName = 'webkit') {
  const functionsStart = source.indexOf('function reconcileSystemInformationConsoleErrors(');
  const functionsEnd = source.indexOf('\ntry {\n', functionsStart);
  const observersStart = source.indexOf("  page.on('console',");
  const observersEnd = source.indexOf("\n  taskMeasurements.failedStep = 'isolating", observersStart);
  assert.ok(functionsStart >= 0 && functionsEnd > functionsStart);
  assert.ok(observersStart >= 0 && observersEnd > observersStart);
  return new Function('browserName', `
    const baseUrl = 'http://127.0.0.1:8080';
    const consoleErrors = [], reconciledConsoleErrors = [], navigationConsoleCandidates = [];
    let systemInformationNavigationLocale = null;
    const handlers = {};
    const page = { on: (event, handler) => { handlers[event] = handler; } };
    ${source.slice(functionsStart, functionsEnd)}
    ${source.slice(observersStart, observersEnd)}
    return {
      errors: consoleErrors, reconciled: reconciledConsoleErrors,
      begin(locale) { systemInformationNavigationLocale = locale; },
      end() { systemInformationNavigationLocale = null; },
      emit(message, event = 'console') {
        handlers[event](event === 'console'
          ? { type: () => 'error', text: () => message } : { message });
      },
      settle(cases) { reconcileSystemInformationConsoleErrors(cases); }
    };
  `)(browserName);
}

const settledLocale = { locale: 'de', aiStatusSettled: true, operationalStatusSettled: true,
  operationalBranch: 'draft' };
const contextCancellation = '/127.0.0.1:8080/api/context/current due to access control checks.';
const gitCancellation = '/127.0.0.1:8080/api/git/state?branch=draft due to access control checks.';

for (const event of ['console', 'pageerror']) {
  test(`verified locale reload reconciles both observed WebKit status cancellations from ${event}`, () => {
    const audit = consoleAudit();
    audit.begin('de');
    audit.emit(contextCancellation, event);
    audit.emit(gitCancellation, event);
    audit.end();
    audit.settle([settledLocale]);
    assert.deepEqual(audit.errors, []);
    assert.equal(audit.reconciled.length, 2);
    assert.deepEqual(audit.reconciled.map(item => item.message), [contextCancellation, gitCancellation]);
  });
}

test('status cancellations remain blockers until the destination operational status is verified', () => {
  const audit = consoleAudit();
  audit.begin('de'); audit.emit(contextCancellation); audit.end();
  audit.settle([{ locale: 'de', aiStatusSettled: true }]);
  assert.deepEqual(audit.errors, [contextCancellation]);
  assert.equal(audit.reconciled.length, 0);
});

test('a different successful locale cannot reconcile the failed navigation', () => {
  const audit = consoleAudit();
  audit.begin('en'); audit.emit(contextCancellation); audit.end();
  audit.settle([settledLocale]);
  assert.deepEqual(audit.errors, [contextCancellation]);
});

for (const operationalBranch of ['other', undefined]) {
  test(`Git cancellation requires the same verified destination branch: ${operationalBranch}`, () => {
    const audit = consoleAudit();
    audit.begin('de'); audit.emit(gitCancellation); audit.end();
    audit.settle([{ ...settledLocale, operationalBranch }]);
    assert.deepEqual(audit.errors, [gitCancellation]);
    assert.equal(audit.reconciled.length, 0);
  });
}

test('an encoded Git branch is compared with the decoded verified destination branch', () => {
  const audit = consoleAudit();
  audit.begin('de');
  audit.emit('/127.0.0.1:8080/api/git/state?branch=feature%2Fone%2Btwo due to access control checks.');
  audit.end();
  audit.settle([{ ...settledLocale, operationalBranch: 'feature/one+two' }]);
  assert.deepEqual(audit.errors, []);
  assert.equal(audit.reconciled[0].requestedBranch, 'feature/one+two');
});

test('navigation reconciliation never admits other origins, endpoints or ordinary failures', () => {
  const messages = [
    'Fetch API cannot load https://other.invalid/api/ai-status due to access control checks.',
    '/127.0.0.1:8080/api/analysis-drafts/123 due to access control checks.',
    '/127.0.0.1:8080/api/admin/system-information due to access control checks.',
    '/127.0.0.1:8080/api/context/current?unexpected=1 due to access control checks.',
    '/127.0.0.1:8080/api/git/state?branch=draft&unexpected=1 due to access control checks.',
    '/127.0.0.1:8080/api/context/current failed with HTTP 503',
    'Uncaught TypeError: application failed'
  ];
  const audit = consoleAudit();
  audit.begin('de'); messages.forEach(message => audit.emit(message)); audit.end();
  audit.settle([settledLocale]);
  assert.deepEqual(audit.errors, messages);
  assert.equal(audit.reconciled.length, 0);
});

for (const browserName of ['webkit', 'chromium', 'firefox']) {
  test(`ordinary ${browserName} console errors remain blockers outside locale navigation`, () => {
    const audit = consoleAudit(browserName);
    audit.emit(contextCancellation);
    audit.begin('de');
    if (browserName !== 'webkit') audit.emit(gitCancellation);
    audit.end(); audit.emit(contextCancellation);
    audit.settle([settledLocale]);
    assert.equal(audit.errors.length, browserName === 'webkit' ? 2 : 3);
    assert.equal(audit.reconciled.length, 0);
  });
}
