import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/' +
      'taxonomy-analysis-session-api-routing.js',
    import.meta.url
  ),
  'utf8'
);

const calls = [];
const client = {};
for (const name of ['request', 'getJson', 'sendJson', 'sendFormData', 'deleteJson']) {
  client[name] = function (...args) {
    calls.push({ name, args });
    return args[0];
  };
}

const runtime = { workspaceId: 'workspace A/1' };
const window = {
  __TaxonomyAnalysisSessionContext: { runtime },
  TaxonomyApiClient: client,
  location: {
    href: 'https://taxonomy.example.test/app/',
    origin: 'https://taxonomy.example.test'
  }
};

vm.runInNewContext(source, {
  window,
  URL,
  Array,
  Object,
  RegExp
}, { filename: 'taxonomy-analysis-session-api-routing.js' });

assert.equal(
  client.getJson('/api/projects?state=open'),
  '/api/projects?state=open&workspaceId=workspace+A%2F1'
);
assert.equal(
  client.sendJson('https://taxonomy.example.test/api/items', {}),
  'https://taxonomy.example.test/api/items?workspaceId=workspace+A%2F1'
);
assert.equal(
  client.getJson('https://other.example.test/api/items'),
  'https://other.example.test/api/items'
);
assert.equal(client.getJson('/assets/help.json'), '/assets/help.json');
assert.equal(calls.length, 4);

runtime.workspaceId = 'workspace-b';
assert.equal(
  client.deleteJson('/api/items/1?workspaceId=obsolete'),
  '/api/items/1?workspaceId=workspace-b'
);
runtime.workspaceId = null;
assert.equal(client.request('/api/status', { method: 'GET' }), '/api/status');
assert.equal(client.__taxonomyWorkspaceRouting, true);
assert.equal(
  typeof window.__TaxonomyAnalysisSessionContext.workspaceScopedApiUrl,
  'function'
);

console.log('Taxonomy analysis-session API routing tests passed.');
