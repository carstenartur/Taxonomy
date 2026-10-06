import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const root = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const bootstrap = await readFile(new URL('static/js/taxonomy-i18n.js',root),'utf8');
const scoring = await readFile(new URL('static/js/core/taxonomy-scoring.js',root),'utf8');
async function messages(locale) {
  const source=await readFile(new URL(`i18n/messages${locale==='de'?'_de':''}.properties`,root),'utf8');
  return Object.fromEntries(source.split(/\r?\n/).filter(line=>!line.startsWith('#')&&line.includes('=')).map(line=>{
    const at=line.indexOf('=');return [line.slice(0,at),line.slice(at+1).replace(/\\u([0-9a-f]{4})/gi,(_,hex)=>String.fromCharCode(parseInt(hex,16)))];
  }));
}
async function boot({html='de',stored='en',unavailable=false}={}) {
 const fetched=[]; const document={currentScript:{src:'https://example.test/js/taxonomy-i18n.js'},cookie:'lang=en',documentElement:{lang:html},dispatchEvent(){}};
 const window={location:{href:'https://example.test/?lang='+html}};
 const context=vm.createContext({window,document,URL,URLSearchParams,CustomEvent:class{},console,
   localStorage:{getItem(){if(unavailable)throw new Error('storage disabled');return stored;},setItem(){if(unavailable)throw new Error('storage disabled');}},
   fetch:async url=>{fetched.push(url);return {ok:true,json:()=>messages(url.endsWith('/de')?'de':'en')};}});
 vm.runInContext(bootstrap,context); await window.TaxonomyI18n.ready();
 return {window,document,context,fetched};
}
for (const [html,stored] of [['de','en'],['en','de'],['de-DE','en']]) {
 test(`server locale ${html} overrides stale storage ${stored}`,async()=>{
  const ui=await boot({html,stored}); const expected=html.startsWith('de')?'de':'en';
  assert.equal(ui.window.TaxonomyI18n.getLocale(),expected);assert.equal(ui.document.documentElement.lang,expected);
  assert.deepEqual(ui.fetched,['/api/i18n/'+expected]);assert.equal(ui.window.TaxonomyI18n.t('nav.analyze'),expected==='de'?'Analyse':'Analyze');
 });
}
test('blocked storage still loads German and allows switching',async()=>{
 const ui=await boot({unavailable:true});assert.equal(ui.window.TaxonomyI18n.t('nav.analyze'),'Analyse');
 ui.window.TaxonomyI18n.setLocale('en');assert.equal(new URL(ui.window.location.href).searchParams.get('lang'),'en');
});
test('actual German suggested-relation renderer localizes actions while preserving source text',async()=>{
 const ui=await boot();const nodes=Object.fromEntries(['suggestedRelationsPanel','suggestedRelationsContent','suggestedRelationsBadge'].map(id=>[id,{style:{},innerHTML:'',textContent:''}]));
 ui.document.getElementById=id=>nodes[id];ui.context.TaxonomyI18n=ui.window.TaxonomyI18n;
 ui.context.TaxonomyUtils={escapeHtml:value=>String(value??'').replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;')};
 ui.window.TaxonomyState={};vm.runInContext(scoring,ui.context);
 ui.window.TaxonomyScoring.renderSuggestedRelations([{hypothesisId:7,status:'PROPOSED',sourceCode:'CP-1',targetCode:'CR-2',sourceName:'Original English title',relationType:'REALIZES',confidence:0.9,reasoning:'Original English evidence'}]);
 const html=nodes.suggestedRelationsContent.innerHTML;
 for(const label of ['Alle ≥80% übernehmen','Dauerhaft übernehmen','Nur für diese Analyse anwenden','Verwerfen','Quelle','Ziel','Konfidenz','Begründung'])assert.ok(html.includes(label),label);
 assert.ok(html.includes('Original English title'));assert.ok(html.includes('Original English evidence'));
 assert.equal(ui.window.TaxonomyI18n.formatEnum('HIGH'),'Hoch');assert.equal(ui.window.TaxonomyI18n.formatEnum('DRAFT'),'Entwurf');
});

test('import criticality options and the real outbound review use the server enum',async()=>{
 const ui=await boot();ui.window.location.pathname='/projects/1/import';ui.window.location.search='?lang=de';
 ui.window.setTimeout=()=>{};ui.context.localStorage.removeItem=()=>{};
 ui.window.TaxonomyUtils={escapeHtml:value=>String(value??'')};let sent;
 ui.window.TaxonomyPortfolioApi={importReviewedRequirements:async(projectId,body)=>{sent={projectId,body};return {json:async()=>({}),headers:{get:()=>null}};}};
 const nodes=new Map();ui.document.addEventListener=()=>{};ui.document.getElementById=id=>{
  if(!nodes.has(id))nodes.set(id,{checked:false,textContent:'',classList:{toggle(){},remove(){}},focus(){}});return nodes.get(id);
 };
 const source=await readFile(new URL('static/js/portfolio/portfolio-import.js',root),'utf8');
 vm.runInContext(source.replace(/\}\)\(\);\s*$/,'window.importProbe={state,criticalitySelect,requirementTypeSelect,updateCandidateFromControl,confirmImport};})();'),ui.context);
 const probe=ui.window.importProbe;const html=probe.criticalitySelect('MISSION_CRITICAL');
 assert.match(html,/<option value="MISSION_CRITICAL" selected>Missionskritisch<\/option>/);
 const schema=await readFile(new URL('../../taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/model/PortfolioTypes.java',import.meta.url),'utf8');
 for(const [enumName,render] of [['Criticality',()=>html],['RequirementType',()=>probe.requirementTypeSelect('FUNCTIONAL')]]){
  const declared=schema.match(new RegExp(`enum ${enumName} \\{([^}]+)\\}`))[1].match(/[A-Z][A-Z_]+/g);
  const rendered=[...render().matchAll(/<option value="([A-Z_]+)"/g)].map(match=>match[1]);assert.deepEqual(rendered,declared);
 }
 probe.state.candidates=[{id:1,decision:'NEW',key:'REQ-1',title:'Original title',text:'Original requirement',type:'FUNCTIONAL',priority:1,criticality:'MEDIUM'}];
 probe.updateCandidateFromControl({target:{value:'MISSION_CRITICAL',closest:()=>({dataset:{candidateId:'1'}}),classList:{contains:name=>name==='candidate-criticality'}}});
 await probe.confirmImport();assert.equal(sent.projectId,1);assert.equal(sent.body.items[0].criticality,'MISSION_CRITICAL');assert.equal(sent.body.items[0].text,'Original requirement');
 probe.state.candidates[0].criticality='CRITICAL'; // A saved draft from the former UI.
 assert.match(probe.criticalitySelect('CRITICAL'), /value="MISSION_CRITICAL" selected/);
 await probe.confirmImport();assert.equal(sent.body.items[0].criticality,'MISSION_CRITICAL');
});
