'use strict';

// JUnit owns each case and passes the packaged production modules to this VM driver.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {readFileSync} = require('node:fs');

const [caseName, apiPath, sourcePath, discoveryPath] = process.argv.slice(2);
const api = readFileSync(apiPath, 'utf8');
const source = readFileSync(sourcePath, 'utf8');
const jobUrl='https://example.test/taxonomy/api/projects/1/analysis-jobs/retained-job';
const storageKey='taxonomy.portfolio.analysisJobs.v2';

function fixture(fetcher=async()=>({ok:true,json:async()=>({id:'retained-job',status:'RUNNING'})}), discover=false) {
    const timers=new Map(),events=new Map(),storage=new Map(),requests=[];
    let nextTimer=0;
    const listen=(name,handler)=>{if(!events.has(name))events.set(name,[]);events.get(name).push(handler);};
    const document={addEventListener:listen,getElementById:()=>null,querySelector:()=>null,querySelectorAll:()=>[]};
    const location=new URL('https://example.test/taxonomy/projects?workspaceId=working-copy&repositoryId=architecture&branch=draft');
    const window={location,
        fetch:async(url,options)=>{requests.push({url,options});return fetcher(url,options);},
        localStorage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
        setTimeout(callback,delay){const id=++nextTimer;timers.set(id,{callback,delay});return id;},
        clearTimeout(id){timers.delete(id);},
        addEventListener:listen,
        TaxonomyI18n:{getBasePath:()=>'/taxonomy',resolveUrl:path=>path.startsWith('/')?'/taxonomy'+path:path}
    };
    const context=vm.createContext({window,document,URL,URLSearchParams,Request,Response,Headers,AbortController,
        fetch:(...args)=>window.fetch(...args),queueMicrotask,Date,JSON,Map,Set});
    vm.runInContext(api,context);vm.runInContext(source,context);
    if(discover)vm.runInContext(readFileSync(discoveryPath,'utf8'),context);
    const register=()=>window.taxonomyPortfolioRegisterJob('/api/projects/1/analysis-jobs/retained-job',{id:'retained-job',status:'RUNNING'});
    return {register,requests,timers,storage,window,
        event(name,value={}) {for(const handler of events.get(name)||[])handler(value);},
        selecting(projectId) {storage.set('taxonomy.portfolio.projectId',String(projectId));this.event('taxonomy:portfolio-project-selecting',{detail:{projectId}});},
        select(projectId) {storage.set('taxonomy.portfolio.projectId',String(projectId));this.event('taxonomy:portfolio-project-selected',{detail:{projectId}});},
        runNext() {const entry=timers.entries().next().value;if(!entry)return Promise.resolve();timers.delete(entry[0]);return entry[1].callback();},
        saved() {return JSON.parse(storage.get(storageKey)||'[]');}
    };
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
        finish=()=>resolve({ok:true,json:async()=>({id:'retained-job',status:'RUNNING'})});
        options.signal?.addEventListener('abort',()=>reject(new DOMException('Page left','AbortError')),{once:true});
    }));
    f.register();const poll=f.runNext();
    try {
        assert.equal(f.requests[0].url,jobUrl);
        f.event('pagehide',{persisted:false});
        assert.equal(f.requests[0].options.signal?.aborted,true,'Navigation must abort the actual job transport');
    } finally {finish();await poll;}
    assert.equal(f.timers.size,0);assert.equal(f.saved()[0].lastPollError,undefined);
    assert.equal(f.saved()[0].job.status,'RUNNING');
}

async function departedResponseCannotRewriteHistory() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}));
    f.register();const poll=f.runNext();
    await new Promise(resolve=>setImmediate(resolve));
    f.event('pagehide',{persisted:true});
    finishBody({id:'retained-job',status:'SUCCESS'});await poll;
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
        finish=()=>resolve({ok:true,json:async()=>[{id:'departed-job',status:'SUCCESS'}]});
        options.signal?.addEventListener('abort',()=>reject(new DOMException('Page left','AbortError')),{once:true});
    }),true);
    f.select(1);const discovery=f.runNext();
    try {
        assert.equal(f.requests[0].url,'/api/projects/1/analysis-jobs');
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
    finishBody([{id:'departed-job',status:'SUCCESS'}]);await discovery;
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
    finishBody([{id:'old-project-job',status:'SUCCESS'}]);await discovery;
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
    } finally {finishBody([{id:'superseded-job',status:'SUCCESS'}]);await discovery;}
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
    } finally {finishBody([{id:'old-project-job',status:'SUCCESS'}]);await discovery;}
    assert.deepEqual(f.saved(),[]);
}

async function pendingSelectionsCannotReviveOldDiscovery() {
    let finishBody;
    const f=fixture(async()=>({ok:true,json:()=>new Promise(resolve=>{finishBody=resolve;})}),true);
    f.select(1);const discovery=f.runNext();
    await new Promise(resolve=>setImmediate(resolve));
    f.selecting(2);f.selecting(1);
    finishBody([{id:'superseded-job',status:'SUCCESS'}]);await discovery;
    assert.deepEqual(f.saved(),[],'Returning to the same project before either refresh completes cannot revive the old discovery');
    assert.equal(f.timers.size,0);
    f.event('taxonomy:portfolio-project-selected',{detail:{projectId:1}});
    assert.equal(f.timers.size,3,'Only successful selection resumes bounded discovery');
}

async function backForwardCacheResumesDiscoveryOnce() {
    const f=fixture(async()=>({ok:true,json:async()=>[{id:'restored-job',status:'SUCCESS'}]}),true);
    f.select(1);f.event('pagehide',{persisted:true});
    assert.equal(f.timers.size,0);
    f.event('pageshow',{persisted:true});f.event('pageshow',{persisted:true});
    assert.equal(f.timers.size,3,'A restored document resumes one bounded discovery schedule');
    while(f.timers.size)await f.runNext();
    assert.equal(f.requests.length,3);assert.equal(f.saved().length,1);
    assert.equal(f.saved()[0].job.id,'restored-job');
}

async function activeDiscoveryFailureRetainsBoundedRetries() {
    const f=fixture(async()=>({ok:false,status:503,json:async()=>({detail:'Unavailable'})}),true);
    f.select(1);
    for(let attempt=0;attempt<3;attempt++)await f.runNext();
    assert.equal(f.requests.length,3);assert.equal(f.timers.size,0);
    assert.deepEqual(f.saved(),[]);
}

const cases = {
    departureCancelsScheduledPoll,
    departureAbortsInFlightPoll,
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
    process.stdout.write("PORTFOLIO_JOB_LIFECYCLE_OK " + caseName + "\n");
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
