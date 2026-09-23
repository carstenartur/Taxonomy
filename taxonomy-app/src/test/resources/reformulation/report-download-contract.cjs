'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const requests = [], downloads = [], revoked = [], delayed = [];
class Element {
    constructor(tag) { this.tagName = tag; this.children = []; this.dataset = {}; this.events = {}; this.isConnected = true; }
    append(...nodes) { this.children.push(...nodes); }
    setAttribute(k, v) { this[k] = v; }
    addEventListener(event, listener) { this.events[event] = listener; }
    click() { if (this.tagName === 'a') downloads.push({href:this.href,download:this.download}); return this.events.click?.(); }
    remove() { this.isConnected = false; }
}
const body = new Element('body');
const document = { body, createElement: tag => new Element(tag), querySelector: () => null };
const ok = (type='application/json') => ({ ok:true, status:200,
    headers: {get: key => ({'content-type':type,'content-disposition':'attachment; filename="report"','x-content-sha256':'a'.repeat(64)}[key.toLowerCase()] ?? null)},
    blob: async () => ({type}), json: async () => ({}) });
let response = () => Promise.resolve(ok());
const context = vm.createContext({ window:{location:{pathname:'/'}}, document, URLSearchParams, console,
    URL:{createObjectURL:blob=>'blob:'+blob.type,revokeObjectURL:url=>revoked.push(url)},
    setTimeout: fn => { delayed.push(fn); return delayed.length; },
    fetch: async (url,init) => { requests.push({url,init}); return response(); } });
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), context);
(async () => {
    const api=context.window.TaxonomyPortfolioApi;
    assert.equal(typeof api.downloadReformulationReport,'function','The real API adapter lacks the saved-report download');
    const proposal='1fc4fc26-4e84-4239-a8d5-9074f65731b1',command='29b073ed-3df3-4a3f-9f92-615df459a49b';
    await api.downloadReformulationReport(7,11,proposal,3,'md');
    assert.equal(requests[0].url,'/api/projects/7/requirements/11/reformulations/'+proposal+'/revisions/3/export?format=md');
    assert.equal(requests[0].init.cache,'no-store'); assert.equal(requests[0].init.credentials,'same-origin');
    assert.ok(!requests[0].init.method || requests[0].init.method==='GET');
    await api.downloadReformulationAdoptionReport(7,11,proposal,command,'json');
    assert.equal(requests[1].url,'/api/projects/7/requirements/11/reformulations/'+proposal+'/adoptions/'+command+'/export?format=json');
    for(const args of [[7,11,'../other',3,'json'],[7,11,proposal,0,'json'],[7,11,proposal,3,'pdf'],[7,11,proposal,Number.MAX_SAFE_INTEGER+1,'json']]) {
        assert.throws(()=>api.downloadReformulationReport(...args));
    }
    assert.equal(requests.length,2,'Invalid paths issued requests');
    vm.runInContext(fs.readFileSync(process.argv[3],'utf8'),context);
    const input={value:'unsaved local wording'},options={projectId:7,requirementId:11,proposalId:proposal,revision:3,language:'de'};
    const controls=context.window.TaxonomyReformulationReports.controls(options);
    const buttons=controls.children.filter(n=>n.tagName==='button');assert.equal(buttons.length,3);
    assert.ok(controls.children.some(n=>String(n.textContent).includes('Ungespeicherte')));
    let resolve; response=()=>new Promise(done=>{resolve=done;});
    const pending=buttons[0].click(); await Promise.resolve(); await Promise.resolve();
    assert.equal(buttons[0].disabled,true); const count=requests.length; await buttons[0].click(); assert.equal(requests.length,count,'Double click started another read');
    options.proposalId='d72211b1-4b6c-4ca2-9435-256b132ea04f';options.revision=9;
    resolve(ok());await pending;
    assert.equal(input.value,'unsaved local wording'); assert.ok(downloads[0].download.includes(proposal));assert.ok(downloads[0].download.includes('revision-3'));
    delayed.splice(0).forEach(fn=>fn());assert.equal(revoked.length,1);
    response=async()=>({ok:true,status:200,headers:{get:k=>k.toLowerCase()==='content-type'?'text/html':null},blob:async()=>({})});
    await buttons[2].click();assert.equal(downloads.length,1,'A login/error page was offered as a report');assert.equal(buttons[2].disabled,false);
    assert.ok(controls.children.some(n=>String(n.textContent).includes('fehlgeschlagen')));
    response=()=>new Promise(done=>{resolve=done;});const obsolete=buttons[1].click();await Promise.resolve();await Promise.resolve();
    buttons[1].isConnected=false;resolve(ok('text/markdown'));await obsolete;assert.equal(downloads.length,1,'Obsolete detached panel triggered a download');
    response=async()=>ok('text/html');
    const receipt=context.window.TaxonomyReformulationReports.controls({projectId:7,requirementId:11,proposalId:proposal,commandId:command,language:'en'});
    await receipt.children.filter(n=>n.tagName==='button')[2].click();assert.equal(downloads.length,2);assert.ok(downloads[1].download.includes(command));
    assert.ok(requests.every(r=>!r.init.method || r.init.method==='GET'),'Export performed a mutation');
    console.log('REFORMULATION_REPORT_DOWNLOAD_OK');
})().catch(error=>{console.error(error);process.exitCode=1;});
