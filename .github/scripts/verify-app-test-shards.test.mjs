import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync,
} from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  discoverAppTestSourceIdentities, stageAppTestShard, verifyAppTestShardInputs, verifyAppTestShards,
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

test('exempts a legitimately excluded class before requiring its report', t => {
  const paths = fixture(t);
  const source = join(paths.sourceRoot, 'com/taxonomy/RealLlmTest.java');
  writeFileSync(source,
    'package com.taxonomy; import org.junit.jupiter.api.Test; import org.junit.jupiter.api.Tag; @Tag("real-llm") class RealLlmTest { @Test void call() {} }\n');
  assert.equal(verify(paths, {
    ...groups,
    excludedTags: ['real-llm'],
  }).tests.excludedClasses, 1);
});

test('does not exempt a class for a method-only excluded tag', t => {
  const paths = fixture(t);
  const source = join(paths.sourceRoot, 'com/taxonomy/MethodTagTest.java');
  writeFileSync(source,
    'package com.taxonomy; import org.junit.jupiter.api.Test; import org.junit.jupiter.api.Tag; class MethodTagTest { @Test @Tag("real-llm") void call() {} }\n');
  assert.throws(() => verify(paths, {
    ...groups,
    excludedTags: ['real-llm'],
  }), /missing report.*MethodTagTest/);
});

test('requires a report for every newly discovered mandatory test class', t => {
  const paths = fixture(t);
  const source = join(paths.sourceRoot, 'com/taxonomy/MissingNewTest.java');
  writeFileSync(source,
    'package com.taxonomy; import org.junit.jupiter.api.Test; class MissingNewTest { @Test void call() {} }\n');
  assert.throws(() => verify(paths), /missing report.*MissingNewTest/);
});

test('discovers ArchUnit-only test classes in the actual source inventory', t => {
  const paths = fixture(t);
  const source = join(paths.sourceRoot, 'com/taxonomy/ArchitectureOnlyTest.java');
  writeFileSync(source,
    'package com.taxonomy; import com.tngtech.archunit.junit.ArchTest; class ArchitectureOnlyTest { @ArchTest static final Object rule = null; }\n');
  assert.ok(discoverAppTestSourceIdentities(paths.sourceRoot).includes('com.taxonomy.ArchitectureOnlyTest'));
  assert.throws(() => verify(paths), /missing report.*ArchitectureOnlyTest/);
});

test('discovers inherited-only test classes and maps nested JUnit suite identities', t => {
  const paths = fixture(t);
  const base = join(paths.sourceRoot, 'com/taxonomy/AbstractTestBase.java');
  writeFileSync(base,
    'package com.taxonomy; import org.junit.jupiter.api.Test; abstract class AbstractTestBase { @Test void inherited() {} }\n');
  const inherited = join(paths.sourceRoot, 'com/taxonomy/InheritedOnlyTest.java');
  writeFileSync(inherited,
    'package com.taxonomy; class InheritedOnlyTest extends AbstractTestBase {}\n');
  assert.throws(() => verify(paths), /missing report.*InheritedOnlyTest/);

  const nested = join(paths.sourceRoot, 'com/taxonomy/NestedSuiteTest.java');
  writeFileSync(nested,
    'package com.taxonomy; import org.junit.jupiter.api.Nested; import org.junit.jupiter.api.Test; class NestedSuiteTest { @Nested class Nested { @Test void nested() {} } }\n');
  const report = join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.NestedSuiteTest$Nested.xml');
  writeFileSync(report,
    '<testsuite name="com.taxonomy.NestedSuiteTest$Nested" tests="1" failures="0" errors="0" skipped="0"><testcase name="nested"/></testsuite>\n');
  const manifestPath = join(paths.evidenceRoot, 'core/manifest.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  manifest.reports.push({
    path: 'tests/TEST-com.taxonomy.NestedSuiteTest$Nested.xml',
    className: 'com.taxonomy.NestedSuiteTest$Nested',
    sha256: digest(report),
  });
  const inheritedReport = join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.InheritedOnlyTest.xml');
  writeFileSync(inheritedReport,
    '<testsuite name="com.taxonomy.InheritedOnlyTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="inherited"/></testsuite>\n');
  manifest.reports.push({
    path: 'tests/TEST-com.taxonomy.InheritedOnlyTest.xml',
    className: 'com.taxonomy.InheritedOnlyTest',
    sha256: digest(inheritedReport),
  });
  writeFileSync(manifestPath, JSON.stringify(manifest));
  assert.equal(verify(paths).tests.classes, 4);
});

test('requires exact product-demo and architecture-anchor execution identities', t => {
  const paths = fixture(t);
  const suffix = 'product-demo-isolated';
  const report = join(paths.evidenceRoot, `core/tests/TEST-com.taxonomy.CoreTest-${suffix}.xml`);
  writeFileSync(report,
    `<testsuite name="com.taxonomy.CoreTest(${suffix})" tests="1" failures="0" errors="0" skipped="0"><testcase name="test"/></testsuite>\n`);
  const manifestPath = join(paths.evidenceRoot, 'core/manifest.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  manifest.reports.push({
    path: `tests/TEST-com.taxonomy.CoreTest-${suffix}.xml`,
    className: 'com.taxonomy.CoreTest',
    sha256: digest(report),
  });
  writeFileSync(manifestPath, JSON.stringify(manifest));
  const selected = {
    ...groups,
    requiredExecutions: [{
      className: 'com.taxonomy.CoreTest',
      identities: ['default', suffix],
    }],
  };
  assert.equal(verify(paths, selected).tests.reports, 3);
  assert.throws(() => verify(paths, {
    ...selected,
    requiredExecutions: [{
      className: 'com.taxonomy.CoreTest',
      identities: ['default', 'module-gate-ci-anchor'],
    }],
  }), /execution identities mismatch/);
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

test('rejects failure elements even when aggregate JUnit counters claim success', t => {
  const paths = fixture(t);
  const report = join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.CoreTest.xml');
  writeFileSync(report,
    '<testsuite name="com.taxonomy.CoreTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="flaky"><flakyFailure message="failed then passed"/></testcase></testsuite>\n');
  const manifest = JSON.parse(readFileSync(join(paths.evidenceRoot, 'core/manifest.json'), 'utf8'));
  manifest.reports[0].sha256 = digest(report);
  writeFileSync(join(paths.evidenceRoot, 'core/manifest.json'), JSON.stringify(manifest));
  assert.throws(() => verify(paths), /failure evidence|flaky/i);
});

test('rejects missing, malformed, mismatched and tampered digests', t => {
  const paths = fixture(t);
  const manifestPath = join(paths.evidenceRoot, 'core/manifest.json');
  const reportPath = join(paths.evidenceRoot, 'core/tests/TEST-com.taxonomy.CoreTest.xml');
  const originalReport = readFileSync(reportPath, 'utf8');
  const original = JSON.parse(readFileSync(manifestPath, 'utf8'));
  for (const badDigest of ['', 'not-a-digest', '0'.repeat(64)]) {
    const manifest = structuredClone(original);
    manifest.reports[0].sha256 = badDigest;
    writeFileSync(manifestPath, JSON.stringify(manifest));
    assert.throws(() => verify(paths), /digest/i);
  }
  for (const badDigest of ['', 'invalid', 'f'.repeat(64)]) {
    const manifest = structuredClone(original);
    manifest.coverage.sha256 = badDigest;
    writeFileSync(manifestPath, JSON.stringify(manifest));
    assert.throws(() => verify(paths), /coverage.*digest/i);
  }
  writeFileSync(manifestPath, JSON.stringify(original));
  writeFileSync(reportPath, `${originalReport} `);
  assert.throws(() => verify(paths), /report digest mismatch/i);
  writeFileSync(reportPath, originalReport);
  const coveragePath = join(paths.evidenceRoot, 'core/coverage.exec');
  writeFileSync(coveragePath, `${readFileSync(coveragePath, 'utf8')} tampered`);
  assert.throws(() => verify(paths), /coverage execution digest mismatch/i);
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

test('stages failed and flaky testcase XML as failure evidence, never as a pass', t => {
  const paths = fixture(t);
  const reports = join(paths.root, 'taxonomy-app/target/surefire-reports');
  mkdirSync(reports, { recursive: true });
  const report = join(reports, 'TEST-com.taxonomy.CoreTest.xml');
  writeFileSync(report,
    '<testsuite name="com.taxonomy.CoreTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="flaky"><flakyFailure message="failed once"/></testcase></testsuite>\n');
  writeFileSync(join(paths.root, 'taxonomy-app/target/jacoco.exec'), 'fixture coverage bytes');

  const manifest = stageAppTestShard({
    repositoryRoot: paths.root,
    shard: 'core',
    commit,
    sourceTree: tree,
    buildId: '123.1.core',
  });

  assert.deepEqual(manifest.reports[0].failureElements, ['flakyFailure']);
  assert.match(readFileSync(join(paths.evidenceRoot,
    'core/tests/surefire-reports/TEST-com.taxonomy.CoreTest.xml'), 'utf8'), /flakyFailure/);
  assert.throws(() => verify(paths), /failure evidence|not successful/);
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
  assert.equal('allowedRepeatedClasses' in plan, false);
  assert.deepEqual(plan.requiredExecutions, [
    {
      className: 'com.taxonomy.composition.demo.ProductDemoEndToEndTest',
      identities: ['product-demo-isolated'],
    },
    {
      className: 'com.taxonomy.ArchitectureExceptionLedgerTest',
      identities: ['default', 'module-gate-ci-anchor'],
    },
  ]);
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

test('core and app shard producers run independently before final coverage aggregation', () => {
  const workflow = readFileSync(join(repositoryRoot, '.github/workflows/ci-cd.yml'), 'utf8');
  const jobs = readWorkflowJobs(workflow);
  const dependsOn = (job, prerequisite, seen = new Set()) => {
    if (seen.has(job)) return false;
    seen.add(job);
    return (jobs.get(job) ?? []).some(
      dependency => dependency === prerequisite || dependsOn(dependency, prerequisite, seen),
    );
  };

  assert.ok(jobs.has('core'));
  assert.ok(jobs.has('app-test-shards'));
  assert.equal(dependsOn('core', 'app-test-shards'), false);
  assert.ok(jobs.has('coverage-aggregate'));
  assert.ok(dependsOn('coverage-aggregate', 'core'));
  assert.ok(dependsOn('coverage-aggregate', 'app-test-shards'));
  assert.ok(dependsOn('verify', 'coverage-aggregate'));
});

test('the full-reactor class artifact shell step stages and verifies a real fixture archive', t => {
  const workflow = readFileSync(join(repositoryRoot, '.github/workflows/ci-cd.yml'), 'utf8');
  const script = workflowStepScript(workflow, 'Stage full-reactor class outputs for aggregate coverage');
  const root = mkdtempSync(join(tmpdir(), 'taxonomy-reactor-class-fixture-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const args of [
    ['init', '-q'],
    ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
      '-c', 'commit.gpgsign=false', 'commit', '-q', '--allow-empty', '-m', 'fixture'],
  ]) {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  }
  const classFile = join(root, 'taxonomy-app/target/classes/com/taxonomy/Fixture.class');
  mkdirSync(join(classFile, '..'), { recursive: true });
  writeFileSync(classFile, Buffer.from([0xca, 0xfe, 0xba, 0xbe]));
  const result = spawnSync('bash', ['-e', '-u', '-o', 'pipefail', '-c', script], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, GITHUB_SHA: 'HEAD' },
  });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  const checksum = spawnSync('sha256sum', ['--check', 'target/core-reactor-classes.sha256'], {
    cwd: root, encoding: 'utf8',
  });
  assert.equal(checksum.status, 0, checksum.stderr);
  const files = spawnSync('tar', ['-tzf', 'target/core-reactor-classes.tar.gz'], {
    cwd: root, encoding: 'utf8',
  });
  assert.equal(files.status, 0, files.stderr);
  assert.match(files.stdout, /taxonomy-app\/target\/classes\/com\/taxonomy\/Fixture\.class/);
  assert.match(readFileSync(join(root, 'target/core-reactor-classes.tree'), 'utf8'), /^[0-9a-f]{40}\n$/);
});

test('CI packaging skips only app Surefire while retaining the real Failsafe selector', () => {
  const rootPom = readFileSync(join(repositoryRoot, 'pom.xml'), 'utf8');
  const appPom = readFileSync(join(repositoryRoot, 'taxonomy-app/pom.xml'), 'utf8');
  const workflow = readFileSync(join(repositoryRoot, '.github/workflows/ci-cd.yml'), 'utf8');
  const catalog = JSON.parse(readFileSync(join(repositoryRoot, '.mvn/verification-suites.json'), 'utf8'));
  assert.equal(catalog.profiles['ci-plugin-packaging'].baseProfile, 'plugin-packaging-tests');
  assert.equal(catalog.profiles['ci-plugin-packaging'].itTest,
    'ExternalPluginPackagedIT,OptionalFeaturePackagedIT');
  assert.match(rootPom, /<id>plugin-packaging-tests<\/id>[\s\S]*?<it\.test>ExternalPluginPackagedIT,OptionalFeaturePackagedIT<\/it\.test>/);
  assert.match(rootPom, /<id>ci-plugin-packaging<\/id>/);
  assert.match(appPom, /<artifactId>maven-surefire-plugin<\/artifactId>[\s\S]*?<skip>\$\{taxonomy\.app\.unit\.tests\.skip\}<\/skip>/);
  assert.match(appPom, /<id>ci-plugin-packaging<\/id>[\s\S]*?<maven\.build\.cache\.enabled>false<\/maven\.build\.cache\.enabled>/);
  assert.match(workflow, /verify -Pplugin-packaging-tests,ci-plugin-packaging/);
  assert.doesNotMatch(workflow, /plugin-packaging-tests[^\n]*-DskipTests/);
});

test('test-scope Maven cache entries cannot cross application shard or packaging profiles', () => {
  const appPom = readFileSync(join(repositoryRoot, 'taxonomy-app/pom.xml'), 'utf8');
  for (const profile of [
    'ci-app-test-shards-core',
    'ci-app-test-shard-recovery',
    'ci-app-test-shard-browser',
    'ci-app-test-shards-coverage',
    'ci-plugin-packaging',
  ]) {
    const start = appPom.indexOf(`<id>${profile}</id>`);
    assert.notEqual(start, -1, `${profile} profile exists`);
    const end = appPom.indexOf('</profile>', start);
    assert.match(appPom.slice(start, end), /<maven\.build\.cache\.enabled>false<\/maven\.build\.cache\.enabled>/);
  }
});
function readWorkflowJobs(workflow) {
  const jobs = new Map();
  let activeJob;
  let inJobs = false;
  let readingNeeds = false;
  for (const line of workflow.split(/\r?\n/)) {
    if (line === 'jobs:') {
      inJobs = true;
      continue;
    }
    if (!inJobs) continue;
    const job = line.match(/^  ([a-z][a-z0-9-]*):\s*$/);
    if (job) {
      activeJob = job[1];
      jobs.set(activeJob, []);
      readingNeeds = false;
      continue;
    }
    if (!activeJob) continue;
    const needs = line.match(/^    needs:\s*(.*)$/);
    if (needs) {
      readingNeeds = true;
      const inline = needs[1].match(/^\[([^\]]*)\]/)?.[1];
      if (inline) jobs.get(activeJob).push(...inline.split(',').map(value => value.trim()).filter(Boolean));
      continue;
    }
    if (readingNeeds) {
      const dependency = line.match(/^      - ([a-z][a-z0-9-]*)\s*$/);
      if (dependency) {
        jobs.get(activeJob).push(dependency[1]);
        continue;
      }
      if (!line.startsWith('      ')) readingNeeds = false;
    }
  }
  return jobs;
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

function digest(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}
