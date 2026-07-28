import assert from 'node:assert/strict';
import test from 'node:test';
import { createEvidencePolicy } from './ui-evidence-policy.mjs';

test('compact mode retains only curated successful states', () => {
  const policy = createEvidencePolicy({ mode: 'compact', curatedStates: 'analysis-success' });
  assert.equal(policy.mode, 'compact');
  assert.equal(policy.shouldCapture('analysis-success'), true);
  assert.equal(policy.shouldCapture('analysis-error'), false);
});

test('full mode retains every successful state', () => {
  const policy = createEvidencePolicy({ mode: 'full', curatedStates: [] });
  assert.equal(policy.shouldCapture('analysis-success'), true);
  assert.equal(policy.shouldCapture('dialog-open'), true);
});

test('curated state parsing trims and removes duplicates', () => {
  const policy = createEvidencePolicy({
    mode: 'compact',
    curatedStates: ' analysis-success,dialog-open,analysis-success '
  });
  assert.deepEqual(policy.curatedStates, ['analysis-success', 'dialog-open']);
});

test('unsupported evidence mode fails before browser execution', () => {
  assert.throws(
    () => createEvidencePolicy({ mode: 'everything' }),
    /expected compact or full/
  );
});
