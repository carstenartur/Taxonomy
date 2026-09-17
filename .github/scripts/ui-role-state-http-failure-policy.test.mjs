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

test('webkit locale-navigation cancellation is reconciled narrowly and remains report evidence', () => {
  assert.match(source, /const reconciledConsoleErrors = \[\];/);
  assert.match(source, /browserName !== 'webkit'/);
  assert.match(source, /\\\/api\\\/ai-status due to access control checks/);
  assert.match(source, /webkit-locale-navigation-ai-status-cancelled/);
  assert.match(source, /reconciledConsoleErrors, consoleErrors/);
  assert.match(systemInformationSource, /browse\.ai\.badge\.unknown/);
  assert.match(systemInformationSource, /AI status bootstrap must settle after locale navigation/);
});
