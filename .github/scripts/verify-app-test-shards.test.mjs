import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  stageAppTestShard, verifyAppTestShardInputs, verifyAppTestShards,
} from './verify-app-test-shards.mjs';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const commit = 'a'.repeat(40);
const tree = 'b'.repeat(40);
const groups = {
  defaultGroup: 'core',
  excludedTags: [],
  groups: {
    core: { default: true },
    restart: { tests: ['com.taxonomy.RestartTest'], tag: 'ci-restart' },
  },
};

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'taxonomy-app-shards-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const sourceRoot = join(root, 'taxonomy-app/src/test/java');
  const evidenceRoot = join(root, 'target/app-test-shards');
  for (const name of ['CoreTest', 'RestartTest']) {
    const source = join(sourceRoot, `com/taxonomy/${name}.java`);
    mkdirSync(join(source, '..'), { recursive: true });
    const tag = name === 'RestartTest' ? '@Tag("ci-restart") ' : '';
    writeFileSync(source,
      `package com.taxonomy;\n// application's test identity must not hide its annotations\nimport org.junit.jupiter.api.Test; import org.junit.jupiter.api.Tag; ${tag}class ${name} { @Test void test() {} }\n`);
  }
  for (const [group, name] of [['core', 'CoreTest'], ['restart', 'RestartTest']]) {
    const directory = join(evidenceRoot, group);
    mkdirSync(join(directory, 'tests'), { recursive: true });
    const report = `tests/TEST-com.taxonomy.${name}.xml`;
    const reportFile = join(directory, report);
    writeFileSync(reportFile,
      `<testsuite name="com.taxonomy.${name}" tests="1" failures="0" errors="0" skipped="0" time="1.25"><testcase name="test"/></testsuite>\n`);
    const coverageFile = join(directory, 'coverage.exec');
    writeFileSync(coverageFile, `${group}-coverage`);
    writeFileSync(join(directory, 'manifest.json'), JSON.stringify({
      schemaVersion: 1, shard: group, commit, sourceTree: tree,
      buildId: `123.1.${group}`,
      reports: [{ path: report, className: `com.taxonomy.${name}`, sha256: digest(reportFile) }],
      coverage: { path: 'coverage.exec', sha256: digest(coverageFile) },
    }));
  }
  return { root, sourceRoot, evidenceRoot };
}

function verify(paths, selectedGroups = groups, expectedCommit = commit, expectedTree = tree) {
  return verifyAppTestShards({
    repositoryRoot: paths.root,
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
  writeFileSync(source,
    'package com.taxonomy; import org.junit.jupiter.api.Test; class NewRegressionTest { @Test void test() {} }\n');
  const report = join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.NewRegressionTest.xml');
  writeFileSync(report,
    '<testsuite name="com.taxonomy.NewRegressionTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>\n');
  const manifest = JSON.parse(readFileSync(join(paths.evidenceRoot, 'core/manifest.json'), 'utf8'));
  manifest.reports.push({
    path: 'tests/TEST-com.taxonomy.NewRegressionTest.xml',
    className: 'com.taxonomy.NewRegressionTest', sha256: digest(report),
  });
  writeFileSync(join(paths.evidenceRoot, 'core/manifest.json'), JSON.stringify(manifest));

  assert.equal(verify(paths).tests.classes, 3);
});

test('preserves test identities when Java comments contain apostrophes', t => {
  const paths = fixture(t);
  assert.equal(verify(paths).tests.classes, 2);
});

test('rejects overlapping shard assignments and unknown shard names', t => {
  const paths = fixture(t);
  assert.throws(() => verify(paths, {
    defaultGroup: 'core',
    groups: {
      core: { default: true, tests: ['com.taxonomy.RestartTest'] },
      restart: { tests: ['com.taxonomy.RestartTest'], tag: 'ci-restart' },
    },
  }), /assigned more than once/);
  assert.throws(() => verify(paths, {
    defaultGroup: 'core',
    groups: {
      core: { default: true },
      unknown: { tests: ['com.taxonomy.RestartTest'], tag: 'ci-restart' },
    },
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

test('rejects malformed or externally defined JUnit XML', t => {
  const paths = fixture(t);
  const file = join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.CoreTest.xml');
  writeFileSync(file,
    '<testsuite name="com.taxonomy.CoreTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="open"></testsuite>\n');
  const manifest = JSON.parse(readFileSync(join(paths.evidenceRoot, 'core/manifest.json'), 'utf8'));
  manifest.reports[0].sha256 = digest(file);
  writeFileSync(join(paths.evidenceRoot, 'core/manifest.json'), JSON.stringify(manifest));
  assert.throws(() => verify(paths), /malformed JUnit XML nesting/);

  writeFileSync(file,
    '<!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/passwd">]><testsuite name="com.taxonomy.CoreTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>\n');
  manifest.reports[0].sha256 = digest(file);
  writeFileSync(join(paths.evidenceRoot, 'core/manifest.json'), JSON.stringify(manifest));
  assert.throws(() => verify(paths), /unsafe JUnit report/);
});

test('rejects duplicate or wrong-source suite reports', t => {
  const paths = fixture(t);
  const core = join(paths.evidenceRoot, 'core');
  const duplicate = 'tests/TEST-com.taxonomy.RestartTest.xml';
  const duplicateFile = join(core, duplicate);
  writeFileSync(duplicateFile,
    '<testsuite name="com.taxonomy.RestartTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>\n');
  const manifest = JSON.parse(readFileSync(join(core, 'manifest.json'), 'utf8'));
  manifest.reports.push({
    path: duplicate, className: 'com.taxonomy.RestartTest', sha256: digest(duplicateFile),
  });
  writeFileSync(join(core, 'manifest.json'), JSON.stringify(manifest));
  assert.throws(() => verify(paths), /appears in more than one shard/);

  rmSync(join(core, duplicate));
  manifest.reports.pop();
  const wrong = 'tests/TEST-com.taxonomy.MissingTest.xml';
  const wrongFile = join(core, wrong);
  writeFileSync(wrongFile,
    '<testsuite name="com.taxonomy.MissingTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>\n');
  manifest.reports.push({
    path: wrong, className: 'com.taxonomy.MissingTest', sha256: digest(wrongFile),
  });
  writeFileSync(join(core, 'manifest.json'), JSON.stringify(manifest));
  assert.throws(() => verify(paths), /no matching test source/);
});

test('requires coverage executions from every shard and keeps skipped tests explicit', t => {
  const paths = fixture(t);
  const report = join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.CoreTest.xml');
  writeFileSync(report,
    '<testsuite name="com.taxonomy.CoreTest" tests="2" failures="0" errors="0" skipped="1"><testcase name="ran"/><testcase name="skipped"><skipped/></testcase></testsuite>\n');
  const manifest = JSON.parse(readFileSync(join(paths.evidenceRoot, 'core/manifest.json'), 'utf8'));
  manifest.reports[0].sha256 = digest(report);
  writeFileSync(join(paths.evidenceRoot, 'core/manifest.json'), JSON.stringify(manifest));
  const result = verify(paths);
  assert.equal(result.tests.skipped, 1);
  assert.deepEqual(result.coverage, ['core', 'restart']);
  rmSync(join(paths.evidenceRoot, 'restart/coverage.exec'));
  assert.throws(() => verify(paths), /restart coverage execution/i);
});

test('preflights all non-core shard reports and coverage before the long core build', t => {
  const paths = fixture(t);
  rmSync(join(paths.evidenceRoot, 'core'), { recursive: true });
  const result = verifyAppTestShardInputs({
    repositoryRoot: paths.root,
    evidenceRoot: paths.evidenceRoot,
    groups,
    expectedCommit: commit,
    expectedTree: tree,
  });
  assert.deepEqual(result.groups, ['restart']);
  assert.deepEqual(result.classes, ['com.taxonomy.RestartTest']);
  assert.throws(() => verifyAppTestShardInputs({
    repositoryRoot: paths.root,
    evidenceRoot: paths.evidenceRoot,
    groups,
    expectedCommit: 'c'.repeat(40),
    expectedTree: tree,
  }), /commit mismatch/);
  rmSync(join(paths.evidenceRoot, 'restart/coverage.exec'));
  assert.throws(() => verifyAppTestShardInputs({
    repositoryRoot: paths.root,
    evidenceRoot: paths.evidenceRoot,
    groups,
    expectedCommit: commit,
    expectedTree: tree,
  }), /coverage execution is missing/);
});

test('stages real module reports and coverage with digest-bound timings', t => {
  const paths = fixture(t);
  const reports = join(paths.root, 'taxonomy-app/target/surefire-reports');
  mkdirSync(reports, { recursive: true });
  const report = join(reports, 'TEST-com.taxonomy.CoreTest.xml');
  writeFileSync(report,
    '<testsuite name="com.taxonomy.CoreTest" tests="1" failures="0" errors="0" skipped="0" time="2.5"><testcase name="test"/></testsuite>\n');
  writeFileSync(join(paths.root, 'taxonomy-app/target/jacoco.exec'), 'execution data');

  const manifest = stageAppTestShard({
    repositoryRoot: paths.root,
    shard: 'core',
    commit,
    sourceTree: tree,
    buildId: '123.1.core',
  });

  assert.equal(manifest.reports.length, 1);
  assert.equal(manifest.reports[0].className, 'com.taxonomy.CoreTest');
  assert.equal(manifest.timing.durationSeconds, 2.5);
  assert.equal(manifest.coverage.sha256,
    digest(join(paths.evidenceRoot, 'core/coverage.exec')));
});

test('Maven shard profiles and catalogued source tags stay synchronized', () => {
  const catalog = JSON.parse(readFileSync(join(repositoryRoot, '.mvn/verification-suites.json'), 'utf8'));
  const plan = catalog.applicationTestShards;
  const rootPom = readFileSync(join(repositoryRoot, 'pom.xml'), 'utf8');
  const appPom = readFileSync(join(repositoryRoot, 'taxonomy-app/pom.xml'), 'utf8');
  const coveragePom = readFileSync(join(repositoryRoot, 'taxonomy-coverage/pom.xml'), 'utf8');
  const buildPom = readFileSync(join(repositoryRoot, 'taxonomy-build/pom.xml'), 'utf8');
  const workflow = readFileSync(join(repositoryRoot, '.github/workflows/ci-cd.yml'), 'utf8');

  assert.equal(plan.defaultGroup, 'core');
  assert.equal(plan.groups.recovery.requiresEmbeddingModel, true);
  for (const [group, definition] of Object.entries(plan.groups)) {
    assert.ok(rootPom.includes(`<id>${definition.profile}</id>`), `${group} profile must exist`);
    if (definition.default) continue;
    assert.ok(rootPom.includes(`<groups>${definition.tag}</groups>`), `${group} tag must be Maven-selected`);
    for (const className of definition.tests) {
      const source = join(repositoryRoot, 'taxonomy-app/src/test/java',
        `${className.replaceAll('.', '/')}.java`);
      assert.match(readFileSync(source, 'utf8'),
        new RegExp(`@Tag\\(\\s*"${definition.tag}"\\s*\\)`));
    }
  }
  assert.match(appPom, /<phase>\$\{taxonomy\.app\.architectureAnchorPhase\}<\/phase>/);
  assert.match(appPom, /<id>stage-core-app-test-shard<\/id>[\s\S]*?<phase>post-integration-test<\/phase>/);
  assert.match(buildPom, /<id>verify-complete-app-test-shard-inventory<\/id>[\s\S]*?<phase>test<\/phase>/);
  assert.match(coveragePom, /<goal>merge<\/goal>/);
  assert.match(coveragePom, /<dataFileInclude>\$\{taxonomy\.app\.shard\.coverageInclude\}<\/dataFileInclude>/);
  assert.match(workflow, /applicationTestShards\.groups/);
  assert.match(workflow, /requiresModel: \(\.value\.requiresEmbeddingModel == true\)/);
  assert.match(workflow, /if: matrix\.requiresModel == true/);
  assert.match(workflow, /--verify-inputs/);
});

function digest(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}
