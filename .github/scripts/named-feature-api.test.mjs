import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const root = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const sources = new Map();
for (const path of ['api/taxonomy-api-client.js', 'api/analysis-api.js', 'api/portfolio-api.js',
  'api/import-api.js', 'core/taxonomy-analysis-session-transport.js', 'portfolio/taxonomy-portfolio-async.js',
  'core/taxonomy-analysis.js', 'shared/taxonomy-document-import.js', 'api/export-api.js',
  'api/copilot-api.js', 'api/architecture-workbench-api.js', 'api/document-templates-api.js', 'shared/architecture-workflow.js', 'portfolio/taxonomy-portfolio.js', 'core/taxonomy-scoring.js', 'api/proposals-api.js', 'api/hypotheses-api.js', 'shared/taxonomy-database-setup.js']) {
  sources.set(path, await readFile(new URL(path, root), 'utf8'));
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => {resolve = yes; reject = no;});
  return {promise, resolve, reject};
}
const flush = () => new Promise(resolve => setImmediate(resolve));

function boot(fetchImpl, {analysis = false, portfolio = false, beforeCanonical = false, workflow = false, search = '', nodes = new Map()} = {}) {
  const events = [], listeners = new Map(), timers = new Map(), stored = new Map();
  let timerId = 0;
  const runtime = {workspaceId: 'workspace-a', analysisGeneration: 0, invalidating: false};
  const window = {
    fetch: fetchImpl,
    location: {href: 'https://taxonomy.example/taxonomy/projects' + search, search, origin: 'https://taxonomy.example', pathname: '/taxonomy/projects'},
    TaxonomyI18n: {t: (key, ...args) => key + args.join(' '), getBasePath: () => '/taxonomy', resolveUrl: path => String(path).startsWith('/api/') ? '/taxonomy' + path : path},
    localStorage: {getItem: key => stored.get(key) || null, setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key)},
    sessionStorage: {getItem: () => null, setItem() {}, removeItem() {}},
    addEventListener() {},
    setTimeout: (callback, delay) => {timers.set(++timerId, {callback, delay}); return timerId;},
    clearTimeout: id => timers.delete(id),
    setInterval: () => 1, clearInterval() {},
    TaxonomyContentLanguage: {analysis: () => 'en'}
  };
  const C = {
    runtime, S: {}, language: () => 'en', requestUrl: input => new URL(String(input), window.location.href),
    comparable: JSON.stringify, jsonRequest: async () => null, currentPayload: () => ({}),
    installWorkspaceFetchRouting() {}, installWorkspaceEventSourceRouting() {}
  };
  window.__TaxonomyAnalysisSessionContext = C;
  const document = {
    documentElement: {lang: 'en'},
    querySelector: selector => selector === 'meta[name="_csrf"]' ? {content: 'csrf-fixture'}
      : selector === 'meta[name="_csrf_header"]' ? {content: 'X-CSRF-TOKEN'} : null,
    querySelectorAll: () => [], getElementById: id => nodes.get(id) || null,
    createTextNode: text => ({textContent: String(text)}),
    createElement: () => ({innerHTML: '', appendChild(node) {this.innerHTML += node.textContent || '';}}),
    addEventListener(type, listener) {if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(listener);},
    dispatchEvent(event) {events.push(event); for (const listener of listeners.get(event.type) || []) listener(event); return true;}
  };
  const context = vm.createContext({window, document, console, URL, URLSearchParams, Request, Response,
    Headers, FormData, Blob, AbortController, DOMException, Proxy, Reflect,
    CustomEvent: class {constructor(type, options = {}) {this.type = type; this.detail = options.detail;}},
    crypto: {randomUUID: () => 'feature-request-id'},
    setTimeout: window.setTimeout, clearTimeout: window.clearTimeout, queueMicrotask,
    Date, Math, Number, Object, Array, JSON, Promise, Set, Map, WeakMap});
  context.fetch = (...args) => window.fetch(...args);
  context.TaxonomyI18n = window.TaxonomyI18n;
  context.TaxonomyUtils = {escapeHtml: String};
  function load(path) {vm.runInContext(sources.get(path), context, {filename: path});}
  if (beforeCanonical && workflow) load('shared/architecture-workflow.js');
  if (beforeCanonical && analysis) load('core/taxonomy-analysis-session-transport.js');
  if (beforeCanonical && portfolio) load('portfolio/taxonomy-portfolio-async.js');
  const source = sources.get('api/taxonomy-api-client.js');
  const marker = source.indexOf('(function loadAuthenticatedUiSurfaces');
  assert.ok(marker > 0);
  vm.runInContext(source.slice(0, marker), context, {filename: 'taxonomy-api-client.js'});
  load('api/analysis-api.js'); load('api/portfolio-api.js'); load('api/import-api.js');
  if (workflow && !beforeCanonical) load('shared/architecture-workflow.js');
  if (analysis && !beforeCanonical) load('core/taxonomy-analysis-session-transport.js');
  if (portfolio && !beforeCanonical) load('portfolio/taxonomy-portfolio-async.js');
  return {window, context, C, runtime, document, events, timers, stored, nodes, load};
}

test('global database onboarding survives initial workspace hydration without acquiring a workspace pin',async()=>{
  const status=deferred(),requests=[];
  const app=boot(async(url,init)=>{
    const endpoint=new URL(url, 'https://taxonomy.example');
    if(endpoint.pathname.endsWith('/api/account/me'))return Response.json({administrator:true});
    requests.push({endpoint,init});return status.promise;
  });
  app.document.readyState='loading';
  app.load('shared/taxonomy-database-setup.js');
  const preparation=app.window.TaxonomyDatabaseSetup.prepareWelcome();
  await flush();
  assert.equal(requests.length,1);assert.equal(requests[0].endpoint.pathname,'/taxonomy/api/admin/database-setup');
  app.runtime.workspaceId='hydrated-workspace';app.runtime.analysisGeneration++;
  app.document.dispatchEvent({type:'taxonomy:entity-context-changed'});
  status.resolve(Response.json({database:'hsqldb',inMemory:true,promptRequired:true}));
  assert.equal(await preparation,true,'The application database offer must survive unrelated workspace initialization');
  assert.equal(requests[0].endpoint.searchParams.has('workspaceId'),false);
  assert.equal(new Headers(requests[0].init.headers).has('X-Taxonomy-Workspace-Id'),false);
  assert.equal(requests[0].init.signal.aborted,false);
  assert.equal(app.timers.size,0);
});

test('portfolio startup reads the selected source only after lifecycle initialization settles',async()=>{
  const hydration=deferred(),requests=[],nodes=new Map();
  function element() {return {value:'',textContent:'',innerHTML:'',dataset:{},children:[],
    classList:{add(){},remove(){},toggle(){}},addEventListener(){},setAttribute(){},
    append(...values){this.children.push(...values);},appendChild(value){this.children.push(value);},
    replaceChildren(...values){this.children=values;},scrollIntoView(){}};}
  const template=await readFile(new URL('../../taxonomy-app/src/main/resources/templates/projects.html',import.meta.url),'utf8');
  for(const match of template.matchAll(/\bid="([^"]+)"/g))nodes.set(match[1],element());
  let scope={workspaceId:'initial-workspace',repositoryId:'initial-repository',branch:'draft'};
  const app=boot(async(url,init)=>{requests.push({url,init});return Response.json([]);},{nodes});
  app.window.TaxonomyI18n.ready=()=>Promise.resolve();
  app.window.TaxonomyArchitectureLifecycle={ready:()=>hydration.promise};
  app.window.TaxonomyArchitectureWorkflow={getScope:()=>scope,preferredProject:()=>null};
  const query=app.document.querySelector;
  app.document.querySelector=selector=>query(selector)||element();
  app.document.createElement=element;
  vm.runInContext(sources.get('portfolio/taxonomy-portfolio.js').replace(/\}\)\(\);\s*$/,
    'window.portfolioStartupFixture = {initialize,state};})();'),app.context);
  const started=app.window.portfolioStartupFixture.initialize();
  await flush();const earlyRequests=requests.length;
  scope={workspaceId:'resolved-workspace',repositoryId:'resolved-repository',branch:'draft'};
  app.runtime.workspaceId='resolved-workspace';hydration.resolve({context:scope});
  await started;
  assert.equal(earlyRequests,0,'The initial portfolio must not use the source before lifecycle hydration');
  assert.equal(requests.length,1);
  const request=new URL(requests[0].url,'https://taxonomy.example');
  assert.equal(request.searchParams.get('workspaceId'),'resolved-workspace');
  assert.equal(request.searchParams.get('repositoryId'),'resolved-repository');
  assert.equal(new Headers(requests[0].init.headers).get('X-Taxonomy-Workspace-Id'),'resolved-workspace');
});

for (const barrier of ['serverUnconfirmed', 'draftDecisionPending']) {
  test(`named analysis honors ${barrier} admission before any provider request`, async () => {
    const requests = [];
    const app = boot(async (url, init) => {requests.push({url, init}); return Response.json({accepted: true});}, {analysis: true});
    app.runtime[barrier] = true;
    await assert.rejects(app.window.AnalysisApi.analyzeGap({scores: {}}),
      error => error.code === 'ANALYSIS_SESSION_NOT_READY');
    assert.equal(requests.length, 0, 'unconfirmed analysis must not reach the network');
  });
}

test('named analysis owns one active request and releases it after a successful response', async () => {
  const pending = deferred();
  const app = boot(() => pending.promise, {analysis: true});
  const request = app.window.AnalysisApi.detectPatterns({scores: {}});
  await flush();
  assert.equal(app.runtime.activeAnalysisControllers.size, 1);
  pending.resolve(Response.json({patterns: []}));
  assert.deepEqual(await request, {patterns: []});
  assert.equal(app.runtime.activeAnalysisControllers.size, 0);
  const events = app.events.filter(event => event.type === 'taxonomy:analysis-running-changed');
  assert.equal(events.length, 2, 'one admission and one completion event');
});

test('named analysis cannot publish JSON completed after its generation is abandoned', async () => {
  const body = deferred(), entered = deferred(), rendered = [];
  const response = Response.json({recommendations: ['old']});
  response.json = () => {entered.resolve(); return body.promise;};
  const app = boot(async () => response, {analysis: true});
  const request = app.window.AnalysisApi.recommend({scores: {}});
  request.then(value => rendered.push(value), () => {});
  await entered.promise;
  app.runtime.analysisGeneration++;
  body.resolve({recommendations: ['old']});
  await flush(); await flush();
  assert.deepEqual(rendered, [], 'the prior generation must not update the new analysis');
});

for (const status of [401, 403]) {
  test(`the actual Portfolio client emits one shared authentication transition for HTTP ${status}`, async () => {
    const calls = [];
    const app = boot(async (url, init) => {calls.push({url, init}); return Response.json({detail: 'Access expired'}, {status});}, {portfolio: true});
    await assert.rejects(app.window.TaxonomyPortfolioApi.getProject(1), error => error.status === status);
    const auth = app.events.filter(event => event.type === 'taxonomy-api-auth-failure');
    assert.equal(auth.length, 1);
    assert.equal(auth[0].detail.status, status);
    assert.equal(new Headers(calls[0].init.headers).get('X-Request-ID'), 'feature-request-id');
  });
}

test('named Portfolio admission keeps background jobs, evidence sanitation and one poll', async () => {
  const calls = [];
  const job = {id: 'job-1', projectId: 1, status: 'QUEUED', items: []};
  const app = boot(async (url, init) => {
    calls.push({url, init});
    return Response.json(job, {status: 202, headers: {Location: '/taxonomy/api/projects/1/analysis-jobs/job-1'}});
  }, {portfolio: true});
  const response = await app.window.TaxonomyPortfolioApi.analyzeRequirement(1, 2,
    {actionEvidence: 'Confirmed in the project portfolio workspace', comment: 'Human review completed'});
  assert.equal(new Headers(calls[0].init.headers).get('X-Request-ID'), 'feature-request-id');
  assert.equal(new Headers(calls[0].init.headers).get('X-CSRF-TOKEN'), 'csrf-fixture');
  assert.equal(response.status, 200, 'legacy background normalization is retained');
  assert.deepEqual(await response.json(), job, 'consume the raw response before asserting its body deadline is released');
  assert.equal(JSON.parse(calls[0].init.body).actionEvidence, null);
  assert.equal(JSON.parse(calls[0].init.body).comment, null);
  const jobs = JSON.parse(app.stored.get('taxonomy.portfolio.analysisJobs.v2'));
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].job.id, 'job-1');
  assert.equal(app.timers.size, 1, 'one durable job poll; no second admission through the global wrapper');
});

test('the actual Portfolio read ignores a response body from the workspace left behind', async () => {
  const body = deferred(), entered = deferred(), rendered = [];
  const response = Response.json({id: 2, text: 'previous workspace'});
  response.json = () => {entered.resolve(); return body.promise;};
  const app = boot(async () => response);
  app.window.TaxonomyPortfolioApi.getRequirement(1, 2).then(value => rendered.push(value), () => {});
  await entered.promise; app.runtime.workspaceId = 'workspace-b';
  body.resolve({id: 2, text: 'previous workspace'});
  await flush(); await flush();
  assert.deepEqual(rendered, []);
});

test('the actual Import FormData client retains the upload body and fences a late workspace result', async () => {
  const body = deferred(), entered = deferred(), rendered = [], calls = [];
  const response = Response.json({preview: 'old'});
  response.json = () => {entered.resolve(); return body.promise;};
  const app = boot(async (url, init) => {calls.push({url, init}); return response;});
  const upload = new FormData(); upload.set('file', new Blob(['fixture']), 'fixture.xml');
  app.window.ImportApi.preview('archimate', upload).then(value => rendered.push(value), () => {});
  await entered.promise; app.runtime.workspaceId = 'workspace-b';
  body.resolve({preview: 'old'});
  await flush(); await flush();
  assert.equal(calls[0].init.body, upload, 'FormData must not be serialized as JSON');
  assert.equal(new Headers(calls[0].init.headers).has('Content-Type'), false);
  assert.deepEqual(rendered, []);
});

function node() {
  const classes = new Set(['d-none']), handlers = new Map();
  return {innerHTML: '', textContent: '', value: '', disabled: false, style: {}, scrollIntoView() {},
    classList: {add: value => classes.add(value), remove: value => classes.delete(value), contains: value => classes.has(value), toggle: (value, present) => present ? classes.add(value) : classes.delete(value)},
    addEventListener: (name, callback) => handlers.set(name, callback), handlers};
}

test('the actual analysis error handler cannot replace the next context panel with an obsolete failure', async () => {
  const requests = [], nodes = new Map([['gapAnalysisContent', node()], ['gapAnalysisPanel', node()]]);
  const app = boot(() => {const pending = deferred(); requests.push(pending); return pending.promise;}, {nodes});
  app.window._taxonomyCurrentScores = {BP: 80};
  app.load('core/taxonomy-analysis.js');
  app.window.TaxonomyAnalysis.runGapAnalysis();
  app.runtime.workspaceId = 'workspace-b';
  app.window.TaxonomyAnalysis.runGapAnalysis();
  const loading = nodes.get('gapAnalysisContent').innerHTML;
  requests[0].reject(new Error('obsolete analysis failure'));
  await flush(); await flush();
  assert.equal(nodes.get('gapAnalysisContent').innerHTML, loading);
  requests[1].reject(new Error('current analysis failure'));
  await flush(); await flush();
  assert.notEqual(nodes.get('gapAnalysisContent').innerHTML, loading);
});

test('the actual import catch and finally handlers leave a newer upload status and spinner owned by that upload', async () => {
  const requests = [], nodes = new Map();
  for (const id of ['docImportUploadBtn', 'docImportFile', 'docImportTitle', 'docImportType', 'docImportSpinner', 'docImportStatus']) nodes.set(id, node());
  nodes.get('docImportFile').files = [new Blob(['fixture'])];
  const app = boot(() => {const pending = deferred(); requests.push(pending); return pending.promise;}, {nodes});
  app.load('shared/taxonomy-document-import.js');
  const click = nodes.get('docImportUploadBtn').handlers.get('click');
  const first = click();
  app.runtime.workspaceId = 'workspace-b';
  const second = click();
  const loading = nodes.get('docImportStatus').innerHTML;
  requests[0].reject(new Error('obsolete import failure'));
  await first;
  assert.equal(nodes.get('docImportStatus').innerHTML, loading);
  assert.equal(nodes.get('docImportUploadBtn').disabled, true);
  assert.equal(nodes.get('docImportSpinner').classList.contains('d-none'), false);
  requests[1].reject(new Error('current import failure'));
  await second;
  assert.notEqual(nodes.get('docImportStatus').innerHTML, loading);
  assert.equal(nodes.get('docImportUploadBtn').disabled, false);
  assert.equal(nodes.get('docImportSpinner').classList.contains('d-none'), true);
});


const actualClients = {
  Import: app => app.window.ImportApi.uploadDocument(new FormData()),
  Analysis: app => app.window.AnalysisApi.analyzeGap({scores: {}}),
  Export: app => app.window.ExportApi.scoreExport({scores: {}}),
  Copilot: app => app.window.TaxonomyCopilotApi.start(1, 2, {}),
  Workbench: app => app.window.ArchitectureWorkbenchApi.load(1, 'snapshot-1'),
  Templates: app => app.window.TaxonomyDocumentTemplatesApi.list('/api/document-templates')
};
function loadAdditionalClients(app) {
  for (const source of ['api/export-api.js', 'api/copilot-api.js', 'api/architecture-workbench-api.js', 'api/document-templates-api.js', 'shared/architecture-workflow.js']) app.load(source);
}
for (const [name, execute] of Object.entries(actualClients)) {
  for (const status of [401, 403]) test(`${name} actual client shares authentication ${status} without retrying writes`, async () => {
    const calls = [];
    const app = boot(async (url, init) => {calls.push({url, init}); return Response.json({detail: 'denied'}, {status});});
    loadAdditionalClients(app);
    await assert.rejects(execute(app), error => error.status === status && error.requestId === 'feature-request-id');
    assert.equal(calls.length, 1);
    assert.equal(app.events.filter(event => event.type === 'taxonomy-api-auth-failure').length, 1);
  });
  test(`${name} actual client rejects JSON delivered after same-workspace navigation`, async () => {
    const body = deferred(), entered = deferred();
    const response = Response.json({old: true});
    response.json = () => {entered.resolve(); return body.promise;};
    const app = boot(async () => response); loadAdditionalClients(app);
    const request = execute(app);
    const rejected = assert.rejects(request, error => error.code === 'ABORTED');
    await entered.promise;
    app.runtime.analysisGeneration++;
    body.resolve({old: true});
    await rejected;
  });
}
test('the named binary export retains bytes and blocks a download body after navigation', async () => {
  const body = deferred(), entered = deferred();
  const response = new Response('binary', {headers: {'Content-Type': 'application/pdf'}});
  response.blob = () => {entered.resolve(); return body.promise;};
  const calls = [];
  const app = boot(async (url, init) => {calls.push({url, init}); return response;}); loadAdditionalClients(app);
  const result = await app.window.ExportApi.diagramResponse('/api/diagram/pdf', '{"elements":[]}');
  assert.equal(result.headers.get('Content-Type'), 'application/pdf');
  const download = result.blob(); const rejected = assert.rejects(download, error => error.code === 'ABORTED');
  await entered.promise; app.runtime.workspaceId = 'workspace-b'; body.resolve(new Blob(['bytes'])); await rejected;
  assert.match(calls[0].url, /\/taxonomy\/api\/diagram\/current\/pdf/);
  assert.equal(calls[0].init.body, '{"elements":[]}');
});
test('named durable job reads retain their original workspace pin after tab switching', async () => {
  const calls = [];
  const app = boot(async (url, init) => {calls.push({url, init}); return Response.json({id: 'job-1'});});
  app.runtime.workspaceId = 'workspace-b';
  await app.window.TaxonomyPortfolioApi.getAnalysisJob(1, 'job-1', {contextSensitive: false, pinnedWorkspaceId: 'workspace-a'});
  assert.equal(new Headers(calls[0].init.headers).get('X-Taxonomy-Workspace-Id'), 'workspace-a');
  assert.equal(new URL(calls[0].url, app.window.location.href).searchParams.get('workspaceId'), 'workspace-a');
});

for (const beforeCanonical of [false, true]) test(`real admission factories preserve one request and one job poll when canonical loads ${beforeCanonical ? 'after' : 'before'} legacy adapters`, async () => {
  const calls = [];
  const app = boot(async (url, init) => {
    calls.push({url, init});
    if (String(url).includes('/analysis-jobs') || String(url).includes('/analyses')) return Response.json(
      {id: 'job-order', projectId: 1, workspaceId: 'workspace-a', status: 'PENDING', items: []},
      {status: 202, headers: {Location: '/taxonomy/api/projects/1/analysis-jobs/job-order'}});
    return Response.json({totalGaps: 0});
  }, {analysis: true, portfolio: true, beforeCanonical});
  await app.window.AnalysisApi.analyzeGap({scores: {}});
  assert.equal(calls.length, 1);
  assert.equal(app.events.filter(event => event.type === 'taxonomy:analysis-running-changed').length, 2);
  await app.window.TaxonomyPortfolioApi.analyzeRequirementJob(1, 2, {});
  assert.equal(calls.length, 2);
  assert.equal(JSON.parse(app.stored.get('taxonomy.portfolio.analysisJobs.v2')).length, 1);
  assert.equal(app.timers.size, 1);
});
for (const workspace of ['retained-workspace', '']) test(`ordinary named Portfolio and Copilot calls retain workflow source ${workspace || 'central'} over the active tab`, async () => {
  const calls = [];
  const app = boot(async (url, init) => {calls.push({url, init}); return Response.json({id: 1});});
  app.window.location.search = '?repositoryId=repository-source&branch=source%2Freview&workspaceId=' + workspace;
  app.load('shared/architecture-workflow.js'); app.load('api/copilot-api.js');
  await app.window.TaxonomyPortfolioApi.getProject(1);
  await app.window.TaxonomyCopilotApi.latest(1, 2);
  for (const call of calls) {
    const target = new URL(call.url, app.window.location.href);
    assert.equal(target.searchParams.get('repositoryId'), 'repository-source');
    assert.equal(target.searchParams.get('branch'), 'source/review');
    assert.equal(target.searchParams.get('workspaceId'), workspace);
    assert.equal(new Headers(call.init.headers).get('X-Taxonomy-Workspace-Id'), workspace);
  }
});
test('named durable job pin outranks a different live workflow source', async () => {
  let call;
  const app = boot(async (url, init) => {call = {url, init}; return Response.json({id: 'job'});});
  app.window.location.search = '?workspaceId=live-source'; app.load('shared/architecture-workflow.js');
  await app.window.TaxonomyPortfolioApi.getAnalysisJob(1, 'job', {contextSensitive: false, pinnedWorkspaceId: 'retained-source'});
  assert.equal(new URL(call.url, app.window.location.href).searchParams.get('workspaceId'), 'retained-source');
  assert.equal(new Headers(call.init.headers).get('X-Taxonomy-Workspace-Id'), 'retained-source');
});
test('analysis UI retains the scores-required path without starting transport', () => {
  const nodes = new Map([['gapAnalysisContent', node()]]);
  const app = boot(() => {throw new Error('No request expected');}, {nodes}); app.load('core/taxonomy-analysis.js');
  app.window.TaxonomyAnalysis.runGapAnalysis();
  assert.match(nodes.get('gapAnalysisContent').innerHTML, /analyze.scores.required/);
});

test('actual Portfolio success, error and finally handlers retain the newer project ownership', async () => {
  const requests = [], nodes = new Map();
  for (const id of ['portfolioBusy', 'portfolioInfo', 'portfolioError', 'portfolioStatus', 'portfolioAlert']) nodes.set(id, node());
  const app = boot(() => {const pending = deferred(); requests.push(pending); return pending.promise;}, {nodes});
  const source = sources.get('portfolio/taxonomy-portfolio.js');
  vm.runInContext(source.replace(/\}\)\(\);\s*$/, 'window.featureFixture = {state, onRequirementAction};})();'), app.context);
  const {state, onRequirementAction} = app.window.featureFixture;
  const target = {closest: selector => selector === '.requirement-confirm' ? {dataset: {requirementId: '2'}} : null};
  state.selectedProjectId = 1; const first = onRequirementAction({target});
  state.selectedProjectId = 2; const second = onRequirementAction({target});
  requests[0].resolve(Response.json({id: 2})); await flush(); await flush();
  assert.equal(requests.length, 2, 'obsolete success cannot refresh the newly selected project');
  assert.equal(nodes.get('portfolioInfo').textContent, '');
  assert.equal(nodes.get('portfolioBusy').classList.contains('d-none'), false);
  await first;
  requests[1].reject(new Error('current project error')); await second;
  assert.match(nodes.get('portfolioError').textContent, /Network request failed/);
  assert.equal(nodes.get('portfolioBusy').classList.contains('d-none'), true);
});
test('actual analysis success from an earlier same-context operation cannot replace the newer loading panel', async () => {
  const requests = [], nodes = new Map([['gapAnalysisContent', node()], ['gapAnalysisPanel', node()]]);
  const app = boot(() => {const pending = deferred(); requests.push(pending); return pending.promise;}, {nodes});
  app.window._taxonomyCurrentScores = {BP: 80}; app.load('core/taxonomy-analysis.js');
  app.window.TaxonomyAnalysis.runGapAnalysis(); app.window.TaxonomyAnalysis.runGapAnalysis();
  const loading = nodes.get('gapAnalysisContent').innerHTML;
  requests[0].resolve(Response.json({totalGaps: 0, totalAnchors: 1, gaps: []})); await flush(); await flush();
  assert.equal(nodes.get('gapAnalysisContent').innerHTML, loading);
  requests[1].reject(new Error('current failure')); await flush(); await flush();
  assert.notEqual(nodes.get('gapAnalysisContent').innerHTML, loading);
});
test('actual import success from an earlier upload cannot release the newer same-context upload controls', async () => {
  const requests = [], nodes = new Map();
  for (const id of ['docImportUploadBtn', 'docImportFile', 'docImportTitle', 'docImportType', 'docImportSpinner', 'docImportStatus']) nodes.set(id, node());
  nodes.get('docImportFile').files = [new Blob(['fixture'])];
  const app = boot(() => {const pending = deferred(); requests.push(pending); return pending.promise;}, {nodes});
  app.load('shared/taxonomy-document-import.js'); const click = nodes.get('docImportUploadBtn').handlers.get('click');
  const first = click(), second = click(), loading = nodes.get('docImportStatus').innerHTML;
  requests[0].resolve(Response.json({totalCandidates: 1, totalPages: 1})); await first;
  assert.equal(nodes.get('docImportStatus').innerHTML, loading);
  assert.equal(nodes.get('docImportUploadBtn').disabled, true);
  assert.equal(nodes.get('docImportSpinner').classList.contains('d-none'), false);
  requests[1].reject(new Error('current upload failed')); await second;
  assert.equal(nodes.get('docImportUploadBtn').disabled, false);
});

for (const beforeCanonical of [false, true]) test(`workflow source and initial status factory keeps one request when canonical loads ${beforeCanonical ? 'after' : 'before'} it`, async () => {
  const calls = [];
  const app = boot(async (url, init) => {calls.push({url, init}); return Response.json({id: 1});}, {
    beforeCanonical, workflow: true, search: '?workspaceId=retained-page&repositoryId=repo-page&branch=retained-branch'});
  app.runtime.workspaceId = null;
  await app.window.TaxonomyPortfolioApi.getProject(1);
  await app.window.TaxonomyApiClient.getJson('/api/context/current', {workspaceScoped: false, contextSensitive: false});
  assert.equal(calls.length, 2);
  const source = new URL(calls[0].url, app.window.location.href);
  assert.equal(source.searchParams.get('repositoryId'), 'repo-page');
  assert.equal(source.searchParams.get('branch'), 'retained-branch');
  assert.equal(new Headers(calls[0].init.headers).get('X-Taxonomy-Workspace-Id'), 'retained-page');
  assert.equal(new Headers(calls[1].init.headers).get('X-Taxonomy-Workspace-Id'), 'retained-page');
});

test('actual scoring leaf catch cannot release a newer request button or show its obsolete error', async () => {
  const calls = [], statuses = [];
  const app = boot(() => {const pending = deferred(); calls.push(pending); return pending.promise;});
  app.window.TaxonomyState = {storedBusinessText: 'fixture', currentScores: {BP: 80}};
  app.window.TaxonomyBrowse = {showStatus: (...args) => statuses.push(args)};
  app.load('core/taxonomy-scoring.js');
  const button = node(); button.textContent = 'Explain';
  app.window.TaxonomyScoring.requestLeafJustification('BP', button);
  app.runtime.workspaceId = 'workspace-b';
  app.window.TaxonomyScoring.requestLeafJustification('BP', button);
  calls[0].reject(new Error('obsolete leaf error')); await flush(); await flush();
  assert.equal(button.disabled, true);
  assert.equal(button.textContent, 'scoring.generating');
  assert.equal(statuses.length, 0);
  calls[1].reject(new Error('current leaf error')); await flush(); await flush();
  assert.equal(button.disabled, false);
  assert.equal(statuses.length, 1);
});

test('actual hypothesis acceptance retains a conflict as already existing and never paints accepted success', async () => {
  const calls = [], statuses = [], row = node(), actions = {innerHTML: ''};
  row.querySelector = () => actions;
  const app = boot(async (url, init) => {calls.push({url, init}); return Response.json({detail: 'already exists'}, {status: 409});}, {nodes: new Map([['suggested-row-0', row]])});
  app.window.TaxonomyState = {}; app.window.TaxonomyBrowse = {showStatus: (...args) => statuses.push(args)};
  app.window._currentProvisionalRelations = [{sourceCode: 'BP', targetCode: 'CP', relationType: 'SUPPORTS'}];
  app.load('api/proposals-api.js'); app.load('core/taxonomy-scoring.js');
  app.window._acceptHypothesis(0); await flush(); await flush();
  assert.equal(calls.length, 1);
  assert.match(actions.innerHTML, /already.exists/);
  assert.equal(row.classList.contains('table-success'), false);
  assert.deepEqual(statuses.map(value => value[0]), ['info']);
});

for (const status of [200, 202, 412]) test(`actual legacy hypothesis acceptance handles review HTTP ${status} truthfully without retry`, async () => {
  const calls = [], statuses = [], row = node(), actions = {innerHTML: '', textContent: ''}; row.querySelector = () => actions;
  const app = boot(async (url, init) => {
    calls.push({url, init});
    return String(url).includes('/from-hypothesis') ? Response.json({id: 'proposal-1'}, {status: 201})
      : Response.json({publication: status === 202 ? 'PENDING' : 'COMPLETE'}, {status});
  }, {nodes: new Map([['suggested-row-0', row]])});
  app.window.TaxonomyState = {}; app.window.TaxonomyBrowse = {showStatus: (...args) => statuses.push(args)};
  app.window._currentProvisionalRelations = [{sourceCode: 'BP', targetCode: 'CP', relationType: 'SUPPORTS'}];
  app.load('api/proposals-api.js'); app.load('core/taxonomy-scoring.js');
  await app.window._acceptHypothesis(0);
  assert.equal(calls.length, 2);
  assert.equal(row.classList.contains('table-success'), status === 200);
  assert.deepEqual(statuses.map(value => value[0]), [status === 200 ? 'success' : status === 202 ? 'warning' : 'danger']);
  if (status === 202) assert.match(actions.textContent, /requires recovery/);
});

test('actual mutable Copilot reads preserve no-store and JSON response negotiation', async () => {
  const calls = [];
  const app = boot(async (url, init) => {calls.push({url, init}); return Response.json({status:'RUNNING'});});
  app.load('api/copilot-api.js');
  await app.window.TaxonomyCopilotApi.latest(1, 2);
  await app.window.TaxonomyCopilotApi.get(1, 'operation-1');
  assert.equal(calls.length, 2);
  for (const call of calls) {
    assert.equal(call.init.cache, 'no-store');
    assert.equal(new Headers(call.init.headers).get('Accept'), 'application/json');
    assert.equal(new Headers(call.init.headers).get('X-Request-ID'), 'feature-request-id');
  }
});
