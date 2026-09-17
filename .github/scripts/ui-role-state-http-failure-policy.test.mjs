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
