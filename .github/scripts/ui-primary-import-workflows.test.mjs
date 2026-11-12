import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test, {afterEach} from 'node:test';
import vm from 'node:vm';
import canonicalFixture from './canonical-api-fixture.cjs';

const source = await readFile(new URL('./ui-primary-import-workflows.mjs', import.meta.url), 'utf8');
afterEach(() => canonicalFixture.dispose());

function matches(pattern, url) {
  if (typeof pattern === 'function') return pattern(new URL(url));
  if (pattern instanceof RegExp) return pattern.test(url);
  const escaped = pattern.split('*').map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*');
  return new RegExp(`^${escaped}$`).test(url);
}

async function run(workspaceId, basePath) {
  const routes = [], registered = [], requests = [], backend = [], passed = [];
  const requestWaiters = [], responseWaiters = [];
  let documentFile, frameworkFile, documentResult;
  const fetch = async (input, init) => {
    const url = new URL(String(input), 'https://taxonomy.example');
    const request = {method: () => init.method, url: () => url.href};
    for (const waiter of requestWaiters.splice(0)) {
      assert.ok(waiter.predicate(request), 'Actual document request must satisfy the workflow observer');
      waiter.resolve(request);
    }
    const route = [...routes].reverse().find(candidate => matches(candidate.pattern, url.href));
    let result;
    if (route) {
      if (--route.remaining === 0) routes.splice(routes.indexOf(route), 1);
      await route.handler({fulfill: async value => {result = value;}});
    } else {
      backend.push(url.href);
      // The real server correctly rejects these intentionally synthetic PDF bytes.
      result = {status: 422, contentType: 'application/json', body: JSON.stringify({
        error: 'DOCUMENT_PARSE_FAILED', message: 'The document could not be parsed as PDF or DOCX'
      })};
    }
    requests.push({url, init, result, file: await init.body.get('file').text()});
    const response = {
      request: () => request, url: () => url.href, status: () => result.status,
      headers: () => ({'content-type': result.contentType}),
      text: async () => result.body, json: async () => JSON.parse(result.body), finished: async () => {}
    };
    for (const waiter of responseWaiters.splice(0)) {
      assert.ok(waiter.predicate(response));
      waiter.resolve(response);
    }
    return new Response(result.body, {status: result.status, headers: {'Content-Type': result.contentType}});
  };
  const context = {
    window: {fetch, __TaxonomyAnalysisSessionContext: {runtime: {workspaceId}},
      location: {href: `https://taxonomy.example${basePath}/`, origin: 'https://taxonomy.example'},
      TaxonomyI18n: {resolveUrl: path => basePath + path}},
    document: {}, URL, Buffer, console,
    navigateToPage: async () => {}, navigateArchitectureSubtab: async () => {}
  };
  canonicalFixture.install(context, ['api/import-api.js']);
  vm.runInContext(source.replace(/^import .*;\n/m, '').replace(/^export /mg, ''), context);
  const form = file => {const data = new FormData(); data.append('file', new Blob([file.buffer], {type: file.mimeType}), file.name); return data;};
  const page = {
    route: async (pattern, handler, options = {}) => {
      registered.push(pattern);
      routes.push({pattern, handler, remaining: options.times || Infinity});
    },
    waitForRequest: predicate => new Promise(resolve => requestWaiters.push({predicate, resolve})),
    waitForResponse: predicate => new Promise(resolve => responseWaiters.push({predicate, resolve})),
    waitForFunction: async callback => ({jsonValue: async () => ({success: documentResult?.totalCandidates === 2})}),
    locator(selector) {
      return {
        waitFor: async () => {}, getAttribute: async () => 'open',
        textContent: async () => String(documentResult.totalCandidates),
        selectOption: async () => {},
        setInputFiles: async file => {if (selector === '#docImportFile') documentFile = file; else frameworkFile = file;},
        click: async () => {
          if (selector === '#docImportUploadBtn') {
            try {documentResult = await context.window.ImportApi.uploadDocument(form(documentFile));}
            catch (error) {
              assert.equal(error.status, 422);
              documentResult = {error: error.message, code: error.responseBody.error};
            }
          } else if (selector === '#importPreviewBtn') {
            const preview = await context.window.ImportApi.preview('apqc', form(frameworkFile));
            assert.equal(preview.elementsMapped, 1);
          } else if (selector === '#importExecuteBtn') {
            await assert.rejects(context.window.ImportApi.execute('apqc', 'draft/import', form(frameworkFile)),
              error => error.responseBody.error === 'INVALID_IMPORT' && error.status === 422);
          }
        }
      };
    }
  };
  const evidence = {
    assert, passed: label => passed.push(label), axeState: async () => {}, saveState: async () => {},
    waitForText: async (selector, predicate) => {
      assert.equal(selector, '#docImportStatus');
      assert.ok(predicate(documentResult.error), 'Resource limit feedback must remain the deliberately intercepted outcome');
      assert.equal(documentResult.code, 'DOCUMENT_RESOURCE_LIMIT');
    }
  };
  await vm.runInContext('runImportWorkflows', context)({page, evidence});
  return {routes, registered, requests, backend, passed};
}

for (const [workspaceId, basePath] of [['architect-workspace', ''], ['admin-workspace', '/taxonomy']]) {
  test(`actual primary import workflow intercepts canonical uploads and framework requests in ${workspaceId} at ${basePath || '/'}`, async () => {
    const result = await run(workspaceId, basePath);
    assert.deepEqual(result.backend, [], 'Synthetic document bytes must never reach the real parser');
    assert.deepEqual(result.requests.map(request => request.result.status), [200, 422, 200, 422]);
    assert.deepEqual(result.requests.map(request => request.file), ['mock-pdf', 'oversized',
      'PCF ID,Name,Level,Description\n1.0,Strategy,1,QA', 'PCF ID,Name,Level,Description\n1.0,Strategy,1,QA']);
    for (const request of result.requests) {
      assert.equal(request.init.method, 'POST');
      assert.ok(request.url.pathname.startsWith(basePath + '/api/'));
      assert.equal(request.url.searchParams.get('workspaceId'), workspaceId);
      assert.equal(new Headers(request.init.headers).get('X-Taxonomy-Workspace-Id'), workspaceId);
    }
    assert.equal(result.requests[3].url.searchParams.get('branch'), 'draft/import');
    const [upload, resourceError, preview, execute] = result.registered;
    for (const matcher of [upload, resourceError]) {
      assert.equal(matches(matcher, 'https://taxonomy.example/api/documents/upload-extra?workspaceId=other'), false);
      assert.equal(matches(matcher, 'https://taxonomy.example/api/documents/upload/42'), false);
    }
    assert.equal(matches(preview, 'https://taxonomy.example/api/import/preview/other?workspaceId=other'), false);
    assert.equal(matches(execute, 'https://taxonomy.example/api/import/apqc?workspaceId=other'), false,
      'The branch-bearing framework command remains distinct from an unselected command');
    assert.equal(matches(execute, 'https://taxonomy.example/api/import/apqc?workspaceId=other&branch=draft%2Fother'), true,
      'Workspace routing must not depend on query parameter order');
    assert.deepEqual(result.passed, ['document upload and candidate review', 'document upload resource error',
      'framework import preview', 'framework import error feedback']);
    assert.equal(result.routes.length, 0, 'All four one-shot interception outcomes must be exercised');
  });
}

test('retained document analysis observes its native result without uploading twice', async () => {
  const id = '12345678-1234-1234-1234-123456789abc';
  const commit = 'f'.repeat(40);
  const calls = [];
  const native = {fileName: 'notice.pdf', matches: [{nodeCode: 'CP'}]};
  const responses = [{state: 'RUNNING', operationId: id, sourceCommit:commit, sequence:0}, {state: 'RUNNING', operationId: id, sourceCommit:commit, sequence:0}, native];
  const window = {location:{href:'https://taxonomy.example/',origin:'https://taxonomy.example'},
    __TaxonomyAnalysisSessionContext:{runtime:{workspaceId:'original'}},
    fetch: async (path, init) => {
      calls.push([init.method || 'GET', new URL(path,'https://taxonomy.example').pathname]);
      return new Response(JSON.stringify(responses.shift()),{status:responses.length ? 202 : 200,headers:{'Content-Type':'application/json','X-Analysis-Operation-Id':id,'X-Analysis-Source-Commit':commit,'Retry-After':'1'}});
    }};
  canonicalFixture.install({window,document:{},URL,console,setTimeout:(run,ms)=>setTimeout(run,ms===1000?1:ms)},['api/analysis-adapter-api.js','api/import-api.js']);
  let cleared = false;
  const result = await window.ImportApi.mapRegulation(new FormData(),() => true, () => () => { cleared = true; });
  assert.equal(JSON.stringify(result), JSON.stringify(native));
  assert.deepEqual(calls,[['POST','/api/documents/map-regulation'],['GET','/api/analysis-adapters/'+id],['GET','/api/analysis-adapters/'+id]]);
  assert.equal(cleared,true);
});

test('superseded document analysis never polls or cancels in the replacement workspace', async () => {
  const id = '12345678-1234-1234-1234-123456789abc';
  const commit = 'f'.repeat(40), runtime = {workspaceId:'original'};
  let cancellation, submitted = 0, cleaned = false;
  const window = {location:{href:'https://taxonomy.example/',origin:'https://taxonomy.example'},
    __TaxonomyAnalysisSessionContext:{runtime},
    fetch: async () => { submitted++; return new Response(JSON.stringify({state:'RUNNING',operationId:id,sourceCommit:commit,sequence:0}),
      {status:202,headers:{'Content-Type':'application/json','X-Analysis-Operation-Id':id,'X-Analysis-Source-Commit':commit,'Retry-After':'1'}}); }};
  canonicalFixture.install({window,document:{},URL,console,setTimeout:(run,ms)=>ms===1000
    ? setTimeout(() => { runtime.workspaceId='replacement'; run(); },1) : setTimeout(run,ms)},['api/analysis-adapter-api.js','api/import-api.js']);
  await assert.rejects(window.ImportApi.extractDocumentWithAi(new FormData(),undefined, cancel => {
    cancellation=cancel; return () => { cleaned=true; };
  }), error => error.code==='ABORTED');
  await assert.rejects(Promise.resolve().then(cancellation), error => error.code==='ABORTED');
  assert.equal(submitted,1); assert.equal(cleaned,true);
});
