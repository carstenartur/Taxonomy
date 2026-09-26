import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const root = new URL('../../taxonomy-app/src/main/resources/static/js/core/', import.meta.url);
const guard = await readFile(new URL('taxonomy-copilot-terminal-state.js', root), 'utf8');
const coordinator = await readFile(new URL('taxonomy-operation-coordinator.js', root), 'utf8');

// Execute both production listeners in loader order. Isolated guard tests cannot
// detect a guard that prevents the coordinator's real Retry button from working.
function fixture({ status = null, scores = null, start = 'request', provider = '' } = {}) {
  const listeners = new Map();
  const timers = [];
  const requests = [];
  const operations = [];
  let enrichments = 0;
  let starts = 0;
  function node(id = '') {
    const classes = new Set();
    const attributes = new Map();
    return {
      id, dataset: {}, style: {}, children: [], textContent: '', disabled: false,
      classList: {
        add(value) { classes.add(value); },
        remove(value) { classes.delete(value); },
        contains(value) { return classes.has(value); },
        toggle(value, on) { if (on) classes.add(value); else classes.delete(value); }
      },
      setAttribute(key, value) { attributes.set(key, String(value)); },
      getAttribute(key) { return attributes.get(key) ?? null; },
      appendChild(child) { this.children.push(child); },
      replaceChildren(...children) { this.children = children; },
      addEventListener(type, listener) { this['on' + type] = listener; },
      click() { return click(this); }
    };
  }
  const elements = Object.fromEntries([
    'copilotBtn', 'copilotSpinner', 'copilotContent', 'copilotPanel',
    'analyzeBtn', 'analyzeSpinner', 'interactiveMode', 'providerSelect', 'statusArea'
  ].map(id => [id, node(id)]));
  elements.providerSelect.value = provider;
  elements.interactiveMode.checked = true;
  elements.analyzeSpinner.classList.add('d-none');
  elements.copilotSpinner.classList.add('d-none');
  const C = { S: { currentScores: scores, lastAnalysisStatus: status }, runtime: {}, language: () => 'en' };
  const document = {
    getElementById(id) { return elements[id] || null; },
    createElement() { return node(); },
    createTextNode(text) { const value = node(); value.textContent = text; return value; },
    addEventListener(type, listener, capture = false) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push({ listener, capture });
    },
    removeEventListener(type, listener) {
      listeners.set(type, (listeners.get(type) || []).filter(entry => entry.listener !== listener));
    },
    dispatchEvent(event) {
      operations.push(event.detail);
      for (const entry of [...listeners.get(event.type) || []]) entry.listener(event);
    }
  };
  function click(target) {
    const event = {
      target: { closest(selector) { return selector === '#' + target.id ? target : null; } },
      prevented: false, stopped: false,
      preventDefault() { this.prevented = true; },
      stopImmediatePropagation() { this.stopped = true; }
    };
    for (const entry of [...listeners.get('click') || []].filter(entry => entry.capture)) {
      entry.listener(event);
      if (event.stopped) break;
    }
    if (!event.stopped && target.onclick) target.onclick(event);
    return event;
  }
  const window = {
    __TaxonomyAnalysisSessionContext: C,
    location: { origin: 'https://taxonomy.example', href: 'https://taxonomy.example/' },
    fetch(input, options) {
      return new Promise((resolve, reject) => requests.push({ input, options, resolve, reject }));
    },
    setTimeout(callback) { timers.push(callback); return timers.length; },
    TaxonomyAnalysis: { runCopilotFlow() { enrichments++; } },
    TaxonomyScoring: {
      runAnalysis() {
        starts++;
        if (start === 'noop') {
          elements.statusArea.textContent = 'Enter a requirement before starting analysis.';
          return;
        }
        if (start === 'throw') throw new Error('Analysis startup failed');
        window.fetch('/api/analyze', { method: 'POST' }).then(response => response.json())
          .then(result => { C.S.currentScores = result.scores; C.S.lastAnalysisStatus = result.status; })
          .catch(() => {});
      }
    }
  };
  const sandbox = vm.createContext({ window, document, Request, URL, Set,
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } } });
  vm.runInContext(guard, sandbox, { filename: 'taxonomy-copilot-terminal-state.js' });
  vm.runInContext(coordinator, sandbox, { filename: 'taxonomy-operation-coordinator.js' });
  function descendants(value) { return [value, ...value.children.flatMap(descendants)]; }
  async function flush() {
    await new Promise(resolve => setImmediate(resolve));
    while (timers.length) {
      timers.shift()();
      await new Promise(resolve => setImmediate(resolve));
    }
  }
  return {
    C, window, elements, requests, operations, flush,
    starts: () => starts, enrichments: () => enrichments,
    listeners: () => (listeners.get('taxonomy:operation-state') || []).length,
    click: () => click(elements.copilotBtn),
    async respond(result) {
      const request = requests.at(-1);
      assert.ok(request, 'A complete analysis request must actually have started');
      request.resolve({ ok: true, clone() { return this; }, json: async () => result });
      await flush();
    },
    retry() {
      const retry = descendants(elements.copilotContent).find(value => value.dataset.sessionControl === 'retry');
      assert.ok(retry, 'The failed operation must offer its real Retry control');
      retry.click();
    }
  };
}

test('complete analysis gates enrichment and releases its listener', async () => {
  const f = fixture(); f.click();
  assert.equal(f.requests.length, 1);
  assert.equal(f.enrichments(), 0);
  await f.respond({ status: 'SUCCESS', scores: { BP: 80 } });
  assert.equal(f.enrichments(), 1);
  assert.equal(f.listeners(), 0);
});

for (const status of ['PARTIAL', 'ERROR', 'CANCELLED']) {
  test(`real Retry starts one new analysis after ${status}, never reusing partial scores`, async () => {
    const f = fixture(); f.click();
    await f.respond({ status, scores: { BP: 40 } });
    assert.equal(f.enrichments(), 0);
    f.retry();
    assert.equal(f.requests.length, 2, 'Retry must reach the coordinator, not be blocked by the earlier guard');
    assert.equal(f.enrichments(), 0);
    await f.respond({ status: 'SUCCESS', scores: { BP: 80 } });
    assert.equal(f.enrichments(), 1);
    assert.equal(f.listeners(), 0);
  });
}

for (const status of [undefined, 'UNKNOWN', 'IN_PROGRESS', 'IMPORTED']) {
  test(`a response with ${status} status and scores cannot authorize enrichment`, async () => {
    const f = fixture(); f.click();
    await f.respond({ status, scores: { BP: 40 } });
    assert.equal(f.enrichments(), 0);
    assert.equal(f.operations.at(-1).status, 'FAILED');
    assert.equal(f.elements.copilotBtn.disabled, false);
    assert.equal(f.listeners(), 0);
  });
}

for (const start of ['noop', 'throw']) {
  test(`a ${start} startup releases Copilot controls and listener without a timeout`, async () => {
    const f = fixture({ start }); f.click(); await f.flush();
    assert.equal(f.starts(), 1);
    assert.equal(f.requests.length, 0);
    assert.equal(f.enrichments(), 0);
    assert.equal(f.elements.copilotBtn.disabled, false);
    assert.equal(f.elements.copilotSpinner.classList.contains('d-none'), true);
    assert.equal(f.listeners(), 0, 'A failed start must not capture a later unrelated analysis');
  });
}

test('completed shared scores remain reusable without another main analysis', () => {
  const f = fixture({ status: 'SUCCESS', scores: { BP: 80 } });
  assert.equal(f.click().prevented, false);
  assert.equal(f.requests.length, 0);
});

test('partial results with MANUAL selected cannot start an automatic analysis', () => {
  const f = fixture({ status: 'PARTIAL', scores: { BP: 40 }, provider: 'MANUAL' }); f.click();
  assert.equal(f.requests.length, 0);
  assert.equal(f.enrichments(), 0);
  assert.equal(f.listeners(), 0);
  assert.equal(f.elements.copilotBtn.disabled, false);
});
