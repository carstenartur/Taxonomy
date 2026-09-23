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

