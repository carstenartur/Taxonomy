import test, {afterEach} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import canonicalFixture from './canonical-api-fixture.cjs';
afterEach(() => canonicalFixture.dispose());
const base = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const source = path => fs.readFileSync(new URL(path, base), 'utf8');
const commit = 'a'.repeat(40);
const selected = {repositoryId:'fixture-repo', workspaceScopeKey:'fixture-workspace', branch:'feature/evidence', commit};
const evidence = {repositoryId:selected.repositoryId, workspaceId:selected.workspaceScopeKey, workspaceScopeKey:selected.workspaceScopeKey,
    branch:selected.branch, commitId:commit, queryPolicy:'git-present-weighted-bfs-v1',readAuthority:'GIT_BRANCH_PROJECTION',
    requestedMaxHops:99,effectiveMaxHops:5,minimumRelevance:.2,hopDecay:.7,completeness:'COMPLETE_WITHIN_QUERY_POLICY',
    absenceScope:'Only within the selected snapshot and query policy.'};
const escapeHtml = value => String(value ?? '').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const settle = () => new Promise(resolve => setImmediate(resolve));
function graphFixture(escaping = escapeHtml) {
    const ids = new Map(), events = new Map(), calls = [];
    function element(id) { if(!ids.has(id)) ids.set(id,{id,value:'',checked:false,style:{},events:{},innerHTML:'',
        addEventListener(kind,cb){this.events[kind]=cb;},querySelectorAll(){return[];},setAttribute(){}}); return ids.get(id); }
    for (const id of ['graphResultsArea','graphResultsContent','graphResultContext','requirementImpactResults','graphApqcFilter','requirementImpactBtn','businessText','graphMaxHops','graphNodeInput','graphUpstreamBtn']) element(id);
    element('graphMaxHops').value='2'; element('businessText').value='Authored requirement';
    let current = {...selected};
    const document = {addEventListener(kind,cb){if(!events.has(kind))events.set(kind,[]);events.get(kind).push(cb);},
        getElementById:id=>ids.get(id)||null,querySelectorAll:()=>[]};
    const sandbox=vm.createContext({document,console,URL,URLSearchParams,TaxonomyI18n:{t:(key,...args)=>key+' '+args.join(' ')},
        TaxonomyUtils:{escapeHtml:escaping},TaxonomyState:{currentScores:{A:90}},
        __TaxonomyAnalysisSessionContext:{runtime:{workspaceId:selected.workspaceScopeKey,analysisGeneration:0}},
        TaxonomyAppChrome:{getContext:()=>({architecture:current})},
        TaxonomyArchitectureLifecycle:{ready:async()=>({context:current}),selectedContext:()=>current},
        fetch:async input=>{
            const url=new URL(String(input),'https://fixture.test/');
            const upstream=url.pathname.match(/^\/api\/graph\/node\/(.+)\/upstream$/);
            if(upstream)return Response.json({originNodeCode:decodeURIComponent(upstream[1]),direction:'UPSTREAM',
                neighbors:[],traversedRelationships:[],evidence});
            if(url.pathname==='/api/graph/apqc-hierarchy')return Response.json({nodes:[],evidence});
            if(url.pathname==='/api/graph/impact')return Response.json({impactedElements:[],traversedRelationships:[],evidence});
            throw new Error('Unexpected request: '+url);
        }});
    sandbox.window=sandbox;
    canonicalFixture.install(sandbox,['api/analysis-api.js']);
    const actualApi=sandbox.AnalysisApi;
    // Observe the real named boundaries while allowing tests to inject a deliberately late domain response.
    const api={...actualApi,
        graphUpstream:(code,hops,ctx)=>{calls.push({code,hops,ctx});return actualApi.graphUpstream(code,hops,ctx);},
        apqcHierarchy:ctx=>{calls.push({kind:'apqc',ctx});return actualApi.apqcHierarchy(ctx);},
        requirementImpact:(payload,ctx)=>{calls.push({kind:'impact',payload,ctx});return actualApi.requirementImpact(payload,ctx);}};
    sandbox.AnalysisApi=api;
    // Canonical owns its captured transport; a consumer bypass remains observable as a raw call.
    const directFetch=sandbox.fetch;
    sandbox.fetch=(...args)=>{calls.push({kind:'raw'});return directFetch(...args);};
    vm.runInContext(source('shared/taxonomy-graph.js'),sandbox);
    return {sandbox,ids,events,calls,api,graph:sandbox.TaxonomyGraph,setCurrent:value=>{current=value;},boot:()=>events.get('DOMContentLoaded')?.forEach(cb=>cb())};
}
test('graph calls carry the captured selected tuple and discard a response after context movement',async()=>{
    const f=graphFixture(); let resolve; f.api.graphUpstream=(code,hops,ctx)=>{f.calls.push({code,hops,ctx});return new Promise(r=>resolve=r);};
    let rendered=false; f.graph.fetchUpstream('A',2,()=>{rendered=true;}); await settle();
    assert.deepEqual(f.calls[0].ctx && JSON.parse(JSON.stringify(f.calls[0].ctx)),selected);
    f.setCurrent({...selected,commit:'b'.repeat(40)});resolve({evidence});await settle();assert.equal(rendered,false);
});
test('graph evidence and edge provenance remain visible and escaped even for an empty bounded answer',()=>{
    const f=graphFixture();
    const html=f.graph.renderNeighborhoodResult({direction:'UPSTREAM',originNodeCode:'A',neighbors:[],traversedRelationships:[],evidence});
    assert.ok(html.includes(commit)); assert.ok(html.includes('feature/evidence'));assert.ok(html.includes('99'));assert.ok(html.includes('5'));
    const edges=f.graph.renderRelationshipsTable([{sourceCode:'A',targetCode:'B',relationType:'SUPPORTS',propagatedRelevance:.8,hopDistance:1,
        evidence:{commitId:commit,causationId:'decision-1',provenance:'<untrusted>',status:'proposed',confidence:.9}}]);
    assert.ok(edges.includes('&lt;untrusted&gt;'));assert.ok(edges.includes('proposed'));assert.ok(edges.includes('decision-1'));
});
test('APQC consumes nodes and evidence through the scoped named API',async()=>{
    const f=graphFixture();f.boot();f.ids.get('graphApqcFilter').checked=true;f.ids.get('graphApqcFilter').events.change();await settle();
    assert.deepEqual(f.calls[0].ctx && JSON.parse(JSON.stringify(f.calls[0].ctx)),selected);
    assert.ok(f.ids.get('graphResultsContent').innerHTML.includes(commit));
});
test('requirement impact uses the named scoped boundary and retains evidence on empty results',async()=>{
    const f=graphFixture();vm.runInContext(source('relations/taxonomy-relations.js'),f.sandbox);f.boot();
    f.ids.get('requirementImpactBtn').events.click();await settle();
    assert.equal(f.calls.filter(call=>call.kind==='impact').length,1);assert.equal(f.calls.filter(call=>call.kind==='raw').length,0);
    assert.deepEqual(f.calls[0].ctx && JSON.parse(JSON.stringify(f.calls[0].ctx)),selected);
    assert.ok(f.ids.get('requirementImpactResults').innerHTML.includes(commit));
});
function detailHtml(detail) {
    const sandbox=vm.createContext({URL,URLSearchParams,document:{documentElement:{lang:'en'},addEventListener(){}},
        location:{pathname:'/projects/1/requirements/2',search:''},TaxonomyUtils:{escapeHtml},
        TaxonomyI18n:{getBasePath:()=>'',resolveUrl:v=>v,formatEnum:v=>v}});sandbox.window=sandbox;
    // Inspect the production renderer without changing its body or adding a production test hook.
    vm.runInContext(source('portfolio/requirement-detail.js').replace(/\}\)\(\);\s*$/,'window.renderResult = renderAnalysisSummary; })();'),sandbox);
    return sandbox.renderResult(detail);
}
test('portfolio shows rule judgments, source and matched versus canonical members without fabricated confidence',()=>{
    const html=detailHtml({analysis:{copilot:{schemaVersion:2,state:'COMPLETE',gaps:{profileId:'authored-profile',profileVersion:'1',
        repositoryId:'fixture-repo',workspaceScopeKey:'fixture-workspace',branch:'feature/evidence',commitId:commit,evidenceCompleteness:'INCOMPLETE',
        evidenceAuthority:'GIT_BRANCH_PROJECTION',judgments:[{ruleId:'needs-support',sourceNodeCode:'S',relationType:'SUPPORTS',targetRoots:['BP'],
        status:'INCONCLUSIVE',observedCount:0,minCount:1,maxCount:null,severity:'WARNING',description:'Evidence missing'}]},
        recommendation:{matchedElements:[{nodeCode:'MATCH'}],acceptedElements:[{nodeCode:'MEMBER'}],proposedElements:[],meanRelevance:null}}}});
    assert.ok(html.includes('needs-support'));assert.ok(html.includes('authored-profile'));assert.ok(html.includes(commit));
    assert.ok(html.includes('INCONCLUSIVE'));assert.ok(html.includes('MATCH'));assert.ok(html.includes('MEMBER'));
    const recommendation=html.slice(html.indexOf('id="recommendationResultSection"'),html.indexOf('id="technicalSnapshotData"'));
    assert.ok(!recommendation.includes('0%'));assert.ok(!recommendation.includes('text-bg-success'));
});

test('portfolio snapshot identifies the retained catalogue, model, scope and profile versions',()=>{
    const html=detailHtml({analysis:{modelBinding:{commit:'b'.repeat(40),semanticRevision:8,modelSha256:'a'.repeat(64),
        catalogueRevision:null,executionCatalogueRevision:'used-3',briefVersion:'brief-7',
        scope:{mode:'TAXONOMIES_ONLY',taxonomyRoots:['BP']},references:[{id:'standard',sourceVersionId:'<edition-4>'}]}}});
    for(const evidence of ['Catalogue assigned to the project','Catalogue used for analysis','used-3','brief-7',
        '&lt;edition-4&gt;','b'.repeat(40),'BP']) assert.ok(html.includes(evidence),evidence);
    assert.ok(!html.includes('<edition-4>'));
});

for (const field of ['repositoryId','workspaceScopeKey','branch','commit']) test(`evidence fence rejects different ${field} and absent proof`,async()=>{
    const f=graphFixture();
    assert.equal(await f.graph.isCurrentEvidence(selected,evidence),true);
    const changed={...selected,[field]:'different'};f.setCurrent(changed);
    assert.equal(await f.graph.isCurrentEvidence(selected,evidence),false);
    f.setCurrent(selected);
    assert.equal(await f.graph.isCurrentEvidence(selected,{...evidence,[field==='commit'?'commitId':field]:'different'}),false);
    assert.equal(await f.graph.isCurrentEvidence(selected,null),false);
});
test('recorded low mean relevance retains percent units and canonical members are separately visible',()=>{
    const html=detailHtml({analysis:{copilot:{schemaVersion:2,state:'COMPLETED',gaps:{judgments:[]},
        recommendation:{matchedElements:[{nodeCode:'MATCH'}],acceptedElements:[{nodeCode:'MEMBER'}],meanRelevance:1}}}})
        .split('<details id="technicalSnapshotData"')[0];
    assert.match(html,/1%/);assert.doesNotMatch(html,/100%/);
    assert.match(html,/Elements in the selected model/);assert.match(html,/MATCH/);assert.match(html,/MEMBER/);
    assert.match(html,/No obligations were evaluated/);assert.doesNotMatch(html,/text-bg-success/);
});

function graphWithProductionEscaping() {
    const utilities=vm.createContext({document:{readyState:'loading',addEventListener(){}}});
    utilities.window=utilities;
    vm.runInContext(source('shared/taxonomy-utils.js'),utilities);
    return graphFixture(utilities.TaxonomyUtils.escapeHtml);
}
test('graph input echoed by the scoped response remains text at the real result HTML sink',async()=>{
    const f=graphWithProductionEscaping();
    const code='<img src=x onerror="window.graphInjected=true"> & "node"';
    f.ids.get('graphNodeInput').value=code;
    f.api.graphUpstream=async(actual,hops,ctx)=>{
        f.calls.push({code:actual,hops,ctx});
        return {originNodeCode:actual,direction:'UPSTREAM',maxHops:hops,totalNeighbors:0,totalRelationships:0,
            neighbors:[],traversedRelationships:[],evidence};
    };
    f.boot();f.ids.get('graphUpstreamBtn').events.click();await settle();
    assert.equal(f.calls[0].code,code,'The request retains the exact user-selected identifier');
    const html=f.ids.get('graphResultsContent').innerHTML;
    assert.ok(html.includes('&lt;img src=x onerror=&quot;window.graphInjected=true&quot;&gt; &amp; &quot;node&quot;'));
    assert.doesNotMatch(html,/<img|<svg|<script/i);
    assert.ok(html.includes(commit));assert.ok(html.includes('graphViewTable'));
    assert.ok(html.includes('&#11014;&#65039;'),'Trusted direction icon remains markup');
    assert.match(html,/graph-stat-value text-secondary">0<\/div>/,'A numeric zero remains visible with the production escaping helper');
});
for(const renderer of ['renderNeighborhoodResult','renderFailureResult']) test(`${renderer} escapes every dynamic summary value`,()=>{
    const f=graphWithProductionEscaping();
    const payload='<svg onload="window.graphInjected=true">';
    const html=f.graph[renderer]({originNodeCode:payload,direction:payload,failedNodeCode:payload,
        totalNeighbors:payload,totalRelationships:payload,totalAffected:payload,maxHops:payload,
        neighbors:[],directlyAffected:[],indirectlyAffected:[],traversedRelationships:[],evidence});
    assert.doesNotMatch(html,/<svg|<script|<img/i);
    assert.ok(html.includes('&lt;svg onload=&quot;window.graphInjected=true&quot;&gt;'));
    assert.ok(html.includes(commit));
});
