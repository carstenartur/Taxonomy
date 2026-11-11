import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const resources=new URL('../../taxonomy-app/src/main/resources/',import.meta.url);
const capabilityScripts=['/js/api/taxonomy-api-client.js','/js/api/plugin-capabilities-api.js','/js/shared/plugin-capabilities.js'];
function scriptsFor(page){
    const common=readFileSync(new URL('templates/fragments/application-chrome.html',resources),'utf8');
    const html=readFileSync(new URL('templates/'+page+'.html',resources),'utf8')
        .replace(/<th:block th:replace="~\{fragments\/application-chrome :: scripts\}"><\/th:block>/g,()=>common);
    return Array.from(html.matchAll(/th:src="@\{([^}]+)\}"/g),match=>match[1]);
}
const descriptor=(id='json',reportType=null)=>({id,reportType,displayName:'Format',fileExtension:'json',contentType:'application/json',binary:false,
    plugin:{id:'example.plugin',version:'1.0.0',artifactSha256:'a'.repeat(64)}});
function boot(data,extra={},page='index'){
    const requests=[],events=[],documentListeners=new Map(),windowListeners=new Map();
    const document={querySelector:()=>null,getElementById:()=>null,
        createElement:()=>({setAttribute(){}}),head:{appendChild(){}},
        addEventListener(type, listener) { if(!documentListeners.has(type))documentListeners.set(type,[]); documentListeners.get(type).push(listener); },
        dispatchEvent(event) {events.push(event); for(const listener of documentListeners.get(event.type)||[])listener(event);}};
    const window={addEventListener(type,listener){if(!windowListeners.has(type))windowListeners.set(type,[]);windowListeners.get(type).push(listener);},location:{href:'https://taxonomy.example.test/taxonomy/',origin:'https://taxonomy.example.test'},
        fetch:async(url,init)=>{
            // The i18n bootstrap supplies this same-origin base-path wrapper.
            requests.push(['/taxonomy'+url,init]);
            if(extra.fetch)return extra.fetch(url,init);
            const response=new Response(JSON.stringify(data),{status:extra.status||200,
                headers:{'Content-Type':'application/json'}});
            if(extra.redirected)Object.defineProperty(response,'redirected',{value:true});
            return response;
        }};
    if(extra.runtime)window.__TaxonomyAnalysisSessionContext={runtime:extra.runtime};
    const context=vm.createContext({window,document,URL,Request,Response,Headers,AbortController,DOMException,
        setTimeout:extra.clock?.setTimeout||setTimeout,clearTimeout:extra.clock?.clearTimeout||clearTimeout,
        Date:extra.clock?.Date||Date,crypto:{randomUUID:()=> 'capability-request-id'},
        CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}}});
    context.fetch=(...args)=>window.fetch(...args);
    for(const script of scriptsFor(page).filter(script=>capabilityScripts.includes(script))){
        vm.runInContext(readFileSync(new URL('static'+script,resources),'utf8'),context,{filename:script});
    }
    return {api:window.TaxonomyCapabilities,boundary:window.TaxonomyCapabilitiesApi,client:window.TaxonomyApiClient,window,document,requests,events,
        emitWindow(type){for(const listener of windowListeners.get(type)||[])listener({type});}};
}
test('absent formats remain absent and each read is fresh and authenticated',async()=>{
    const {api,requests}=boot({revision:7,features:['analysis'],exports:[],reports:[]});
    const value=await api.load();assert.equal(value.exports.length,0);assert.equal(value.revision,7);
    assert.ok(Object.isFrozen(value));assert.ok(Object.isFrozen(value.exports));
    await api.load();assert.equal(requests.length,2);
    assert.equal(requests[0][0],'/taxonomy/api/capabilities');assert.equal(requests[0][1].credentials,'same-origin');assert.equal(requests[0][1].cache,'no-store');
    assert.equal(new Headers(requests[0][1].headers).get('X-Request-ID'),'capability-request-id');
    assert.equal(new Headers(requests[0][1].headers).get('Accept'),'application/json');
});
test('report families may offer the same format and retain their exact artifact',async()=>{
    const {api}=boot({reports:[descriptor('json','decision-rationale'),descriptor('json','architecture')],exports:[descriptor()]});
    const value=await api.load();assert.equal(value.reports.length,2);
    assert.equal(api.formats(value.reports,'architecture').length,1);
    assert.equal(value.reports[0].plugin.artifactSha256,'a'.repeat(64));assert.ok(Object.isFrozen(value.reports[0].plugin));
});
test('malformed paths, media types and unknown artifact identities are never offered',async()=>{
    const {api}=boot({exports:[descriptor('../download'),{...descriptor('badext'),fileExtension:'../file'},
        {...descriptor('badmime'),contentType:'text/plain\r\nLocation: secret'}, {...descriptor('badidentity'),plugin:{}},descriptor('safe'),descriptor('safe')]});
    const value=await api.load();assert.deepEqual(Array.from(value.exports,x=>x.id),['safe']);
});
test('sign-in redirects and server errors fail closed',async()=>{
    await assert.rejects(boot({}, {redirected:true,status:200}).api.load(),/Capabilities unavailable/);
    await assert.rejects(boot({}, {ok:false,status:503}).api.load(),/503/);
});
test('authentication failures retain the shared transport status and notification',async()=>{
    const {api,events}=boot({detail:'Sign in again'},{status:401});
    await assert.rejects(api.load(),error=>error.status===401&&error.code==='HTTP_ERROR');
    assert.equal(events.length,1);assert.equal(events[0].type,'taxonomy-api-auth-failure');
    assert.equal(events[0].detail.requestId,'capability-request-id');
});
for(const page of ['index','requirement-detail','architecture-workbench']){
    test(page+' loads capabilities through the API boundary after the base-path bootstrap',async()=>{
        const scripts=scriptsFor(page);
        const expected=['/js/taxonomy-i18n.js',...capabilityScripts];
        const positions=expected.map(script=>scripts.indexOf(script));
        assert.ok(positions.every((position,index)=>position>=0&&(index===0||position>positions[index-1])),
            'i18n, shared transport, capability API and consumer must load in dependency order');
        const {api,requests}=boot({revision:3}, {},page);
        assert.equal((await api.load()).revision,3);
        assert.equal(new Headers(requests[0][1].headers).get('X-Request-ID'),'capability-request-id');
    });
}

const settle=()=>new Promise(resolve=>setImmediate(resolve));
const capabilities={revision:17,features:['analysis'],exports:[],reports:[]};
function pendingBody() {
    let resolve;
    const body=new Promise(done=>{resolve=done;});
    const response=new Response(JSON.stringify(capabilities),{headers:{'Content-Type':'application/json'}});
    // Control completion of the HTTP body's JSON read while retaining native Response metadata.
    Object.defineProperty(response,'json',{configurable:true,value:()=>body});
    return {response,finish(){resolve(capabilities);}};
}
for(const phase of ['headers','body']) test(`global format catalog survives startup hydration while ${phase} is pending`,async()=>{
    let headers;const body=pendingBody();
    const runtime={workspaceId:null,analysisGeneration:0};
    const h=boot({}, {runtime,fetch:()=>phase==='headers'?new Promise(resolve=>{headers=resolve;}):Promise.resolve(body.response)});
    const pending=h.api.load().then(value=>({value}),error=>({error}));await settle();
    runtime.workspaceId='hydrated-workspace';runtime.analysisGeneration++;
    h.window.TaxonomyContextBar={getCurrentContext:()=>({contextId:'hydrated-context',branch:'draft',commitId:'a'.repeat(40)})};
    h.document.dispatchEvent({type:'taxonomy:entity-context-changed'});
    if(headers)headers(body.response);body.finish();
    const outcome=await pending;
    assert.equal(outcome.value?.revision,17,'Hydration must not reject the global catalog: '+outcome.error?.code);
    assert.equal(h.requests[0][1].signal.aborted,false);
});
test('the global catalog never acquires the current workspace pin',async()=>{
    const h=boot(capabilities,{runtime:{workspaceId:'private-workspace',analysisGeneration:1}});
    await h.api.load();
    assert.equal(h.requests[0][0],'/taxonomy/api/capabilities');
    assert.equal(new Headers(h.requests[0][1].headers).has('X-Taxonomy-Workspace-Id'),false);
});

function fakeClock() {
    let now=Date.now(),id=0;const timers=new Map();
    return {Date:class extends Date {static now(){return now;}},
        setTimeout(callback,delay){timers.set(++id,{callback,due:now+delay});return id;},
        clearTimeout(timer){timers.delete(timer);},
        advance(milliseconds){now+=milliseconds;for(const [key,timer] of [...timers])if(timer.due<=now){timers.delete(key);timer.callback();}}};
}
for(const phase of ['headers','body']) {
    for(const boundary of ['caller','page','deadline']) test(`global capabilities retain ${boundary} cancellation while ${phase} is pending`,async()=>{
        let headers;const body=pendingBody(),clock=fakeClock(),caller=new AbortController();
        const h=boot({}, {clock,fetch:()=>phase==='headers'?new Promise(resolve=>{headers=resolve;}):Promise.resolve(body.response)});
        const pending=h.boundary.load({signal:caller.signal,timeoutMillis:50}).then(value=>({value}),error=>({error}));
        await settle();
        if(boundary==='caller')caller.abort();
        else if(boundary==='page')h.emitWindow('pagehide');
        else clock.advance(50);
        const outcome=await pending;
        assert.equal(outcome.error?.code,boundary==='deadline'?'TIMEOUT':'ABORTED');
        assert.equal(h.requests[0][1].signal.aborted,true);
        if(headers)headers(body.response);body.finish();await settle();
    });
}
for(const status of [401,403]) test(`another HTTP ${status} invalidates an in-flight global capability body`,async()=>{
    const body=pendingBody();
    const h=boot({}, {fetch:url=>url.includes('auth-probe')
        ?Promise.resolve(new Response('{}',{status,headers:{'Content-Type':'application/json'}}))
        :Promise.resolve(body.response)});
    const pending=h.api.load().then(value=>({value}),error=>({error}));await settle();
    await assert.rejects(h.client.getJson('/api/auth-probe'),error=>error.status===status);
    assert.equal((await pending).error?.code,'ABORTED');
    assert.equal(h.requests[0][1].signal.aborted,true);
    const auth=h.events.filter(event=>event.type==='taxonomy-api-auth-failure');
    assert.equal(auth.length,1);assert.equal(auth[0].detail.status,status);
    body.finish();await settle();
});
