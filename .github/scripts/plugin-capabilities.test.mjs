import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const resources=new URL('../../taxonomy-app/src/main/resources/',import.meta.url);
const capabilityScripts=['/js/api/taxonomy-api-client.js','/js/api/plugin-capabilities-api.js','/js/shared/plugin-capabilities.js'];
function scriptsFor(page){
    const html=readFileSync(new URL('templates/'+page+'.html',resources),'utf8');
    return Array.from(html.matchAll(/th:src="@\{([^}]+)\}"/g),match=>match[1]);
}
const descriptor=(id='json',reportType=null)=>({id,reportType,displayName:'Format',fileExtension:'json',contentType:'application/json',binary:false,
    plugin:{id:'example.plugin',version:'1.0.0',artifactSha256:'a'.repeat(64)}});
function boot(data,extra={},page='index'){
    const requests=[],events=[];
    const document={querySelector:()=>null,getElementById:()=>null,
        createElement:()=>({setAttribute(){}}),head:{appendChild(){}},
        dispatchEvent:event=>events.push(event)};
    const window={location:{href:'https://taxonomy.example.test/taxonomy/',origin:'https://taxonomy.example.test'},
        fetch:async(url,init)=>{
            // The i18n bootstrap supplies this same-origin base-path wrapper.
            requests.push(['/taxonomy'+url,init]);
            const response=new Response(JSON.stringify(data),{status:extra.status||200,
                headers:{'Content-Type':'application/json'}});
            if(extra.redirected)Object.defineProperty(response,'redirected',{value:true});
            return response;
        }};
    const context=vm.createContext({window,document,URL,Request,Response,Headers,AbortController,
        setTimeout,clearTimeout,crypto:{randomUUID:()=> 'capability-request-id'},
        CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}}});
    context.fetch=(...args)=>window.fetch(...args);
    for(const script of scriptsFor(page).filter(script=>capabilityScripts.includes(script))){
        vm.runInContext(readFileSync(new URL('static'+script,resources),'utf8'),context,{filename:script});
    }
    return {api:window.TaxonomyCapabilities,requests,events};
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
