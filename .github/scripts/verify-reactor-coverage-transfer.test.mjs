import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { stageAppTestShard } from './verify-app-test-shards.mjs';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

// Execute the actual producer and consumer shell commands. Synthetic execution
// bytes test lossless transport; interpreting probes remains JaCoCo's job.
function aggregateFixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'taxonomy-aggregate-workflow-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = args => {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git(['init', '-q']);
  git(['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
    '-c', 'commit.gpgsign=false', 'commit', '-q', '--allow-empty', '-m', 'fixture']);
  const sha = git(['rev-parse', 'HEAD']);
  const sourceTree = git(['rev-parse', 'HEAD^{tree}']);
  const put = (path, content) => {
    const full = join(root, path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, content);
    return full;
  };
  const plan = {
    defaultGroup: 'core', excludedTags: [], requiredExecutions: [],
    groups: { core: { default: true, tests: [] },
      recovery: { tests: ['com.taxonomy.RecoveryTest'], tag: 'ci-recovery' },
      browser: { tests: ['com.taxonomy.BrowserTest'], tag: 'ci-browser' } },
  };
  put('.mvn/verification-suites.json', JSON.stringify({ applicationTestShards: plan }));
  put('.github/scripts/verify-app-test-shards.mjs', readFileSync(
    join(repositoryRoot, '.github/scripts/verify-app-test-shards.mjs')));
  for (const [shard, name] of [['core', 'CoreTest'], ['recovery', 'RecoveryTest'], ['browser', 'BrowserTest']]) {
    const tag = shard === 'core' ? '' : `@Tag("ci-${shard}") `;
    put(`taxonomy-app/src/test/java/com/taxonomy/${name}.java`,
      `package com.taxonomy; import org.junit.jupiter.api.Test; import org.junit.jupiter.api.Tag; ${tag}class ${name} { @Test void test() {} }`);
    const reports = join(root, 'taxonomy-app/target/surefire-reports');
    rmSync(reports, { recursive: true, force: true });
    put(`taxonomy-app/target/surefire-reports/TEST-com.taxonomy.${name}.xml`,
      `<testsuite name="com.taxonomy.${name}" tests="1" failures="0" errors="0" skipped="0" time="1"><testcase name="test"/></testsuite>`);
    put('taxonomy-app/target/jacoco.exec', `${shard}-coverage`);
    stageAppTestShard({ repositoryRoot: root, shard, commit: sha, sourceTree, buildId: `123.1.${shard}` });
  }
  put('taxonomy-app/target/classes/com/taxonomy/Fixture.class', Buffer.from([0xca, 0xfe, 0xba, 0xbe]));
  put('taxonomy-domain/target/classes/com/taxonomy/Upstream.class', Buffer.from([0xca, 0xfe, 0xba, 0xbe, 1]));
  put('taxonomy-domain/target/surefire-reports/TEST-com.taxonomy.UpstreamTest.xml',
    '<testsuite name="com.taxonomy.UpstreamTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>');
  const upstreamExec = put('taxonomy-domain/target/jacoco.exec', 'upstream-only-probes');
  const workflow = readFileSync(join(repositoryRoot, '.github/workflows/ci-cd.yml'), 'utf8');
  const env = { ...process.env, GITHUB_SHA: sha, GITHUB_STEP_SUMMARY: join(root, 'summary.md') };
  const shell = script => spawnSync('bash', ['-e', '-u', '-o', 'pipefail', '-c', script], {
    cwd: root, encoding: 'utf8', env, timeout: 30000,
  });
  const stage = () => shell(workflowStepScript(workflow, 'Stage full-reactor class outputs for aggregate coverage'));
  const consume = () => shell(workflowStepScript(workflow, 'Verify source-bound reactor outputs and shard inputs'));
  const verifyCli = () => spawnSync(process.execPath, [
    '.github/scripts/verify-app-test-shards.mjs', '--verify', 'true', '--commit', sha, '--source-tree', sourceTree,
  ], { cwd: root, encoding: 'utf8', env, timeout: 30000 });
  return { root, plan, put, upstreamExec, shell, stage, consume, verifyCli };
}

function prepareAggregate(t) {
  const paths = aggregateFixture(t);
  const result = paths.stage();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  return paths;
}

function workflowStepScript(workflow, name) {
  const marker = `      - name: ${name}\n`;
  const start = workflow.indexOf(marker);
  assert.notEqual(start, -1, `workflow step exists: ${name}`);
  const end = workflow.indexOf('\n      - ', start + marker.length);
  const step = workflow.slice(start, end < 0 ? workflow.length : end);
  const run = step.match(/^        run: \|\n((?:^          .*(?:\n|$)|^\s*\n)*)/m);
  assert.ok(run, `workflow step has a literal shell command: ${name}`);
  return run[1].split('\n').map(line => line.startsWith('          ') ? line.slice(10) : line)
    .join('\n');
}

test('the actual aggregate workflow accepts all three shards and writes validated timings', t => {
  const paths = prepareAggregate(t);
  const result = paths.consume();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const timings = JSON.parse(readFileSync(join(paths.root, 'target/app-test-shards/timings.json'), 'utf8'));
  assert.equal(timings.tests.classes, 3);
  assert.equal(timings.tests.cases, 3);
  assert.deepEqual(timings.coverage, ['core', 'recovery', 'browser']);
  assert.doesNotMatch(result.stdout, /NaN|undefined/);
});

for (const shard of ['core', 'recovery', 'browser']) {
  test(`the actual aggregate workflow fails closed without ${shard}`, t => {
    const paths = prepareAggregate(t);
    rmSync(join(paths.root, 'target/app-test-shards', shard), { recursive: true });
    const result = paths.consume();
    assert.notEqual(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stderr, /missing shard artifact|expected shard inputs/);
    assert.match(result.stderr, new RegExp(shard));
  });
}

test('the full verifier CLI enforces catalogued isolated execution identities', t => {
  const paths = aggregateFixture(t);
  paths.plan.requiredExecutions = [{ className: 'com.taxonomy.CoreTest', identities: ['default', 'module-gate-ci-anchor'] }];
  paths.put('.mvn/verification-suites.json', JSON.stringify({ applicationTestShards: paths.plan }));
  const result = paths.verifyCli();
  assert.notEqual(result.status, 0, 'CLI silently discarded requiredExecutions');
  assert.match(result.stderr, /CoreTest execution identities mismatch/);
});

test('the reactor archive restores upstream module execution data as well as classes', t => {
  const paths = prepareAggregate(t);
  const original = readFileSync(paths.upstreamExec);
  rmSync(join(paths.root, 'taxonomy-domain/target'), { recursive: true });
  const result = paths.consume();
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.deepEqual(readFileSync(paths.upstreamExec), original);
});

test('the reactor producer rejects a tested module with missing execution data', t => {
  const paths = aggregateFixture(t);
  rmSync(paths.upstreamExec);
  const result = paths.stage();
  assert.notEqual(result.status, 0, 'Producer accepted a tested module without execution data');
  assert.match(result.stderr + result.stdout, /Missing JaCoCo execution data.*taxonomy-domain/);
});

for (const corruption of ['missing', 'tampered']) {
  test(`the reactor consumer rejects ${corruption} upstream execution data even with a refreshed archive checksum`, t => {
    const paths = prepareAggregate(t);
    const unpack = paths.shell('tar -xzf target/core-reactor-outputs.tar.gz -C .');
    assert.equal(unpack.status, 0, unpack.stderr);
    if (corruption === 'missing') rmSync(paths.upstreamExec, { force: true });
    else writeFileSync(paths.upstreamExec, 'different upstream probes');
    const archive = paths.shell(`
      set -euo pipefail
      find taxonomy-app/target/classes taxonomy-domain/target/classes -type f -print0 > target/corrupt-files
      find taxonomy-app taxonomy-domain -type f -path '*/target/jacoco.exec' -print0 >> target/corrupt-files
      printf '%s\\0' target/core-reactor-files.sha256 >> target/corrupt-files
      tar --null -T target/corrupt-files -czf target/core-reactor-outputs.tar.gz
      sha256sum target/core-reactor-outputs.tar.gz > target/core-reactor-outputs.sha256
    `);
    assert.equal(archive.status, 0, archive.stderr);
    const result = paths.consume();
    assert.notEqual(result.status, 0, `Consumer accepted ${corruption} upstream execution data`);
    assert.match(result.stderr + result.stdout, /taxonomy-domain\/target\/jacoco.exec|checksum/);
  });
}

test('the aggregate profile cannot overwrite transferred coverage through the Maven build cache', () => {
  const pom = readFileSync(join(repositoryRoot, 'pom.xml'), 'utf8');
  const profile = pom.match(/<profile>\s*<id>ci-app-test-shards-coverage<\/id>([\s\S]*?)<\/profile>/)?.[1];
  assert.ok(profile, 'The aggregate profile must exist');
  assert.match(profile, /<maven.build.cache.enabled>false<\/maven.build.cache.enabled>/);
});

test('Maven-owned frontend verification executes both shard regression suites', () => {
  const { scripts } = JSON.parse(readFileSync(join(repositoryRoot, '.github/package.json'), 'utf8'));
  assert.equal(scripts['test:app-test-shards'],
    'node --test scripts/verify-app-test-shards.test.mjs scripts/verify-reactor-coverage-transfer.test.mjs');
  for (const entryPoint of ['verify:ui', 'verify:ui-contracts']) {
    assert.match(scripts[entryPoint], /(?:^|&&\s*)npm run test:app-test-shards(?:\s*&&|$)/);
  }
});

test('a test-only module with an empty classes directory does not need execution data', t => {
  const paths = aggregateFixture(t);
  mkdirSync(join(paths.root, 'taxonomy-build/target/classes'), { recursive: true });
  paths.put('taxonomy-build/target/surefire-reports/TEST-com.taxonomy.build.PolicyTest.xml',
    '<testsuite name="com.taxonomy.build.PolicyTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>');
  const result = paths.stage();
  assert.equal(result.status, 0, result.stdout + result.stderr);
});
