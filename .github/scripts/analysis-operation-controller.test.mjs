import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const stateScript = await readFile(path.join(
  repoRoot,
  'taxonomy-app/src/main/resources/static/js/core/taxonomy-state.js'
), 'utf8');

function loadRuntime(overrides = {}) {
  const domReady = [];
  const window = {
    console,
    setTimeout,
    ...overrides.window
  };
  const document = {
    querySelectorAll: () => [],
    addEventListener(type, listener) {
      if (type === 'DOMContentLoaded') domReady.push(listener);
    },
    ...overrides.document
  };

  vm.runInNewContext(stateScript, {
    window,
    document,
    console,
    Array,
    Boolean,
    JSON,
    Map,
    Number,
    Object,
    Proxy,
    Reflect,
    Set,
    String
  }, { filename: 'taxonomy-state.js' });

  return {
    window,
    fireDomReady() {
      for (const listener of domReady) listener();
    }
  };
}

function createController() {
  return loadRuntime().window.TaxonomyAnalysisOperationController.create();
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

test('the installed EventSource guard blocks stale and out-of-order UI callbacks', () => {
  const instances = [];

  class FakeEventSource {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 2;

    constructor(url) {
      this.url = url;
      this.listeners = new Map();
      this.closeCalls = 0;
      this.onerror = null;
      instances.push(this);
    }

    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, []);
      this.listeners.get(type).push(listener);
    }

    removeEventListener(type, listener) {
      const listeners = this.listeners.get(type) || [];
      this.listeners.set(type, listeners.filter(candidate => candidate !== listener));
    }

    close() {
      this.closeCalls += 1;
    }

    emit(type, envelope) {
      const message = {
        type,
        data: envelope === undefined ? undefined : JSON.stringify(envelope)
      };
      for (const listener of this.listeners.get(type) || []) listener.call(this, message);
      if (type === 'error' && typeof this.onerror === 'function') this.onerror(message);
    }

    failTransport() {
      const transportEvent = { type: 'error' };
      for (const listener of this.listeners.get('error') || []) {
        listener.call(this, transportEvent);
      }
      if (typeof this.onerror === 'function') this.onerror(transportEvent);
    }
  }

  const accepted = [];
  let transportFailures = 0;
  const runtime = loadRuntime({ window: { EventSource: FakeEventSource } });
  runtime.window.TaxonomyScoring = {
    runStreamingAnalysis() {
      const source = new runtime.window.EventSource('/api/analyze-stream');
      source.addEventListener('scores', message => {
        accepted.push(['scores', JSON.parse(message.data).sequence]);
      });
      source.addEventListener('complete', message => {
        accepted.push(['complete', JSON.parse(message.data).sequence]);
        source.close();
      });
      source.addEventListener('error', message => {
        accepted.push(['error', JSON.parse(message.data).sequence]);
        source.close();
      });
      source.onerror = () => { transportFailures += 1; };
      return source;
    }
  };
  runtime.fireDomReady();

  const sourceA = runtime.window.TaxonomyScoring.runStreamingAnalysis();
  instances[0].emit('scores', event('operation-a', 1));
  instances[0].emit('scores', event('operation-a', 1));
  instances[0].emit('scores', event('operation-a', 0));
  instances[0].emit('scores', event('operation-b', 2));
  assert.deepEqual(accepted, [['scores', 1]]);

  const sourceB = runtime.window.TaxonomyScoring.runStreamingAnalysis();
  assert.equal(instances[0].closeCalls, 1);
  instances[0].emit('scores', event('operation-a', 2));
  instances[0].failTransport();
  assert.equal(transportFailures, 0);

  instances[1].emit('scores', event('operation-b', 1));
  instances[1].emit('complete', event('operation-b', 2));
  instances[1].emit('scores', event('operation-b', 3));
  instances[1].failTransport();

  const sourceC = runtime.window.TaxonomyScoring.runStreamingAnalysis();
  instances[2].emit('error', event('operation-c', 1));
  instances[2].emit('scores', event('operation-c', 2));
  instances[2].failTransport();

  assert.deepEqual(accepted, [
    ['scores', 1],
    ['scores', 1],
    ['complete', 2],
    ['error', 1]
  ]);
  assert.equal(transportFailures, 0);
  assert.equal(instances[1].closeCalls, 1);
  assert.equal(instances[2].closeCalls, 1);
  assert.equal(sourceA.closeCalls, 1);
  assert.equal(sourceB.closeCalls, 1);
  assert.equal(sourceC.closeCalls, 1);
});
