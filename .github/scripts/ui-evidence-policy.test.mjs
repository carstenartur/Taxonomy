import assert from 'node:assert/strict';
import test from 'node:test';
import { createEvidencePolicy, screenshotStrategy } from './ui-evidence-policy.mjs';

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

test('compact captured states always use one bounded viewport image', () => {
  assert.equal(screenshotStrategy({
    mode: 'compact', captured: true, width: 120_000, height: 240_000
  }), 'viewport');
});

test('full mode keeps complete evidence with bounded or segmented capture', () => {
  assert.equal(screenshotStrategy({
    mode: 'full', captured: true, width: 1_440, height: 10_000
  }), 'full-page');
  assert.equal(screenshotStrategy({
    mode: 'full', captured: true, width: 1_440, height: 30_001
  }), 'segmented');
});

test('uncaptured states never create screenshots', () => {
  assert.equal(screenshotStrategy({
    mode: 'compact', captured: false, width: 1_440, height: 10_000
  }), 'none');
});
