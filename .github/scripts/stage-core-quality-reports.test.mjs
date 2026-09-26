import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync,
  readdirSync, rmSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script = fileURLToPath(new URL('./stage-core-quality-reports.sh', import.meta.url));

function put(root, relative, content = 'fixture report\n') {
  const file = join(root, relative);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, content);
}

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'taxonomy-core-reports-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const args of [
    ['init', '-q'],
    ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
      '-c', 'commit.gpgsign=false', 'commit', '-q', '--allow-empty', '-m', 'fixture'],
  ]) {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  }
  return root;
}

function stage(root, env = {}) {
  return spawnSync('bash', [script], {
    cwd: root,
    encoding: 'utf8',
    timeout: 30_000,
    env: {
      ...process.env,
      GITHUB_SHA: 'HEAD', GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2',
      ...env,
    },
  });
}

function files(root, relative = '') {
  return readdirSync(join(root, relative), { withFileTypes: true })
    .flatMap(entry => {
      const path = join(relative, entry.name);
      return entry.isDirectory() ? files(root, path) : [path];
    }).sort();
}

test('stages each original once without recursively collecting its own output', t => {
  const root = fixture(t);
  // More than a pipe buffer of source names makes concurrent find/copy traversal
  // reach the output tree after the consumer has already created report copies.
  const source = `taxonomy-fixture-${'x'.repeat(180)}/target/surefire-reports`;
  const expected = [];
  for (let i = 0; i < 512; i++) {
    const name = `${source}/TEST-${String(i).padStart(4, '0')}.xml`;
    put(root, name, `<testsuite name="case-${i}" tests="1" failures="0" errors="0"/>`);
    expected.push(name);
  }
  put(root, 'taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml', '<report/>');
  const result = stage(root);
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  assert.deepEqual(files(join(root, 'target/quality-reports/tests')), expected.sort());
});

test('preserves module ownership, unusual names, coverage and provenance across repeated staging', t => {
  const root = fixture(t);
  const names = [
    'taxonomy-one/target/surefire-reports/TEST-shared.xml',
    'taxonomy-two/target/failsafe-reports/TEST-shared.xml',
    'taxonomy-two/target/failsafe-reports/diagnostic with space\nand-ü.txt',
    'taxonomy-two/target/failsafe-reports/failure.dump',
    'taxonomy-two/target/failsafe-reports/failure.dumpstream',
  ];
  names.forEach((name, i) => put(root, name, `report ${i}\n`));
  put(root, 'taxonomy-one/target/surefire-reports/unrelated.json');
  put(root, 'taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml', '<report/>');
  put(root, 'taxonomy-coverage/target/site/jacoco-aggregate/index.html', 'coverage');
  put(root, 'target/maven-verification.log', 'BUILD SUCCESS');
  put(root, 'target/quality-reports/tests/stale.txt');
  for (let attempt = 0; attempt < 2; attempt++) {
    const result = stage(root);
    assert.equal(result.status, 0, result.stderr || result.error?.message);
    assert.deepEqual(files(join(root, 'target/quality-reports/tests')), [...names].sort());
    names.forEach((name, i) => assert.equal(
      readFileSync(join(root, 'target/quality-reports/tests', name), 'utf8'), `report ${i}\n`));
    assert.equal(readFileSync(join(root, 'target/quality-reports/coverage/jacoco.xml'), 'utf8'), '<report/>');
    assert.equal(readFileSync(join(root, 'target/quality-reports/evidence/maven-verification.log'), 'utf8'), 'BUILD SUCCESS');
    assert.match(readFileSync(join(root, 'target/quality-reports/README.txt'), 'utf8'),
      /Commit: HEAD\nSource tree: [0-9a-f]{40}\nCore build ID: 123\.2\.core/);
  }
});

test('does not hide a failed source inventory behind a successful copy loop', t => {
  const root = fixture(t);
  put(root, 'taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml', '<report/>');
  // A controlled failed external command exercises shell exit propagation, not
  // the traversal itself; the successful-path tests above use the real find.
  put(root, 'bin/find', '#!/bin/sh\necho "inventory failure" >&2\nexit 23\n');
  chmodSync(join(root, 'bin/find'), 0o755);
  const result = stage(root, { PATH: `${join(root, 'bin')}:${process.env.PATH}` });
  assert.equal(result.status, 23, result.stderr || result.error?.message);
  assert.match(result.stderr, /inventory failure/);
  assert.equal(existsSync(join(root, 'target/quality-reports/README.txt')), false);
});

test('still rejects missing aggregate coverage rather than publishing success', t => {
  const root = fixture(t);
  put(root, 'taxonomy-fixture/target/surefire-reports/TEST-one.xml', '<testsuite/>');
  const result = stage(root);
  assert.equal(result.status, 1, result.stderr || result.error?.message);
  assert.match(result.stdout, /Aggregate JaCoCo XML is missing/);
  assert.equal(existsSync(join(root, 'target/quality-reports/README.txt')), false);
});
