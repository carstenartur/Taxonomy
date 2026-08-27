import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(
  new URL('./ui-role-state-acceptance.mjs', import.meta.url),
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
