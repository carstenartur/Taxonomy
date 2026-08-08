#!/usr/bin/env python3
"""Install the hardened canonical frontend API transport for issue #644."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIENT = ROOT / "taxonomy-app/src/main/resources/static/js/api/taxonomy-api-client.js"
CI = ROOT / ".github/workflows/ci-cd.yml"
NODE_TEST = ROOT / ".github/scripts/test-taxonomy-api-client.mjs"
JAVA_TEST = ROOT / "taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyApiClientContractTest.java"

source = CLIENT.read_text(encoding="utf-8")
suffix_marker = "\n\n(function loadAuthenticatedUiSurfaces()"
suffix_index = source.find(suffix_marker)
if suffix_index < 0:
    raise SystemExit("Could not locate authenticated-surface suffix in taxonomy-api-client.js")
suffix = source[suffix_index:]

core = r'''/* taxonomy-api-client.js – Canonical HTTP transport for the Taxonomy UI.
 *
 * Named api/*.js feature clients use this transport for JSON and FormData.
 * Streaming and large-download adapters remain deliberately separate.
 * The legacy global interceptor keeps direct fetch() debt CSRF-safe until those
 * callers are migrated, while named helpers use request() directly.
 */
window.TaxonomyApiClient = (function () {
    'use strict';

    // taxonomy-i18n.js installs the external base-path wrapper before this file.
    // Capture that wrapper once, then apply the remaining transport policy here.
    var transportFetch = window.fetch.bind(window);
    var accountContextPromise = null;
    var DEFAULT_TIMEOUT_MILLIS = 30000;
    var REQUEST_ID_HEADER = 'X-Request-ID';

    function csrfMetadata() {
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !token.content) return null;
        return {
            name: (header && header.content) || 'X-CSRF-TOKEN',
            value: token.content
        };
    }

    function csrfHeaders() {
        var metadata = csrfMetadata();
        if (!metadata) return {};
        var headers = {};
        headers[metadata.name] = metadata.value;
        return headers;
    }

    function inputUrl(input) {
        if (typeof input === 'string' || input instanceof URL) return String(input);
        return input && input.url ? input.url : '';
    }

    function isSameOrigin(input) {
        try {
            return new URL(inputUrl(input), window.location.href).origin === window.location.origin;
        } catch (ignored) {
            return true;
        }
    }

    function requestPath(input) {
        try {
            return new URL(inputUrl(input), window.location.href).pathname;
        } catch (ignored) {
            return '';
        }
    }

    function createRequestId() {
        if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
            return crypto.randomUUID();
        }
        return 'taxonomy-' + Date.now().toString(36) + '-' +
            Math.random().toString(36).slice(2);
    }

    function responseRequestId(response, fallback) {
        if (!response || !response.headers) return fallback;
        return response.headers.get(REQUEST_ID_HEADER)
            || response.headers.get('X-Correlation-ID')
            || fallback;
    }

    function ApiError(message, status, url, responseBody, metadata) {
        var details = metadata || {};
        this.name = 'ApiError';
        this.message = message;
        this.status = status || 0;
        this.url = url || '';
        this.responseBody = responseBody;
        this.type = details.type || null;
        this.title = details.title || null;
        this.detail = details.detail || null;
        this.instance = details.instance || null;
        this.requestId = details.requestId || null;
        this.code = details.code || 'HTTP_ERROR';
        this.retryable = Boolean(details.retryable);
        this.cause = details.cause || null;
        if (Error.captureStackTrace) Error.captureStackTrace(this, ApiError);
    }
    ApiError.prototype = Object.create(Error.prototype);
    ApiError.prototype.constructor = ApiError;

    function parseResponseBody(response) {
        return response.clone().text().then(function (text) {
            if (!text) return null;
            try {
                return JSON.parse(text);
            } catch (ignored) {
                return text;
            }
        }).catch(function () { return null; });
    }

    function dispatchAuthFailure(error) {
        if (error.status !== 401 && error.status !== 403) return;
        document.dispatchEvent(new CustomEvent('taxonomy-api-auth-failure', {
            detail: {
                status: error.status,
                url: error.url,
                requestId: error.requestId,
                code: error.code
            }
        }));
    }

    function checkStatus(response, context) {
        if (response.ok) return Promise.resolve(response);
        return parseResponseBody(response).then(function (body) {
            var problem = body && typeof body === 'object' ? body : {};
            var detail = problem.detail || problem.message || problem.error
                || (typeof body === 'string' ? body : null);
            var title = problem.title || null;
            var message = detail || title
                ? 'HTTP ' + response.status + ': ' + (detail || title)
                : 'HTTP ' + response.status;
            var error = new ApiError(message, response.status,
                response.url || context.url, body, {
                    type: problem.type,
                    title: title,
                    detail: detail,
                    instance: problem.instance,
                    requestId: responseRequestId(response, context.requestId),
                    code: 'HTTP_ERROR',
                    retryable: [429, 502, 503, 504].indexOf(response.status) >= 0
                });
            dispatchAuthFailure(error);
            throw error;
        });
    }

    function parseJson(response) {
        if (response.status === 204) return null;
        return response.json().catch(function (error) {
            throw new ApiError('Invalid JSON response from server', response.status,
                response.url, null, {
                    requestId: responseRequestId(response, null),
                    code: 'INVALID_JSON',
                    cause: error
                });
        });
    }

    function validateOptions(options) {
        var retries = options.retries === undefined ? 0 : Number(options.retries);
        if (!Number.isInteger(retries) || retries < 0) {
            throw new TypeError('API retry count must be a non-negative integer');
        }
        if (retries > 0 && options.idempotent !== true) {
            throw new TypeError('Automatic API retry requires idempotent: true');
        }
        var timeoutMillis = options.timeoutMillis === undefined
            ? DEFAULT_TIMEOUT_MILLIS : Number(options.timeoutMillis);
        if (!Number.isFinite(timeoutMillis) || timeoutMillis < 0) {
            throw new TypeError('API timeoutMillis must be a non-negative number');
        }
        return { retries: retries, timeoutMillis: timeoutMillis };
    }

    function createRequestScope(options, validated) {
        var controller = new AbortController();
        var timedOut = false;
        var callerAborted = false;
        var timer = null;
        var callerSignal = options.signal || null;
        var onCallerAbort = function () {
            callerAborted = true;
            controller.abort(callerSignal.reason);
        };

        if (callerSignal) {
            if (callerSignal.aborted) onCallerAbort();
            else callerSignal.addEventListener('abort', onCallerAbort, { once: true });
        }
        if (validated.timeoutMillis > 0) {
            timer = setTimeout(function () {
                timedOut = true;
                controller.abort();
            }, validated.timeoutMillis);
        }
        return {
            signal: controller.signal,
            timedOut: function () { return timedOut; },
            callerAborted: function () { return callerAborted; },
            cleanup: function () {
                if (timer !== null) clearTimeout(timer);
                if (callerSignal) callerSignal.removeEventListener('abort', onCallerAbort);
            }
        };
    }

    function prepareRequest(input, init, options, requestId, signal) {
        var prepared = Object.assign({}, init || {});
        var method = (prepared.method || (input instanceof Request ? input.method : 'GET'))
            .toUpperCase();
        var inheritedHeaders = input instanceof Request ? input.headers : undefined;
        var headers = new Headers(prepared.headers || inheritedHeaders || {});
        var sameOrigin = isSameOrigin(input);
        var metadata = csrfMetadata();

        if (sameOrigin && prepared.credentials === undefined) {
            prepared.credentials = 'same-origin';
        }
        if (sameOrigin && !headers.has(REQUEST_ID_HEADER)) {
            headers.set(REQUEST_ID_HEADER, requestId);
        }
        if (metadata && sameOrigin && method !== 'GET' && method !== 'HEAD'
                && !headers.has(metadata.name)) {
            headers.set(metadata.name, metadata.value);
        }
        prepared.method = method;
        prepared.headers = headers;
        prepared.signal = signal;
        return prepared;
    }

    function normalizeTransportError(error, context, scope, validated) {
        if (error instanceof ApiError) return error;
        if (scope.timedOut()) {
            return new ApiError(
                'Request timed out after ' + validated.timeoutMillis + ' ms',
                0, context.url, null, {
                    requestId: context.requestId,
                    code: 'TIMEOUT',
                    retryable: true,
                    cause: error
                });
        }
        if (scope.callerAborted()) {
            return new ApiError('Request was cancelled', 0, context.url, null, {
                requestId: context.requestId,
                code: 'ABORTED',
                retryable: false,
                cause: error
            });
        }
        return new ApiError('Network request failed', 0, context.url, null, {
            requestId: context.requestId,
            code: 'NETWORK_ERROR',
            retryable: true,
            cause: error
        });
    }

    function request(url, init, options) {
        var requestOptions = Object.assign({}, options || {});
        var validated = validateOptions(requestOptions);
        var requestId = requestOptions.requestId || createRequestId();
        var context = { url: inputUrl(url), requestId: requestId };

        function attempt(number) {
            var scope = createRequestScope(requestOptions, validated);
            var prepared = prepareRequest(
                url, init, requestOptions, requestId, scope.signal);
            return transportFetch(url, prepared)
                .then(function (response) { return checkStatus(response, context); })
                .catch(function (error) {
                    throw normalizeTransportError(error, context, scope, validated);
                })
                .finally(scope.cleanup)
                .catch(function (error) {
                    var callerCancelled = requestOptions.signal
                        && requestOptions.signal.aborted;
                    if (!callerCancelled && number < validated.retries
                            && error.retryable && requestOptions.idempotent === true) {
                        return attempt(number + 1);
                    }
                    throw error;
                });
        }
        return attempt(0);
    }

    function getAccountContext() {
        if (!accountContextPromise) {
            accountContextPromise = getJson('/api/account/me', {
                idempotent: true,
                timeoutMillis: 10000
            }).catch(function (error) {
                accountContextPromise = null;
                throw error;
            });
        }
        return accountContextPromise;
    }

    function isAdminPromptBootstrap(input, method) {
        if (method !== 'GET' || !isSameOrigin(input)) return false;
        var path = requestPath(input);
        return path === '/api/prompts' || path === '/api/prompts/categories';
    }

    function emptyPromptBootstrapResponse(input) {
        var body = requestPath(input) === '/api/prompts/categories' ? '{}' : '[]';
        return new Response(body, {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
        });
    }

    function installGlobalCsrfInterceptor() {
        if (window.fetch.__taxonomyCsrfInterceptor) return;
        var originalFetch = window.fetch.bind(window);

        function csrfAwareFetch(input, init) {
            var requestInit = Object.assign({}, init || {});
            var method = (requestInit.method || (input instanceof Request ? input.method : 'GET'))
                .toUpperCase();
            var metadata = csrfMetadata();
            if (metadata && isSameOrigin(input) && method !== 'GET' && method !== 'HEAD') {
                var inheritedHeaders = input instanceof Request ? input.headers : undefined;
                requestInit.headers = new Headers(requestInit.headers || inheritedHeaders || {});
                if (!requestInit.headers.has(metadata.name)) {
                    requestInit.headers.set(metadata.name, metadata.value);
                }
            }

            if (isAdminPromptBootstrap(input, method)) {
                return getAccountContext().then(function (account) {
                    return account && account.administrator
                        ? originalFetch(input, requestInit)
                        : emptyPromptBootstrapResponse(input);
                });
            }
            return originalFetch(input, requestInit);
        }

        csrfAwareFetch.__taxonomyCsrfInterceptor = true;
        csrfAwareFetch.__taxonomyOriginalFetch = originalFetch;
        window.fetch = csrfAwareFetch;
    }

    function getJson(url, options) {
        var requestOptions = Object.assign({ idempotent: true }, options || {});
        return request(url, { method: 'GET' }, requestOptions).then(parseJson);
    }

    function sendJson(url, body, method, options) {
        var actualMethod = method;
        var requestOptions = options;
        if (actualMethod && typeof actualMethod === 'object') {
            requestOptions = actualMethod;
            actualMethod = null;
        }
        return request(url, {
            method: actualMethod || 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }, requestOptions).then(parseJson);
    }

    function sendFormData(url, formData, method, options) {
        var actualMethod = method;
        var requestOptions = options;
        if (actualMethod && typeof actualMethod === 'object') {
            requestOptions = actualMethod;
            actualMethod = null;
        }
        return request(url, {
            method: actualMethod || 'POST',
            body: formData
        }, requestOptions).then(parseJson);
    }

    function deleteJson(url, options) {
        return request(url, { method: 'DELETE' }, options).then(parseJson);
    }

    installGlobalCsrfInterceptor();

    return {
        ApiError: ApiError,
        request: request,
        getJson: getJson,
        getAccountContext: getAccountContext,
        sendJson: sendJson,
        sendFormData: sendFormData,
        deleteJson: deleteJson,
        csrfHeaders: csrfHeaders,
        defaultTimeoutMillis: DEFAULT_TIMEOUT_MILLIS
    };
}());
'''

CLIENT.write_text(core + suffix, encoding="utf-8")

node_test = r'''import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(
  new URL('../../taxonomy-app/src/main/resources/static/js/api/taxonomy-api-client.js', import.meta.url),
  'utf8'
);
const core = source.slice(0, source.indexOf('(function loadAuthenticatedUiSurfaces'));

class TestCustomEvent {
  constructor(type, options = {}) {
    this.type = type;
    this.detail = options.detail;
  }
}

function abortingFetch() {
  return (_input, init) => new Promise((_resolve, reject) => {
    const rejectAbort = () => reject(new DOMException('Aborted', 'AbortError'));
    if (init.signal.aborted) rejectAbort();
    else init.signal.addEventListener('abort', rejectAbort, { once: true });
  });
}

function loadClient(fetchImpl) {
  const events = [];
  const csrf = { content: 'csrf-token' };
  const csrfHeader = { content: 'X-CSRF-TOKEN' };
  const document = {
    querySelector(selector) {
      if (selector === 'meta[name="_csrf"]') return csrf;
      if (selector === 'meta[name="_csrf_header"]') return csrfHeader;
      return null;
    },
    dispatchEvent(event) {
      events.push(event);
      return true;
    }
  };
  const window = {
    fetch: fetchImpl,
    location: {
      href: 'https://taxonomy.example.test/taxonomy/',
      origin: 'https://taxonomy.example.test'
    }
  };
  const context = vm.createContext({
    window,
    document,
    URL,
    Request,
    Response,
    Headers,
    AbortController,
    DOMException,
    CustomEvent: TestCustomEvent,
    crypto: { randomUUID: () => 'client-request-id' },
    setTimeout,
    clearTimeout,
    Date,
    Math,
    Number,
    Object,
    JSON,
    Promise,
    console
  });
  context.fetch = (...args) => window.fetch(...args);
  vm.runInContext(core, context, { filename: 'taxonomy-api-client.js' });
  return { client: window.TaxonomyApiClient, events };
}

{
  const calls = [];
  const { client } = loadClient(async (input, init) => {
    calls.push({ input, init });
    return new Response('{"ok":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  });
  assert.deepEqual(await client.getJson('/api/status'), { ok: true });
  assert.equal(calls[0].input, '/api/status');
  assert.equal(calls[0].init.credentials, 'same-origin');
  assert.equal(calls[0].init.headers.get('X-Request-ID'), 'client-request-id');
  assert.equal(calls[0].init.headers.has('X-CSRF-TOKEN'), false);
}

{
  const calls = [];
  const { client } = loadClient(async (input, init) => {
    calls.push({ input, init });
    return new Response('{"saved":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  });
  assert.deepEqual(await client.sendJson('/api/items', { name: 'A' }), { saved: true });
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.credentials, 'same-origin');
  assert.equal(calls[0].init.headers.get('Content-Type'), 'application/json');
  assert.equal(calls[0].init.headers.get('X-CSRF-TOKEN'), 'csrf-token');
  assert.equal(calls[0].init.headers.get('X-Request-ID'), 'client-request-id');
  assert.equal(calls[0].init.body, '{"name":"A"}');
}

{
  const { client } = loadClient(async () => new Response(JSON.stringify({
    type: 'https://taxonomy.example.test/problems/invalid',
    title: 'Invalid request',
    status: 400,
    detail: 'The supplied project is invalid',
    instance: '/api/projects/42'
  }), {
    status: 400,
    headers: {
      'Content-Type': 'application/problem+json',
      'X-Request-ID': 'server-request-id'
    }
  }));
  await assert.rejects(client.getJson('/api/projects/42'), error => {
    assert.equal(error.name, 'ApiError');
    assert.equal(error.status, 400);
    assert.equal(error.type, 'https://taxonomy.example.test/problems/invalid');
    assert.equal(error.title, 'Invalid request');
    assert.equal(error.detail, 'The supplied project is invalid');
    assert.equal(error.instance, '/api/projects/42');
    assert.equal(error.requestId, 'server-request-id');
    return true;
  });
}

{
  const { client, events } = loadClient(async () => new Response('{"detail":"Forbidden"}', {
    status: 403,
    headers: { 'Content-Type': 'application/problem+json' }
  }));
  await assert.rejects(client.getJson('/api/admin'), error => error.status === 403);
  assert.equal(events.length, 1);
  assert.equal(events[0].type, 'taxonomy-api-auth-failure');
  assert.deepEqual(events[0].detail, {
    status: 403,
    url: '/api/admin',
    requestId: 'client-request-id',
    code: 'HTTP_ERROR'
  });
}

{
  const { client } = loadClient(abortingFetch());
  await assert.rejects(
    client.getJson('/api/slow', { timeoutMillis: 5 }),
    error => error.code === 'TIMEOUT' && error.retryable === true
  );
}

{
  const controller = new AbortController();
  const { client } = loadClient(abortingFetch());
  const pending = client.getJson('/api/cancelled', {
    signal: controller.signal,
    timeoutMillis: 1000
  });
  controller.abort();
  await assert.rejects(
    pending,
    error => error.code === 'ABORTED' && error.retryable === false
  );
}

{
  const { client } = loadClient(async () => new Response('{}', { status: 200 }));
  assert.throws(
    () => client.sendJson('/api/mutate', {}, 'POST', { retries: 1 }),
    /requires idempotent: true/
  );
}

{
  let attempts = 0;
  const { client } = loadClient(async () => {
    attempts += 1;
    if (attempts === 1) throw new TypeError('transient network failure');
    return new Response('{"recovered":true}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  });
  assert.deepEqual(
    await client.getJson('/api/retry', { retries: 1 }),
    { recovered: true }
  );
  assert.equal(attempts, 2);
}

console.log('Taxonomy canonical API transport tests passed.');
'''
NODE_TEST.write_text(node_test, encoding="utf-8")

java_test = r'''package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the canonical timeout, cancellation and ProblemDetail transport contract. */
class TaxonomyApiClientContractTest {

    @Test
    void namedHelpersUseOneCanonicalTransportInsteadOfCallingGlobalFetch() throws Exception {
        String source = resource("/static/js/api/taxonomy-api-client.js");

        assertThat(source)
                .contains("function request(url, init, options)")
                .contains("var transportFetch = window.fetch.bind(window)")
                .contains("prepared.credentials = 'same-origin'")
                .contains("headers.set(REQUEST_ID_HEADER, requestId)")
                .contains("function createRequestScope(options, validated)")
                .contains("code: 'TIMEOUT'")
                .contains("code: 'ABORTED'")
                .contains("taxonomy-api-auth-failure")
                .contains("Automatic API retry requires idempotent: true")
                .contains("return request(url, { method: 'GET' }, requestOptions).then(parseJson)")
                .doesNotContain("function getJson(url) {\n        return fetch(url)")
                .doesNotContain("function sendJson(url, body, method) {\n        return fetch(url");
    }

    @Test
    void problemDetailsRemainStructuredOnApiErrors() throws Exception {
        String source = resource("/static/js/api/taxonomy-api-client.js");

        assertThat(source)
                .contains("this.type = details.type || null")
                .contains("this.title = details.title || null")
                .contains("this.detail = details.detail || null")
                .contains("this.instance = details.instance || null")
                .contains("this.requestId = details.requestId || null")
                .contains("problem.detail || problem.message || problem.error");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = TaxonomyApiClientContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
'''
JAVA_TEST.parent.mkdir(parents=True, exist_ok=True)
JAVA_TEST.write_text(java_test, encoding="utf-8")

ci = CI.read_text(encoding="utf-8")
marker = "          node .github/scripts/test-taxonomy-base-path.mjs\n"
replacement = marker + "          node .github/scripts/test-taxonomy-api-client.mjs\n"
if replacement not in ci:
    if ci.count(marker) != 1:
        raise SystemExit(f"Expected one base-path test invocation, found {ci.count(marker)}")
    CI.write_text(ci.replace(marker, replacement, 1), encoding="utf-8")

print("Installed canonical API timeout, abort, request-ID and ProblemDetail transport.")
