'use strict';

// JUnit owns each case and passes the packaged production modules to this VM driver.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {readFileSync} = require('node:fs');

const [caseName, apiPath, sourcePath] = process.argv.slice(2);
const api = readFileSync(apiPath, 'utf8');
const source = readFileSync(sourcePath, 'utf8');
const jobUrl='https://example.test/taxonomy/api/projects/1/analysis-jobs/retained-job';
const storageKey='taxonomy.portfolio.analysisJobs.v2';

function fixture(fetcher=async()=>({ok:true,json:async()=>({id:'retained-job',status:'RUNNING'})})) {
    const timers=new Map(),events=new Map(),storage=new Map(),requests=[];
    let nextTimer=0;
    const document={addEventListener(){},getElementById:()=>null,querySelector:()=>null,querySelectorAll:()=>[]};
    const location=new URL('https://example.test/taxonomy/projects?workspaceId=working-copy&repositoryId=architecture&branch=draft');
    const window={location,
        fetch:async(url,options)=>{requests.push({url,options});return fetcher(url,options);},
        localStorage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
        setTimeout(callback,delay){const id=++nextTimer;timers.set(id,{callback,delay});return id;},
        clearTimeout(id){timers.delete(id);},
        addEventListener(name,handler){if(!events.has(name))events.set(name,[]);events.get(name).push(handler);},
        TaxonomyI18n:{getBasePath:()=>'/taxonomy',resolveUrl:path=>path.startsWith('/')?'/taxonomy'+path:path}
    };
    const context=vm.createContext({window,document,URL,URLSearchParams,Request,Response,Headers,AbortController,
        fetch:(...args)=>window.fetch(...args),queueMicrotask,Date,JSON,Map,Set});
    vm.runInContext(api,context);vm.runInContext(source,context);
    const register=()=>window.taxonomyPortfolioRegisterJob('/api/projects/1/analysis-jobs/retained-job',{id:'retained-job',status:'RUNNING'});
    return {register,requests,timers,storage,
        event(name,value={}) {for(const handler of events.get(name)||[])handler(value);},
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

const cases = {
    departureCancelsScheduledPoll,
    departureAbortsInFlightPoll,
    departedResponseCannotRewriteHistory,
    backForwardCacheResumesOnce,
    activeFailureRetainsBoundedRetry
};

(async function () {
    assert.equal(Object.hasOwn(cases, caseName), true, "Unknown JUnit contract: " + caseName);
    await cases[caseName]();
    process.stdout.write("PORTFOLIO_JOB_LIFECYCLE_OK " + caseName + "\n");
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
