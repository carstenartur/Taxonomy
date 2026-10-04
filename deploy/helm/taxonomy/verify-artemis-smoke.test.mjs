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

function smokeFunction(name) {
  const definition = readFileSync(script, 'utf8').match(new RegExp(`^${name}\\(\\) \\{(?:[^\\n]*\\}|[\\s\\S]*?^\\})\\n`, 'm'));
  assert.ok(definition, `Actual smoke function ${name} must exist`);
  return definition[0];
}

// Run the actual loss predicate with external observations supplied by the test.
// This deliberately does not reproduce its boolean logic in a test-only helper.
function pollLostWorker(root, {consumers = 0, delivering = 0, sessions = 0, brokerAvailable = true, databaseAvailable = true}) {
  const evidence = join(root, 'evidence'), privateDir = join(root, 'private');
  for (const directory of [evidence, privateDir]) mkdirSync(directory, {recursive: true});
  writeFileSync(join(root, 'broker.json'), JSON.stringify({status: 200, value: {
    ConsumerCount: consumers, DeliveringCount: delivering, MessageCount: 1,
    MessagesAcknowledged: 0, MessagesAdded: 1, secret: 'must-not-be-retained',
  }, secret: 'must-not-be-retained'}));
  writeFileSync(join(root, 'database.json'), JSON.stringify({sessionCount: sessions, lockWaitCount: sessions,
    activeCount: sessions, clientConnectionCheckInterval: '1s', query: 'must-not-be-retained'}));
  const result = spawnSync('bash', ['-c', `set -euo pipefail
broker_stats() {
  echo broker >>"$SMOKE_TEST_STATE/calls"
  cp "$SMOKE_TEST_STATE/broker.json" "$PRIVATE_DIR/broker-stats.json"
  [[ "$BROKER_AVAILABLE" == true ]]
}
sql() {
  echo database >>"$SMOKE_TEST_STATE/calls"
  [[ "$DATABASE_AVAILABLE" == true ]] || return 1
  if [[ "$1" == *json_build_object* ]]; then cat "$SMOKE_TEST_STATE/database.json";
  else jq -r '.sessionCount' "$SMOKE_TEST_STATE/database.json"; fi
}
${smokeFunction('no_cp_consumers')}
${smokeFunction('lost_worker_disconnected')}
if lost_worker_disconnected; then exit 0; else exit 1; fi
`], {env: {...process.env, SMOKE_TEST_STATE: root, EVIDENCE_DIR: evidence, PRIVATE_DIR: privateDir,
    LOST_IP: '10.244.0.11', BROKER_AVAILABLE: String(brokerAvailable), DATABASE_AVAILABLE: String(databaseAvailable)},
  encoding: 'utf8', timeout: 5_000});
  assert.ok(!result.error, result.error?.message);
  return {result, evidence};
}

for (const [name, state, disconnected] of [
  ['consumer remains', {consumers: 1}, false],
  ['delivery remains', {delivering: 1}, false],
  ['blocked database client remains', {sessions: 1}, false],
  ['both resources disconnected', {}, true],
  ['broker observation fails', {brokerAvailable: false}, false],
  ['database observation fails', {databaseAvailable: false}, false],
]) {
  test(`Loss poll records both bounded observations and fails closed: ${name}`, () => {
    const root = mkdtempSync(join(tmpdir(), 'artemis-loss-poll-'));
    try {
      const {result, evidence} = pollLostWorker(root, state);
      assert.equal(result.status, disconnected ? 0 : 1, result.stdout + result.stderr);
      assert.deepEqual(readFileSync(join(root, 'calls'), 'utf8').trim().split('\n'), ['broker', 'database'],
        'A negative broker observation must not short-circuit database diagnostics');
      assert.deepEqual(readdirSync(evidence).sort(), ['worker-loss-broker.json', 'worker-loss-database.json']);
      const broker = JSON.parse(readFileSync(join(evidence, 'worker-loss-broker.json'), 'utf8'));
      const database = JSON.parse(readFileSync(join(evidence, 'worker-loss-database.json'), 'utf8'));
      assert.equal(broker.observed, state.brokerAvailable !== false);
      assert.equal(database.observed, state.databaseAvailable !== false);
      if (database.observed) {
        assert.equal(database.sessionCount, state.sessions ?? 0);
        assert.equal(database.lockWaitCount, state.sessions ?? 0);
        assert.equal(database.clientConnectionCheckInterval, '1s');
      }
      assert.ok(!evidenceText(evidence).includes('must-not-be-retained'), 'Retain only allowed diagnostic fields');
    } finally {
      rmSync(root, {recursive: true, force: true});
    }
  });
}

test('Loss polling replaces previous observations instead of retaining stale success or growing artifacts', () => {
  const root = mkdtempSync(join(tmpdir(), 'artemis-loss-poll-'));
  try {
    assert.equal(pollLostWorker(root, {}).result.status, 0);
    const {result, evidence} = pollLostWorker(root, {brokerAvailable: false, databaseAvailable: false});
    assert.equal(result.status, 1);
    assert.deepEqual(readdirSync(evidence).sort(), ['worker-loss-broker.json', 'worker-loss-database.json']);
    for (const file of readdirSync(evidence)) {
      const observation = JSON.parse(readFileSync(join(evidence, file), 'utf8'));
      assert.equal(observation.observed, false);
      assert.deepEqual(Object.keys(observation).sort(), ['observed', 'observedAt']);
    }
  } finally {
    rmSync(root, {recursive: true, force: true});
  }
});
