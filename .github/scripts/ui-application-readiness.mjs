import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';

const MAX_BODY_BYTES = 16 * 1024;
const MAX_OBSERVATIONS = 100;
// Applied only to the child process started by the browser harness.
export const UI_READINESS_GROUP = 'readinessState,taxonomy';

function applicationUrl(baseUrl) {
  let url;
  try { url = new URL(baseUrl); } catch { throw new Error('Invalid UI application base URL'); }
  if (!['http:', 'https:'].includes(url.protocol)
      || url.username || url.password || url.search || url.hash) {
    throw new Error('UI application base URL must be HTTP(S) without credentials, query or fragment');
  }
  url.pathname = `${url.pathname.replace(/\/+$/, '')}/`;
  return url;
}

async function readBoundedJson(response) {
  if (!response.body) throw new Error('Missing readiness body');
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) throw new Error('Oversized readiness body');
      chunks.push(Buffer.from(value));
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } finally {
    // Do not let an unresponsive peer's cancellation delay the startup deadline.
    void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

/**
 * Gate ordinary ready-state browser scenarios on BOTH process readiness and the
 * asynchronous catalogue. The caller includes UI_READINESS_GROUP in the child
 * process configuration so UP covers both indicators without authenticating.
 * Defaults preserve the existing 180-second total budget and 2-second polling.
 * Evidence contains only fixed endpoint paths, status codes and local outcomes:
 * never response bodies, headers, credentials, query strings or exception text.
 */
export async function waitForApplication(application, {
  baseUrl,
  timeoutMs = 180_000,
  intervalMs = 2_000,
  requestTimeoutMs = 5_000,
  fetchImpl = globalThis.fetch,
  evidence = {}
}) {
  for (const value of [timeoutMs, intervalMs, requestTimeoutMs]) {
    if (!Number.isSafeInteger(value) || value <= 0 || value > 2_147_483_647) {
      throw new Error('Readiness budgets must be positive 32-bit timer integers');
    }
  }
  const base = applicationUrl(baseUrl);
  const started = performance.now();
  const lifetime = new AbortController();
  const timeoutError = new Error(`UI application did not become ready within ${timeoutMs} ms`);
  const onExit = () => lifetime.abort(new Error('UI application exited before readiness'));
  const onError = () => lifetime.abort(new Error('UI application failed to start'));
  const checkProcess = () => {
    if (application.exitCode != null || application.signalCode != null) onExit();
    lifetime.signal.throwIfAborted();
  };
  evidence.observations = [];
  evidence.suppressedObservations = 0;
  evidence.outcome = 'waiting';
  const record = observation => {
    const item = { ...observation, elapsedMs: Math.round(performance.now() - started) };
    if (evidence.observations.length < MAX_OBSERVATIONS) evidence.observations.push(item);
    else evidence.suppressedObservations++;
  };
  const timer = setTimeout(() => lifetime.abort(timeoutError), timeoutMs);
  application.once('exit', onExit);
  application.once('error', onError);

  async function probe() {
    const endpoint = 'actuator/health/readiness';
    const attempt = new AbortController();
    const remaining = timeoutMs - (performance.now() - started);
    if (remaining <= 0) throw timeoutError;
    const attemptTimer = setTimeout(() => attempt.abort(), Math.min(requestTimeoutMs, remaining));
    const signal = AbortSignal.any([lifetime.signal, attempt.signal]);
    let status = null;
    let body;
    let observation;
    try {
      const response = await fetchImpl(new URL(endpoint, base), {
        redirect: 'manual', cache: 'no-store', signal,
        headers: { Accept: 'application/json' }
      });
      status = response.status;
      if (status !== 200) {
        void response.body?.cancel().catch(() => {});
        observation = { path: endpoint, status, outcome: 'http-not-ready' };
      } else {
        body = await readBoundedJson(response);
        observation = { path: endpoint, status, outcome: body?.status === 'UP' ? 'ready' : 'not-ready' };
      }
    } catch {
      observation = { path: endpoint, status,
        outcome: signal.aborted ? 'aborted' : status === null ? 'unreachable' : 'invalid-body' };
    } finally {
      clearTimeout(attemptTimer);
      attempt.abort();
    }
    record(observation);
    checkProcess();
    if (performance.now() - started >= timeoutMs) throw timeoutError;
    return observation.outcome === 'ready';
  }

  try {
    while (true) {
      checkProcess();
      if (performance.now() - started >= timeoutMs) throw timeoutError;
      const ready = await probe();
      checkProcess();
      if (ready) {
        evidence.outcome = 'ready';
        return evidence;
      }
      const remaining = timeoutMs - (performance.now() - started);
      if (remaining <= 0) throw timeoutError;
      await delay(Math.min(intervalMs, remaining), undefined, { signal: lifetime.signal });
    }
  } catch (error) {
    evidence.outcome = 'failed';
    throw lifetime.signal.aborted ? lifetime.signal.reason : error;
  } finally {
    clearTimeout(timer);
    lifetime.abort();
    application.removeListener('exit', onExit);
    application.removeListener('error', onError);
    evidence.durationMs = Math.round(performance.now() - started);
  }
}
