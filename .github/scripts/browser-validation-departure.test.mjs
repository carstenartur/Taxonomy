import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Execute the unchanged acceptance setup through its departure checkpoint.
// Only Playwright's page/transport boundary is simulated; the observer and flag
// transitions are taken from the actual acceptance code, not reimplemented here.
const source = readFileSync(new URL('./browser-sessions-acceptance.mjs', import.meta.url), 'utf8');
const start = source.indexOf('    let pageInventoryRequests = 0, hiddenValidationRequests = 0;');
const end = source.indexOf('      const health = ', start);
assert.ok(start >= 0 && end > start, 'The acceptance setup must remain inspectable');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const inspectDeparture = new AsyncFunction('assert', 'page', 'navigateToPage', 'base', 'apiUrl',
  'validationUrl', 'locale', source.slice(start, end) + `
      return hiddenValidationRequests;
    } finally {
      page.off('request', observe);
    }
`);

async function scenario(leakAt) {
  const base = new URL('https://example.invalid/prefix/');
  const validationUrl = new URL('api/dsl/validate', base);
  const document = 'taxonomy IP { name "Information products"; }';
  let observer, destination, waitCount = 0, editorVisits = 0;
  const waiters = [];
  const request = { url: () => validationUrl.href, method: () => 'POST', postData: () => document };
  const response = { url: () => validationUrl.href, request: () => request,
    status: () => 200, finished: async () => null };
  const exported = { url: () => new URL('api/dsl/export', base).href,
    request: () => ({ method: () => 'GET' }), status: () => 200,
    finished: async () => null, text: async () => document };
  const emptyValidation = { ...response,
    request: () => ({ ...request, postData: () => '' }) };
  const deliver = value => waiters.forEach(({ predicate, resolve }) => {
    Promise.resolve(predicate(value)).then(matches => { if (matches) resolve(value); });
  });
  const emit = () => observer(request);
  const page = {
    on(event, callback) { assert.equal(event, 'request'); observer = callback; },
    off(event, callback) { assert.equal(callback, observer); observer = null; },
    async evaluate() { return document; },
    async waitForLoadState() {},
    async goto() { if (leakAt === 'startup') emit(); },
    async waitForFunction() {
      waitCount++;
      if (destination === 'admin' && leakAt === 'hide-checkpoint') emit();
    },
    waitForResponse(predicate) {
      return new Promise(resolve => { waiters.push({ predicate, resolve }); });
    },
    locator() { return { async scrollIntoViewIfNeeded() {} }; }
  };
  const navigate = async (_, target) => {
    destination = target;
    if (target === 'dsl-editor') {
      if (++editorVisits === 1) {
        emit();
        deliver(emptyValidation);
        // Resolve the initial export after the empty editor's validation. The
        // acceptance code must wait for the matching nonempty document instead.
        await new Promise(resolve => setImmediate(resolve));
        deliver(exported);
      }
      emit();
      deliver(response);
    }
    if (target === 'admin' && leakAt === 'departure') emit();
  };
  try {
    const count = await inspectDeparture(assert, page, navigate, base,
      new URL('api/admin/sessions', base), validationUrl, 'de');
    assert.equal(waitCount, 4);
    return count;
  } finally {
    assert.equal(observer, null, 'The request observer must be removed even on assertion failure');
  }
}

test('only loaded-document validation completes reveal, including a slow initial export', async () => {
  assert.equal(await scenario(null), 0);
});

test('a request emitted while navigation departs must count as unexpected', async () => {
  assert.equal(await scenario('departure'), 1,
    'The departure window must not inherit the reveal request allowance');
});

test('a request emitted before the hide checkpoint resolves must count as unexpected', async () => {
  assert.equal(await scenario('hide-checkpoint'), 1);
});

test('background startup validation still fails the acceptance checkpoint', async () => {
  await assert.rejects(scenario('startup'), /hidden startup editor/);
});
