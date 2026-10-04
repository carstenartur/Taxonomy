import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

// Exercise the actual smoke script's failure path without a cluster, Docker, or application JVM.
const script = join(dirname(fileURLToPath(import.meta.url)), 'artemis-constrained-smoke.sh');
const mock = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const name = path.basename(process.argv[1]);
let args = process.argv.slice(2);
const state = process.env.SMOKE_TEST_STATE;
const file = name => path.join(state, name);
fs.appendFileSync(file('calls.jsonl'), JSON.stringify([name, ...args]) + '\n');
if (['--context', '--kube-context'].includes(args[0])) args = args.slice(2);
if (name === 'helm') {
  if (args[0] === 'upgrade') {
    if (args.includes('--atomic')) fs.writeFileSync(file('removed'), '');
    console.error('Error: context deadline exceeded');
    process.exit(42);
  }
  if (args[0] === 'uninstall') fs.writeFileSync(file('removed'), '');
  if (args[0] === 'template') console.log('kind: Deployment');
} else if (name === 'docker') {
  if (!args.includes('--format')) console.log('{}');
  else if (args.at(-1).includes('revision')) console.log(process.env.SOURCE_SHA);
  else console.log('sha256:' + 'a'.repeat(64));
} else if (name === 'curl') {
  console.log(JSON.stringify({status: 200, value: {DeliveringCount: 0}}));
} else if (name === 'kubectl') {
  if (args[0] === 'get' && args[1] === 'namespace') {
    // The fixture namespace does not exist yet.
  } else if (args[0] === 'create' && args[1] === 'secret') {
    const credentials = {};
    for (const arg of args) {
      if (!arg.startsWith('--from-literal=')) continue;
      const literal = arg.slice('--from-literal='.length);
      const separator = literal.indexOf('=');
      const key = literal.slice(0, separator), value = literal.slice(separator + 1);
      if (['ADMIN_PASSWORD', 'ADMIN_TOKEN', 'SPRING_DATASOURCE_PASSWORD', 'ARTEMIS_PASSWORD'].includes(key)) credentials[key] = value;
      if (key === 'ARTEMIS_BROKER_URL') credentials.TLS_PASSWORD = new URL(value).searchParams.get('trustStorePassword');
    }
    if (Object.keys(credentials).length) fs.writeFileSync(file('credentials.json'), JSON.stringify(credentials));
    console.log('kind: Secret');
  } else if (args[0] === 'apply' && args[1] === '-f' && args[2] === '-') {
    fs.readFileSync(0, 'utf8');
  } else if (args[0] === 'get' && ['pod', 'pods'].includes(args[1])) {
    console.log(args.some(arg => arg.includes('.items[0].metadata.name')) ? 'smoke-postgres-1' : 'taxonomy-artemis-worker-cp-1');
  } else if (args[0] === 'logs') {
    if (fs.existsSync(file('removed'))) {
      console.error('pod not found after release removal');
      process.exit(1);
    }
    if (!args.includes('--previous') && process.env.SMOKE_TEST_CURRENT_LOG_FAILURE === 'true') {
      console.error('current log unavailable');
      process.exit(1);
    }
    console.log('SearchException: HSEARCH800001: Hibernate Search was not initialized.');
    console.log('at CommitIndexSearchLifecycle.run(CommitIndexSearchLifecycle.java:48)');
    for (const [key, value] of Object.entries(JSON.parse(fs.readFileSync(file('credentials.json'), 'utf8')))) console.log(key + '=' + value);
  } else if (args[0] === 'describe') {
    console.log('Last State: Terminated; Reason: Error; Restart Count: 5');
  } else if (args[0] === 'get') {
    console.log('Worker restart diagnostics');
  }
}
`;

function evidenceText(directory) {
  return readdirSync(directory).map(name => {
    const path = join(directory, name);
    return statSync(path).isDirectory() ? evidenceText(path) : readFileSync(path, 'utf8');
  }).join('\n');
}

for (const [keep, missingCurrent] of [[false, false], [true, false], [false, true]]) {
  test(`Helm failure retains redacted diagnostics before cleanup (keep=${keep}, missingCurrent=${missingCurrent})`, () => {
    const root = mkdtempSync(join(tmpdir(), 'artemis-smoke-test-'));
    try {
      const binary = join(root, 'bin'), state = join(root, 'state'), evidence = join(root, 'evidence');
      for (const directory of [binary, state, evidence]) mkdirSync(directory);
      const driver = join(binary, 'mock.cjs');
      writeFileSync(driver, mock, {mode: 0o755});
      for (const name of ['helm', 'kubectl', 'docker', 'kind', 'curl', 'openssl', 'keytool']) symlinkSync(driver, join(binary, name));
      writeFileSync(join(evidence, 'evidence.json'), '{"result":"stale success"}');
      const result = spawnSync('bash', [script], {
        env: {...process.env, PATH: binary + ':' + process.env.PATH, SOURCE_SHA: '1'.repeat(40),
          EVIDENCE_DIR: evidence, KEEP_RESOURCES: String(keep), SMOKE_TEST_STATE: state,
          SMOKE_TEST_CURRENT_LOG_FAILURE: String(missingCurrent)},
        encoding: 'utf8', timeout: 20_000,
      });
      assert.equal(result.status, 42, result.stdout + result.stderr + (result.error?.message ?? ''));
      assert.ok(!readdirSync(evidence).includes('evidence.json'));
      const diagnostics = join(evidence, 'diagnostics');
      const previous = readFileSync(join(diagnostics, 'taxonomy-artemis-worker-cp-1-previous.log'), 'utf8');
      assert.match(previous, /HSEARCH800001/);
      assert.match(previous, /CommitIndexSearchLifecycle/);
      assert.ok(statSync(join(diagnostics, 'resources.yaml')).isFile());
      assert.ok(statSync(join(diagnostics, 'events.txt')).isFile());
      assert.match(readFileSync(join(diagnostics, 'taxonomy-artemis-worker-cp-1-describe.txt'), 'utf8'), /Restart Count: 5/);
      const captured = evidenceText(evidence);
      for (const credential of Object.values(JSON.parse(readFileSync(join(state, 'credentials.json'), 'utf8')))) {
        assert.ok(!captured.includes(credential), 'Fixture credentials must not enter diagnostic artifacts');
      }
      assert.match(previous, /\[REDACTED\]/);
      const commands = readFileSync(join(state, 'calls.jsonl'), 'utf8').trim().split('\n').map(line => JSON.parse(line).join(' '));
      const install = commands.find(command => command.includes(' upgrade --install '));
      assert.ok(!install.includes('--atomic'), 'Automatic uninstall destroys evidence before cleanup can collect it');
      assert.ok(install.includes('--timeout 12m'));
      const removals = commands.flatMap((command, index) => command.includes(' uninstall ') || command.includes(' delete namespace ') ? [index] : []);
      assert.equal(removals.length > 0, !keep);
      if (removals.length) {
        const lastLog = commands.findLastIndex(command => command.includes(' logs '));
        assert.ok(lastLog < Math.min(...removals), 'Collect logs before release or namespace removal');
      }
    } finally {
      rmSync(root, {recursive: true, force: true});
    }
  });
}
