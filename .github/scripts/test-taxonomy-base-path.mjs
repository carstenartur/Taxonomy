import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const i18nSource = await readFile(
  new URL('../../taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js', import.meta.url),
  'utf8'
);
const stateSource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-state.js',
    import.meta.url
  ),
  'utf8'
);
const sessionLoaderSource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session.js',
    import.meta.url
  ),
  'utf8'
);
const sessionContextPathRoutingSource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/' +
      'taxonomy-analysis-session-context-path-routing.js',
    import.meta.url
  ),
  'utf8'
);
const sessionProjectsSource = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/' +
      'taxonomy-analysis-session-projects.js',
    import.meta.url
  ),
  'utf8'
);

async function loadBootstrap(scriptUrl) {
  const requests = [];
  const nativeFetch = async input => {
    requests.push(input);
    return { ok: true, json: async () => ({}) };
  };
  const document = {
    currentScript: { src: scriptUrl },
    cookie: '',
    documentElement: { lang: 'en' },
    querySelector: () => null,
    createElement: () => ({ dataset: {} }),
    head: { appendChild: () => undefined },
    dispatchEvent: () => undefined
  };
  const window = {
    fetch: nativeFetch,
    location: { href: 'https://taxonomy.example.test/taxonomy/' }
  };
  const context = vm.createContext({
    window,
    document,
    localStorage: { getItem: () => null, setItem: () => undefined },
    URL,
    CustomEvent: class CustomEvent {},
    encodeURIComponent,
    console
  });
  context.fetch = (...args) => window.fetch(...args);
  vm.runInContext(i18nSource, context, { filename: 'taxonomy-i18n.js' });
  await window.TaxonomyI18n.ready();
  return { window, requests };
}

function resolvePrefixed(value) {
  if (typeof value !== 'string' || !value.startsWith('/')
      || value.startsWith('/taxonomy/')) return value;
  return '/taxonomy' + value;
}

function stateRuntime({ suppressLoader = false } = {}) {
  const domReady = [];
  const appendedScripts = [];
  const document = {
    querySelector(selector) {
      assert.equal(selector, 'script[data-taxonomy-analysis-session]');
      return suppressLoader ? {} : null;
    },
    querySelectorAll: () => [],
    createElement(type) {
      assert.equal(type, 'script');
      return { dataset: {} };
    },
    head: {
      appendChild(script) {
        appendedScripts.push(script);
      }
    },
    addEventListener(type, listener) {
      if (type === 'DOMContentLoaded') domReady.push(listener);
    }
  };
  const window = {
    console,
    setTimeout,
    TaxonomyI18n: { resolveUrl: resolvePrefixed }
  };
  vm.runInNewContext(stateSource, {
    window,
    document,
    console,
    Array,
    Boolean,
    JSON,
    Map,
    Number,
    Object,
    Proxy,
    Reflect,
    Set,
    String
  }, { filename: 'taxonomy-state.js' });
  return { window, domReady, appendedScripts };
}

function loadOrderedSessionParts() {
  const appendedScripts = [];
  const document = {
    createElement(type) {
      assert.equal(type, 'script');
      const listeners = new Map();
      return {
        dataset: {},
        addEventListener(event, listener) {
          listeners.set(event, listener);
        },
        fire(event) {
          const listener = listeners.get(event);
          if (listener) listener();
        }
      };
    },
    head: {
      appendChild(script) {
        appendedScripts.push(script);
        script.fire('load');
      }
    }
  };
  const window = {
    console,
    TaxonomyI18n: { resolveUrl: resolvePrefixed }
  };
  vm.runInNewContext(sessionLoaderSource, {
    window,
    document,
    console,
    Map,
    String
  }, { filename: 'taxonomy-analysis-session.js' });
  return appendedScripts;
}

function contextPathRoutingRuntime() {
  const fetchCalls = [];
  const eventSourceCalls = [];
  const nativeFetch = async (input, init) => {
    fetchCalls.push({ input, init });
    return { ok: true };
  };

  class NativeEventSource {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 2;

    constructor(url, configuration) {
      this.url = url;
      this.configuration = configuration;
      eventSourceCalls.push({ url, configuration });
    }
  }

  const analysisContext = {
    runtime: { workspaceId: 'workspace-42' },
    installWorkspaceFetchRouting: () => undefined,
    installWorkspaceEventSourceRouting: () => undefined
  };
  const window = {
    __TaxonomyAnalysisSessionContext: analysisContext,
    TaxonomyI18n: { resolveUrl: resolvePrefixed },
    location: {
      href: 'https://taxonomy.example.test/taxonomy/',
      origin: 'https://taxonomy.example.test'
    },
    fetch: nativeFetch,
    EventSource: NativeEventSource,
    console
  };
  vm.runInNewContext(sessionContextPathRoutingSource, {
    window,
    console,
    URL,
    Headers,
    Request,
    Object,
    String
  }, { filename: 'taxonomy-analysis-session-context-path-routing.js' });
  return { window, analysisContext, fetchCalls, eventSourceCalls };
}

async function promotedNavigationTarget() {
  const assigned = [];
  const invalidations = [];
  const analysisContext = {
    S: {},
    runtime: {},
    CHANGE_POLL_MS: 1500,
    language: () => 'en',
    text: key => key,
    businessTextElement: () => null,
    jsonRequest: () => Promise.resolve([]),
    rememberedWorkspaceId: () => null,
    rememberWorkspaceId: () => undefined,
    installWorkspaceFetchRouting: () => undefined,
    installWorkspaceEventSourceRouting: () => undefined,
    currentPayload: () => ({}),
    comparable: () => '{}',
    isStale: () => false,
    statusArea: () => null,
    showActionAlert: () => undefined,
    invalidate: options => invalidations.push(options),
    queueStaleActions: () => undefined,
    queueSave: () => undefined,
    deleteDraft: () => Promise.resolve(),
    saveDraft: () => Promise.resolve(),
    loadDraft: () => Promise.resolve(null)
  };
  const document = {
    readyState: 'loading',
    addEventListener: () => undefined
  };
  const window = {
    __TaxonomyAnalysisSessionContext: analysisContext,
    TaxonomyI18n: { resolveUrl: resolvePrefixed },
    location: { assign: value => assigned.push(value) },
    setInterval: () => 0,
    addEventListener: () => undefined,
    console
  };
  vm.runInNewContext(sessionProjectsSource, {
    window,
    document,
    console,
    Array,
    Date,
    JSON,
    Math,
    MutationObserver: class MutationObserver {},
    Number,
    Object,
    Promise,
    String,
    encodeURIComponent
  }, { filename: 'taxonomy-analysis-session-projects.js' });

  await analysisContext.discardDraftAndNavigate('/projects/42');
  assert.equal(invalidations.length, 1);
  return assigned[0];
}

{
  const { window, requests } = await loadBootstrap(
    'https://taxonomy.example.test/taxonomy/js/taxonomy-i18n.js'
  );
  assert.equal(window.TaxonomyI18n.getBasePath(), '/taxonomy');
  assert.equal(requests[0], '/taxonomy/api/i18n/en');

  await window.fetch('/api/status');
  await window.fetch('/taxonomy/api/status');
  await window.fetch('https://provider.example.test/v1/status');
  assert.deepEqual(requests.slice(1), [
    '/taxonomy/api/status',
    '/taxonomy/api/status',
    'https://provider.example.test/v1/status'
  ]);
}

{
  const { window, requests } = await loadBootstrap(
    'https://taxonomy.example.test/js/taxonomy-i18n.js'
  );
  assert.equal(window.TaxonomyI18n.getBasePath(), '');
  assert.equal(requests[0], '/api/i18n/en');
}

{
  const runtime = stateRuntime();
  assert.equal(runtime.appendedScripts.length, 1);
  assert.equal(
    runtime.appendedScripts[0].src,
    '/taxonomy/js/core/taxonomy-analysis-session.js'
  );
}

{
  const parts = loadOrderedSessionParts();
  assert.deepEqual(parts.map(script => script.src), [
    '/taxonomy/js/api/analysis-session-api.js',
    '/taxonomy/js/core/taxonomy-analysis-session-core.js',
    '/taxonomy/js/core/taxonomy-analysis-session-context-path-routing.js',
    '/taxonomy/js/core/taxonomy-analysis-session-api-routing.js',
    '/taxonomy/js/core/taxonomy-analysis-session-transport.js',
    '/taxonomy/js/core/taxonomy-analysis-session-ui.js',
    '/taxonomy/js/core/taxonomy-analysis-session-draft.js',
    '/taxonomy/js/core/taxonomy-analysis-session-projects.js'
  ]);
}

{
  const runtime = contextPathRoutingRuntime();
  runtime.analysisContext.installWorkspaceFetchRouting();
  const installedFetch = runtime.window.fetch;
  runtime.analysisContext.installWorkspaceFetchRouting();
  assert.equal(runtime.window.fetch, installedFetch);
  assert.equal(installedFetch.__taxonomyWorkspaceRouting, true);
  assert.equal(installedFetch.__taxonomyContextPathWorkspaceRouting, true);

  await runtime.window.fetch('/taxonomy/api/status', {
    headers: { 'X-Caller': 'preserved' }
  });
  const request = new Request(
    'https://taxonomy.example.test/taxonomy/api/request', {
      headers: { 'X-Request': 'request-header' }
    }
  );
  await runtime.window.fetch(request, {
    headers: { 'X-Init': 'init-header' }
  });
  await runtime.window.fetch('/api/status', {
    headers: { 'X-Root': 'root-header' }
  });
  await runtime.window.fetch('https://provider.example.test/v1/status');

  const prefixedHeaders = new Headers(runtime.fetchCalls[0].init.headers);
  assert.equal(prefixedHeaders.get('X-Taxonomy-Workspace-Id'), 'workspace-42');
  assert.equal(prefixedHeaders.get('X-Caller'), 'preserved');
  const requestHeaders = new Headers(runtime.fetchCalls[1].init.headers);
  assert.equal(requestHeaders.get('X-Taxonomy-Workspace-Id'), 'workspace-42');
  assert.equal(requestHeaders.get('X-Request'), 'request-header');
  assert.equal(requestHeaders.get('X-Init'), 'init-header');
  assert.equal(
    new Headers(runtime.fetchCalls[2].init.headers).get('X-Taxonomy-Workspace-Id'),
    null
  );
  assert.equal(runtime.fetchCalls[3].init.headers, undefined);

  runtime.analysisContext.installWorkspaceEventSourceRouting();
  const InstalledEventSource = runtime.window.EventSource;
  runtime.analysisContext.installWorkspaceEventSourceRouting();
  assert.equal(runtime.window.EventSource, InstalledEventSource);
  assert.equal(InstalledEventSource.__taxonomyWorkspaceRouting, true);
  assert.equal(InstalledEventSource.__taxonomyContextPathWorkspaceRouting, true);

  new runtime.window.EventSource('/taxonomy/api/analyze-stream?existing=1');
  new runtime.window.EventSource('/api/analyze-stream');
  new runtime.window.EventSource('https://provider.example.test/events');
  const prefixedEventUrl = new URL(runtime.eventSourceCalls[0].url);
  assert.equal(prefixedEventUrl.pathname, '/taxonomy/api/analyze-stream');
  assert.equal(prefixedEventUrl.searchParams.get('existing'), '1');
  assert.equal(prefixedEventUrl.searchParams.get('workspaceId'), 'workspace-42');
  assert.equal(runtime.eventSourceCalls[1].url, '/api/analyze-stream');
  assert.equal(runtime.eventSourceCalls[2].url, 'https://provider.example.test/events');
}

{
  const nativeCalls = [];
  class NativeEventSource {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 2;

    constructor(url) {
      this.url = url;
      this.listeners = new Map();
      nativeCalls.push(url);
    }

    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, []);
      this.listeners.get(type).push(listener);
    }

    removeEventListener(type, listener) {
      const listeners = this.listeners.get(type) || [];
      this.listeners.set(type, listeners.filter(candidate => candidate !== listener));
    }

    close() {}
  }

  const runtime = stateRuntime({ suppressLoader: true });
  runtime.window.EventSource = NativeEventSource;
  runtime.window.TaxonomyScoring = {
    runStreamingAnalysis() {
      return new runtime.window.EventSource('/api/analyze-stream');
    }
  };
  for (const listener of runtime.domReady) listener();

  function LateWorkspaceRouter(url, configuration) {
    return new NativeEventSource('/taxonomy' + url, configuration);
  }
  LateWorkspaceRouter.prototype = NativeEventSource.prototype;
  runtime.window.EventSource = LateWorkspaceRouter;

  runtime.window.TaxonomyScoring.runStreamingAnalysis();
  assert.deepEqual(nativeCalls, ['/taxonomy/api/analyze-stream']);
  assert.equal(runtime.window.EventSource, LateWorkspaceRouter);
}

assert.equal(await promotedNavigationTarget(), '/taxonomy/projects/42');

console.log('Taxonomy base-path bootstrap tests passed.');
