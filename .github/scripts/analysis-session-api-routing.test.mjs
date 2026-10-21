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

const eventSourceCalls = [];
class FakeEventSource {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;

  constructor(url, configuration) {
    this.url = url;
    this.configuration = configuration;
    eventSourceCalls.push({ url, configuration });
  }
}

function resolveApplicationUrl(value) {
  if (typeof value !== 'string' || !value.startsWith('/')
      || value.startsWith('/taxonomy/')) return value;
  return '/taxonomy' + value;
}

const runtime = { workspaceId: 'workspace A/1' };
const window = {
  __TaxonomyAnalysisSessionContext: { runtime },
  TaxonomyApiClient: client,
  TaxonomyI18n: {
    getBasePath: () => '/taxonomy',
    resolveUrl: resolveApplicationUrl
  },
  EventSource: FakeEventSource,
  location: {
    href: 'https://taxonomy.example.test/taxonomy/',
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
  client.getJson('/taxonomy/api/projects?state=open'),
  '/taxonomy/api/projects?state=open&workspaceId=workspace+A%2F1'
);
assert.equal(
  client.getJson('https://other.example.test/api/items'),
  'https://other.example.test/api/items'
);
assert.equal(client.getJson('/assets/help.json'), '/assets/help.json');
assert.equal(calls.length, 5);

// Database setup is installation-wide and must remain usable when the tab's
// workspace is unavailable. All wizard operations retain their original URL.
for (const prefix of ['', '/taxonomy', 'https://taxonomy.example.test/taxonomy']) {
  const setupUrl = prefix + '/api/admin/database-setup';
  assert.equal(client.getJson(setupUrl), setupUrl);
  for (const operation of ['dismiss', 'check', 'preview']) {
    const operationUrl = setupUrl + '/' + operation;
    assert.equal(client.sendJson(operationUrl, {}), operationUrl);
  }
}
// The exception is limited to this exact route family, and explicit caller
// pins are retained for the server's usual ownership/lifecycle validation.
assert.equal(
  client.getJson('/api/admin/database-setup-history'),
  '/api/admin/database-setup-history?workspaceId=workspace+A%2F1'
);
assert.equal(
  client.getJson('/api/admin/database-setup?workspaceId=explicit'),
  '/api/admin/database-setup?workspaceId=explicit'
);
assert.equal(
  client.request('/api/admin/database-setup', { method: 'GET' }, { pinnedWorkspaceId: 'explicit' }),
  '/api/admin/database-setup?workspaceId=explicit'
);

runtime.workspaceId = 'workspace-b';
assert.equal(
  client.deleteJson('/api/items/1?workspaceId=obsolete'),
  '/api/items/1?workspaceId=workspace-b'
);
assert.equal(client.__taxonomyWorkspaceRouting, true);
assert.equal(
  typeof window.__TaxonomyAnalysisSessionContext.workspaceScopedApiUrl,
  'function'
);

window.__TaxonomyAnalysisSessionContext.installWorkspaceEventSourceRouting();
new window.EventSource('/api/analyze-stream?businessText=example');
assert.equal(
  eventSourceCalls[0].url,
  'https://taxonomy.example.test/taxonomy/api/analyze-stream' +
    '?businessText=example&workspaceId=workspace-b'
);
assert.equal(window.EventSource.CONNECTING, FakeEventSource.CONNECTING);

runtime.workspaceId = null;
new window.EventSource('/api/status');
new window.EventSource('https://provider.example.test/events');
assert.equal(eventSourceCalls[1].url, '/taxonomy/api/status');
assert.equal(eventSourceCalls[2].url, 'https://provider.example.test/events');

assert.equal(client.request('/api/status', { method: 'GET' }), '/api/status');

console.log('Taxonomy analysis-session API routing tests passed.');
