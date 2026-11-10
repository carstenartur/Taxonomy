import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test, {afterEach} from 'node:test';
import vm from 'node:vm';
import canonicalFixture from './canonical-api-fixture.cjs';

const java = await readFile(new URL('../../taxonomy-app/src/test/java/com/taxonomy/TaxonomyLargeResultBudgetIT.java', import.meta.url), 'utf8');
const method = java.split('private static void installSyntheticSearchBoundary() {')[1];
const source = method?.match(/execute\("""([\s\S]*?)"""\);/)?.[1];
assert.ok(source, 'Exercise the actual native-browser fixture installation');
const flush = () => new Promise(resolve => setImmediate(resolve));
afterEach(() => canonicalFixture.dispose());

function boot() {
  const delegated = [], timers = new Map();
  let nextTimer = 0;
  const context = {
    window: {},
    document: {querySelector: selector => selector.includes('.tax-node') ? {dataset: {code: 'BP-1'}} : null},
    fetch: async (input, init) => {
      delegated.push({input, init});
      return Response.json([{code: 'REAL-BACKEND'}]);
    },
    setTimeout(callback, delay) {const id = ++nextTimer; timers.set(id, {callback, delay}); return id;},
    clearTimeout(id) {timers.delete(id);}
  };
  canonicalFixture.install(context, ['api/search-api.js']);
  const fetch = context.window.fetch;
  const install = () => vm.runInContext('(function () {' + source + '\n}());', context);
  return {context, delegated, timers, fetch, install, api: context.window.TaxonomySearchApi};
}

test('native budget fixture reaches captured canonical Search transport and updates one adapter', async () => {
  const h = boot();
  let registrations = 0;
  const register = h.context.window.TaxonomyApiClient.installTransportAdapter;
  h.context.window.TaxonomyApiClient.installTransportAdapter = (...args) => {registrations++; return register(...args);};
  h.install();
  h.context.window.__taxonomyBudgetInstall(3);
  assert.equal((await (await h.api.search('budget-3', 'lexical', 500)).json()).length, 3);
  h.install();
  h.context.window.__taxonomyBudgetInstall(7);
  const rows = await (await h.api.search('budget-7', 'lexical', 500)).json();
  assert.equal(rows.length, 7);
  assert.equal(rows[0].code, 'BP-1', 'Navigation still targets a real catalogue node');
  assert.equal(registrations, 1, 'Install once per document; later scenarios update only fixture state');
  assert.equal(h.context.window.fetch, h.fetch, 'Production global transport is not replaced after capture');
  assert.equal(h.delegated.length, 0, 'Synthetic search never falls through to real backend');
  const response = await h.context.window.TaxonomyApiClient.request('/api/other');
  assert.equal((await response.json())[0].code, 'REAL-BACKEND');
  assert.equal(h.delegated.length, 1, 'Unrelated calls delegate through the supplied transport exactly once');
});

test('native budget response delay remains inside the real canonical request lifecycle', async () => {
  const h = boot(); h.install(); h.context.window.__taxonomyBudgetInstall(4, 1300);
  let settled = false;
  const result = h.api.search('budget-4', 'lexical', 500).then(response => response.json()).then(rows => {settled = true; return rows;});
  await flush();
  assert.equal(settled, false);
  const delayed = [...h.timers.values()].filter(timer => timer.delay === 1300);
  assert.equal(delayed.length, 1, 'The actual synthetic response owns the unchanged requested delay');
  delayed[0].callback();
  assert.equal((await result).length, 4);
  assert.equal(h.delegated.length, 0);
});

test('native budget race fixtures preserve captured rows and slow failure after a newer search', async () => {
  const h = boot(); h.install(); h.context.window.__taxonomyBudgetInstall(3);
  const slow = h.api.search('race-slow-success', 'lexical', 500).then(response => response.json());
  const failed = h.api.search('race-slow-failure', 'lexical', 500);
  const rejection = assert.rejects(failed, error => error.code === 'NETWORK_ERROR');
  rejection.catch(() => {}); // Keep a failing RED assertion handled until its controlled await.
  await flush();
  h.context.window.__taxonomyBudgetInstall(5);
  assert.equal((await (await h.api.search('newer', 'lexical', 500)).json()).length, 5);
  const delayed = [...h.timers.values()].filter(timer => timer.delay === 220);
  assert.equal(delayed.length, 2);
  delayed.forEach(timer => timer.callback());
  assert.equal((await slow).length, 3, 'An in-flight response retains its original workload');
  await rejection;
  assert.equal(h.delegated.length, 0);
});
