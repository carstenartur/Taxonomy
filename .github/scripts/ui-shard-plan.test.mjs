import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  scenarioKey,
  shardScenarioKeys,
  validateShardPlan
} from './ui-shard-plan.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const matrix = JSON.parse(await readFile(
  path.join(repoRoot, '.github', 'ui-acceptance-matrix.json'), 'utf8'));
const plan = JSON.parse(await readFile(
  path.join(repoRoot, '.github', 'ui-shards.json'), 'utf8'));

const expected = [
  'ui/desktop-chromium',
  'ui/desktop-firefox',
  'ui/tablet-chromium',
  'ui/mobile-chromium',
  'accessibility/desktop',
  'accessibility/tablet',
  'accessibility/mobile',
  ...matrix.primaryWorkflowProfiles.map(profile => scenarioKey('primary', profile.id)),
  ...matrix.profiles
    .filter(profile => !profile.textSpacing)
    .map(profile => scenarioKey('role-state', profile.id)),
  'special-modes/text-spacing-and-offline'
];

test('authoritative shard plan covers all eighteen browser scenarios exactly once', () => {
  assert.doesNotThrow(() => validateShardPlan(plan, expected));
  assert.equal(expected.length, 18);
  assert.equal(plan.shards.length, 6);
});

test('shard selection is repository-defined and rejects unknown ids', () => {
  assert.deepEqual(
    [...shardScenarioKeys(plan, 'architect-and-a11y-desktop')],
    ['role-state/desktop-architect-firefox', 'accessibility/desktop']);
  assert.throws(() => shardScenarioKeys(plan, 'not-a-shard'), /Unknown UI shard/);
});

test('duplicate and missing assignments fail closed', () => {
  const duplicate = structuredClone(plan);
  duplicate.shards[1].scenarios.push(duplicate.shards[0].scenarios[0]);
  assert.throws(() => validateShardPlan(duplicate, expected), /assigned to both/);

  const missing = structuredClone(plan);
  missing.shards[0].scenarios.shift();
  assert.throws(() => validateShardPlan(missing, expected), /missing scenarios/);
});
