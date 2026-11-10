import canonicalFixture from './canonical-api-fixture.cjs';
import test, {afterEach} from 'node:test';
afterEach(() => canonicalFixture.dispose());
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';

const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/api/analysis-api.js', import.meta.url), 'utf8');
const analysisSource = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis.js', import.meta.url), 'utf8');
const selected = {repositoryId:'architecture-a', workspaceScopeKey:'__shared__', branch:'design/one', commit:'a'.repeat(40)};
function fixture(current=selected) {
    const calls=[];
    const context=vm.createContext({URLSearchParams, Object, Promise, Error,
        TaxonomyAppChrome:{getContext:()=>({architecture:current})},
        TaxonomyArchitectureLifecycle:{ready:async()=>({context:current}),selectedContext:()=>current},
        TaxonomyApiClient:{
            getJson:async(...args)=>{calls.push({method:'GET',args});return [];},
            sendJson:async(...args)=>{calls.push({method:'POST',args});return {};}
        }});
    context.window=context;
    vm.runInContext(source,context);
    return {api:context.AnalysisApi,calls};
}

test('graph reads require a complete explicitly selected revision before transport',async()=>{
    const {api,calls}=fixture();
    for(const context of [undefined,{}, {...selected,commit:''},{...selected,repositoryId:''},{...selected,workspaceScopeKey:undefined},
        {...selected,workspaceScopeKey:undefined,workspaceId:undefined},{...selected,workspaceScopeKey:undefined,workspaceId:null}]) {
        await assert.rejects(async()=>api.graphDownstream('CR-1000',2,context));
    }
    assert.equal(calls.length,0,'An unresolved selection must not become a primary-context query');
});

test('central and private graph requests preserve exact repository branch revision and workspace pin',async()=>{
    for(const workspaceScopeKey of ['__shared__','private-b']) {
        const {api,calls}=fixture();
        await api.graphDownstream('CR / fixture',3,{...selected,workspaceScopeKey});
        const [path,policy]=calls[0].args;
        const query=new URL(path,'https://fixture.test').searchParams;
        assert.equal(query.get('repositoryId'),'architecture-a');
        assert.equal(query.get('branch'),'design/one');
        assert.equal(query.get('commitId'),'a'.repeat(40));
        assert.equal(query.get('workspaceId'),workspaceScopeKey==='__shared__'?'':workspaceScopeKey);
        assert.equal(query.get('maxHops'),'3');
        assert.equal(policy.pinnedWorkspaceId,query.get('workspaceId'));
        assert.ok(path.includes('CR%20%2F%20fixture'));
    }
});

test('obligation requests carry explicit profile evidence and selected context separately',async()=>{
    const {api,calls}=fixture();
    const body={profileId:'reviewed-profile',profileVersion:'7',evidenceCompleteness:'INCOMPLETE',businessText:'Authored fixture'};
    await api.analyzeGap(body,selected);
    const [path,payload,method,policy]=calls[0].args;
    assert.equal(new URL(path,'https://fixture.test').searchParams.get('commitId'),selected.commit);
    assert.deepEqual(payload,body);
    assert.equal(method,'POST');
    assert.equal(policy.pinnedWorkspaceId,'');
    assert.equal(Object.hasOwn(payload,'scores'),false);
});

test('profile discovery is read only and never invents a profile version',async()=>{
    const {api,calls}=fixture();
    await api.gapProfiles();
    assert.equal(calls[0].method,'GET');
    assert.equal(calls[0].args[0],'/api/gap/profiles');
});

test('context capture uses the existing lifecycle revision and rejects missing retained evidence',async()=>{
    const {api}=fixture();
    assert.deepEqual(JSON.parse(JSON.stringify(await api.captureContext())),selected);
    await assert.rejects(()=>fixture({...selected,commit:null}).api.captureContext());
});

test('impact and APQC queries use the same explicit source tuple',async()=>{
    const {api,calls}=fixture();
    await api.requirementImpact({scores:{},businessText:'fixture',maxHops:2},selected);
    await api.apqcHierarchy(selected);
    await api.apqcCoverage(selected);
    for(const call of calls) {
        const url=new URL(call.args[0],'https://fixture.test');
        assert.equal(url.searchParams.get('commitId'),selected.commit);
        assert.equal(url.searchParams.get('workspaceId'),'');
    }
});

test('pattern requests require and preserve the selected saved model',async()=>{
    const {api,calls}=fixture();
    await assert.rejects(async()=>api.detectPatterns({scores:{},minScore:50}));
    assert.equal(calls.length,0);
    await api.detectPatterns({scores:{'CP-fixture':80},minScore:50},selected);
    const [path,payload,method,policy]=calls[0].args;
    const query=new URL(path,'https://fixture.test').searchParams;
    assert.equal(query.get('repositoryId'),selected.repositoryId);
    assert.equal(query.get('branch'),selected.branch);
    assert.equal(query.get('commitId'),selected.commit);
    assert.equal(query.get('workspaceId'),'');
    assert.equal(policy.pinnedWorkspaceId,'');
    assert.equal(payload.minScore,50);
    assert.equal(method,'POST');
});

function renderFixture(gaps,recommendation) {
    const elements=new Map();
    const listeners=new Map();
    for(const id of ['gapAnalysisContent','recommendationContent','patternDetectionContent','copilotContent','copilotPanel','copilotBtn','copilotSpinner'])
        elements.set(id,{innerHTML:'',style:{},classList:{add(){},remove(){}},addEventListener(){}});
    const context=vm.createContext({document:{documentElement:{lang:'en'},getElementById:id=>elements.get(id)||null,
        addEventListener:(type,listener)=>{if(!listeners.has(type))listeners.set(type,[]);listeners.get(type).push(listener);}},
        TaxonomyI18n:{t:(key,...args)=>key+(args.length?' '+args.join(','):'')},
        TaxonomyUtils:{escapeHtml:value=>String(value??'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;')},
        TaxonomyState:{}});
    context.window=context;
    vm.runInContext(analysisSource,context);
    context.TaxonomyAnalysis.renderCopilotResult({schemaVersion:2,state:'COMPLETED',gaps,recommendation,
        patterns:{patternCoverage:0,matchedPatterns:[],incompletePatterns:[],notes:[]},notes:[]});
    elements.emit=type=>{for(const listener of listeners.get(type)||[])listener({});};
    return elements;
}

test('inconclusive obligations stay visibly open even when the violated count is zero',()=>{
    const elements=renderFixture({profileId:'service-support',profileVersion:'1.0.0',evidenceCompleteness:'INCOMPLETE',
        totalAnchors:1,totalGaps:0,judgments:[{ruleId:'service-support',sourceNodeCode:'fixture-service',relationType:'SUPPORTS',
            targetRoots:['BP','BR'],minCount:1,maxCount:null,observedCount:0,severity:'WARNING',status:'INCONCLUSIVE',description:'Evidence is incomplete'}],notes:[]},
        {matchedElements:[],acceptedElements:[],proposedElements:[],suggestedRelations:[],meanRelevance:null,reasoning:[],notes:[]});
    const html=elements.get('gapAnalysisContent').innerHTML;
    assert.ok(html.includes('data-assessment-status="INCONCLUSIVE"'));
    assert.ok(html.includes('fixture-service'));
    assert.ok(html.includes('analysis.evidence.inconclusive'));
    assert.ok(!html.includes('alert-success'),'An unresolved assessment cannot advertise complete success');
});

test('high relevance is displayed as a ranked match separately from accepted model membership',()=>{
    const elements=renderFixture({profileId:'compatibility',profileVersion:'1.0.0',totalAnchors:0,totalGaps:0,judgments:[],notes:[]},
        {matchedElements:[{nodeCode:'fixture-ranked',title:'Ranked candidate',taxonomyRoot:'CR',score:99,reasoning:'Text match'}],
            acceptedElements:[],proposedElements:[],suggestedRelations:[],meanRelevance:99,reasoning:[],notes:[]});
    const html=elements.get('recommendationContent').innerHTML;
    assert.ok(html.includes('fixture-ranked'));
    assert.ok(html.includes('analysis.recommendation.matches'));
    assert.ok(html.includes('analysis.recommendation.accepted 0'));
    assert.ok(!html.includes('analyze.recommendation.confidence'));
});

test('a compatibility-only profile reports no obligation assessment instead of architectural completeness',()=>{
    const elements=renderFixture({profileId:'compatibility',profileVersion:'1.0.0',totalAnchors:8,totalGaps:0,judgments:[],notes:[]},
        {matchedElements:[],acceptedElements:[],proposedElements:[],suggestedRelations:[],meanRelevance:null,reasoning:[],notes:[]});
    assert.ok(elements.get('gapAnalysisContent').innerHTML.includes('analysis.evidence.noObligations'));
    assert.ok(elements.get('recommendationContent').innerHTML.includes('analysis.evidence.notAssessed'));
    assert.ok(!elements.get('copilotContent').innerHTML.includes('alert-success'));
    assert.ok(elements.get('copilotContent').innerHTML.includes('analysis.evidence.noObligations'));
});

function pendingAssessmentFixture(kind='gap') {
    const elements=new Map();
    for(const id of ['gapAnalysisContent','gapAnalysisPanel','recommendationContent','recommendationPanel','patternDetectionContent','patternDetectionPanel','copilotContent','copilotPanel','businessText','gapProfile','gapEvidenceComplete'])
        elements.set(id,{value:'',innerHTML:'',style:{},dataset:{loaded:'true'},checked:false});
    elements.get('businessText').value='Authored requirement';
    elements.get('gapProfile').value='service-support';
    let resolveResponse, request;
    const response=new Promise(resolve=>{resolveResponse=resolve;});
    const current={...selected};
    const patternProof=()=>({repositoryId:selected.repositoryId,workspaceId:null,workspaceScopeKey:selected.workspaceScopeKey,
        branch:selected.branch,commitId:selected.commit,evidenceAuthority:'CANONICAL_SNAPSHOT',readAuthority:'GIT_BRANCH_PROJECTION',
        readinessState:'READY',templateVersion:'1.0.0',minimumScore:50,eligibleAnchors:1,completeness:'COMPLETE_WITHIN_PATTERN_TEMPLATES'});
    const listeners=new Map();
    const context=vm.createContext({document:{documentElement:{lang:'en'},getElementById:id=>elements.get(id)||null,
        addEventListener:(type,listener)=>{if(!listeners.has(type))listeners.set(type,[]);listeners.get(type).push(listener);}},
        TaxonomyI18n:{t:(key,...args)=>key+' '+args.join(',')},
        TaxonomyUtils:{escapeHtml:value=>String(value??'')},
        TaxonomyState:{currentScores:{'CP-fixture':80}},TaxonomyArchitectureLifecycle:{state:()=>({context:current})},
        AnalysisApi:{captureContext:async()=>({...current}),gapProfiles:async()=>[{id:'service-support',version:'7'}],
            detectPatterns:(body,context)=>{if(kind==='pattern'){request={body,context};return response;}
                return Promise.resolve({evidence:patternProof(),patternCoverage:null,matchedPatterns:[],incompletePatterns:[],notes:[]});},
            analyzeGap:(body,context)=>{request={body,context};return response;},
            recommend:(body,context)=>{request={body,context};return response;}}});
    context.window=context;
    context.TaxonomyAppChrome={getContext:()=>({architecture:current})};
    context.fetch=()=>{throw new Error('Domain evidence fixture must use its injected named assessment responses');};
    canonicalFixture.install(context);
    vm.runInContext(analysisSource,context);
    const proof=()=>({repositoryId:selected.repositoryId,workspaceId:null,workspaceScopeKey:selected.workspaceScopeKey,
        branch:selected.branch,commitId:selected.commit,profileId:'service-support',profileVersion:'7',metamodelVersion:'1.0.0',
        businessText:'Authored requirement',evidenceAuthority:'CANONICAL_SNAPSHOT',evidenceCompleteness:'INCOMPLETE',judgments:[],notes:['delivered-result']});
    return {elements,current,state:context.TaxonomyState,api:context.AnalysisApi,patternProof,request:()=>request,run:()=>context.TaxonomyAnalysis[{gap:'runGapAnalysis',recommendation:'runRecommendation',copilot:'runCopilotFlow',pattern:'runPatternDetection'}[kind]](),
        emit:(type,id)=>{for(const listener of listeners.get(type)||[])listener({target:{id}});},
        resolve:(mutate=value=>value)=>{if(kind==='pattern'){resolveResponse({evidence:mutate(patternProof()),patternCoverage:null,matchedPatterns:[],incompletePatterns:[],notes:['delivered-result']});return;}
            const evidence=mutate(proof());resolveResponse(kind!=='recommendation'?evidence:
            {gapAnalysis:evidence,acceptedElements:[{nodeCode:'delivered-result',title:'Model member'}],matchedElements:[],
                proposedElements:[],suggestedRelations:[],meanRelevance:null,notes:[]});}};
}

for(const [name,change] of [
    ['revision',f=>{f.current.commit='b'.repeat(40);}],
    ['profile',f=>{f.elements.get('gapProfile').value='compatibility';}],
    ['completeness',f=>{f.elements.get('gapEvidenceComplete').checked=true;}],
    ['requirement',f=>{f.elements.get('businessText').value='Later input';}]
]) test(`pending assessment cannot overwrite a changed ${name}`,async()=>{
    const f=pendingAssessmentFixture(); const running=f.run();
    await new Promise(resolve=>setImmediate(resolve));
    assert.equal(f.request().body.profileVersion,'7');
    assert.equal(f.request().body.evidenceCompleteness,'INCOMPLETE');
    assert.equal(f.request().context.commit,selected.commit);
    change(f);f.resolve();await running;
    assert.ok(!f.elements.get('gapAnalysisContent').innerHTML.includes('delivered-result'));
});

for(const [name,mutate] of [
    ['missing',()=>undefined],['repository',p=>({...p,repositoryId:'another-model'})],
    ['workspace',p=>({...p,workspaceId:'private-b'})],['branch',p=>({...p,branch:'another-branch'})],
    ['revision',p=>({...p,commitId:'b'.repeat(40)})],['authority',p=>({...p,evidenceAuthority:'RETAINED_ANALYSIS'})],
    ['readiness',p=>({...p,readinessState:'STALE'})],['template',p=>({...p,templateVersion:''})],
    ['threshold',p=>({...p,minimumScore:20})],['completeness',p=>({...p,completeness:'UNKNOWN'})]
]) test(`pattern rejects ${name} response proof`,async()=>{
    const f=pendingAssessmentFixture('pattern');const pending=f.run();await new Promise(resolve=>setImmediate(resolve));
    f.resolve(mutate);await pending;
    const html=f.elements.get('patternDetectionContent').innerHTML;
    assert.ok(!html.includes('delivered-result'));
    assert.ok(html.includes('analysis.evidence.invalidResponse'));
});

for(const event of ['taxonomy:lifecycle-changed','taxonomy:architecture-reconciled','taxonomy:architecture-source-changed'])
test(`pattern invalidates displayed selected-model evidence on ${event}`,async()=>{
    const f=pendingAssessmentFixture('pattern');const pending=f.run();await new Promise(resolve=>setImmediate(resolve));
    assert.equal(f.request().context.commit,selected.commit);
    f.resolve();await pending;
    const panel=f.elements.get('patternDetectionContent');
    assert.ok(panel.innerHTML.includes('delivered-result'));
    assert.ok(panel.innerHTML.includes('analysis.evidence.notAssessed'),'An empty metric is not zero percent');
    assert.ok(panel.innerHTML.includes(selected.repositoryId),'Visible source identifies the evaluated model');
    f.emit(event);await new Promise(resolve=>setImmediate(resolve));
    assert.ok(!panel.innerHTML.includes('delivered-result'));
    assert.ok(panel.innerHTML.includes('analysis.evidence.selectionChanged'));
});

for(const [name,change] of [
    ['model',f=>{f.current.commit='b'.repeat(40);}],
    ['scores',f=>{f.state.currentScores['CP-fixture']=20;}],
    ['requirement',f=>{f.elements.get('businessText').value='Another requirement';}]
]) test(`pending pattern cannot overwrite changed ${name}`,async()=>{
    const f=pendingAssessmentFixture('pattern');const pending=f.run();await new Promise(resolve=>setImmediate(resolve));
    change(f);f.resolve();await pending;
    assert.ok(!f.elements.get('patternDetectionContent').innerHTML.includes('delivered-result'));
});

test('live Copilot rejects a pattern from a different model before recommendations',async()=>{
    const f=pendingAssessmentFixture('copilot');let recommendations=0;
    f.api.detectPatterns=async(body,context)=>{
        assert.equal(context.commit,selected.commit);
        return {evidence:{...f.patternProof(),repositoryId:'another-model'},matchedPatterns:[],incompletePatterns:[],notes:['wrong-pattern']};
    };
    f.api.recommend=async()=>{recommendations++;return {};};
    const pending=f.run();await new Promise(resolve=>setImmediate(resolve));f.resolve();await pending;
    assert.equal(recommendations,0);
    assert.ok(!f.elements.get('patternDetectionContent').innerHTML.includes('wrong-pattern'));
    assert.ok(f.elements.get('copilotContent').innerHTML.includes('analysis.evidence.invalidResponse'));
});

for(const kind of ['gap','recommendation']) for(const [name,mutate] of [
    ['missing',()=>undefined], ['repository',p=>({...p,repositoryId:'another-model'})],
    ['workspace',p=>({...p,workspaceId:'private-b'})], ['branch',p=>({...p,branch:'another-branch'})],
    ['revision',p=>({...p,commitId:'b'.repeat(40)})], ['profile',p=>({...p,profileId:'another-profile'})],
    ['profile version',p=>({...p,profileVersion:'8'})], ['authority',p=>({...p,evidenceAuthority:'RETAINED_ANALYSIS'})],
    ['false completeness',p=>({...p,evidenceCompleteness:'COMPLETE'})]
]) test(`${kind} rejects ${name} response proof`,async()=>{
    const f=pendingAssessmentFixture(kind);const pending=f.run();await new Promise(resolve=>setImmediate(resolve));
    f.resolve(mutate);await pending;
    const html=f.elements.get(kind==='gap'?'gapAnalysisContent':'recommendationContent').innerHTML;
    assert.ok(!html.includes('delivered-result'),'Unverified response content must not be presented as the selected model');
    assert.ok(html.includes('analysis.evidence.invalidResponse'),'Explain the rejected proof');
});

test('a complete request accepts an explicit incomplete evidence downgrade',async()=>{
    const f=pendingAssessmentFixture();f.elements.get('gapEvidenceComplete').checked=true;
    const pending=f.run();await new Promise(resolve=>setImmediate(resolve));f.resolve();await pending;
    assert.ok(f.elements.get('gapAnalysisContent').innerHTML.includes('delivered-result'));
});

for(const kind of ['gap','recommendation']) for(const [event,target] of [
    ['taxonomy:lifecycle-changed'],['taxonomy:architecture-reconciled'],['taxonomy:architecture-source-changed'],
    ['change','gapProfile'],['change','gapEvidenceComplete'],['input','businessText']
]) test(`${kind} invalidates displayed live evidence on ${target||event}`,async()=>{
    const f=pendingAssessmentFixture(kind);const pending=f.run();await new Promise(resolve=>setImmediate(resolve));
    f.resolve();await pending;
    const panel=f.elements.get(kind==='gap'?'gapAnalysisContent':'recommendationContent');
    assert.ok(panel.innerHTML.includes('delivered-result'));
    f.emit(event,target);await new Promise(resolve=>setImmediate(resolve));
    assert.ok(!panel.innerHTML.includes('delivered-result'));
    assert.ok(panel.innerHTML.includes('analysis.evidence.selectionChanged'));
});

test('live Copilot rejects a gap stage from another selected model',async()=>{
    const f=pendingAssessmentFixture('copilot');const pending=f.run();await new Promise(resolve=>setImmediate(resolve));
    f.resolve(p=>({...p,repositoryId:'another-model'}));await pending;
    assert.ok(!f.elements.get('gapAnalysisContent').innerHTML.includes('delivered-result'));
    assert.ok(f.elements.get('copilotContent').innerHTML.includes('analysis.evidence.invalidResponse'));
});

test('live Copilot does not mark completion when a later recommendation has no bound proof',async()=>{
    const f=pendingAssessmentFixture('copilot');const pending=f.run();await new Promise(resolve=>setImmediate(resolve));
    f.resolve();await pending;
    assert.ok(f.elements.get('gapAnalysisContent').innerHTML.includes('delivered-result'));
    assert.ok(f.elements.get('copilotContent').innerHTML.includes('analysis.evidence.invalidResponse'));
    assert.ok(!f.elements.get('copilotContent').innerHTML.includes('data-assessment-state="COMPLETED"'));
});

test('explicit retained reports remain labelled and readable after the live model changes',()=>{
    const elements=renderFixture({profileId:'compatibility',profileVersion:'1.0.0',judgments:[],notes:['retained-result']},
        {matchedElements:[],acceptedElements:[],meanRelevance:null,proposedElements:[],suggestedRelations:[]});
    elements.emit('taxonomy:lifecycle-changed');
    assert.ok(elements.get('gapAnalysisContent').innerHTML.includes('retained-result'));
    assert.ok(elements.get('gapAnalysisContent').innerHTML.includes('analysis.evidence.retained'));
});
