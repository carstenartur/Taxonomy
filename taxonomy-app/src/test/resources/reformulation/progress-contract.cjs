'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const apiSource=fs.readFileSync(process.argv[2],'utf8');
const source=fs.readFileSync(process.argv[3],'utf8');
const start=source.indexOf('    // Read-only progress state');
const end=source.indexOf('    async function refreshRuns()',start);
assert(start>=0 && end>start,'Production partial-result actions must exist');
function element(tag,text) {
    return {tag,text:text || '',children:[],dataset:{},style:{},
        append(...items){items.forEach(item=>{item.parent=this;this.children.push(item);});},
        replaceChildren(...items){this.children=[];this.append(...items);},
        remove(){if(this.parent)this.parent.children=this.parent.children.filter(n=>n!==this);},
        setAttribute(k,v){this[k]=v;}, focus(){},
        set innerHTML(value){assert.fail('Untrusted result must not become HTML');}};
}
const all=n=>[n,...n.children.flatMap(all)];
const fullText=n=>[n.text,...n.children.map(fullText)].join(' ');
const host=element('main');
const offer={id:'offer-a',currentRevision:{number:1,text:'Saved proposal'}};
const calls=[];
let held=null, holdId=null;
const a='a'.repeat(64),b='b'.repeat(64),c='c'.repeat(64);
const metadata=id=>({checkpointId:id,kind:'NODE',createdAt:'2026-09-21T14:00:00Z'});
const page=(run,after)=>({runId:run,sourceVersionId:3,sourceRevision:1,status:'CANCELLED',failureCode:null,
    createdAt:'2026-09-21T13:00:00Z',lastCheckpointAt:'2026-09-21T14:00:00Z',
    runCheckpointCount:3,proposalCheckpointCount:7,countsByKind:{NODE:3},
    items:after?[metadata(c)]:[metadata(a),metadata(b)],nextAfter:after?null:b});
const partial=(run,id)=>({runId:run,checkpointId:id,sourceRevision:1,sourceVersionId:3,kind:'NODE',
    node:{nodeId:'BP',summary:'Partial '+id[0]+' <img onerror=attack()>',statementProposals:[],preservedStatementIds:['original'],
        questionProposals:[{id:'question-'+id[0],wording:'Browser or terminal?',state:'OPEN',answerSchema:{options:['Browser','Terminal']},discoveries:[]}],
        preservedQuestionIds:[],conflictCandidates:[],uncoveredSourceRefs:[]}});
const usage=run=>({runId:run,recorded:true,fromFirstAttempt:run!=='run-b',httpAttempts:4,replays:1,pendingAttempts:1,retries:1,httpErrors:1,transportErrors:0,invalidUsage:0,
    inputTokens:{reported:'9223372036854775814',reports:2,unknown:2},outputTokens:{reported:'3',reports:1,unknown:3},
    totalTokens:{reported:'10',reports:1,unknown:3},cachedInputTokens:{reported:'0',reports:1,unknown:3},reasoningTokens:{reported:null,reports:0,unknown:4}});
const document={getElementById:id=>all(host).find(n=>n.id===id),querySelector:()=>null};
const sandbox={window:{location:{pathname:'/'}},document,URLSearchParams,host,offer,project:7,requirement:11,
    t:key=>key,el:element,pre:text=>element('pre',text),hasDrafts:()=>true,announce(){},
    button:(label,action)=>Object.assign(element('button',label),{action}),
    fetch:async(url,init={})=>{
        calls.push({url,init});const parsed=new URL(url,'http://localhost');
        const run=decodeURIComponent(parsed.pathname.match(/synthesis-runs\/([^/]+)/)[1]);
        const id=parsed.pathname.split('/checkpoints/')[1];
        if(id && id===holdId)return new Promise(resolve=>{held=()=>resolve({ok:true,status:200,json:async()=>partial(run,id)});});
        return {ok:true,status:200,json:async()=>parsed.pathname.endsWith('/usage')?usage(run):id?partial(run,id):page(run,parsed.searchParams.get('after'))};
    }};
vm.createContext(sandbox);
vm.runInContext(apiSource,sandbox);
sandbox.api=sandbox.window.TaxonomyPortfolioApi;
vm.runInContext(source.slice(start,end)+'\nthis.actions={showProgress,refreshProgress,showPartial,renderProgress};',sandbox);
(async()=>{
    await sandbox.actions.showProgress('run-a');
    let panel=document.getElementById('reformulationProgress');
    assert(fullText(panel).includes('storedRun: 3'),'Show actual saved-step count, not current page size');
    assert(fullText(panel).includes('storedOffer: 7'));
    assert(fullText(panel).includes('partialWarning') && fullText(panel).includes('countWarning'));
    assert(fullText(panel).includes('httpAttempts: 4'),'Show actual attempt evidence separately from saved steps');
    assert(fullText(panel).includes('9223372036854775814'),'Preserve exact token totals without numeric coercion');
    assert(fullText(panel).includes('pendingAttempts: 1') && fullText(panel).includes('usageWarning'));
    const unchanged=panel.children[0];
    await sandbox.actions.refreshProgress();
    assert.equal(panel.children[0],unchanged,'Unchanged polling must preserve DOM focus and open details');
    await all(panel).find(n=>n.text==='moreResults').action();
    assert.equal(all(panel).filter(n=>n.dataset.progressCheckpoint).length,3,'Append and deduplicate metadata pages');
    assert(!all(panel).some(n=>n.text==='moreResults'),'Do not repeat final page');
    await sandbox.actions.showPartial(a);
    assert(fullText(panel).includes('<img onerror=attack()>'),'Render literal text');
    assert(fullText(panel).includes('Browser or terminal?'));
    assert(fullText(panel).includes('original'),'Keep references to preserved evidence visible');
    holdId=b;
    const old=sandbox.actions.showPartial(b);
    await Promise.resolve();
    await sandbox.actions.showPartial(c);held();await old;
    assert(all(panel).some(n=>n.dataset.partialCheckpoint===c),'Stale detail must not replace latest selection');
    const oldAgain=sandbox.actions.showPartial(b);await Promise.resolve();
    await sandbox.actions.showProgress('run-b');held();await oldAgain;
    panel=document.getElementById('reformulationProgress');
    assert(!all(panel).some(n=>n.dataset.partialCheckpoint),'Old run result must not leak into another selection');
    assert(fullText(panel).includes('usageLate'),'A newly metered resumed run must not claim complete earlier usage');
    assert.equal(offer.currentRevision.text,'Saved proposal');
    assert.equal(offer.currentRevision.number,1);
    assert(calls.every(c=>!c.init.method || c.init.method==='GET'),'Progress actions must never mutate');
    assert(calls.every(c=>c.init.credentials==='same-origin' && c.init.cache==='no-store'),'Use authenticated no-cache API reads');
    await sandbox.api.getReformulationProgress(7,11,'offer/a','run/a?x','cursor?x');
    assert(calls.at(-1).url.includes('offer%2Fa/synthesis-runs/run%2Fa%3Fx/progress?limit=20&after=cursor%3Fx'),'Encode every path/query component');
    await all(panel).find(n=>n.text==='closeProgress').action();
    assert(!document.getElementById('reformulationProgress'));
    console.log('REFORMULATION_PROGRESS_CONTROLS_OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
