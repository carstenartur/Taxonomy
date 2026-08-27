import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { validateShardPlan } from './ui-shard-plan.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const resultsRoot = path.resolve(
  repoRoot,
  process.env.TAXONOMY_UI_SHARD_RESULTS_ROOT || 'target/ui-verification');
const manifestPath = path.resolve(
  repoRoot,
  process.env.TAXONOMY_UI_APPLICATION_MANIFEST
    || 'target/ui-application/manifest.json');
const expectedCommit = process.env.TAXONOMY_UI_EXPECTED_COMMIT || '';

if (!/^[0-9a-f]{40}$/.test(expectedCommit)) {
  throw new Error('TAXONOMY_UI_EXPECTED_COMMIT must be one full Git commit SHA');
}

const plan = JSON.parse(await readFile(
  path.join(repoRoot, '.github', 'ui-shards.json'), 'utf8'));
const inventory = JSON.parse(await readFile(
  path.join(repoRoot, '.github', 'ui-acceptance-matrix.json'), 'utf8'));
const expectedScenarios = [
  'ui/desktop-chromium',
  'ui/desktop-firefox',
  'ui/tablet-chromium',
  'ui/mobile-chromium',
  'accessibility/desktop',
  'accessibility/tablet',
  'accessibility/mobile',
  ...inventory.primaryWorkflowProfiles.map(profile => `primary/${profile.id}`),
  ...inventory.profiles
    .filter(profile => !profile.textSpacing)
    .map(profile => `role-state/${profile.id}`),
  'special-modes/text-spacing-and-offline'
];
validateShardPlan(plan, expectedScenarios);

const application = JSON.parse(await readFile(manifestPath, 'utf8'));
if (application.schemaVersion !== 1
    || application.sourceCommit !== expectedCommit
    || !/^[0-9a-f]{40}$/.test(application.sourceTree || '')
    || !/^taxonomy-app-[A-Za-z0-9._+-]+\.jar$/.test(application.jarName || '')
    || !/^[0-9a-f]{64}$/.test(application.sha256 || '')) {
  throw new Error('UI application manifest is not bound to the expected commit and digest');
}
const applicationJarPath = path.join(path.dirname(manifestPath), application.jarName);
const actualApplicationSha256 = createHash('sha256')
  .update(await readFile(applicationJarPath))
  .digest('hex');
if (actualApplicationSha256 !== application.sha256) {
  throw new Error(
    `UI application digest mismatch; expected ${application.sha256}, `
    + `actual ${actualApplicationSha256}`);
}

const expectedShardIds = new Set(plan.shards.map(shard => shard.id));
const actualTopLevel = (await readdir(resultsRoot, { withFileTypes: true }))
  .filter(entry => entry.isDirectory())
  .map(entry => entry.name)
  .sort();
const unexpectedDirectories = actualTopLevel.filter(name => !expectedShardIds.has(name));
const missingDirectories = [...expectedShardIds].filter(name => !actualTopLevel.includes(name));
if (unexpectedDirectories.length || missingDirectories.length) {
  throw new Error(
    `UI shard evidence directory mismatch; missing=[${missingDirectories.join(', ')}], `
    + `unexpected=[${unexpectedDirectories.join(', ')}]`);
}

const observedScenarios = new Map();
const shardSummaries = [];
for (const shard of plan.shards) {
  const reportPath = path.join(resultsRoot, shard.id, 'timings.json');
  const report = JSON.parse(await readFile(reportPath, 'utf8'));
  if (report.schemaVersion !== 1
      || report.requestedShard !== shard.id
      || report.outcome !== 'passed'
      || report.sourceCommit !== expectedCommit
      || report.applicationArtifactSha256 !== application.sha256) {
    throw new Error(`UI shard ${shard.id} is not successful and artifact-bound`);
  }

  if (!Array.isArray(report.groups)
      || report.applicationStartCount !== report.groups.length
      || !Number.isInteger(report.durationMs)
      || report.durationMs < 0) {
    throw new Error(`UI shard ${shard.id} has inconsistent group or timing metadata`);
  }

  const scenarioKeys = [];
  for (const group of report.groups) {
    if (group.outcome !== 'passed'
        || !Array.isArray(group.scenarios)
        || group.scenarioCount !== group.scenarios.length) {
      throw new Error(`UI shard ${shard.id} has an inconsistent application group`);
    }
    for (const scenario of group.scenarios) {
      const key = `${scenario.suite}/${scenario.id}`;
      if (scenario.outcome !== 'passed') {
        throw new Error(`UI scenario ${key} did not pass in shard ${shard.id}`);
      }
      if (observedScenarios.has(key)) {
        throw new Error(
          `UI scenario ${key} appears in both ${observedScenarios.get(key)} and ${shard.id}`);
      }
      observedScenarios.set(key, shard.id);
      scenarioKeys.push(key);
    }
  }

  const expectedForShard = [...shard.scenarios].sort();
  const actualForShard = [...scenarioKeys].sort();
  if (JSON.stringify(actualForShard) !== JSON.stringify(expectedForShard)
      || report.scenarioCount !== expectedForShard.length) {
    throw new Error(
      `UI shard ${shard.id} scenario inventory mismatch; `
      + `expected=${expectedForShard.join(', ')}, actual=${actualForShard.join(', ')}`);
  }

  shardSummaries.push({
    id: shard.id,
    scenarioCount: report.scenarioCount,
    applicationStartCount: report.applicationStartCount,
    durationMs: report.durationMs,
    outcome: report.outcome
  });
}

const observedKeys = [...observedScenarios.keys()].sort();
const expectedKeys = [...expectedScenarios].sort();
if (JSON.stringify(observedKeys) !== JSON.stringify(expectedKeys)) {
  const missing = expectedKeys.filter(key => !observedScenarios.has(key));
  const extra = observedKeys.filter(key => !expectedKeys.includes(key));
  throw new Error(
    `Consolidated UI scenario inventory mismatch; missing=[${missing.join(', ')}], `
    + `extra=[${extra.join(', ')}]`);
}

const summary = {
  schemaVersion: 1,
  sourceCommit: expectedCommit,
  sourceTree: application.sourceTree,
  applicationJar: application.jarName,
  applicationArtifactSha256: application.sha256,
  shardCount: plan.shards.length,
  scenarioCount: expectedScenarios.length,
  outcome: 'passed',
  shards: shardSummaries
};
await mkdir(resultsRoot, { recursive: true });
await writeFile(
  path.join(resultsRoot, 'summary.json'),
  `${JSON.stringify(summary, null, 2)}\n`,
  'utf8');
console.log(
  `Verified ${summary.scenarioCount} UI scenarios exactly once in `
  + `${summary.shardCount} artifact-bound shards for ${expectedCommit}.`);
