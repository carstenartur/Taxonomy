import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/plugin-capabilities.js',import.meta.url),'utf8');
const descriptor=(id='json',reportType=null)=>({id,reportType,displayName:'Format',fileExtension:'json',contentType:'application/json',binary:false,
    plugin:{id:'example.plugin',version:'1.0.0',artifactSha256:'a'.repeat(64)}});
function boot(data,extra={}){const requests=[];const context=vm.createContext({window:{},fetch:async(...args)=>{requests.push(args);return {ok:true,json:async()=>data,...extra};}});
    vm.runInContext(source,context);return {api:context.window.TaxonomyCapabilities,requests};}
test('absent formats remain absent and each read is fresh and authenticated',async()=>{
    const {api,requests}=boot({revision:7,features:['analysis'],exports:[],reports:[]});
    const value=await api.load();assert.equal(value.exports.length,0);assert.equal(value.revision,7);
    assert.ok(Object.isFrozen(value));assert.ok(Object.isFrozen(value.exports));
    assert.equal(requests[0][0],'/api/capabilities');assert.equal(requests[0][1].credentials,'same-origin');assert.equal(requests[0][1].cache,'no-store');
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
