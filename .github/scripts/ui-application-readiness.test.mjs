import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { UI_READINESS_GROUP, waitForApplication } from './ui-application-readiness.mjs';

function application() {
  return Object.assign(new EventEmitter(), { exitCode: null, signalCode: null });
}
const up = () => Response.json({ status: 'UP' });
const baseUrl = 'http://127.0.0.1:8080/taxonomy/';
const options = { baseUrl, timeoutMs: 2_000, intervalMs: 1, requestTimeoutMs: 500 };

function assertClean(app) {
  assert.equal(app.listenerCount('exit'), 0);
  assert.equal(app.listenerCount('error'), 0);
}

async function serve(t, handler) {
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  return `http://127.0.0.1:${server.address().port}`;
}

function json(response, body, status = 200) {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
}

test('only authoritative UP from the configured combined readiness group permits scenarios', async () => {
  const app = application();
  const paths = [];
  const evidence = {};
  const result = await waitForApplication(app, { ...options, evidence,
    fetchImpl: async (url, init) => {
      paths.push(url.pathname);
      assert.equal(init.redirect, 'manual');
      assert.equal(init.cache, 'no-store');
      assert.equal(init.signal.aborted, false);
      return up();
    }
  });
  assert.equal(result, evidence);
  assert.equal(result.outcome, 'ready');
  assert.deepEqual(paths, ['/taxonomy/actuator/health/readiness']);
  assert.equal(UI_READINESS_GROUP, 'readinessState,taxonomy');
  assertClean(app);
});

test('base paths work with and without a trailing slash', async () => {
  for (const base of ['http://localhost:8080', 'http://localhost:8080/',
    'http://localhost:8080/taxonomy', 'http://localhost:8080/taxonomy/']) {
    const paths = [];
    await waitForApplication(application(), { ...options, baseUrl: base,
      fetchImpl: async url => { paths.push(url.pathname); return up(); }
    });
    const prefix = base.includes('taxonomy') ? '/taxonomy' : '';
    assert.deepEqual(paths, [`${prefix}/actuator/health/readiness`]);
  }
});

test('redirects, unauthorized, not-found, unavailable, empty and malformed bodies never mean ready', async t => {
  const invalid = [
    ...[204, 301, 302, 401, 403, 404, 429, 499, 500, 503].map(status => () => new Response(null, { status })),
    () => Response.json({ status: 'DOWN' }),
    () => Response.json({ status: 'UNKNOWN' }),
    () => Response.json({ status: 'up' }),
    () => Response.json({}),
    () => Response.json(null),
    () => new Response('<html>login</html>'),
    () => new Response('{not-json'),
    () => new Response(' '.repeat(16 * 1024 + 1)),
    () => { throw new Error('secret-network-message'); }
  ];
  for (let index = 0; index < invalid.length; index++) {
    await t.test(`rejection case ${index}`, async () => {
      let calls = 0;
      const evidence = {};
      await waitForApplication(application(), { ...options, evidence,
        fetchImpl: async () => {
          calls++;
          if (calls === 1) return invalid[index]();
          return up();
        }
      });
      assert.equal(calls, 2, 'a rejected response must be polled again, never accepted');
      assert.notEqual(evidence.observations[0].outcome, 'ready');
      assert.equal(evidence.outcome, 'ready');
      assert.ok(!JSON.stringify(evidence).includes('secret-network-message'));
    });
  }
});

test('DOWN while the taxonomy loads must not start ready-state scenarios', async () => {
  let calls = 0;
  const evidence = await waitForApplication(application(), { ...options,
    fetchImpl: async () => ++calls < 4 ? Response.json({ status: 'DOWN' }) : up()
  });
  assert.equal(calls, 4);
  assert.deepEqual(evidence.observations.map(item => item.outcome),
    ['not-ready', 'not-ready', 'not-ready', 'ready']);
});

test('process exit, termination signal and spawn failure stop readiness', async t => {
  for (const state of ['exitCode', 'signalCode', 'exit', 'error']) {
    await t.test(state, async () => {
      const app = application();
      if (state === 'exitCode') app.exitCode = 0;
      if (state === 'signalCode') app.signalCode = 'SIGTERM';
      await assert.rejects(waitForApplication(app, { ...options,
        fetchImpl: async () => {
          app.emit(state, state === 'error' ? new Error('secret-spawn-detail') : 1);
          return up();
        }
      }), /UI application (exited before readiness|failed to start)/);
      assertClean(app);
    });
  }
});

test('exit while reading a successful probe cannot start a scenario', async () => {
  const app = application();
  await assert.rejects(waitForApplication(app, { ...options,
    fetchImpl: async url => {
      app.exitCode = 1;
      return up();
    }
  }), /exited before readiness/);
});

test('real HTTP server with login available waits for the combined health group', async t => {
  let calls = 0;
  const paths = [];
  const url = await serve(t, (request, response) => {
    paths.push(request.url);
    if (request.url.endsWith('/readiness')) {
      calls++;
      json(response, { status: calls === 1 ? 'DOWN' : 'UP' }, calls === 1 ? 503 : 200);
    } else json(response, { status: 'UP' });
  });
  const evidence = await waitForApplication(application(), { ...options, baseUrl: `${url}/taxonomy` });
  assert.equal(calls, 2);
  assert.equal(paths.includes('/login'), false);
  assert.equal(evidence.outcome, 'ready');
});

test('deadline bounds HTTP headers and body reads; partial responses cannot hang startup', async t => {
  for (const sendHeaders of [false, true]) {
    await t.test(sendHeaders ? 'hung body' : 'hung headers', async t => {
      const url = await serve(t, (_request, response) => {
        if (sendHeaders) {
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.write('{"status":');
        }
      });
      const app = application();
      const evidence = {};
      await assert.rejects(waitForApplication(app, { baseUrl: url, evidence,
        timeoutMs: 100, intervalMs: 1, requestTimeoutMs: 25
      }), /did not become ready within 100 ms/);
      assert.equal(evidence.outcome, 'failed');
      assert.ok(evidence.observations.length >= 1);
      assert.ok(evidence.observations.every(item => item.outcome === 'aborted'));
      assertClean(app);
    });
  }
});

test('redirect responses are not followed even when their target would report UP', async t => {
  let targetVisited = false;
  const url = await serve(t, (request, response) => {
    if (request.url === '/login') { targetVisited = true; json(response, { status: 'UP' }); }
    else { response.writeHead(302, { Location: '/login?token=never-log' }); response.end(); }
  });
  const evidence = {};
  await assert.rejects(waitForApplication(application(), { baseUrl: url, evidence,
    timeoutMs: 60, intervalMs: 1, requestTimeoutMs: 20
  }), /did not become ready/);
  assert.equal(targetVisited, false);
  assert.ok(!JSON.stringify(evidence).includes('never-log'));
});

test('diagnostic history is capped rather than accumulating unbounded responses', async () => {
  let calls = 0;
  const evidence = await waitForApplication(application(), { ...options,
    fetchImpl: async () => {
      calls++;
      if (calls <= 105) return new Response(null, { status: 503 });
      return up();
    }
  });
  assert.equal(evidence.observations.length, 100);
  assert.equal(evidence.suppressedObservations, 6);
  assert.equal(evidence.outcome, 'ready');
});

test('reject invalid budgets and credential-bearing base URLs without reflecting input', async () => {
  for (const base of ['not-a-url', 'file:///secret', 'https://u:p@host/',
    'http://host/?token=secret', 'http://host/#secret']) {
    await assert.rejects(waitForApplication(application(), { ...options, baseUrl: base }), error => {
      assert.ok(!error.message.includes('secret'));
      return /base URL/.test(error.message);
    });
  }
  for (const timeoutMs of [0, -1, NaN, Infinity, 1.5, 2_147_483_648]) {
    await assert.rejects(waitForApplication(application(), { ...options, timeoutMs }), /budgets/);
  }
});

test('runner and Maven-owned npm contract both use the readiness gate', async () => {
  const runner = await readFile(new URL('./run-ui-suite.mjs', import.meta.url), 'utf8');
  assert.match(runner, /import \{ UI_READINESS_GROUP, waitForApplication \} from '\.\/ui-application-readiness\.mjs'/);
  assert.match(runner, /await waitForApplication\(application, \{ baseUrl, evidence: groupTiming.readiness \}\)/);
  assert.match(runner, /MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE: UI_READINESS_GROUP/);
  assert.doesNotMatch(runner, /fetch\(`\$\{baseUrl\}\/login`/);
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
  assert.match(pkg.scripts['test:ui-application-readiness'], /node --test scripts\/ui-application-readiness\.test\.mjs/);
  for (const entry of ['verify:ui', 'verify:ui-contracts']) {
    assert.ok(pkg.scripts[entry].includes('npm run test:ui-application-readiness'));
  }
});

test('HTTP failure diagnostics retain method/path/status but not credentials or query secrets', async () => {
  const { observeHttpFailures } = await import('./ui-http-failure-evidence.mjs');
  const page = new EventEmitter();
  const evidence = observeHttpFailures(page);
  const request = { url: () => 'https://user:password@private-host/api/taxonomy;jsessionid=secret?token=hidden#fragment', method: () => 'GET' };
  page.emit('response', { status: () => 503, request: () => request });
  page.emit('response', { status: () => 200, request: () => request });
  page.emit('requestfailed', { url: () => 'http://localhost/assets/app.js?key=secret', method: () => 'GET' });
  assert.deepEqual(evidence.requests, [
    { path: '/api/taxonomy;[redacted]', method: 'GET', status: 503 },
    { path: '/assets/app.js', method: 'GET', status: null }
  ]);
  assert.ok(!/password|private-host|secret|hidden|fragment/.test(JSON.stringify(evidence)));
});

test('HTTP diagnostics are bounded and do not reflect malformed URLs', async () => {
  const { observeHttpFailures } = await import('./ui-http-failure-evidence.mjs');
  const page = new EventEmitter();
  const evidence = observeHttpFailures(page);
  for (let index = 0; index < 105; index++) {
    page.emit('requestfailed', { url: () => 'secret-invalid-url', method: () => 'GET' });
  }
  assert.equal(evidence.requests.length, 100);
  assert.equal(evidence.suppressed, 5);
  assert.equal(evidence.requests[0].path, '[invalid-url]');
});

test('process death aborts an in-flight HTTP probe and removes lifecycle listeners', async t => {
  const app = application();
  const url = await serve(t, () => app.emit('exit', 1));
  await assert.rejects(waitForApplication(app, { ...options, baseUrl: url }), /exited before readiness/);
  assertClean(app);
});

test('browser report includes HTTP evidence without weakening the clean-console gate', async () => {
  const source = await readFile(new URL('./ui-acceptance.mjs', import.meta.url), 'utf8');
  assert.match(source, /const httpFailures = observeHttpFailures\(page\)/);
  assert.match(source, /externalRequests, consoleErrors, httpFailures, auditError/);
  assert.match(source, /assert\(consoleErrors.length === 0,/);
});
