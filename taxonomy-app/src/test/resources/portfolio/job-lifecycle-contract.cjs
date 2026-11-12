'use strict';

// JUnit owns each case and passes the packaged production modules to this VM driver.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {readFileSync} = require('node:fs');

const [caseName, apiPath, sourcePath, discoveryPath, canonicalPath] = process.argv.slice(2);
const api = readFileSync(apiPath, 'utf8');
const source = readFileSync(sourcePath, 'utf8');
const canonical = readFileSync(canonicalPath, 'utf8');
const deadlineScopes = [];
const jobUrl='https://example.test/taxonomy/api/projects/1/analysis-jobs/retained-job';
const storageKey='taxonomy.portfolio.analysisJobs.v2';

function fixture(fetcher=async()=>({ok:true,json:async()=>({projectId:1,workspaceId:null,id:'retained-job',status:'RUNNING'})}), discover=false, history=[]) {
    const timers=new Map(),deadlines=new Map(),allTimers=new Map(),events=new Map(),storage=new Map(),requests=[];
    if(history.length)storage.set(storageKey,JSON.stringify(history));
    deadlineScopes.push({timers,deadlines,allTimers});
    let nextTimer=0;
    function schedule(callback,delay,kind) {
        const id=++nextTimer,entry={callback,delay,kind};allTimers.set(id,entry);
        (kind==='transport-deadline'?deadlines:timers).set(id,entry);return id;
    }
    function clearTimer(id) {timers.delete(id);deadlines.delete(id);allTimers.delete(id);}
    // HTTP doubles describe only domain payloads; complete their browser Response metadata.
    async function responseFor(url,options) {
        const response=await fetcher(url,options);
        response.status ??= response.ok===false?500:200;
        response.headers ||= new Headers({'Content-Type':'application/json'});
        response.json ||= async()=>({});
        response.text ||= async()=>JSON.stringify(await response.json());
        response.clone ||= ()=>response;
        return response;
    }
    const listen=(name,handler)=>{if(!events.has(name))events.set(name,[]);events.get(name).push(handler);};
    const document={addEventListener:listen,getElementById:()=>null,querySelector:()=>null,querySelectorAll:()=>[],
        createElement:()=>({setAttribute(){}}),head:{appendChild(){}},
        dispatchEvent(event){for(const handler of events.get(event.type)||[])handler(event);}};
    const location=new URL('https://example.test/taxonomy/projects?workspaceId=working-copy&repositoryId=architecture&branch=draft');
    const window={location,
        fetch:async(url,options)=>{requests.push({url,options});return responseFor(url,options);},
        localStorage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
        setTimeout:(callback,delay)=>schedule(callback,delay,'lifecycle'),
        clearTimeout:clearTimer,
        addEventListener:listen,
        TaxonomyI18n:{getBasePath:()=>'/taxonomy',resolveUrl:path=>path.startsWith('/')?'/taxonomy'+path:path}
    };
    const context=vm.createContext({window,document,URL,URLSearchParams,Request,Response,Headers,AbortController,DOMException,FormData,
        CustomEvent:class{constructor(type,options){this.type=type;this.detail=options?.detail;}},
        crypto:require('node:crypto').webcrypto,
        setTimeout:(callback,delay)=>schedule(callback,delay,'transport-deadline'),clearTimeout:clearTimer,
        fetch:(...args)=>window.fetch(...args),queueMicrotask,Date,JSON,Map,Set});
    vm.runInContext(canonical,context);vm.runInContext(api,context);vm.runInContext(source,context);
    if(discover)vm.runInContext(readFileSync(discoveryPath,'utf8'),context);
    const register=()=>window.taxonomyPortfolioRegisterJob('/api/projects/1/analysis-jobs/retained-job',{projectId:1,workspaceId:null,id:'retained-job',status:'RUNNING'});
    return {register,requests,timers,storage,window,
        event(name,value={}) {for(const handler of events.get(name)||[])handler(value);},
        async eventWithMicrotaskCheckpoints(name,value={},afterListener=()=>{}) {
            for(const handler of events.get(name)||[]) {
                handler(value);
                // Native callbacks can drain promises before the next listener;
                // the synchronous event helper deliberately keeps its existing contract.
                await new Promise(resolve=>setImmediate(resolve));
                afterListener();
            }
        },
        selecting(projectId) {storage.set('taxonomy.portfolio.projectId',String(projectId));this.event('taxonomy:portfolio-project-selecting',{detail:{projectId}});},
        select(projectId) {storage.set('taxonomy.portfolio.projectId',String(projectId));this.event('taxonomy:portfolio-project-selected',{detail:{projectId}});},
        runNext() {const entry=timers.entries().next().value;if(!entry)return Promise.resolve();clearTimer(entry[0]);return entry[1].callback();},
        saved() {return JSON.parse(storage.get(storageKey)||'[]');}
    };
}

function invalidJobUrls() {
    return [
        'https://other.example.test/taxonomy/api/projects/1/analysis-jobs/retained-job',
        'https://example.test/taxonomy-other/api/projects/1/analysis-jobs/retained-job',
        jobUrl+'?unexpected=1',
        jobUrl+'#fragment'
    ];
}

function assertValidatedJobCompleted(f) {
    assert.equal(f.requests.length,1,'Only the validated job resource may be polled');
    const request=f.requests[0];
    assert.equal(new URL(request.url,f.window.location.href).href,jobUrl+'?workspaceId=');
    assert.equal(request.options.method,'GET');
    assert.equal(request.options.credentials,'same-origin');
    assert.equal(new Headers(request.options.headers).get('X-Taxonomy-Workspace-Id'),'');
    const saved=f.saved();
    assert.equal(saved.length,1,'Invalid locations must not survive persisted job history');
    assert.equal(saved[0].url,jobUrl);
    assert.equal(saved[0].projectId,1);
    assert.equal(saved[0].job.status,'SUCCESS');
    assert.equal(saved[0].lastPollError,undefined);
    assert.equal(f.timers.size,0,'A completed job must not schedule another poll');
}

async function registeredJobUsesValidatedProjectIdentity() {
    const job={id:'retained-job',status:'RUNNING',items:[]};
    const f=fixture(async()=>({ok:true,json:async()=>({...job,status:'SUCCESS'})}));
    for(const url of invalidJobUrls()) {
        assert.equal(f.window.taxonomyPortfolioRegisterJob(url,job),false);
    }
    assert.equal(f.window.taxonomyPortfolioRegisterJob(jobUrl,{...job,projectId:2}),false);
    assert.equal(f.window.taxonomyPortfolioRegisterJob(jobUrl,{...job,id:'different-job'}),false);
    assert.equal(f.timers.size,0);
    assert.deepEqual(f.saved(),[]);
    assert.equal(f.window.taxonomyPortfolioRegisterJob('/api/projects/1/analysis-jobs/retained-job',job),true);
    assert.equal(f.saved()[0].projectId,1,'Registration recovers the project from its validated URL');
    assert.equal(f.saved()[0].job.projectId,undefined);
    await f.runNext();
    assertValidatedJobCompleted(f);
}

async function restoredJobUsesValidatedProjectIdentity() {
    const job={id:'retained-job',status:'RUNNING',items:[]};
    const history=[{url:jobUrl,projectId:null,job},...invalidJobUrls().map(url=>({url,job}))];
    const f=fixture(async()=>({ok:true,json:async()=>({...job,status:'SUCCESS'})}),false,history);
    // Loading the real adapter restores history; its page lifecycle hook resumes polling.
    f.event('pageshow',{persisted:true});
    assert.equal(f.timers.size,1,'Only the validated restored job may schedule a poll');
    await f.runNext();
    assertValidatedJobCompleted(f);
}

async function departureCancelsScheduledPoll() {
    const f=fixture();assert.equal(f.register(),true);assert.equal(f.timers.size,1);
    f.event('pagehide',{persisted:false});
    await f.runNext();
    assert.equal(f.requests.length,0,'The departing document must not begin another background job request');
    assert.equal(f.timers.size,0);
    assert.equal(f.saved()[0].job.id,'retained-job');
}

async function departureAbortsInFlightPoll() {
    let finish;
    const f=fixture((_url,options)=>new Promise((resolve,reject)=>{
        finish=()=>resolve({ok:true,json:async()=>({projectId:1,workspaceId:null,id:'retained-job',status:'RUNNING'})});
        options.signal?.addEventListener('abort',()=>reject(new DOMException('Page left','AbortError')),{once:true});
    }));
    f.register();const poll=f.runNext();
    try {
        const requested = new URL(f.requests[0].url,f.window.location.href);
        assert.equal(requested.origin+requested.pathname,jobUrl);
        assert.equal(requested.search,'?workspaceId=','Retained central job must not inherit the selected working copy');
        assert.equal(new Headers(f.requests[0].options.headers).get('X-Taxonomy-Workspace-Id'),'');
        f.event('pagehide',{persisted:false});
        assert.equal(f.requests[0].options.signal?.aborted,true,'Navigation must abort the actual job transport');
    } finally {finish();await poll;}
    assert.equal(f.timers.size,0);assert.equal(f.saved()[0].lastPollError,undefined);
    assert.equal(f.saved()[0].job.status,'RUNNING');
}

async function departureCheckpointsDoNotPublishCancellation() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}));
    assert.equal(f.register(),true);
    const poll=f.runNext();
    const scheduledAtCheckpoints=[];
    try {
        await new Promise(resolve=>setImmediate(resolve));
        assert.equal(typeof finishBody,'function','The actual canonical request must be consuming its held JSON body');
        assert.equal(f.requests.length,1);
        assert.equal(f.requests[0].options.signal.aborted,false);
        assert.equal(f.saved()[0].lastPollError,undefined,'The admitted read starts without an earlier polling error');

        await f.eventWithMicrotaskCheckpoints('pagehide',{persisted:false},()=>{
            scheduledAtCheckpoints.push(f.timers.size);
        });
        await poll;

        assert.equal(f.requests[0].options.signal.aborted,true,'Departure aborts the real transport while its body is pending');
        assert.equal(f.saved()[0].job.id,'retained-job');
        assert.equal(f.saved()[0].job.status,'RUNNING');
        assert.equal(f.saved()[0].lastPollError,undefined,'A canonical departure cancellation must not become a persisted polling error');
        assert.equal(f.requests.length,1,'Cancellation must not begin another request');
        assert.equal(f.timers.size,0);
        assert.ok(scheduledAtCheckpoints.every(count=>count===0),
            'Canonical cancellation must not schedule another poll before the portfolio departure listener runs');
    } finally {
        f.event('pagehide',{persisted:false});
        finishBody?.({projectId:1,workspaceId:null,id:'retained-job',status:'SUCCESS'});
        await poll;
    }
}

async function departedResponseCannotRewriteHistory() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}));
    f.register();const poll=f.runNext();
    await new Promise(resolve=>setImmediate(resolve));
    f.event('pagehide',{persisted:true});
    finishBody({projectId:1,workspaceId:null,id:'retained-job',status:'SUCCESS'});await poll;
    assert.equal(f.saved()[0].job.status,'RUNNING','A response belonging to the previous document cannot publish completion');
    assert.equal(f.timers.size,0);
}

async function backForwardCacheResumesOnce() {
    const f=fixture();f.register();f.event('pagehide',{persisted:true});
    assert.equal(f.timers.size,0);
    f.event('pageshow',{persisted:true});f.event('pageshow',{persisted:true});
    assert.equal(f.timers.size,1);
    await f.runNext();
    assert.equal(f.requests.length,1);assert.equal(f.timers.size,1);
    assert.equal(f.saved()[0].job.id,'retained-job');
}

async function activeFailureRetainsBoundedRetry() {
    const f=fixture(async()=>({ok:false,status:503}));f.register();await f.runNext();
    assert.equal(f.saved()[0].lastPollError,'HTTP 503');
    assert.equal(f.timers.size,1);assert.equal([...f.timers.values()][0].delay,4500);
}

async function departureCancelsScheduledDiscovery() {
    const f=fixture(undefined,true);f.select(1);assert.equal(f.timers.size,3);
    f.event('pagehide',{persisted:false});
    f.event('taxonomy:portfolio-project-selected',{detail:{projectId:1}});
    f.event('click',{target:{closest:selector=>selector==='#analyzeAllBtn, .requirement-analyze'}});
    await f.runNext();
    assert.equal(f.requests.length,0,'The departing document must not begin job discovery');
    assert.equal(f.timers.size,0);
}

async function departureAbortsInFlightDiscovery() {
    let finish;
    const f=fixture((_url,options)=>new Promise((resolve,reject)=>{
        finish=()=>resolve({ok:true,json:async()=>[{projectId:1,workspaceId:null,id:'departed-job',status:'SUCCESS'}]});
        options.signal?.addEventListener('abort',()=>reject(new DOMException('Page left','AbortError')),{once:true});
    }),true);
    f.select(1);const discovery=f.runNext();
    try {
        assert.equal(f.requests[0].url,'/taxonomy/api/projects/1/analysis-jobs');
        f.event('pagehide',{persisted:false});
        assert.equal(f.requests[0].options.signal?.aborted,true,'Navigation must abort the actual discovery transport');
    } finally {finish();await discovery;}
    assert.equal(f.timers.size,0);assert.deepEqual(f.saved(),[]);
}

async function departedDiscoveryResponseCannotRegisterJobs() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}),true);
    f.select(1);const discovery=f.runNext();
    await new Promise(resolve=>setImmediate(resolve));
    f.event('pagehide',{persisted:true});
    finishBody([{projectId:1,workspaceId:null,id:'departed-job',status:'SUCCESS'}]);await discovery;
    assert.deepEqual(f.saved(),[],'A response from the departed document cannot publish jobs');
    assert.equal(f.timers.size,0);
}

async function changedSelectionCannotRegisterOldDiscovery() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}),true);
    f.select(1);const discovery=f.runNext();
    await new Promise(resolve=>setImmediate(resolve));
    // Selection storage changes before the new project's successful-selection event.
    f.storage.set('taxonomy.portfolio.projectId','2');
    finishBody([{projectId:1,workspaceId:null,id:'old-project-job',status:'SUCCESS'}]);await discovery;
    assert.deepEqual(f.saved(),[],'Only the currently selected project may register discovered jobs');
}

async function replacedDiscoveryAbortsAndRejectsOldResponse() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}),true);
    f.select(1);const discovery=f.runNext();
    await new Promise(resolve=>setImmediate(resolve));
    f.select(2);f.select(1);
    try {
        assert.equal(f.requests[0].options.signal?.aborted,true,'A new discovery schedule must cancel the superseded request');
        assert.equal(f.timers.size,3,'Repeated selections retain one bounded discovery schedule');
    } finally {finishBody([{projectId:1,workspaceId:null,id:'superseded-job',status:'SUCCESS'}]);await discovery;}
    assert.deepEqual(f.saved(),[],'Returning to the same project cannot revive its superseded response');
}

async function selectionStartCancelsDiscoveryBeforeFailedRefresh() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}),true);
    f.select(1);const discovery=f.runNext();
    await new Promise(resolve=>setImmediate(resolve));
    f.selecting(2);
    try {
        assert.equal(f.requests[0].options.signal?.aborted,true,'Selection must cancel discovery before its portfolio refresh completes');
        assert.equal(f.timers.size,0,'A failed selection refresh leaves discovery stopped until a successful selection');
    } finally {finishBody([{projectId:1,workspaceId:null,id:'old-project-job',status:'SUCCESS'}]);await discovery;}
    assert.deepEqual(f.saved(),[]);
}

async function pendingSelectionsCannotReviveOldDiscovery() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}),true);
    f.select(1);const discovery=f.runNext();
    await new Promise(resolve=>setImmediate(resolve));
    f.selecting(2);f.selecting(1);
    finishBody([{projectId:1,workspaceId:null,id:'superseded-job',status:'SUCCESS'}]);await discovery;
    assert.deepEqual(f.saved(),[],'Returning to the same project before either refresh completes cannot revive the old discovery');
    assert.equal(f.timers.size,0);
    f.event('taxonomy:portfolio-project-selected',{detail:{projectId:1}});
    assert.equal(f.timers.size,3,'Only successful selection resumes bounded discovery');
}

async function backForwardCacheResumesDiscoveryOnce() {
    const f=fixture(async()=>({ok:true,json:async()=>[{projectId:1,workspaceId:null,id:'restored-job',status:'SUCCESS'}]}),true);
    f.select(1);f.event('pagehide',{persisted:true});
    assert.equal(f.timers.size,0);
    f.event('pageshow',{persisted:true});f.event('pageshow',{persisted:true});
    assert.equal(f.timers.size,3,'A restored document resumes one bounded discovery schedule');
    while(f.timers.size)await f.runNext();
    assert.equal(f.requests.length,3);assert.equal(f.saved().length,1);
    assert.equal(f.saved()[0].job.id,'restored-job');
}

async function actionableJobsSurviveHistoryLimitAndReload() {
    const completed = Array.from({length: 25}, (_, i) => ({id: 'done-' + i, status: 'SUCCESS', projectId: 1}));
    const actionable = ['RUNNING', 'PENDING', 'PARTIAL', 'FAILED'].map(status => ({
        id: 'older-' + status, projectId: 1, status
    }));
    const f = fixture(async () => ({ok: true, json: async () => [...completed, ...actionable]}), true);
    f.select(1);
    await f.runNext();
    const history = f.saved();
    assert.deepEqual(history.filter(e => e.job.status !== 'SUCCESS').map(e => e.job.id).sort(),
        actionable.map(job => job.id).sort());
    assert.equal(history.filter(e => e.job.status === 'SUCCESS').length, 20);
    const restored = fixture(async () => ({ok: true}), false, history);
    restored.event('pageshow', {persisted: true});
    assert.equal(restored.timers.size, 2, 'Both active jobs must resume polling after reload');
}

async function activeDiscoveryFailureRetainsBoundedRetries() {
    const f=fixture(async()=>({ok:false,status:503,json:async()=>({detail:'Unavailable'})}),true);
    f.select(1);
    for(let attempt=0;attempt<3;attempt++)await f.runNext();
    assert.equal(f.requests.length,3);assert.equal(f.timers.size,0);
    assert.deepEqual(f.saved(),[]);
}

const cases = {
    actionableJobsSurviveHistoryLimitAndReload,
    registeredJobUsesValidatedProjectIdentity,
    restoredJobUsesValidatedProjectIdentity,
    departureCancelsScheduledPoll,
    departureAbortsInFlightPoll,
    departureCheckpointsDoNotPublishCancellation,
    departedResponseCannotRewriteHistory,
    backForwardCacheResumesOnce,
    activeFailureRetainsBoundedRetry,
    departureCancelsScheduledDiscovery,
    departureAbortsInFlightDiscovery,
    departedDiscoveryResponseCannotRegisterJobs,
    changedSelectionCannotRegisterOldDiscovery,
    replacedDiscoveryAbortsAndRejectsOldResponse,
    selectionStartCancelsDiscoveryBeforeFailedRefresh,
    pendingSelectionsCannotReviveOldDiscovery,
    backForwardCacheResumesDiscoveryOnce,
    activeDiscoveryFailureRetainsBoundedRetries
};

(async function () {
    assert.equal(Object.hasOwn(cases, caseName), true, "Unknown JUnit contract: " + caseName);
    await cases[caseName]();
    for(const clock of deadlineScopes) {
        assert.equal(clock.deadlines.size,0,'Completed or cancelled HTTP work releases canonical deadlines');
        assert.equal(clock.allTimers.size,clock.timers.size,'Only the asserted lifecycle schedule may remain');
    }
    process.stdout.write("PORTFOLIO_JOB_LIFECYCLE_OK " + caseName + "\n");
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
