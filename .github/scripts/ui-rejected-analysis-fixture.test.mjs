import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const flow = await readFile(new URL('./ui-role-state-flow.mjs', import.meta.url), 'utf8');
// Run the actual injection and final audit without starting a browser. The only
// double is the Playwright boundary; neither the fixture nor its policy is copied.
const fixtureImports = flow.includes("from './ui-rejected-analysis-fixture.mjs'")
  ? await import('./ui-rejected-analysis-fixture.mjs') : {};
const injectionStart = flow.indexOf("  const sunburst = page.locator('#viewSunburst');");
const click = "  await page.locator('#analyzeBtn').click();";
const injectionEnd = flow.indexOf(click, injectionStart) + click.length;
const auditStart = flow.indexOf('  const expectedHttpFailure = failure => {');
const auditEndMarker = "  passed('expected HTTP error states only');";
const auditEnd = flow.indexOf(auditEndMarker, auditStart) + auditEndMarker.length;
assert.ok(injectionStart >= 0 && injectionEnd > injectionStart);
assert.ok(auditStart >= 0 && auditEnd > auditStart);
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const runFixtureAndAudit = new AsyncFunction('page', 'httpFailures', 'role', 'assert', 'passed',
  ...Object.keys(fixtureImports),
  flow.slice(injectionStart, injectionEnd) + '\n' + flow.slice(auditStart, auditEnd));

const continuationId = '4f4c29d8-4269-4101-90fc-6d92412cf4d5';
const rejectedStart = { path: '/api/analyze', method: 'POST', status: 503,
  requestId: 'qa-rejected-analysis-4f4c29d8-4269-4101-90fc-6d92412cf4d5' };
const missingReceipt = { path: '/api/analysis-continuations/4f4c29d8-4269-4101-90fc-6d92412cf4d5',
  method: 'GET', status: 404, requestId: 'receipt-request' };

async function audit(failures, { role = 'USER', method = 'POST', mount = '', body = {
  resumable: true, continuationAction: 'START', continuationId
} } = {}) {
  let routeHandler;
  let fulfilled = false;
  const request = { method: () => method, url: () => 'http://127.0.0.1:8080' + mount + '/api/analyze',
    postDataJSON: () => body, headers: () => ({ 'x-request-id': 'start-request' }) };
  const route = { request: () => request, fulfill: async response => {
    assert.equal(response.status, 503);
    assert.equal(response.headers['x-request-id'], rejectedStart.requestId);
    fulfilled = true;
  } };
  const page = {
    route: async (matches, handler, options) => {
      assert.equal(matches(new URL(request.url())), true);
      assert.equal(matches(new URL('http://127.0.0.1:8080/api/analysis-continuations/other')), false);
      assert.deepEqual(options, { times: 1 });
      routeHandler = handler;
    },
    locator: selector => ({ scrollIntoViewIfNeeded: async () => {},
      click: async () => { if (selector === '#analyzeBtn') await routeHandler(route); } }),
    waitForFunction: async () => {}
  };
  await runFixtureAndAudit(page, failures, role,
    (condition, message) => assert.ok(condition, message), () => {}, ...Object.values(fixtureImports));
  assert.equal(fulfilled, true);
}

test('the exact observation is registered before the injected response finishes', async () => {
  const { createRejectedAnalysisFixture } = fixtureImports;
  const fixture = createRejectedAnalysisFixture();
  let finishResponse;
  const responsePending = new Promise(resolve => { finishResponse = resolve; });
  const rejection = fixture.rejectStart({ request: () => ({
    method: () => 'POST', url: () => 'http://127.0.0.1:8080/api/analyze',
    postDataJSON: () => ({ resumable: true, continuationAction: 'START', continuationId })
  }), fulfill: () => responsePending });
  assert.equal(fixture.matches(rejectedStart), true);
  assert.equal(fixture.matches(missingReceipt), true);
  fixture.assertComplete();
  finishResponse();
  await rejection;
});

test('the rejected durable start admits exactly its same-ID missing receipt observation', async () => {
  await audit([rejectedStart, missingReceipt]);
});

for (const mount of ['', '/taxonomy']) {
  test(`the rejected START admits repeated same-ID receipt reads at ${mount || 'the root mount'}`, async () => {
    await audit([{ ...rejectedStart, path: mount + rejectedStart.path },
      ...['initial-read', 'decision-observation', 'restored-observation'].map(requestId => ({
        ...missingReceipt, path: mount + missingReceipt.path, requestId
      }))], { mount });
  });
}

test('a mounted rejection never authorizes the same UUID at a different mount', async () => {
  await assert.rejects(audit([{ ...rejectedStart, path: '/taxonomy' + rejectedStart.path },
    missingReceipt], { mount: '/taxonomy' }));
});

test('the injection cannot reject a second START even with the same identity', async () => {
  const fixture = fixtureImports.createRejectedAnalysisFixture();
  let fulfilled = 0;
  const route = { request: () => ({ method: () => 'POST',
    url: () => 'http://127.0.0.1:8080/api/analyze',
    postDataJSON: () => ({ resumable: true, continuationAction: 'START', continuationId })
  }), fulfill: async () => { fulfilled++; } };
  await fixture.rejectStart(route);
  await assert.rejects(fixture.rejectStart(route), /requires one resumable START/);
  assert.equal(fulfilled, 1);
});

for (const [label, failures] of [
  ['missing receipt observation', [rejectedStart]],
  ['missing rejected start', [missingReceipt]],
  ['no observed requests', []],
  ['duplicate rejected start', [rejectedStart, { ...rejectedStart }, missingReceipt]],
  ['wrong continuation ID', [rejectedStart, { ...missingReceipt, path: '/api/analysis-continuations/another-id' }]],
  ['wrong observation method', [rejectedStart, { ...missingReceipt, method: 'POST' }]],
  ['wrong observation status', [rejectedStart, { ...missingReceipt, status: 503 }]],
  ['wrong start method', [{ ...rejectedStart, method: 'GET' }, missingReceipt]],
  ['wrong start status', [{ ...rejectedStart, status: 502 }, missingReceipt]],
  ['another start response', [{ ...rejectedStart, requestId: 'unrelated-request' }, missingReceipt]],
  ['unrelated missing receipt', [rejectedStart, missingReceipt,
    { ...missingReceipt, path: '/api/analysis-continuations/another-id' }]],
  ['unrelated HTTP failure', [rejectedStart, missingReceipt,
    { path: '/api/context/current', method: 'GET', status: 404 }]]
]) {
  test(`the role-state audit still rejects ${label}`, async () => {
    await assert.rejects(audit(failures));
  });
}

for (const [role, status] of [['USER', 403], ['ARCHITECT', 409], ['ADMIN', 409]]) {
  test(`the injected analysis pair preserves the existing ${role} proposal policy`, async () => {
    await audit([rejectedStart, missingReceipt,
      { path: '/api/proposals/from-hypothesis', method: 'POST', status }], { role });
  });
}

for (const [label, body] of [
  ['non-durable request', { resumable: false, continuationAction: 'START', continuationId }],
  ['another operation', { resumable: true, continuationAction: 'RESUME', continuationId }],
  ['missing identity', { resumable: true, continuationAction: 'START' }],
  ['path instead of identity', { resumable: true, continuationAction: 'START', continuationId: '../other' }]
]) {
  test(`the injection refuses a ${label} before authorizing any observation`, async () => {
    await assert.rejects(audit([rejectedStart, missingReceipt], { body }), /resumable START/);
  });
}
