import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const controllerScript = await readFile(path.join(
  repoRoot,
  'taxonomy-app/src/main/resources/static/js/core/analysis-operation-controller.js'
), 'utf8');

function createController() {
  const window = { console };
  vm.runInNewContext(controllerScript, {
    window,
    console,
    Boolean,
    Number,
    Object,
    String
  }, { filename: 'analysis-operation-controller.js' });
  return window.TaxonomyAnalysisOperationController.create();
}

function event(operationId, sequence) {
  return { operationId, sequence };
}

test('accepts one operation only in strictly increasing sequence order', () => {
  const controller = createController();
  const session = controller.begin();

  assert.equal(session.accept(event('operation-a', 1)), true);
  assert.equal(session.accept(event('operation-a', 2)), true);
  assert.equal(session.accept(event('operation-a', 3)), true);
  assert.deepEqual(
    { ...session.snapshot() },
    {
      generation: 1,
      operationId: 'operation-a',
      highestSequence: 3,
      terminal: false,
      superseded: false,
      transportClosed: false,
      active: true
    }
  );
});

test('rejects duplicate, decreasing, invalid and foreign events', () => {
  const controller = createController();
  const session = controller.begin();

  assert.equal(session.accept(event('operation-a', 2)), true);
  assert.equal(session.accept(event('operation-a', 2)), false);
  assert.equal(session.accept(event('operation-a', 1)), false);
  assert.equal(session.accept(event('operation-b', 3)), false);
  assert.equal(session.accept(event('', 3)), false);
  assert.equal(session.accept(event('operation-a', 0)), false);
  assert.equal(session.accept({ operationId: 'operation-a', sequence: 2.5 }), false);
  assert.equal(session.snapshot().highestSequence, 2);
});

test('starting a newer operation closes and invalidates the previous transport', () => {
  const controller = createController();
  let closedA = 0;
  let closedB = 0;
  const sessionA = controller.begin(() => { closedA += 1; });
  assert.equal(sessionA.accept(event('operation-a', 1)), true);

  const sessionB = controller.begin(() => { closedB += 1; });
  assert.equal(closedA, 1);
  assert.equal(closedB, 0);
  assert.equal(sessionA.accept(event('operation-a', 2)), false);
  assert.equal(sessionA.failTransport(), false);
  assert.equal(sessionB.accept(event('operation-b', 1)), true);
  assert.equal(sessionB.isActive(), true);
});

test('accepts one terminal event and rejects every later callback', () => {
  const controller = createController();
  let closeCalls = 0;
  const session = controller.begin(() => { closeCalls += 1; });

  assert.equal(session.accept(event('operation-a', 1)), true);
  assert.equal(session.accept(event('operation-a', 2), { terminal: true }), true);
  assert.equal(closeCalls, 1);
  assert.equal(session.isActive(), false);
  assert.equal(session.accept(event('operation-a', 3)), false);
  assert.equal(session.failTransport(), false);
  assert.equal(closeCalls, 1);
});

test('only the active transport may report a connection failure', () => {
  const controller = createController();
  let closedA = 0;
  let closedB = 0;
  const sessionA = controller.begin(() => { closedA += 1; });
  const sessionB = controller.begin(() => { closedB += 1; });

  assert.equal(closedA, 1);
  assert.equal(sessionA.failTransport(), false);
  assert.equal(sessionB.failTransport(), true);
  assert.equal(closedB, 1);
  assert.equal(sessionB.failTransport(), false);
});

test('explicit cancellation closes the active transport exactly once', () => {
  const controller = createController();
  let closeCalls = 0;
  const session = controller.begin(() => { closeCalls += 1; });

  assert.equal(session.cancel(), true);
  assert.equal(session.cancel(), false);
  assert.equal(closeCalls, 1);
  assert.equal(controller.snapshot(), null);
});
