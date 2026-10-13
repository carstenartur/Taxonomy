/** Native-browser regressions for the approved analysis UX fixes.
 * Run: CHROMIUM_EXECUTABLE=/usr/bin/chromium node --test .github/scripts/analysis-ux.browser.test.mjs
 * Uses the project's Playwright dependency; no provider/network calls are made.
 */
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';
const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT || path.resolve('taxonomy-app/src/main/resources');
let browser;
before(async () => {
    browser = await chromium.launch({headless: true,
    ...(process.env.CHROMIUM_EXECUTABLE ? {executablePath: process.env.CHROMIUM_EXECUTABLE} : {}),
    args: ['--no-sandbox', '--disable-dev-shm-usage']}); });
after(async () => { await browser?.close(); });
async function fixture(locale = 'de', width = 1100) {
    const page = await browser.newPage({viewport: {width, height: 768}});
    page.uiErrors=[]; page.on('pageerror',error=>page.uiErrors.push(error.message));
    await page.setContent(`<html lang="${locale}"><body><nav><span id="analysisStatusMenuSlot"></span></nav>
        <main id="tab-analyze"><textarea id="businessText">Zeiterfassung</textarea>
        <button id="analyzeBtn">Analyse</button><div id="analysisRecoveryHost"></div>
        <div id="statusArea"></div><div id="taxonomyTree"></div></main></body></html>`);
    await page.evaluate(() => {
        window.TaxonomyI18n = {t: (key, ...args) => key + (args.length ? ': ' + args.join(', ') : ''),
            getLocale: () => document.documentElement.lang};
        window.TaxonomyUtils = {escapeHtml: text => String(text ?? '').replace(/[&<>"']/g,
            c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
            isTaxonomyContainer: node => node.taxonomyRoot === true};
    });
    for (const css of ['taxonomy.css', 'taxonomy-analysis-recovery.css'])
        await page.addStyleTag({content:await readFile(path.join(resources, 'static/css', css), 'utf8')});
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-state.js')});
    return page;
}
async function mountRecovery(page) {
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-recovery-viewport.js')});
    await page.evaluate(() => {
        window.cancels = 0; window.scopeKey = 'workspace-a/run-a';
        window.recoveryUi = TaxonomyRecoveryViewport.mount({
            text: (de,en) => document.documentElement.lang === 'de' ? de : en,
            scopeKey: () => window.scopeKey,
            cancel: () => { window.cancels++; },
            open: () => recoveryUi.open('Details','Status', (body, make) => body.append(make('p','Evidence')), [])
        });
    });
}
for (const locale of ['de','en']) {
    test(`${locale}: analysis feedback does not cover the work area`, async () => {
        const page=await fixture(locale);
        try {
            await mountRecovery(page);
            await page.evaluate(() => recoveryUi.update('Running', true, true));
            assert.notEqual(await page.locator('#analysisRecoveryBar').evaluate(e => getComputedStyle(e).position), 'fixed');
            assert.equal(await page.locator('#analysisRecoveryHost #analysisRecoveryBar').count(), 1);
        } finally { await finish(page); }
    });
    test(`${locale}: dismiss survives progress and dialog close, never cancels`, async () => {
        const page=await fixture(locale);
        try {
            await mountRecovery(page);
            await page.evaluate(() => recoveryUi.update('Running', true, true));
            assert.equal(await page.locator('#analysisRecoveryDismiss').count(), 1, 'Explicit dismiss control is missing');
            await page.locator('#analysisRecoveryDismiss').click();
            await page.evaluate(() => recoveryUi.update('More work complete', true, true));
            assert.equal(await page.locator('#analysisRecoveryBar').isVisible(), false);
            await page.locator('#analysisRecoveryReopen').click();
            assert.equal(await page.locator('#analysisRecoveryDialog').isVisible(), true);
            await page.locator('#analysisRecoveryClose').click();
            assert.equal(await page.locator('#analysisRecoveryBar').isVisible(), false);
            assert.equal(await page.evaluate(() => window.cancels), 0);
            await page.evaluate(() => { window.scopeKey='workspace-a/run-b'; recoveryUi.update('New run',true,true); });
            assert.equal(await page.locator('#analysisRecoveryBar').isVisible(), true);
        } finally { await finish(page); }
    });
    test(`${locale}: terminal feedback is on demand and empty state removes its launcher`, async () => {
        const page=await fixture(locale);
        try {
            await mountRecovery(page);
            await page.evaluate(() => recoveryUi.update('Completed', false, false));
            assert.equal(await page.locator('#analysisRecoveryBar').isVisible(), false);
            assert.equal(await page.locator('#analysisRecoveryReopen').isVisible(), true);
            await page.evaluate(() => recoveryUi.update('', false, false));
            assert.equal(await page.locator('#analysisRecoveryReopen').isVisible(), false);
        } finally { await finish(page); }
    });
    test(`${locale}: imported evidence clears stale recovery UI`, async () => {
        const page=await fixture(locale);
        try {
            await page.evaluate(() => {
                window.__TaxonomyAnalysisSessionContext={S:TaxonomyState, runtime:{workspaceId:'a',analysisGeneration:0},
                    language:()=>document.documentElement.lang};
                TaxonomyState.analysisRecovery={state:'COMPLETED'};
            });
            await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-recovery-viewport.js')});
            await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-recovery.js')});
            // Exercise the same public import event the JSON import flow uses.
            await page.evaluate(() => {
                document.getElementById('analysisRecoveryStatus').textContent='Old run';
                document.getElementById('analysisRecoveryBar').hidden=false;
                document.dispatchEvent(new CustomEvent('taxonomy:analysis-evidence-imported'));
            });
            assert.equal(await page.locator('#analysisRecoveryStatus').textContent(), '');
            assert.equal(await page.locator('#analysisRecoveryBar').isVisible(), false);
        } finally { await finish(page); }
    });
}
async function renderNode(page) {
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-scoring.js')});
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-browse.js')});
    await page.evaluate(() => {
        const nodes=[{code:'BP-1000',name:'Unternehmensweite Zeiterfassung mit einem langen Knotennamen',children:[]}];
        Object.assign(TaxonomyState, {currentView:'list', taxonomyData:nodes,
            currentScores:{'BP-1000':70}, currentReasons:{'BP-1000':'Reason <script>not HTML</script>\nSecond line'},
            currentScoreDetails:{},storedBusinessText:'Zeiterfassung'});
        TaxonomyBrowse.renderView(nodes, TaxonomyState.currentScores);
    });
}
for (const width of [1100, 680, 390]) {
    test(`reason mouse target is stationary at ${width}px`, async () => {
        const page=await fixture('de',width);
        try {
            await renderNode(page);
            const reason=page.locator('.tax-reason-icon');
            const before=await reason.boundingBox();
            assert.ok(before);
            await page.mouse.move(before.x+before.width/2,before.y+before.height/2);
            await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
            const after=await reason.boundingBox();
            assert.ok(Math.abs(after.x-before.x) <= 1 && Math.abs(after.y-before.y) <= 1,
                `reason moved: ${JSON.stringify({before,after})}`);
        } finally { await finish(page); }
    });
}
test('reason is keyboard-accessible, readable as text and restores focus', async () => {
    const page=await fixture();
    try {
        await renderNode(page);
        const reason=page.locator('.tax-reason-icon');
        assert.equal(await reason.evaluate(e=>e.tagName), 'BUTTON');
        await reason.focus(); await page.keyboard.press('Enter');
        const dialog=page.locator('#taxonomyReasonDialog');
        assert.equal(await dialog.isVisible(), true);
        assert.match(await dialog.textContent(), /Reason <script>not HTML<\/script>/);
        assert.equal(await dialog.locator('script').count(), 0);
        await page.keyboard.press('Escape');
        assert.equal(await dialog.isVisible(), false);
        assert.equal(await reason.evaluate(e=>document.activeElement===e), true);
    } finally { await finish(page); }
});

const relationReport = {totalCalls:2, maxCalls:20, searchExhausted:true, sources:[], tasks:[],
    result:{edges:[{contribution:{source:{id:'BP-1000'},text:'Time capture',quote:'requirement'},
        target:{id:'UA-1000'},type:'SUPPORTED_BY',direction:'OUTGOING',
        evidence:{rationale:'Traceable <evidence>',contribution:'Service',quote:'need'}}],unfinished:[]}};
async function reportFixture() {
    const page=await fixture();
    await page.evaluate(()=>{
        const panel=document.createElement('section'); panel.id='relationSearchReportPanel'; panel.hidden=true;
        document.getElementById('tab-analyze').append(panel);
        const architecture=document.createElement('section'); architecture.id='architectureViewPanel';
        architecture.style.display='none';
        const content=document.createElement('div'); content.id='architectureViewContent';
        architecture.append(content); document.body.append(architecture);
        const placeholder=document.createElement('div'); placeholder.id='architecturePlaceholder';
        document.body.append(placeholder);
        const summary=document.createElement('button'); summary.id='viewSummary';
        summary.style.display='none'; document.body.append(summary);
        const list=document.createElement('button'); list.id='viewList';
        list.className='btn btn-outline-secondary'; list.dataset.view='list'; document.body.append(list);
        const expandCollapse=document.createElement('div'); expandCollapse.id='expandCollapseGroup';
        expandCollapse.style.display='none';
        expandCollapse.innerHTML='<button id="expandAll"></button><button id="collapseAll"></button>';
        document.body.append(expandCollapse);
    });
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-scoring.js')});
    return page;
}
test('relation evidence is visible without an architecture view and absent next results clear it', async()=>{
    const page=await reportFixture();
    try {
        assert.equal(await page.evaluate(()=>typeof TaxonomyScoring.acceptRelationSearchReport), 'function');
        await page.evaluate(report=>TaxonomyScoring.acceptRelationSearchReport({relationSearchReport:report,architectureView:null}),relationReport);
        const panel=page.locator('#relationSearchReportPanel');
        assert.equal(await panel.isVisible(),true);
        await panel.locator('summary').click();
        assert.match(await panel.textContent(),/BP-1000 → UA-1000/);
        assert.match(await panel.textContent(),/Traceable <evidence>/);
        assert.equal(await panel.locator('evidence').count(),0);
        // Changing the selected diagram must not delete independent evidence.
        await page.evaluate(()=>TaxonomyScoring.renderArchitectureView(null));
        assert.equal(await panel.isVisible(),true);
        await page.evaluate(()=>TaxonomyScoring.acceptRelationSearchReport({scores:{}}));
        assert.equal(await panel.isVisible(),false);
        assert.equal(await page.evaluate(()=>TaxonomyState.currentRelationSearchReport),null);
    } finally { await finish(page); }
});
test('legacy nested reports remain readable; explicit null never revives old evidence',async()=>{
    const page=await reportFixture();
    try {
        assert.equal(await page.evaluate(()=>typeof TaxonomyScoring.acceptRelationSearchReport), 'function');
        await page.evaluate(report=>TaxonomyScoring.acceptRelationSearchReport({architectureView:{relationSearchReport:report}}),relationReport);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(),true);
        await page.evaluate(report=>{
            const architectureView={relationSearchReport:report,includedElements:[],
                includedRelationships:[],anchors:[]};
            TaxonomyScoring.acceptRelationSearchReport({
                relationSearchReport:null,architectureView
            });
            TaxonomyScoring.renderArchitectureView(architectureView);
        },relationReport);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(),false);
        assert.equal(await page.locator('.relation-search-report').count(),0,
            'An explicit independent null must not revive the nested report');
    } finally { await finish(page); }
});
test('architecture-enabled result renders its relation report exactly once', async()=>{
    const page=await reportFixture();
    try {
        await page.evaluate(report=>{
            const result={relationSearchReport:report,architectureView:{
                relationSearchReport:report,includedElements:[],includedRelationships:[],anchors:[]}};
            window.architectureEnabledResult=result;
            TaxonomyScoring.acceptRelationSearchReport(result);
            TaxonomyScoring.renderArchitectureView(result.architectureView);
        },relationReport);
        assert.equal(await page.locator('.relation-search-report').count(),1);
        assert.equal(await page.locator('#relationSearchReportPanel .relation-search-report').count(),1);
        assert.equal(await page.locator('#architectureViewContent .relation-search-report').count(),0);
        await page.evaluate(()=>{
            document.getElementById('relationSearchReportPanel').remove();
            TaxonomyState.currentRelationSearchReport=null;
            TaxonomyScoring.renderArchitectureView(architectureEnabledResult.architectureView);
        });
        assert.equal(await page.locator('#architectureViewContent .relation-search-report').count(),1,
            'Legacy architecture-only presentation must retain its nested report');
    } finally { await finish(page); }
});
test('workspace draft carries the independent report even without a diagram',async()=>{
    const page=await reportFixture();
    try {
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-core.js')});
        const saved=await page.evaluate(report=>{
            TaxonomyState.currentRelationSearchReport=report; TaxonomyState.currentArchView=null;
            return __TaxonomyAnalysisSessionContext.currentPayload();
        },relationReport);
        assert.deepEqual(saved.relationSearchReport,relationReport);
    } finally { await finish(page); }
});
test('draft restore and EMPTY tombstone restore/clear independent evidence',async()=>{
    const page=await reportFixture();
    try {
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-core.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-ui.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-draft.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-browse.js')});
        const restored=await page.evaluate(report=>{
            TaxonomyState.taxonomyData=[{code:'BP-1000',name:'Time capture',children:[]}];
            __TaxonomyAnalysisSessionContext.applyDraft({version:1,payload:{businessText:'Zeiterfassung',lastAnalyzedText:'Zeiterfassung',
                scores:{'BP-1000':70},relationSearchReport:report,architectureView:null,draftState:'ACTIVE'}});
            return TaxonomyState.currentRelationSearchReport;
        },relationReport);
        assert.deepEqual(restored,relationReport);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(),true);
        await page.evaluate(report=>__TaxonomyAnalysisSessionContext.applyDraft({version:2,payload:{draftState:'EMPTY',relationSearchReport:report}}),relationReport);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(),false);
        assert.equal(await page.evaluate(()=>TaxonomyState.currentRelationSearchReport),null);
    } finally { await finish(page); }
});
test('legacy nested-only draft restores report context; explicit null takes precedence', async()=>{
    const page=await reportFixture();
    try {
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-core.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-ui.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-draft.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-browse.js')});
        await page.evaluate(report=>{
            TaxonomyState.taxonomyData=[{code:'BP-1000',name:'Time capture',children:[]}];
            __TaxonomyAnalysisSessionContext.applyDraft({version:3,payload:{businessText:'Zeiterfassung',
                scores:{'BP-1000':70},architectureView:{relationSearchReport:report,
                    includedElements:[],includedRelationships:[],anchors:[]}}});
        },relationReport);
        assert.deepEqual(await page.evaluate(()=>TaxonomyState.currentRelationSearchReport),relationReport);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(),true);
        await page.evaluate(report=>__TaxonomyAnalysisSessionContext.applyDraft({version:4,payload:{
            businessText:'Zeiterfassung',scores:{'BP-1000':70},relationSearchReport:null,
            architectureView:{relationSearchReport:report,includedElements:[],includedRelationships:[],anchors:[]}
        }}),relationReport);
        assert.equal(await page.evaluate(()=>TaxonomyState.currentRelationSearchReport),null);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(),false);
    } finally { await finish(page); }
});
test('draft data restores before the scoring renderer has loaded', async()=>{
    const page=await fixture();
    try {
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-core.js')});
        await page.evaluate(()=>{ __TaxonomyAnalysisSessionContext.MAX_RESTORE_ATTEMPTS=0; });
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-session-draft.js')});
        const saved=await page.evaluate(report=>{
            if (window.TaxonomyScoring) throw new Error('Fixture loaded the renderer too soon');
            __TaxonomyAnalysisSessionContext.applyDraft({version:1,payload:{draftState:'ACTIVE',scores:{'BP-1000':70},relationSearchReport:report}});
            return TaxonomyState.currentRelationSearchReport;
        },relationReport);
        assert.deepEqual(saved,relationReport);
    } finally { await finish(page); }
});
for (const width of [390, 320]) {
    test(`node actions remain reachable without horizontal overflow at ${width}px`,async()=>{
        const page=await fixture('de',width);
        try {
            await renderNode(page);
            const sizes=await page.locator('.tax-node-header').evaluate(e=>({width:e.clientWidth,scroll:e.scrollWidth}));
            assert.ok(sizes.scroll<=sizes.width+1,JSON.stringify(sizes));
            const action=page.locator('.tax-node-actions-toggle');
            assert.equal(await action.count(),1,'A stable compact action launcher is required');
            await action.click();
            const dialog=page.locator('#taxonomyActionsDialog');
            assert.equal(await dialog.isVisible(),true);
            await page.evaluate(()=>{ window.graphRequests=[]; window.TaxonomyGraph={openGraphExplorer:code=>graphRequests.push(code)}; });
            await dialog.locator('.graph-explore-btn').click();
            assert.deepEqual(await page.evaluate(()=>graphRequests),['BP-1000']);
            assert.equal(await dialog.isVisible(),false);
            await action.click(); await page.keyboard.press('Escape');
            assert.equal(await action.evaluate(e=>document.activeElement===e),true);
        } finally { await finish(page); }
    });
}

const relationProgress={completedSearches:2,totalSearches:8,unresolvedSearches:1,pendingSearches:5,assessedSources:1,totalSources:3,
    verifiedRelations:2,calls:4,maxCalls:20,step:'NAVIGATE',taxonomies:[],current:{sourceId:'BP-1000',targetRoot:'UA',type:'SUPPORTED_BY',direction:'OUTGOING',depth:1}};
async function progressFixture(locale='de'){
    const page=await fixture(locale);
    await page.evaluate(()=>{
        window.progressSnapshot={operationId:'ux-progress',sequence:1,status:'RUNNING',phase:'WAITING_RATE_LIMIT',
            startedAt:1000,lastActivityAt:2000,serverTime:3000,evaluatedNodes:1,databaseStorage:'FILE',indexStorage:'FILE',calls:[],memory:{percent:10},
            nodeProgress:{assessed:1,excluded:0,total:4,open:3,taxonomies:[{root:'BP',nameDe:'Geschäftsprozesse',nameEn:'Business processes',assessed:1,excluded:0,total:4,open:3}]}};
        window.TaxonomyAnalysisSessionApi={getRunStatus:async()=>({status:200,json:async()=>structuredClone(progressSnapshot)})};
        document.addEventListener('taxonomy:analysis-progress',event=>{window.observedSequence=event.detail.sequence;});
        TaxonomyState.taxonomyData=[{code:'BP-1000',name:'Zeiterfassung',children:[]},{code:'UA',name:'Anwendungen',children:[]}];
    });
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-progress.js')});
    return page;
}
for(const locale of ['de','en']){
    test(`${locale}: distributed relation work never appears complete just because root scoring finished`,async()=>{
        const page=await progressFixture(locale);try{
            const summary=await page.evaluate(()=>TaxonomyAnalysisProgress.workSummary({phase:'RELATIONS',cluster:{completedRoots:8,totalRoots:8,tasks:[{taskType:'RELATION_ANALYSIS',queued:4,running:2,completed:3,failed:1}]}}));
            assert.match(summary,locale==='de'?/Relations/:/Relationship/);
            assert.match(summary,/3/);assert.match(summary,/2/);assert.doesNotMatch(summary,/8 (von|of) 8/);
        }finally{await finish(page);}
    });
    test(`${locale}: actual waiting reason is not overwritten by the relations phase`,async()=>{
        const page=await progressFixture(locale);try{
            await page.evaluate(report=>{progressSnapshot.relationProgress=report;window.progressMonitor=TaxonomyAnalysisProgress.start('ux-progress');},relationProgress);
            await page.waitForFunction(()=>window.observedSequence===1);
            const title=await page.locator('#analysisLiveProgress > strong').textContent();
            assert.match(title,locale==='de'?/Ratenlimit/:/rate limit/);
            assert.match(await page.locator('#analysisWorkProgress').textContent(),/Zeiterfassung \(BP-1000\)/);
            assert.match(await page.locator('#analysisWorkProgress').textContent(),/Anwendungen \(UA\)/);
            await page.evaluate(()=>progressMonitor.stop());
        }finally{await finish(page);}
    });
    test(`${locale}: taxonomy progress details keep keyboard focus across genuine counter updates`,async()=>{
        const page=await progressFixture(locale);try{
            await page.evaluate(()=>{window.progressMonitor=TaxonomyAnalysisProgress.start('ux-progress');});
            await page.waitForFunction(()=>window.observedSequence===1);
            const summary=page.locator('#analysisWorkProgress details > summary');
            await summary.click();await summary.focus();
            await page.evaluate(()=>{progressSnapshot.sequence=2;progressSnapshot.nodeProgress.assessed=2;});
            await page.waitForFunction(()=>window.observedSequence===2);
            assert.equal(await summary.evaluate(e=>document.activeElement===e),true);
            assert.equal(await page.locator('#analysisWorkProgress details').getAttribute('open'),'');
            await page.evaluate(()=>progressMonitor.stop());
        }finally{await finish(page);}
    });
}

test('full analysis result pipeline retains relations with the architecture option off',async()=>{
    const page=await reportFixture();try{
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-browse.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-progress.js')});
        await page.evaluate(report=>{
            const checkbox=document.createElement('input');checkbox.id='includeArchitectureView';checkbox.type='checkbox';checkbox.checked=false;document.body.append(checkbox);
            TaxonomyState.taxonomyData=[{code:'BP-1000',name:'Zeiterfassung',children:[]}];
            window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'test-workspace'})};
            window.TaxonomyAnalysisSessionApi={getRunStatus:async id=>({status:200,json:async()=>({operationId:id,status:'COMPLETED',phase:'FINISHED',sequence:1,serverTime:3000,lastActivityAt:3000,elapsedMillis:1000,calls:[],evaluatedNodes:1})})};
            window.fetch=async(url,options)=>{
                if(url!=='/api/analyze')throw new Error('Unexpected request '+url);
                window.analysisRequest=JSON.parse(options.body);
                return {ok:true,json:async()=>({status:'SUCCESS',scores:{'BP-1000':70},rawScores:{'BP-1000':70},reasons:{},warnings:[],architectureView:null,relationSearchReport:report})};
            };
        },relationReport);
        await page.evaluate(()=>TaxonomyScoring.runAnalysis());
        assert.equal(await page.evaluate(()=>analysisRequest.includeArchitectureView),false);
        assert.deepEqual(await page.evaluate(()=>TaxonomyState.currentRelationSearchReport),relationReport);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(),true);
    }finally{await finish(page);}
});
test('same-operation START recovery retains its existing architecture and relation evidence', async()=>{
    const page=await reportFixture();
    try {
        await page.evaluate(report=>{
            const checkbox=document.createElement('input');checkbox.id='includeArchitectureView';
            checkbox.type='checkbox';document.body.append(checkbox);
            TaxonomyState.currentView='summary';
            TaxonomyState.currentArchView={
                relationSearchReport:report,includedElements:[],includedRelationships:[],anchors:[]};
            TaxonomyScoring.acceptRelationSearchReport({relationSearchReport:report});
            TaxonomyScoring.renderArchitectureView(TaxonomyState.currentArchView);
            document.getElementById('viewSummary').style.display='';
            window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'recovery-workspace'})};
            window.TaxonomyBrowse={clearStatus(){},renderView(){},showStatus(){},switchView(){}};
            window.TaxonomyAnalysisProgress={start:()=>({
                acceptsResult:()=>true,finish(){},transportFailed(){}
            })};
            window.fetch=async()=>({ok:true,json:async()=>({
                status:'SUCCESS',scores:{},reasons:{},warnings:[]
            })});
        },relationReport);
        const retained=await page.evaluate(()=>{
            const architecture=TaxonomyState.currentArchView;
            return TaxonomyScoring.runAnalysis({
                continuation:{continuationAction:'START'},recoveryManaged:true
            }).then(()=>({
                sameArchitecture:TaxonomyState.currentArchView===architecture,
                currentView:TaxonomyState.currentView,
                report:TaxonomyState.currentRelationSearchReport
            }));
        });
        assert.equal(retained.sameArchitecture,true);
        assert.equal(retained.currentView,'summary');
        assert.deepEqual(retained.report,relationReport);
        assert.equal(await page.locator('#architectureViewPanel').isVisible(),true,
            JSON.stringify(await page.locator('#architectureViewPanel').evaluate(element=>({
                display:getComputedStyle(element).display,text:element.textContent,rect:element.getBoundingClientRect().toJSON()
            }))));
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(),true);
    } finally { await finish(page); }
});

for (const entryPoint of ['runInteractiveAnalysis', 'runStreamingAnalysis']) {
    test(`${entryPoint}: starting a separate scoring run clears previous relation evidence`, async () => {
        const page = await reportFixture();
        try {
            await page.addScriptTag({path: path.join(resources, 'static/js/core/taxonomy-browse.js')});
            await page.evaluate(report => {
                TaxonomyState.taxonomyData = [{code: 'BP-1000', name: 'Zeiterfassung', children: []}];
                TaxonomyState.currentView = 'summary';
                TaxonomyState.currentArchView = {
                    relationSearchReport: report, includedElements: [], includedRelationships: [], anchors: []};
                TaxonomyScoring.acceptRelationSearchReport({relationSearchReport: report});
                TaxonomyScoring.renderArchitectureView(TaxonomyState.currentArchView);
                document.getElementById('viewSummary').style.display = '';
                // The transport is deliberately inert; this case tests the real start/reset path.
                window.EventSource = class { addEventListener() {} close() {} };
            }, relationReport);
            assert.equal(await page.locator('#relationSearchReportPanel').isVisible(), true);
            assert.equal(await page.locator('#architectureViewPanel').isVisible(), true);
            await page.evaluate(entryPoint => TaxonomyScoring[entryPoint](), entryPoint);
            assert.equal(await page.evaluate(() => TaxonomyState.currentRelationSearchReport), null);
            assert.equal(await page.evaluate(() => TaxonomyState.currentArchView), null);
            assert.equal(await page.evaluate(() => TaxonomyState.currentView), 'list');
            assert.equal(await page.locator('#viewList').evaluate(element =>
                element.classList.contains('btn-primary')), true);
            assert.equal(await page.locator('#expandCollapseGroup').isVisible(), true);
            assert.equal(await page.locator('#architectureViewPanel').isVisible(), false);
            assert.equal(await page.locator('#viewSummary').evaluate(element =>
                getComputedStyle(element).display === 'none'), true);
            assert.equal(await page.locator('#relationSearchReportPanel').isVisible(), false);
        } finally { await finish(page); }
    });
}

async function bootBrowseWithReport(page) {
    await page.evaluate(() => {
        for (const id of ['expandAll', 'collapseAll']) {
            if (document.getElementById(id)) continue;
            const button = document.createElement('button'); button.id = id; document.body.append(button);
        }
        const info = document.createElement('div'); info.id = 'aiProviderInfo'; document.body.append(info);
        const provider = document.createElement('select'); provider.id = 'providerSelect';
        provider.innerHTML = '<option value="">Default</option>'; document.body.append(provider);
        window.fetch = async url => ({ok: true, json: async () => String(url).includes('ai-status')
            ? {available: false, availableProviders: []}
            : String(url).includes('/account/me') ? {administrator: false, roles: ['USER']} : []});
    });
    await page.addScriptTag({path: path.join(resources, 'static/js/core/taxonomy-browse.js')});
    await page.evaluate(() => document.dispatchEvent(new Event('DOMContentLoaded')));
    await page.waitForFunction(() => document.querySelector('#providerSelect option[value="MANUAL"]'));
    await page.evaluate(report => {
        TaxonomyState.taxonomyData = [{code: 'BP-1000', name: 'Zeiterfassung', children: []}];
        TaxonomyState.currentScores = {'BP-1000': 70};
        TaxonomyState.lastAnalyzedText = 'Zeiterfassung';
        TaxonomyState.currentView = 'summary';
        TaxonomyState.currentArchView = {
            relationSearchReport: report, includedElements: [], includedRelationships: [], anchors: []};
        TaxonomyScoring.acceptRelationSearchReport({relationSearchReport: report});
        TaxonomyScoring.renderArchitectureView(TaxonomyState.currentArchView);
        document.getElementById('viewSummary').style.display = '';
        TaxonomyBrowse.switchView('summary');
    }, relationReport);
}

async function bootScoreImport(page, importedResult) {
    await page.evaluate(({result, oldReport}) => {
        document.body.insertAdjacentHTML('beforeend', `
            <button id="expandAll"></button><button id="collapseAll"></button>
            <button id="importJson"></button><input id="importJsonFile" type="file">
            <select id="providerSelect"><option value="">Default</option></select>
            <div id="aiProviderInfo"></div>
        `);
        window.importResult = result;
        window.importRequestBody = null;
        window.fetch = async (url, options = {}) => {
            if (url === '/api/scores/import') {
                window.importRequestBody = options.body;
                const imported = options.body === 'not-json'
                    ? {error: 'Malformed score import'} : window.importResult;
                return {ok: true, json: async () => imported};
            }
            if (url === '/api/taxonomy') {
                return {ok: true, status: 200, json: async () => [
                    {code: 'BP-1000', name: 'Zeiterfassung', children: []}
                ]};
            }
            if (url === '/api/ai-status') {
                return {ok: true, json: async () => ({available: false, availableProviders: []})};
            }
            if (url === '/api/account/me') {
                return {ok: true, json: async () => ({administrator: false, roles: ['USER']})};
            }
            throw new Error(`Unexpected test request: ${url}`);
        };
        window.setInterval = () => 0;
        TaxonomyState.taxonomyData = [{code: 'BP-1000', name: 'Zeiterfassung', children: []}];
        TaxonomyState.currentScores = {'BP-1000': 91};
        TaxonomyState.currentView = 'summary';
        TaxonomyState.currentArchView = {
            notes: ['OLD ARCHITECTURE EVIDENCE'],
            relationSearchReport: oldReport,
            includedElements: [], includedRelationships: [], anchors: []
        };
        TaxonomyScoring.acceptRelationSearchReport({relationSearchReport: oldReport});
        TaxonomyScoring.renderArchitectureView(TaxonomyState.currentArchView);
        document.getElementById('viewSummary').style.display = '';
    }, {result: importedResult, oldReport: relationReport});
    await page.addScriptTag({path: path.join(resources, 'static/js/core/taxonomy-analysis-session-core.js')});
    await page.addScriptTag({path: path.join(resources, 'static/js/core/taxonomy-browse.js')});
    await page.evaluate(() => document.dispatchEvent(new Event('DOMContentLoaded')));
    await page.waitForFunction(() => document.querySelector('#providerSelect option[value="MANUAL"]'));
}

const importedRelationReport = {...relationReport, totalCalls: 1};
const importedArchitecture = {
    notes: ['IMPORTED ARCHITECTURE EVIDENCE'],
    includedElements: [], includedRelationships: [], anchors: []
};
for (const [label, architectureFields] of [
    ['absent architecture', {}],
    ['explicit-null architecture', {architectureView: null}],
    ['replacement architecture', {architectureView: importedArchitecture}]
]) {
    test(`score import replaces stale architecture for ${label} and retains imported scores/evidence`, async () => {
        const page = await reportFixture();
        try {
            await bootScoreImport(page, {
                scores: {'BP-1000': 42},
                relationSearchReport: importedRelationReport,
                ...architectureFields
            });
            const file = {name: 'scores.json', mimeType: 'application/json',
                buffer: Buffer.from(JSON.stringify({scores: {'BP-1000': 42}}))};
            await page.locator('#importJsonFile').setInputFiles(file);
            await page.waitForFunction(() => TaxonomyState.lastAnalysisProvider === 'IMPORTED');

            const payload = await page.evaluate(() => __TaxonomyAnalysisSessionContext.currentPayload());
            assert.equal(await page.evaluate(() => TaxonomyState.currentScores['BP-1000']), 42);
            assert.deepEqual(payload.architectureView, architectureFields.architectureView || null);
            assert.deepEqual(payload.relationSearchReport, importedRelationReport);
            assert.equal(await page.locator('#architectureViewContent').textContent()
                .then(text => text.includes('OLD ARCHITECTURE EVIDENCE')), false);
            assert.equal(await page.locator('#architectureViewPanel').isVisible(),
                Boolean(architectureFields.architectureView));
            assert.equal(await page.locator('#viewSummary').evaluate(element =>
                getComputedStyle(element).display !== 'none'), Boolean(architectureFields.architectureView));
            if (architectureFields.architectureView) {
                assert.match(await page.locator('#architectureViewContent').textContent(),
                    /IMPORTED ARCHITECTURE EVIDENCE/);
            } else {
                assert.equal(await page.evaluate(() => TaxonomyState.currentView), 'list');
            }
            assert.equal(await page.evaluate(() => JSON.parse(window.importRequestBody).scores['BP-1000']), 42);
        } finally { await finish(page); }
    });
}

test('score import preserves legacy nested-only relationship evidence', async () => {
    const page = await reportFixture();
    const legacyArchitecture = {...importedArchitecture, relationSearchReport: importedRelationReport};
    try {
        await bootScoreImport(page, {scores: {'BP-1000': 42}, architectureView: legacyArchitecture});
        await page.locator('#importJsonFile').setInputFiles({name: 'legacy.json', mimeType: 'application/json',
            buffer: Buffer.from(JSON.stringify({scores: {'BP-1000': 42}}))});
        await page.waitForFunction(() => TaxonomyState.lastAnalysisProvider === 'IMPORTED');
        const payload = await page.evaluate(() => __TaxonomyAnalysisSessionContext.currentPayload());
        assert.deepEqual(payload.architectureView, legacyArchitecture);
        assert.deepEqual(payload.relationSearchReport, importedRelationReport);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(), true);
    } finally { await finish(page); }
});

test('malformed score import leaves existing architecture and scores untouched', async () => {
    const page = await reportFixture();
    try {
        await bootScoreImport(page, {error: 'Malformed score import'});
        await page.locator('#importJsonFile').setInputFiles({name: 'invalid.json',
            mimeType: 'application/json', buffer: Buffer.from('not-json')});
        await page.waitForFunction(() => window.importRequestBody === 'not-json');
        assert.equal(await page.evaluate(() => TaxonomyState.currentScores['BP-1000']), 91);
        assert.match(await page.locator('#architectureViewContent').textContent(),
            /OLD ARCHITECTURE EVIDENCE/);
        assert.equal(await page.evaluate(() => TaxonomyState.currentArchView.notes[0]),
            'OLD ARCHITECTURE EVIDENCE');
    } finally { await finish(page); }
});

test('manual scoring never retains relations from the previous automatic assessment', async () => {
    const page = await reportFixture();
    try {
        await bootBrowseWithReport(page);
        await page.locator('#providerSelect').selectOption('MANUAL');
        await page.locator('#analyzeBtn').click();
        assert.equal(await page.locator('.manual-score-input').count(), 1);
        assert.equal(await page.evaluate(() => TaxonomyState.currentRelationSearchReport), null);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(), false);
        await page.locator('.manual-score-input').fill('49');
        await page.locator('#manualApplyBtn').click();
        assert.equal(await page.evaluate(() => TaxonomyState.currentScores['BP-1000']), 49);
        assert.equal(await page.evaluate(() => TaxonomyState.currentArchView), null);
        assert.equal(await page.evaluate(() => TaxonomyState.currentView), 'list');
        assert.equal(await page.locator('#viewList').evaluate(element =>
            element.classList.contains('btn-primary')), true);
        assert.equal(await page.locator('#expandCollapseGroup').isVisible(), true);
        assert.equal(await page.locator('#architectureViewPanel').isVisible(), false);
        assert.equal(await page.locator('#viewSummary').evaluate(element =>
            getComputedStyle(element).display === 'none'), true);
    } finally { await finish(page); }
});
test('the explicit stale-result reset removes its independent relations too', async () => {
    const page = await reportFixture();
    try {
        await bootBrowseWithReport(page);
        await page.locator('#businessText').fill('Changed requirement');
        await page.locator('#statusArea .btn-warning').click();
        assert.equal(await page.evaluate(() => TaxonomyState.currentScores), null);
        assert.equal(await page.evaluate(() => TaxonomyState.currentRelationSearchReport), null);
        assert.equal(await page.evaluate(() => TaxonomyState.currentArchView), null);
        assert.equal(await page.evaluate(() => TaxonomyState.currentView), 'list');
        assert.equal(await page.locator('#viewList').evaluate(element =>
            element.classList.contains('btn-primary')), true);
        assert.equal(await page.locator('#expandCollapseGroup').isVisible(), true);
        assert.equal(await page.locator('#architectureViewPanel').isVisible(), false);
        assert.equal(await page.locator('#viewSummary').evaluate(element =>
            getComputedStyle(element).display === 'none'), true);
        assert.equal(await page.locator('#relationSearchReportPanel').isVisible(), false);
    } finally { await finish(page); }
});

async function finish(page) {
    await page.close();
    assert.deepEqual(page.uiErrors, [], 'Unexpected browser script errors');
}
