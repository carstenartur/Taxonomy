import assert from 'node:assert/strict';
import {
  mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { verifyAppTestShards } from './verify-app-test-shards.mjs';

const commit = 'a'.repeat(40);
const tree = 'b'.repeat(40);
const groups = {
  core: { tests: ['CoreTest'] },
  restart: { tests: ['RestartTest'] },
};

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'taxonomy-app-shards-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const sourceRoot = join(root, 'taxonomy-app/src/test/java');
  const evidenceRoot = join(root, 'target/app-test-shards');
  for (const name of ['CoreTest', 'RestartTest']) {
    const source = join(sourceRoot, `com/taxonomy/${name}.java`);
    mkdirSync(join(source, '..'), { recursive: true });
    writeFileSync(source, `package com.taxonomy; class ${name} { @org.junit.jupiter.api.Test void test() {} }\n`);
  }
  for (const [group, name] of [['core', 'CoreTest'], ['restart', 'RestartTest']]) {
    const directory = join(evidenceRoot, group);
    mkdirSync(join(directory, 'tests'), { recursive: true });
    const report = `tests/TEST-com.taxonomy.${name}.xml`;
    writeFileSync(join(directory, report),
      `<testsuite name="com.taxonomy.${name}" tests="1" failures="0" errors="0" skipped="0" time="1.25"><testcase name="test"/></testsuite>\n`);
    writeFileSync(join(directory, 'coverage.exec'), `${group}-coverage`);
    writeFileSync(join(directory, 'manifest.json'), JSON.stringify({
      schemaVersion: 1, shard: group, commit, sourceTree: tree,
      buildId: `123.1.${group}`,
      reports: [{ path: report, sha256: '' }],
      coverage: { path: 'coverage.exec', sha256: '' },
    }));
  }
  return { sourceRoot, evidenceRoot };
}

function verify(paths, selectedGroups = groups, expectedCommit = commit, expectedTree = tree) {
  return verifyAppTestShards({
    sourceRoot: paths.sourceRoot,
    evidenceRoot: paths.evidenceRoot,
    groups: selectedGroups,
    expectedCommit,
    expectedTree,
  });
}

test('discovers newly added test classes into the default shard', t => {
  const paths = fixture(t);
  const source = join(paths.sourceRoot, 'com/taxonomy/NewRegressionTest.java');
  writeFileSync(source, 'package com.taxonomy; class NewRegressionTest { @org.junit.jupiter.api.Test void test() {} }\n');
  const report = join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.NewRegressionTest.xml');
  writeFileSync(report,
    '<testsuite name="com.taxonomy.NewRegressionTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>\n');
  const manifest = JSON.parse(readFileSync(join(paths.evidenceRoot, 'core/manifest.json'), 'utf8'));
  manifest.reports.push({ path: 'tests/TEST-com.taxonomy.NewRegressionTest.xml', sha256: '' });
  writeFileSync(join(paths.evidenceRoot, 'core/manifest.json'), JSON.stringify(manifest));

  assert.equal(verify(paths).tests.classes, 3);
});

test('rejects overlapping shard assignments and unknown shard names', t => {
  const paths = fixture(t);
  assert.throws(() => verify(paths, {
    core: { tests: ['CoreTest', 'RestartTest'] },
    restart: { tests: ['RestartTest'] },
  }), /assigned more than once/);
  assert.throws(() => verify(paths, {
    core: { tests: ['CoreTest'] },
    unknown: { tests: ['RestartTest'] },
  }), /unknown shard/);
});

test('rejects a missing suite report instead of accepting the shard artifact', t => {
  const paths = fixture(t);
  rmSync(join(paths.evidenceRoot, 'restart/tests/TEST-com.taxonomy.RestartTest.xml'));
  assert.throws(() => verify(paths), /missing report.*RestartTest/i);
});

test('binds every shard report to the exact commit and source tree', t => {
  const paths = fixture(t);
  assert.throws(() => verify(paths, groups, 'c'.repeat(40)), /commit mismatch/);
  assert.throws(() => verify(paths, groups, commit, 'd'.repeat(40)), /source tree mismatch/);
});

test('rejects duplicate or wrong-source suite reports', t => {
  const paths = fixture(t);
  const core = join(paths.evidenceRoot, 'core');
  const duplicate = 'tests/TEST-com.taxonomy.RestartTest.xml';
  writeFileSync(join(core, duplicate),
    '<testsuite name="com.taxonomy.RestartTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>\n');
  const manifest = JSON.parse(readFileSync(join(core, 'manifest.json'), 'utf8'));
  manifest.reports.push({ path: duplicate, sha256: '' });
  writeFileSync(join(core, 'manifest.json'), JSON.stringify(manifest));
  assert.throws(() => verify(paths), /appears in more than one shard/);

  rmSync(join(core, duplicate));
  manifest.reports.pop();
  const wrong = 'tests/TEST-com.taxonomy.MissingTest.xml';
  writeFileSync(join(core, wrong),
    '<testsuite name="com.taxonomy.MissingTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>\n');
  manifest.reports.push({ path: wrong, sha256: '' });
  writeFileSync(join(core, 'manifest.json'), JSON.stringify(manifest));
  assert.throws(() => verify(paths), /no matching test source/);
});

test('requires coverage executions from every shard and keeps skipped tests explicit', t => {
  const paths = fixture(t);
  writeFileSync(join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.CoreTest.xml'),
    '<testsuite name="com.taxonomy.CoreTest" tests="2" failures="0" errors="0" skipped="1"><testcase name="ran"/><testcase name="skipped"><skipped/></testcase></testsuite>\n');
  assert.equal(verify(paths).tests.skipped, 1);
  rmSync(join(paths.evidenceRoot, 'restart/coverage.exec'));
  assert.throws(() => verify(paths), /coverage execution.*restart/i);
});
