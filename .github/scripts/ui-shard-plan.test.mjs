import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { groupScenarios } from './ui-suite-plan.mjs';

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

test('CI runs successive user browser profiles against one application', () => {
  const profiles = ['desktop-user-chromium', 'landscape-user-firefox'];
  const shard = plan.shards.find(candidate =>
    candidate.scenarios.includes(scenarioKey('role-state', profiles[0])));
  for (const profile of profiles) {
    assert.ok(shard.scenarios.includes(scenarioKey('role-state', profile)),
      `Shared-draft regression requires ${profile} in the same CI shard`);
  }
  const scenarios = matrix.profiles
    .filter(profile => shard.scenarios.includes(scenarioKey('role-state', profile.id)))
    .map(profile => ({ suite: 'role-state', id: profile.id,
      env: { TAXONOMY_ROLE: profile.role } }));
  const group = groupScenarios(scenarios).find(candidate => candidate.id === 'role-state-user');
  assert.deepEqual(group.scenarios.map(scenario => scenario.id), profiles);
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

// Contract tests need the pinned Node packages, not native browser packages.
// Each shard runs on a different machine and must install its own runtime.
function maskXmlComments(xml) {
  // Mask rather than delete: joining fragments can invent XML delimiters.
  // This only prepares the repository-owned POM for assertions, not untrusted HTML.
  return xml.replace(/<!--[\s\S]*?-->/g, comment => comment.replace(/[^\r\n]/g, ' '));
}

const verificationPom = maskXmlComments(await readFile(
  path.join(repoRoot, '.github', 'ui-verification-pom.xml'), 'utf8'));

function verificationProfile(id) {
  const profiles = [...verificationPom.matchAll(/<profile>([\s\S]*?)<\/profile>/g)]
    .map(match => match[1])
    .filter(profile => profile.match(/<id>\s*([^<]+?)\s*<\/id>/)?.[1] === id);
  assert.equal(profiles.length, 1, `Exactly one ${id} verification profile is required`);
  return profiles[0];
}

function verificationExecutions(id) {
  return [...verificationProfile(id).matchAll(/<execution>([\s\S]*?)<\/execution>/g)]
    .map(match => {
      const text = tag => match[1].match(new RegExp(`<${tag}>\\s*([^<]*?)\\s*</${tag}>`))?.[1];
      assert.doesNotMatch(match[1], /<skip>\s*true\s*<\/skip>/);
      return { id: text('id'), phase: text('phase'), goal: text('goal'), arguments: text('arguments') };
    });
}

test('contract verification runs the complete contracts without native browser setup', () => {
  const executions = verificationExecutions('contracts');
  assert.ok(executions.some(step => step.goal === 'install-node-and-npm'));
  assert.ok(executions.some(step => step.arguments === 'ci --ignore-scripts --no-audit --no-fund'));
  for (const command of ['run test:api-transport', 'run verify:ui-contracts']) {
    assert.ok(executions.some(step => step.phase === 'test' && step.arguments === command), command);
  }
  assert.doesNotMatch(verificationProfile('contracts'), /install:playwright|<phase>\s*pre-integration-test\s*<\/phase>/,
    'A slow native package mirror must not block already-passing Node contracts');
});

test('browser shards still install their pinned runtime before executing acceptance', () => {
  const executions = verificationExecutions('shard');
  const setup = executions.find(step => step.arguments === 'run install:playwright:github');
  const acceptance = executions.find(step => step.arguments === 'run verify:ui-shard');
  assert.ok(setup, 'The browser-runtime installation remains mandatory on shard runners');
  assert.ok(acceptance, 'The actual browser acceptance remains mandatory');
  assert.equal(setup.phase, 'pre-integration-test');
  assert.equal(acceptance.phase, 'integration-test');
  assert.ok(executions.some(step => step.goal === 'install-node-and-npm'));
});

test('final evidence gate still validates all shard evidence without installing browsers', () => {
  const executions = verificationExecutions('evidence-gate');
  assert.ok(executions.some(step => step.phase === 'test' && step.arguments === 'run verify:ui-shards'));
  assert.doesNotMatch(verificationProfile('evidence-gate'), /install:playwright/);
  assert.match(verificationPom, /<maven\.build\.cache\.enabled>\s*false\s*<\/maven\.build\.cache\.enabled>/);
});


test('XML comment masking preserves boundaries and source positions', () => {
  const xml = '<project>\r\n<!-- <profile>ignored</profile>\ncomment -->\r\n<profile>real</profile></project>';
  const masked = maskXmlComments(xml);
  assert.equal(masked.length, xml.length);
  assert.equal(masked.indexOf('<profile>real'), xml.indexOf('<profile>real'));
  assert.equal(masked.split('\n').length, xml.split('\n').length);
  assert.doesNotMatch(masked, /ignored|comment/);
  assert.match(masked, /<profile>real<\/profile>/);
});

for (const [xml, token] of [
  ['<pro<!-- first -->file>not a profile</pro<!-- second -->file>', '<profile>'],
  ['<!<!-- nested fragments -->-->', '<!--'],
  ['--<!-- nested fragments -->>', '-->']
]) test(`XML comment masking cannot join fragments into ${token}`, () => {
  assert.equal(maskXmlComments(xml).includes(token), false);
});

test('XML comment masking leaves non-comment source unchanged and handles adjacent comments', () => {
  const source = '<profile><id>contracts</id></profile>';
  assert.equal(maskXmlComments(source), source);
  assert.equal(maskXmlComments('<!-- first --><!-- second -->').trim(), '');
});

for (const gate of ['verify:ui', 'verify:ui-contracts']) {
  test(`${gate} preserves both localization/state and search-readiness regressions`, async () => {
    const { scripts } = JSON.parse(await readFile(
      path.join(repoRoot, '.github', 'package.json'), 'utf8'));
    const commands = scripts[gate].split(' && ');
    for (const name of ['test:ui-localization', 'test:ux-state-recovery', 'test:search-mode-readiness']) {
      assert.ok(scripts[name], `Missing regression command ${name}`);
      assert.equal(commands.filter(command => command === `npm run ${name}`).length, 1,
        `${gate} must execute ${name} exactly once`);
    }
    assert.ok(commands.includes('node --test scripts/quality-dashboard-api.test.mjs'));
    assert.equal(commands.includes('node scripts/run-ui-suite.mjs'), gate === 'verify:ui');
    for (const [name, file] of [
      ['test:integrations-review', 'scripts/integration-startup.test.mjs'],
      ['test:state-render-consistency', '../taxonomy-app/src/test/js/diagram-keyboard-controls.cjs'],
      ['test:architecture-workbench-basepath', 'scripts/architecture-workbench-print.test.mjs']
    ]) assert.ok(scripts[name].split(' ').includes(file), `Missing ${file}`);
  });
}
