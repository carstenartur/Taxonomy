#!/usr/bin/env python3
"""Preserve request context on JSON errors and harden the Node test bootstrap marker."""

from pathlib import Path

root = Path(__file__).resolve().parents[2]
client_path = root / "taxonomy-app/src/main/resources/static/js/api/taxonomy-api-client.js"
node_test_path = root / ".github/scripts/test-taxonomy-api-client.mjs"
java_test_path = root / (
    "taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyApiClientContractTest.java"
)

client = client_path.read_text(encoding="utf-8")
old_state = """    var transportFetch = window.fetch.bind(window);
    var accountContextPromise = null;
    var DEFAULT_TIMEOUT_MILLIS = 30000;
"""
new_state = """    var transportFetch = window.fetch.bind(window);
    var accountContextPromise = null;
    var responseContexts = new WeakMap();
    var DEFAULT_TIMEOUT_MILLIS = 30000;
"""
if client.count(old_state) != 1:
    raise SystemExit(f"Expected one transport state block, found {client.count(old_state)}")
client = client.replace(old_state, new_state, 1)

old_status = """    function checkStatus(response, context) {
        if (response.ok) return Promise.resolve(response);
        return parseResponseBody(response).then(function (body) {
"""
new_status = """    function checkStatus(response, context) {
        if (response.ok) {
            responseContexts.set(response, context);
            return Promise.resolve(response);
        }
        return parseResponseBody(response).then(function (body) {
"""
if client.count(old_status) != 1:
    raise SystemExit(f"Expected one status block, found {client.count(old_status)}")
client = client.replace(old_status, new_status, 1)

old_json = """    function parseJson(response) {
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
"""
new_json = """    function parseJson(response) {
        if (response.status === 204) return null;
        var context = responseContexts.get(response) || {};
        return response.json().catch(function (error) {
            throw new ApiError('Invalid JSON response from server', response.status,
                response.url || context.url, null, {
                    requestId: responseRequestId(response, context.requestId || null),
                    code: 'INVALID_JSON',
                    cause: error
                });
        });
    }
"""
if client.count(old_json) != 1:
    raise SystemExit(f"Expected one JSON parser block, found {client.count(old_json)}")
client_path.write_text(client.replace(old_json, new_json, 1), encoding="utf-8")

node_test = node_test_path.read_text(encoding="utf-8")
old_cut = """const core = source.slice(0, source.indexOf('(function loadAuthenticatedUiSurfaces'));
"""
new_cut = """const authenticatedSurfaceMarker = '(function loadAuthenticatedUiSurfaces';
const authenticatedSurfaceIndex = source.indexOf(authenticatedSurfaceMarker);
assert.notEqual(
  authenticatedSurfaceIndex,
  -1,
  `Missing API client bootstrap marker: ${authenticatedSurfaceMarker}`
);
const core = source.slice(0, authenticatedSurfaceIndex);
"""
if node_test.count(old_cut) != 1:
    raise SystemExit(f"Expected one Node source cut, found {node_test.count(old_cut)}")
node_test = node_test.replace(old_cut, new_cut, 1)

invalid_json_test = """
{
  const { client } = loadClient(async () => new Response('not-json', {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  }));
  await assert.rejects(
    client.getJson('/api/invalid-json', { requestId: 'invalid-json-request' }),
    error => {
      assert.equal(error.code, 'INVALID_JSON');
      assert.equal(error.requestId, 'invalid-json-request');
      assert.equal(error.url, '/api/invalid-json');
      return true;
    }
  );
}

"""
retry_marker = """{
  const { client } = loadClient(abortingFetch());
  await assert.rejects(
"""
if invalid_json_test not in node_test:
    if node_test.count(retry_marker) != 1:
        raise SystemExit(f"Expected one timeout-test marker, found {node_test.count(retry_marker)}")
    node_test = node_test.replace(retry_marker, invalid_json_test + retry_marker, 1)
node_test_path.write_text(node_test, encoding="utf-8")

java_test = java_test_path.read_text(encoding="utf-8")
old_assertion = """                .contains(\"headers.set(REQUEST_ID_HEADER, requestId)\")
                .contains(\"function createRequestScope(options, validated)\")
"""
new_assertion = """                .contains(\"headers.set(REQUEST_ID_HEADER, requestId)\")
                .contains(\"var responseContexts = new WeakMap()\")
                .contains(\"responseRequestId(response, context.requestId || null)\")
                .contains(\"function createRequestScope(options, validated)\")
"""
if java_test.count(old_assertion) != 1:
    raise SystemExit(f"Expected one Java assertion block, found {java_test.count(old_assertion)}")
java_test_path.write_text(java_test.replace(old_assertion, new_assertion, 1), encoding="utf-8")

print("Applied request-context and fail-closed test-marker review fixes.")
