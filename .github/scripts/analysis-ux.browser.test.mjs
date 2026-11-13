/** Native-browser regressions for the approved analysis UX fixes.
 * Run: CHROMIUM_EXECUTABLE=/usr/bin/chromium node --test .github/scripts/analysis-ux.browser.test.mjs
 * Uses the project's Playwright dependency; no provider/network calls are made.
 */
import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import os from 'node:os';
import { installBrowserApiFixture } from './canonical-browser-fixture.mjs';
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
    await page.route('http://taxonomy.fixture/',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><html></html>'}));
    await page.route('http://taxonomy.fixture/api/**',route=>route.fulfill({contentType:'application/json',body:'[]'}));
    await page.goto('http://taxonomy.fixture/');
    await page.setContent(`<html lang="${locale}"><body><nav><span id="analysisStatusActions"></span></nav>
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
    // Load real transport and named API boundaries for migrated browse consumers.
    await installBrowserApiFixture(page, resources);
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-state.js')});
    return page;
}

async function architectureFixture(withGraph = true, withoutD3 = false) {
    const page = await fixture();
    await page.evaluate(() => {
        document.body.insertAdjacentHTML('beforeend', '<div id="architectureViewPanel"><div id="architectureViewContent"></div></div><div id="architecturePlaceholder"></div>');
    });
    if (withGraph) {
        const source = process.env.TAXONOMY_TEST_D3_SCRIPT
            ? await readFile(process.env.TAXONOMY_TEST_D3_SCRIPT, 'utf8')
            : execFileSync('unzip', ['-p', path.join(process.env.MAVEN_REPO_LOCAL || path.join(os.homedir(), '.m2/repository'),
                'org/webjars/npm/d3/7.9.0/d3-7.9.0.jar'), 'META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js'], {encoding:'utf8'});
        if (!withoutD3) await page.addScriptTag({content:source});
        for (const css of ['taxonomy-impact-map.css', 'shared/architecture-diagram.css'])
            await page.addStyleTag({path:path.join(resources,'static/css',css)});
        for (const script of ['shared/architecture-diagram.js', 'shared/taxonomy-impact-map.js'])
            await page.addScriptTag({path:path.join(resources,'static/js',script)});
    }
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-scoring.js')});
    return page;
}

for (const count of [1, 2]) {
    test(`small architecture with ${count} element(s) starts in the common interactive graph`, async () => {
        const page = await architectureFixture();
        try {
            await page.evaluate(count => {
                const includedElements = Array.from({length:count}, (_,i) => ({nodeCode:'BP-'+i,
                    title:'Element '+i,taxonomySheet:'BP',relevance:0.8,anchor:true,selectedForImpact:true}));
                const includedRelationships = count === 2 ? [{sourceCode:'BP-0',targetCode:'BP-1',
                    relationType:'SUPPORTS',propagatedRelevance:0.8,relationCategory:'impact'}] : [];
                TaxonomyScoring.renderArchitectureView({includedElements,includedRelationships,anchors:includedElements});
            }, count);
            await page.waitForFunction(() => document.querySelector('.impact-map-workbench')?.dataset.fitState === 'complete', null, {timeout:3000});
            assert.equal(await page.locator('#impactGraphView').isVisible(), true);
            assert.equal(await page.locator('#impactSwimView').isVisible(), false);
            assert.equal(await page.locator('#impactGraphView .impact-map-node').count(), count);
            await page.locator('#impactGraphView [data-node-id="BP-0"]').click();
            assert.match(await page.locator('.impact-map-details').textContent(), /Element 0/);
            await page.locator('.impact-view-btn[data-mode="swimlane"]').click();
            assert.equal(await page.locator('#impactSwimView').isVisible(), true);
            await page.locator('.impact-view-btn[data-mode="graph"]').click();
            assert.equal(await page.locator('#impactGraphView').isVisible(), true);
        } finally { await finish(page); }
    });
}

for (const unavailable of ['adapter', 'd3']) test(`fallback when the graph ${unavailable} is unavailable keeps the layer view usable`, async () => {
    const page = await architectureFixture(unavailable !== 'adapter', unavailable === 'd3');
    try {
        await page.evaluate(() => TaxonomyScoring.renderArchitectureView({includedElements:[
            {nodeCode:'BP-0',title:'Element',taxonomySheet:'BP',relevance:0.8,anchor:true}
        ],includedRelationships:[],anchors:[]}));
        assert.equal(await page.locator('#impactSwimView').isVisible(), true);
        assert.equal(await page.locator('#impactGraphView').isVisible(), false);
        assert.equal(await page.locator('.impact-view-btn[data-mode="graph"]').isDisabled(), true);
    } finally { await finish(page); }
});

test('replacing or clearing an architecture disposes the old graph lifecycle', async () => {
    const page = await architectureFixture();
    try {
        await page.evaluate(() => {
            const add = document.addEventListener.bind(document), remove = document.removeEventListener.bind(document);
            window.graphListenerCount = 0;
            document.addEventListener = (type, ...args) => {
                if (type === 'fullscreenchange') graphListenerCount++;
                return add(type, ...args);
            };
            document.removeEventListener = (type, ...args) => {
                if (type === 'fullscreenchange') graphListenerCount--;
                return remove(type, ...args);
            };
            window.testArchitecture = {includedElements:[{nodeCode:'BP-0',title:'Element',
                taxonomySheet:'BP',relevance:0.8,anchor:true}],includedRelationships:[],anchors:[]};
            TaxonomyScoring.renderArchitectureView(testArchitecture);
        });
        assert.equal(await page.evaluate(() => graphListenerCount), 1);
        await page.evaluate(() => TaxonomyScoring.renderArchitectureView(testArchitecture));
        assert.equal(await page.evaluate(() => graphListenerCount), 1);
        await page.evaluate(() => TaxonomyScoring.renderArchitectureView(null));
        assert.equal(await page.evaluate(() => graphListenerCount), 0);
    } finally { await finish(page); }
});
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
test('node Graph action reveals the Graph page and an initial result through the real action dialog', async () => {
    const page = await fixture();
    try {
        await renderNode(page);
        const template = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
        const graph = template.slice(template.indexOf('<div id="tab-graph"'),
            template.indexOf('</div><!-- /tab-graph -->') + '</div>'.length);
        await page.evaluate(graph => {
            const right = document.createElement('div'); right.id = 'rightPanel'; right.innerHTML = graph;
            const left = document.createElement('div'); left.id = 'leftPanel';
            left.append(document.getElementById('taxonomyTree'));
            document.body.append(left, right);
            window.graphQueries = [];
            window.graphContext = {repositoryId:'fixture-repo',workspaceScopeKey:'__shared__',branch:'fixture',commit:'a'.repeat(40)};
            window.AnalysisApi = { captureContext: async () => graphContext, graphUpstream: async (code, hops) => {
                window.graphQueries.push({code, hops});
                return {originNodeCode: code, direction: 'UPSTREAM', maxHops: hops,
                    neighbors: [], traversedRelationships: [], notes: [], evidence: {...graphContext,commitId:graphContext.commit}};
            }};
        }, graph);
        await page.addStyleTag({content: '.d-none { display:none !important; }'});
        await page.addScriptTag({content: template.match(/<!-- Page Navigation Script -->\s*<script>([\s\S]*?)<\/script>/)[1]});
        await page.addScriptTag({path: path.join(resources, 'static/js/shared/taxonomy-graph.js')});
        await page.locator('.tax-node-actions-toggle').click();
        await page.locator('dialog button').filter({hasText: 'browse.btn.graph'}).click();
        assert.equal(await page.locator('#tab-graph').isVisible(), true);
        assert.equal(await page.locator('#graphNodeInput').inputValue(), 'BP-1000');
        assert.deepEqual(await page.evaluate(() => window.graphQueries), [{code: 'BP-1000', hops: 2}]);
        assert.equal(await page.locator('#graphNodeInput').evaluate(el => el === document.activeElement), true);
        assert.equal(await page.locator('#graphResultsContent [role=status]').isVisible(), true);
        assert.equal(await page.locator('dialog[open]').count(), 0);
        await page.evaluate(() => {
            const nodes = [...TaxonomyState.taxonomyData, {code:'BP-1001', name:'Safety reporting', children:[]}];
            TaxonomyState.taxonomyData = nodes;
            TaxonomyBrowse.renderView(nodes, TaxonomyState.currentScores);
        });
        assert.equal(await page.evaluate(() => graphQueries.length), 1, 'Rendering does not query the graph');
        const next = page.locator('.tax-node[data-code="BP-1001"]');
        await next.locator(':scope > .tax-node-header').click();
        assert.equal(await page.locator('#graphNodeInput').inputValue(), 'BP-1001');
        assert.deepEqual(await page.evaluate(() => graphQueries), [
            {code:'BP-1000',hops:2}, {code:'BP-1001',hops:2}]);
        assert.equal(await page.locator('#graphNodeInput').evaluate(el => el === document.activeElement), false,
            'Linked selection keeps keyboard focus on the left');
        await next.focus();
        await page.keyboard.press('Home');
        assert.equal(await page.locator('#graphNodeInput').inputValue(), 'BP-1000');
    } finally { await finish(page); }
});

async function searchFixture(locale = 'de', withArchitecture = false, delayedTranslations = false) {
    const page = withArchitecture ? await architectureFixture() : await fixture(locale);
    const template = await readFile(path.join(resources, 'templates/index.html'), 'utf8');
    const messages = {};
    for (const name of ['messages.properties', 'messages_search.properties',
        ...(locale === 'de' ? ['messages_de.properties', 'messages_search_de.properties'] : [])]) {
        for (const line of (await readFile(path.join(resources, 'i18n', name), 'utf8')).split('\n')) {
            const entry = line.match(/^([^#!\s][^=]*)=(.*)$/);
            if (entry) messages[entry[1]] = entry[2].replace(/\\u([\da-f]{4})/gi,
                (_, code) => String.fromCharCode(parseInt(code, 16)));
        }
    }
    await page.evaluate(({template, messages, locale, delayedTranslations}) => {
        document.documentElement.lang = locale;
        window.searchMessagesReady = !delayedTranslations;
        window.TaxonomyI18n.t = (key, ...args) => (searchMessagesReady && messages[key] || key)
            .replace(/\{(\d+)\}/g, (_, index) => String(args[index] ?? ''));
        const source = new DOMParser().parseFromString(template, 'text/html');
        const left = document.createElement('div'); left.id = 'leftPanel';
        left.append(source.getElementById('searchPanel'), document.getElementById('taxonomyTree'));
        document.body.append(left, source.getElementById('tab-graph'));
        document.getElementById('tab-graph').classList.remove('d-none');
        document.getElementById('searchPanel').open = true;
        window.searchRequests = [];
        window.graphQueries = [];
        window.embeddingStatus = {available: true, graphReady: true};
        window.searchNodes = [{code: 'BP-1000', nameEn: 'Time capture'},
            {code: 'BP-1001', nameEn: 'Safety reporting'}, {code: 'BP-1002', nameEn: 'Outside architecture'}];
        window.__taxonomyFixtureFetch = async url => {
            if (url === '/api/embedding/status') return {ok: true, json: async () => embeddingStatus};
            searchRequests.push(url);
            return {ok: true, json: async () => searchNodes};
        };
        function query(kind, code, hops) {
            graphQueries.push({kind, code, hops});
            return Promise.resolve({originNodeCode: code, direction: kind.toUpperCase(), maxHops: hops,
                neighbors: [], traversedRelationships: [], notes: [], evidence: {...graphContext,commitId:graphContext.commit}});
        }
        window.graphContext = {repositoryId:'fixture-repo',workspaceScopeKey:'__shared__',branch:'fixture',commit:'a'.repeat(40)};
        window.AnalysisApi = {captureContext: async () => graphContext, graphUpstream: (code, hops) => query('upstream', code, hops),
            graphDownstream: (code, hops) => query('downstream', code, hops)};
    }, {template, messages, locale, delayedTranslations});
    if (!withArchitecture) await page.addScriptTag({path: path.join(resources, 'static/js/core/taxonomy-scoring.js')});
    for (const script of ['shared/taxonomy-graph.js',
        'shared/taxonomy-search.js', 'shared/architecture-workspace.js']) {
        await page.addScriptTag({path: path.join(resources, 'static/js', script)});
    }
    await page.evaluate(() => document.dispatchEvent(new Event('DOMContentLoaded')));
    await page.addScriptTag({path: path.join(resources, 'static/js/core/taxonomy-browse.js')});
    await page.evaluate(() => {
        TaxonomyState.currentView = 'list';
        TaxonomyState.taxonomyData = searchNodes.map(node => ({...node, name: node.nameEn, children: []}));
        TaxonomyBrowse.renderView(TaxonomyState.taxonomyData, {});
    });
    return page;
}

for (const locale of ['de', 'en']) test(`${locale}: search explains accepted input for each mode and submits natural language unchanged`, async () => {
    const page = await searchFixture(locale);
    try {
        const help = page.locator('#searchInputHelp');
        assert.equal(await help.count(), 1, 'Mode guidance is visible beside the search controls');
        assert.equal(await help.isVisible(), true);
        assert.match(await page.locator('#searchInput').getAttribute('aria-describedby'), /\bsearchInputHelp\b/);
        const prompts = locale === 'de'
            ? {fulltext: /Stichwörter.*Knoten.?Code/i, semantic: /natürlicher Sprache.*keine.*Syntax/i,
                hybrid: /natürlicher Sprache.*Volltext/i, graph: /natürlicher Sprache.*Relation/i}
            : {fulltext: /keywords.*node code/i, semantic: /natural language.*no.*syntax/i,
                hybrid: /natural language.*full.text/i, graph: /natural language.*relation/i};
        for (const mode of ['fulltext', 'semantic', 'hybrid', 'graph']) {
            await page.locator('#searchModeSelect').selectOption(mode);
            assert.match(await help.textContent(), prompts[mode]);
            assert.doesNotMatch(await page.locator('#searchInput').getAttribute('placeholder'), /^search\./,
                'The example must be translated rather than exposing an i18n key');
        }
        await page.locator('#searchModeSelect').selectOption('semantic');
        const query = locale === 'de' ? 'Wie erfassen wir Arbeitszeiten sicher?' : 'How can we record working time securely?';
        await page.locator('#searchInput').fill(query);
        await page.locator('#searchInput').press('Enter');
        await page.locator('.search-result-item').first().waitFor();
        assert.match(await page.locator('.search-result-orientation').innerText(),
            locale === 'de' ? /Modus: Semantisch/ : /Mode: Semantic/,
            'Active-search information uses the same localized mode name as the selector');
        assert.equal(await page.evaluate(() => new URL(searchRequests[0], 'https://taxonomy.test').searchParams.get('q')), query);
        assert.match(await page.evaluate(() => searchRequests[0]), /^\/api\/search\/semantic\?/);
        await page.evaluate(async () => {
            embeddingStatus = {available: false, graphReady: false};
            TaxonomySearch.checkEmbeddingStatus();
        });
        await page.waitForFunction(() => document.getElementById('searchModeSelect').value === 'fulltext');
        assert.match(await help.textContent(), prompts.fulltext, 'Readiness fallback explains the mode actually selected');
        assert.equal(await page.locator('#searchInput').inputValue(), query);
    } finally { await finish(page); }
});

test('search guidance uses translations that arrive after the search controls initialize', async () => {
    const page = await searchFixture('de', false, true);
    try {
        await page.locator('#searchModeSelect').selectOption('semantic');
        await page.evaluate(() => {
            searchMessagesReady = true;
            document.dispatchEvent(new CustomEvent('taxonomy-i18n-loaded', {detail: {locale: 'de'}}));
        });
        assert.match(await page.locator('#searchInputHelp').textContent(), /natürlicher Sprache.*keine.*Syntax/i);
        assert.match(await page.locator('#searchInput').getAttribute('placeholder'), /Wie erfassen wir/);
    } finally { await finish(page); }
});

test('search result activation and next-result navigation select the graph without a focus or context detour', async () => {
    const page = await searchFixture();
    try {
        await page.locator('#graphMaxHops').selectOption('3');
        await page.locator('#graphNodeInput').fill('BP-1002');
        await page.locator('#graphDownstreamBtn').click();
        await page.locator('#searchInput').fill('safety');
        await page.locator('#searchBtn').click();
        const result = page.locator('.search-result-item').first();
        await result.focus();
        await result.press('Enter');
        await page.waitForFunction(() => TaxonomySearch.resultDiagnostics().navigationFocusConfirmed);
        assert.equal(await page.locator('#graphNodeInput').inputValue(), 'BP-1000');
        assert.equal(await page.locator('.tax-node[data-code="BP-1000"]').evaluate(node => node === document.activeElement), true);
        const context = await page.evaluate(() => ({href: location.href, view: TaxonomyState.currentView,
            query: document.getElementById('searchInput').value}));
        await page.locator('[data-search-result-nav="next"]').click();
        await page.waitForFunction(() => {
            const state = TaxonomySearch.resultDiagnostics();
            return state.currentIndex === 1 && state.navigationAction === 'next'
                && state.navigationFocusConfirmed
                && document.activeElement === document.querySelector('.search-result-item[data-result-index="1"]');
        });
        assert.equal(await page.locator('#graphNodeInput').inputValue(), 'BP-1001',
            'Graph selection must follow the hit even when focus stays in the result list');
        assert.equal(await page.locator('.search-result-item[data-result-index="1"]').evaluate(node => node === document.activeElement), true);
        assert.deepEqual(await page.evaluate(() => graphQueries), [
            {kind: 'downstream', code: 'BP-1002', hops: 3},
            {kind: 'downstream', code: 'BP-1000', hops: 3},
            {kind: 'downstream', code: 'BP-1001', hops: 3}
        ]);
        assert.deepEqual(await page.evaluate(() => ({href: location.href, view: TaxonomyState.currentView,
            query: document.getElementById('searchInput').value})), context);
    } finally { await finish(page); }
});

for (const mode of ['overview', 'focus', 'hidden-context']) test(`search retains the ${mode} analysis diagram while selecting an included hit in Graph Explorer`, async () => {
    const page = await searchFixture('de', true);
    try {
        await page.evaluate(async mode => {
            TaxonomyState.currentArchView = {includedElements: searchNodes.slice(0, 2).map((node, index) => ({
                nodeCode: node.code, title: node.nameEn, taxonomySheet: 'BP', relevance: 0.8,
                anchor: index === 0 || mode !== 'hidden-context', hopDistance: index
            })), includedRelationships: [], anchors: []};
            TaxonomyScoring.renderArchitectureView(TaxonomyState.currentArchView);
            const diagram = document.getElementById('impactGraphView').__taxonomyImpactMapDiagram;
            window.searchWorkspace = TaxonomyArchitectureWorkspaceController.create({initial: {
                kind: 'analysis', branch: 'review/a', element: 'BP-1000', revision: 17},
                adapters: {analysis: {activate: async () => {}, getData: () => TaxonomyState.currentArchView,
                    getDiagram: () => diagram, select: id => diagram.select(id)}}});
            await searchWorkspace.open('analysis');
            window.TaxonomyArchitectureWorkspace = searchWorkspace;
            diagram.select('BP-1000');
            diagram.setSearch('Time');
        }, mode);
        await page.waitForFunction(() => document.querySelector('.impact-map-workbench')?.dataset.fitState === 'complete');
        await page.evaluate(mode => {
            const diagram = TaxonomyArchitectureWorkspace.getDiagram();
            if (mode === 'focus') diagram.focus('BP-1000');
            if (mode === 'hidden-context') diagram.setContext(false);
        }, mode);
        await page.waitForFunction(() => document.querySelector('.impact-map-workbench')?.dataset.fitState === 'complete');
        if (mode !== 'overview') {
            assert.equal(await page.evaluate(() => TaxonomyArchitectureWorkspace.getDiagram().getModel().nodeIds.has('BP-1001')), false,
                'The included search hit is absent from the focused or filtered diagram');
        }
        async function architectureState() {
            return page.evaluate(() => ({
                session: TaxonomyArchitectureWorkspace.getDiagram().captureSession(),
                source: TaxonomyArchitectureWorkspace.getSource(),
                data: JSON.stringify(TaxonomyState.currentArchView),
                href: location.href
            }));
        }
        const before = await architectureState();
        await page.locator('#searchInput').fill('safety');
        await page.locator('#searchBtn').click();
        await page.locator('.search-result-item[data-result-index="1"]').click();
        await page.waitForFunction(() => TaxonomySearch.resultDiagnostics().currentIndex === 1
            && TaxonomySearch.resultDiagnostics().navigationFocusConfirmed);
        assert.equal(await page.locator('#graphNodeInput').inputValue(), 'BP-1001');
        assert.deepEqual(await architectureState(), before,
            'Search must preserve architecture selection, focus, filters, viewport and source for an included hit');
        await page.locator('.search-result-item[data-result-index="2"]').click();
        await page.waitForFunction(() => TaxonomySearch.resultDiagnostics().currentIndex === 2
            && TaxonomySearch.resultDiagnostics().navigationFocusConfirmed);
        assert.equal(await page.locator('#graphNodeInput').inputValue(), 'BP-1002');
        assert.deepEqual(await architectureState(), before,
            'An absent catalogue hit must also preserve the architecture');
    } finally { await finish(page); }
});

for (const kind of ['working', 'snapshot']) test(`search retains the ${kind} source selection while preparing Graph Explorer`, async () => {
    const page = await searchFixture();
    try {
        await page.evaluate(async kind => {
            window.retainedArchitecture = {includedElements: [{nodeCode: 'BP-1000'}], draft: 'unsaved form'};
            window.TaxonomyArchitectureWorkspace = TaxonomyArchitectureWorkspaceController.create({
                initial: {kind, element: 'BP-1002', branch: 'review/a', revision: 17, snapshotId: 'snapshot-a'},
                adapters: {[kind]: {activate: async () => {}, getData: () => retainedArchitecture}}
            });
            await TaxonomyArchitectureWorkspace.open(kind);
        }, kind);
        const before = await page.evaluate(() => ({source: TaxonomyArchitectureWorkspace.getSource(),
            data: JSON.stringify(retainedArchitecture), href: location.href}));
        await page.locator('#searchInput').fill('time');
        await page.locator('#searchBtn').click();
        await page.locator('.search-result-item').first().click();
        assert.equal(await page.locator('#graphNodeInput').inputValue(), 'BP-1000');
        assert.deepEqual(await page.evaluate(() => ({source: TaxonomyArchitectureWorkspace.getSource(),
            data: JSON.stringify(retainedArchitecture), href: location.href})), before);
    } finally { await finish(page); }
});
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
    test(`${locale}: eight shared taxonomy bars retain catalogue order and fit desktop and mobile widths`,async()=>{
        const page=await progressFixture(locale);try{
            if(process.env.BOOTSTRAP_CSS) await page.addStyleTag({path:process.env.BOOTSTRAP_CSS});
            const roots=['BP','BR','CP','CI','CO','CR','IP','UA'];
            await page.evaluate(roots=>{
                TaxonomyState.taxonomyData=roots.map(code=>({code,name:code,children:code==='BP'
                    ? [{code:'source-time-capture',taxonomyRoot:'BP',nameDe:'Zeiterfassung',nameEn:'Time tracking'}] : []}));
                const taxonomies=roots.map(root=>({root,nameDe:'Teiltaxonomie '+root,nameEn:'Taxonomy '+root,
                    total:100,assessed:35,excluded:25,open:40,positive:20,allocatedPercent:37.5,rootPercent:80}));
                progressSnapshot.phase='RELATIONS';
                progressSnapshot.nodeProgress={total:800,assessed:280,excluded:200,open:320,taxonomies:taxonomies.reverse()};
                progressSnapshot.relationProgress={totalSources:40,assessedSources:32,totalSearches:80,
                    completedSearches:32,unresolvedSearches:8,pendingSearches:40,calls:12,maxCalls:24,
                    verifiedRelations:7,step:'NAVIGATE',
                    current:{sourceId:'source-time-capture',targetRoot:'IP',type:'CONSUMES',direction:'OUTGOING',depth:1,candidates:['IP-1']},
                    taxonomies:roots.slice().reverse().map(root=>({root,total:10,completed:4,unresolved:1,pending:5})),
                    sourceTaxonomies:roots.slice().reverse().map(root=>({root,total:5,completed:2,unresolved:1}))};
                window.progressMonitor=TaxonomyAnalysisProgress.start('ux-progress');
            },roots);
            await page.waitForFunction(()=>window.observedSequence===1);
            const bars=page.locator('#analysisWorkProgress progress[data-taxonomy-root]');
            assert.equal(await bars.count(),8);
            assert.deepEqual(await bars.evaluateAll(elements=>elements.map(e=>e.dataset.taxonomyRoot)),roots);
            assert.equal(await page.locator('progress[data-progress-kind="relations"][data-taxonomy-root]').count(),0);
            assert.equal(await page.locator('#analysisProgressDiagnostics').evaluate(e=>e.open),false);
            const first=bars.first();
            assert.equal(await first.getAttribute('value'),'60');
            assert.equal(await first.getAttribute('max'),'100');
            assert.equal(await first.evaluate(e=>e.position),0.6);
            assert.equal(await first.getAttribute('data-relations-completed'),'2');
            assert.equal(await first.getAttribute('data-relations-unresolved'),'1');
            assert.equal(await page.locator('.analysis-work-meter[data-active="true"] progress').getAttribute('data-taxonomy-root'),'BP');
            assert.match(await first.getAttribute('aria-valuetext'),locale==='de'?/37,5%.*80%/:/37\.5%.*80%/);
            assert.equal(await page.locator('#analysis-taxonomy-counts-BP dt').count(),4);
            for(const width of [1280,390]){
                await page.setViewportSize({width,height:900});
                assert.equal(await bars.evaluateAll(elements=>elements.every(e=>{
                    const b=e.getBoundingClientRect();return b.width>0&&b.left>=0&&b.right<=innerWidth;
                })),true);
                assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
                if(process.env.TAXONOMY_UI_OUTPUT_ROOT){
                    const directory=path.join(process.env.TAXONOMY_UI_OUTPUT_ROOT,'analysis-progress');
                    await mkdir(directory,{recursive:true});
                    await page.screenshot({path:path.join(directory,`shared-taxonomy-bars-${locale}-${width}.png`),fullPage:true});
                }
            }
            await page.evaluate(()=>progressMonitor.stop());
        }finally{await finish(page);}
    });
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
            const details=page.locator('#analysisWorkProgress details');
            const summary=page.locator('#analysisWorkProgress details > summary');
            const taxonomyBar=details.locator('progress[data-taxonomy-root="BP"]');
            assert.equal(await details.evaluate(e=>e.open),true,'Taxonomy progress starts expanded');
            assert.equal(await taxonomyBar.isVisible(),true);
            assert.equal(await taxonomyBar.getAttribute('value'),'1');
            await summary.focus();await page.keyboard.press('Space');
            assert.equal(await details.evaluate(e=>e.open),false,'Keyboard closes the initially expanded details');
            await page.evaluate(()=>{
                progressSnapshot.sequence=2;
                Object.assign(progressSnapshot.nodeProgress,{assessed:2,open:2});
                Object.assign(progressSnapshot.nodeProgress.taxonomies[0],{assessed:2,open:2});
            });
            await page.waitForFunction(()=>window.observedSequence===2);
            assert.equal(await summary.evaluate(e=>document.activeElement===e),true);
            assert.equal(await details.evaluate(e=>e.open),false,'Counter updates preserve the user’s closed disclosure');
            assert.equal(await taxonomyBar.isVisible(),false);
            assert.equal(await taxonomyBar.getAttribute('value'),'2');
            await page.keyboard.press('Space');
            assert.equal(await details.evaluate(e=>e.open),true,'Keyboard reopens the focused details');
            await page.evaluate(()=>{
                progressSnapshot.sequence=3;
                Object.assign(progressSnapshot.nodeProgress,{assessed:3,open:1});
                Object.assign(progressSnapshot.nodeProgress.taxonomies[0],{assessed:3,open:1});
            });
            await page.waitForFunction(()=>window.observedSequence===3);
            assert.equal(await summary.evaluate(e=>document.activeElement===e),true);
            assert.equal(await details.evaluate(e=>e.open),true,'Counter updates preserve the user’s reopened disclosure');
            assert.equal(await taxonomyBar.isVisible(),true);
            assert.equal(await taxonomyBar.getAttribute('value'),'3');
            await page.evaluate(()=>progressMonitor.stop());
        }finally{await finish(page);}
    });
}

for(const currentView of ['list','tree']) test(`a completed architecture analysis preserves the selected ${currentView} view`,async()=>{
    const page=await reportFixture();try{
        const result=await page.evaluate(async selected=>{
            const checkbox=document.createElement('input');checkbox.id='includeArchitectureView';checkbox.type='checkbox';checkbox.checked=true;document.body.append(checkbox);
            Object.assign(TaxonomyState,{currentView:selected,taxonomyData:[{code:'BP-1',name:'Time capture',children:[]}]});
            window.switchedViews=[];
            window.TaxonomyBrowse={clearStatus(){},showStatus(){},renderView(){},switchView(view){switchedViews.push(view);TaxonomyState.currentView=view;}};
            window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'test-workspace'})};
            window.TaxonomyAnalysisProgress={start:()=>({acceptsResult:()=>true,finish(){}})};
            window.__taxonomyFixtureFetch=async()=>({ok:true,json:async()=>({status:'SUCCESS',scores:{'BP-1':70},rawScores:{'BP-1':70},
                reasons:{},warnings:[],architectureView:{includedElements:[],includedRelationships:[],anchors:[]}})});
            await TaxonomyScoring.runAnalysis();
            return {current:TaxonomyState.currentView,switched:switchedViews,hasArchitecture:!!TaxonomyState.currentArchView};
        },currentView);
        assert.equal(result.current,currentView);
        assert.deepEqual(result.switched,[]);
        assert.equal(result.hasArchitecture,true);
        assert.equal(await page.locator('#viewSummary').isVisible(),true);
        assert.equal(await page.locator('#architectureViewPanel').isVisible(),true);
    }finally{await finish(page);}
});
test('an explicitly selected summary renders the newly accepted architecture on recovery',async()=>{
    const page=await reportFixture();try{
        const rendered=await page.evaluate(async()=>{
            const checkbox=document.createElement('input');checkbox.id='includeArchitectureView';checkbox.type='checkbox';checkbox.checked=true;document.body.append(checkbox);
            Object.assign(TaxonomyState,{currentView:'summary',taxonomyData:[{code:'BP-1',name:'Time capture',children:[]}],
                currentArchView:{includedElements:[{nodeCode:'OLD'}],includedRelationships:[],anchors:[]}});
            window.renderedViews=[];
            window.TaxonomyBrowse={clearStatus(){},showStatus(){},renderView(){renderedViews.push(TaxonomyState.currentArchView);},switchView(view){TaxonomyState.currentView=view;}};
            window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'test-workspace'})};
            window.TaxonomyAnalysisProgress={start:()=>({acceptsResult:()=>true,finish(){}})};
            window.__taxonomyFixtureFetch=async()=>({ok:true,json:async()=>({status:'SUCCESS',scores:{'BP-1':70},rawScores:{'BP-1':70},
                reasons:{},warnings:[],architectureView:{includedElements:[],includedRelationships:[],anchors:[],viewTitle:'new-result'}})});
            await TaxonomyScoring.runAnalysis({continuation:{continuationAction:'CONTINUE'},recoveryManaged:true});
            return {current:TaxonomyState.currentView,last:renderedViews.at(-1)?.viewTitle};
        });
        assert.deepEqual(rendered,{current:'summary',last:'new-result'});
    }finally{await finish(page);}
});
test('full analysis result pipeline retains relations with the architecture option off',async()=>{
    const page=await reportFixture();try{
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-browse.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-progress.js')});
        await page.evaluate(report=>{
            const checkbox=document.createElement('input');checkbox.id='includeArchitectureView';checkbox.type='checkbox';checkbox.checked=false;document.body.append(checkbox);
            TaxonomyState.taxonomyData=[{code:'BP-1000',name:'Zeiterfassung',children:[]}];
            window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'test-workspace'})};
            window.TaxonomyAnalysisSessionApi={getRunStatus:async id=>({status:200,json:async()=>({operationId:id,status:'COMPLETED',phase:'FINISHED',sequence:1,serverTime:3000,lastActivityAt:3000,elapsedMillis:1000,calls:[],evaluatedNodes:1})})};
            window.__taxonomyFixtureFetch=async(url,options)=>{
                if(new URL(url,location.href).pathname!=='/api/analyze')throw new Error('Unexpected request '+url);
                window.analysisRequest={url:String(url),method:options.method,
                    headers:Object.fromEntries(new Headers(options.headers)),body:JSON.parse(options.body)};
                return {ok:true,json:async()=>({status:'SUCCESS',scores:{'BP-1000':70},rawScores:{'BP-1000':70},reasons:{},warnings:[],architectureView:null,relationSearchReport:report})};
            };
        },relationReport);
        await page.evaluate(()=>TaxonomyScoring.runAnalysis());
        const request=await page.evaluate(()=>window.analysisRequest);
        assert.ok(request,'The actual named analysis request must reach the captured transport');
        const url=new URL(request.url,'http://taxonomy.fixture');
        assert.equal(url.pathname,'/api/analyze');
        assert.deepEqual([...url.searchParams],[['workspaceId','test-workspace']]);
        assert.equal(request.headers['x-taxonomy-workspace-id'],'test-workspace');
        assert.equal(request.method,'POST');
        assert.equal(request.body.includeArchitectureView,false);
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
            window.__taxonomyFixtureFetch=async()=>({ok:true,json:async()=>({
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
        window.__taxonomyFixtureFetch = async url => ({ok: true, json: async () => String(url).includes('ai-status')
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
        window.__taxonomyFixtureFetch = async (url, options = {}) => {
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
test('portable Copilot import renders and re-exports retained evidence without granting a server reference', async () => {
    const page = await reportFixture();
    const copilot = {schemaVersion:2,state:'PARTIAL',gaps:{profileId:'compatibility',profileVersion:'1.0.0',totalAnchors:0,totalGaps:0,notes:[],judgments:[],evidenceCompleteness:'INCOMPLETE'},
        patterns:{matchedPatterns:[],incompletePatterns:[],patternCoverage:0,notes:[]},
        recommendation:{matchedElements:[],acceptedElements:[],proposedElements:[],suggestedRelations:[],reasoning:[],notes:[],meanRelevance:null},
        notes:['PORTABLE COPILOT EVIDENCE']};
    try {
        await page.evaluate(() => {
            for (const id of ['copilotPanel','copilotContent','gapAnalysisPanel','gapAnalysisContent',
                'patternDetectionPanel','patternDetectionContent','recommendationPanel','recommendationContent']) {
                const element=document.createElement('div');element.id=id;document.body.append(element);
            }
        });
        await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis.js')});
        await page.addScriptTag({path:path.join(resources,'static/js/shared/taxonomy-export.js')});
        await bootScoreImport(page, {scores:{'BP-1000':42},requirement:'Imported requirement',copilot,
            analysisOperationId:'untrusted-operation'});
        await page.evaluate(() => {
            __TaxonomyAnalysisSessionContext.runtime.analysisReference={workspaceId:'previous',operationId:'previous'};
            __TaxonomyAnalysisSessionContext.runtime.analysisEvidence='previous';
        });
        await page.locator('#importJsonFile').setInputFiles({name:'retained.json',mimeType:'application/json',buffer:Buffer.from('{}')});
        await page.waitForFunction(() => TaxonomyState.lastAnalysisProvider === 'IMPORTED');
        assert.match(await page.locator('#copilotContent').textContent(),/PORTABLE COPILOT EVIDENCE/);
        assert.deepEqual(await page.evaluate(() => TaxonomyState.currentCopilot),copilot);
        assert.equal(await page.evaluate(() => __TaxonomyAnalysisSessionContext.runtime.analysisReference),null);
        await page.evaluate(() => {
            window.__taxonomyFixtureFetch=async (url,options)=>{window.portableExport={url,body:JSON.parse(options.body)};
                return {ok:true,json:async()=>window.portableExport.body};};
            TaxonomyExport.exportJson(TaxonomyState.currentScores,TaxonomyState.currentReasons,'Imported requirement','IMPORTED');
        });
        await page.waitForFunction(() => window.portableExport);
        const exported=await page.evaluate(() => window.portableExport);
        assert.equal(exported.url,'/api/scores/export');
        assert.deepEqual(exported.body.copilot,copilot);assert.equal(exported.body.analysisReference,undefined);
    } finally { await finish(page); }
});

for (const edited of [false,true]) test(`report export ${edited ? 'retains manually edited evidence' : 'uses only the owned saved reference'}`,async()=>{
    const page=await reportFixture();
    try {
        await page.evaluate(()=>{const button=document.createElement('button');button.id='exportReportMd';button.textContent='Report';document.body.append(button);});
        await bootScoreImport(page,{});
        await page.evaluate(edited=>{
            const C=__TaxonomyAnalysisSessionContext,S=TaxonomyState;
            C.runtime.workspaceId='ws-owned';S.currentReasons={'BP-1000':'Retained rationale'};
            window.TaxonomyArchitectureWorkspace={ready:async()=>true};
            window.TaxonomyArchitectureWorkflow={getScope:()=>({repositoryId:'current-repository',workspaceId:'',branch:'review'})};
            S.currentCopilot={schemaVersion:2,state:'COMPLETED',notes:[]};
            S.analysisOperationId='owned-operation';S.analysisRecovery=null;
            C.runtime.analysisReference={workspaceId:'ws-owned',operationId:'owned-operation'};
            C.runtime.analysisEvidence=C.currentAnalysisEvidence();
            if(edited)S.currentReasons['BP-1000']='Manual rationale';
            window.__taxonomyFixtureFetch=async(url,options)=>{window.reportRequest={url:String(url),method:options.method,
                headers:Object.fromEntries(new Headers(options.headers)),body:JSON.parse(options.body)};
                return {ok:true,blob:async()=>new Blob(['report'],{type:'text/markdown'})};};
        },edited);
        await page.locator('#exportReportMd').click();await page.waitForFunction(()=>window.reportRequest);
        const request=await page.evaluate(()=>window.reportRequest);
        const url=new URL(request.url,'http://taxonomy.fixture');
        assert.equal(url.pathname,'/api/report/markdown');
        assert.deepEqual(Object.fromEntries(url.searchParams), edited
            ? {repositoryId:'current-repository',workspaceId:'',branch:'review'} : {workspaceId:'ws-owned'});
        assert.equal(request.headers['x-taxonomy-workspace-id'],edited ? '' : 'ws-owned');
        assert.equal(request.method,'POST');
        if(edited){assert.equal(request.body.analysisReference,undefined);assert.equal(request.body.reasons['BP-1000'],'Manual rationale');}
        else {assert.deepEqual(request.body.analysisReference,{workspaceId:'ws-owned',operationId:'owned-operation'});
            assert.equal(request.body.scores,undefined);}
    } finally {await finish(page);}
});
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

async function mountComponentTemplate(page, markup) {
    await page.evaluate(html => {
        // Parse the trusted repository template without bootstrapping the app.
        // The component fixtures load their controllers explicitly afterward.
        const parsed = new DOMParser().parseFromString(html, 'text/html');
        parsed.querySelectorAll('script, link').forEach(element => element.remove());
        document.documentElement.replaceWith(document.adoptNode(parsed.documentElement));
    }, markup);
}

test('component template parsing removes mixed-case scripts and links before mounting', async () => {
    const page = await browser.newPage();
    page.uiErrors = [];
    page.on('pageerror', error => page.uiErrors.push(error.message));
    try {
        await mountComponentTemplate(page, '<!doctype html><html><head>'
            + '<ScRiPt>window.unexpectedBootstrap = true;</ScRiPt>'
            + '<LiNk rel="stylesheet" href="https://unexpected.invalid/style.css">'
            + '</head><body><button id="fixtureAction">Continue</button>'
            + '<script>window.secondBootstrap = true;</script></body></html>');
        assert.equal(await page.locator('script, link').count(), 0);
        assert.equal(await page.locator('#fixtureAction').textContent(), 'Continue');
        assert.deepEqual(await page.evaluate(() => [Boolean(window.unexpectedBootstrap), Boolean(window.secondBootstrap)]), [false, false]);
    } finally { await finish(page); }
});

async function applicationMessages(locale) {
    const messages = {};
    const files = locale === 'de' ? ['messages.properties','messages_de.properties'] : ['messages.properties'];
    for (const file of files) {
        const source = await readFile(path.join(resources,'i18n',file),'utf8');
        for (const line of source.split('\n')) {
            const entry = line.match(/^([^#!\s][^=]*)=(.*)$/);
            if (entry) messages[entry[1]] = entry[2];
        }
    }
    return messages;
}

async function guidanceFixture(locale='de', search='') {
    const page=await fixture(locale);
    page.setDefaultTimeout(5000);
    const template=await readFile(path.join(resources,'templates/index.html'),'utf8');
    await page.route('http://taxonomy.test/**',route=>route.fulfill({
        contentType:'text/html',body:'<!doctype html><html><head></head><body></body></html>'}));
    await page.goto('http://taxonomy.test/taxonomy/'+search);
    await mountComponentTemplate(page, template);
    const dict=await applicationMessages(locale);
    await page.evaluate(({locale,dict})=>{
        document.documentElement.lang=locale;
        window.TaxonomyI18n={getLocale:()=>locale,ready:()=>Promise.resolve(),resolveUrl:p=>'/taxonomy'+p,
            t:(key,...args)=>(dict[key]||key).replace(/\{(\d+)\}/g,(_,n)=>String(args[n]??''))};
        document.querySelectorAll('[th\\:text]').forEach(el=>{const m=el.getAttribute('th:text').match(/^#\{(.+)\}$/);if(m)el.textContent=TaxonomyI18n.t(m[1]);});
        const bootstrap=document.createElement('script');bootstrap.dataset.taxonomyAnalysisSession='fixture';document.head.appendChild(bootstrap);
        window.guideCalls=[];
        window.TaxonomyAnalysisSession={state:()=>({ready:true}),saveNow:async()=>true,
            addAsRequirement:()=>guideCalls.push('save-requirement')};
        window.TaxonomyArchitectureWorkspace={open:async kind=>{guideCalls.push('architecture-'+kind);return true;},
            openLens:()=>guideCalls.push('open-lens')};
        window.TaxonomyAnalysisRecovery={open:()=>guideCalls.push('clarify')};
        document.getElementById('analyzeBtn').addEventListener('click',()=>guideCalls.push('analyze'));
        document.getElementById('businessText').value='Arbeitsschutz';
    },{locale,dict});
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-state.js')});
    assert.equal(await page.locator('#analysisNextSteps').count(),1,'Analysis needs visible actionable next steps');
    await page.addScriptTag({path:path.join(resources,'static/js/core/taxonomy-analysis-guidance.js')});
    return page;
}
for(const locale of ['de','en']) test(`${locale}: partial results expose clarification, reformulation and a scoped architecture lens`,async()=>{
    const page=await guidanceFixture(locale,'?workspaceScopeKey=private-alice&branch=feature');
    try{
        await page.evaluate(()=>{
            Object.assign(TaxonomyState,{lastAnalyzedText:'Arbeitsschutz',lastAnalysisStatus:'PARTIAL',
                analysisRecovery:{state:'COMPLETED_WITH_GAPS',openQuestions:[{key:'CI',error:'ROOT_UNRESOLVED: scope?'}]}});
            document.dispatchEvent(new CustomEvent('taxonomy:view-rendered'));
        });
        assert.equal(await page.locator('#analysisClarifyAction').isVisible(),true);
        assert.equal(await page.locator('#analysisReformulationAction').isVisible(),true);
        assert.equal(await page.locator('#analysisLensAction').isVisible(),true);
        assert.match(await page.locator('#analysisNextStepText').textContent(),locale==='de'?/offen|klären/i:/open|clarif/i);
        assert.equal(await page.locator('#analysisLensAction').getAttribute('href'),'#architecture');
        await page.locator('#analysisClarifyAction').click();
        assert.deepEqual(await page.evaluate(()=>guideCalls),['clarify']);
        await page.locator('#analysisReformulationAction').click();
        assert.deepEqual(await page.evaluate(()=>guideCalls),['clarify','save-requirement']);
        await page.locator('#analysisLensAction').click();
        assert.deepEqual(await page.evaluate(()=>guideCalls),['clarify','save-requirement','architecture-analysis','open-lens']);
        assert.match(page.url(),/workspaceScopeKey=private-alice&branch=feature/);
        assert.match(await page.locator('#analysisReformulationHint').textContent(),locale==='de'?/Snapshot|Projekt/:/snapshot|project/);
        assert.equal(await page.locator('#businessText').inputValue(),'Arbeitsschutz');
    }finally{await finish(page);}
});
test('saved requirement link preserves exact context; a changed requirement is directed to reanalysis',async()=>{
    const page=await guidanceFixture('en','?projectId=7&requirementId=9&snapshot=snapshot-4');
    try{
        await page.evaluate(()=>{Object.assign(TaxonomyState,{lastAnalyzedText:'Arbeitsschutz',lastAnalysisStatus:'SUCCESS'});document.dispatchEvent(new CustomEvent('taxonomy:view-rendered'));});
        assert.equal(await page.locator('#analysisReformulationAction').getAttribute('href'),'/taxonomy/projects/7/requirements/9?snapshot=snapshot-4#reformulationOffers');
        await page.locator('#businessText').fill('Changed requirement');
        assert.equal(await page.locator('#analysisReformulationAction').getAttribute('aria-disabled'),'true');
        assert.match(await page.locator('#analysisNextStepText').textContent(),/changed|current wording/i);
        await page.locator('#analysisReanalyzeAction').click();
        assert.deepEqual(await page.evaluate(()=>guideCalls),['analyze']);
    }finally{await finish(page);}
});
test('running analysis leaves tools visible with an explanation and does not start another workflow',async()=>{
    const page=await guidanceFixture();
    try{
        await page.evaluate(()=>{TaxonomyState.lastAnalysisStatus='IN_PROGRESS';document.dispatchEvent(new CustomEvent('taxonomy:analysis-running-changed'));});
        assert.equal(await page.locator('#analysisReformulationAction').isVisible(),true);
        assert.equal(await page.locator('#analysisReformulationAction').getAttribute('aria-disabled'),'true');
        assert.match(await page.locator('#analysisNextStepText').textContent(),/läuft/i);
        await page.locator('#analysisReformulationAction').dispatchEvent('click');
        assert.deepEqual(await page.evaluate(()=>guideCalls),[]);
    }finally{await finish(page);}
});

async function mountDocumentImport(page, locale, deferWorkspace = false) {
    const template = await readFile(path.join(resources,'templates/index.html'),'utf8');
    const messages = await applicationMessages(locale);
    await page.evaluate(({template,locale,messages,deferWorkspace}) => {
        const source = new DOMParser().parseFromString(template,'text/html');
        for (const name of ['documentImportPanel','docCandidateReviewPanel','docAiResultPanel','docRegMapResultPanel']) {
            const element = source.getElementById(name);
            if (element) document.body.append(element);
        }
        document.documentElement.lang = locale;
        document.getElementById('documentImportPanel').open = true;
        window.TaxonomyI18n = {t:key=>messages[key] || key,resolveUrl:path=>path};
        window.__TaxonomyAnalysisSessionContext = {runtime:{workspaceId:deferWorkspace ? null : 'document-workspace'}};
        if (deferWorkspace) window.TaxonomyAnalysisSessionReady = new Promise(resolve => setTimeout(() => {
            window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'document-workspace';
            resolve(true);
        },200));
    }, {template,locale,messages,deferWorkspace});
    await installBrowserApiFixture(page, resources);
    for (const script of ['api/analysis-adapter-api.js','api/import-api.js','shared/taxonomy-document-import.js'])
        await page.addScriptTag({path:path.join(resources,'static/js',script)});
}

for (const locale of ['en', 'de']) {
    test('native document receipt can be recovered and cancelled in ' + locale, async () => {
        const page = await browser.newPage({locale});
        const origin = 'http://taxonomy.fixture';
        const id = '9a93df57-2938-4c75-8056-940ac7cdaaaa', commit = 'a'.repeat(40);
        const cancelLabel = locale === 'de' ? 'Analyse abbrechen' : 'Cancel analysis';
        const cancelledLabel = locale === 'de' ? 'Die Analyse wurde abgebrochen.' : 'Analysis was cancelled.';
        const calls = [];
        let state = 'RUNNING';
        try {
            await page.route(origin + '/**', async route => {
                const request = route.request(), url = new URL(request.url());
                if (url.pathname === '/') return route.fulfill({contentType:'text/html', body:'<html><body></body></html>'});
                calls.push(request.method() + ' ' + url.pathname);
                const headers = {'Content-Type':'application/json','X-Analysis-Operation-Id':id,'X-Analysis-Source-Commit':commit,'Retry-After':'1'};
                if (url.pathname === '/api/analysis-adapters/' + id + '/cancel' && request.method() === 'POST') state='CANCELLED';
                if (url.pathname === '/api/documents/map-regulation' && request.method() === 'POST') state='RUNNING';
                const status = state === 'RUNNING' ? 202 : state === 'CANCELLED' ? 409 : 200;
                const body = state === 'COMPLETED' ? {fileName:'notice.pdf',totalPages:1,matches:[{nodeCode:'CP',confidence:0.9}]}
                    : {operationId:id,sourceCommit:commit,sequence:0,state,code:state};
                await route.fulfill({status,headers,body:JSON.stringify(body)});
            });
            await page.goto(origin);
            await mountDocumentImport(page,locale);
            await page.locator('#docImportFile').setInputFiles({name:'notice.pdf',mimeType:'application/pdf',buffer:Buffer.from('controlled input')});
            await page.locator('input[name="importMode"][value="reg-map"]').check();
            const button = page.locator('#docImportUploadBtn');
            const cancel = page.getByRole('button',{name:cancelLabel,exact:true});
            await button.click();
            await cancel.waitFor();
            assert.equal(await page.locator('#docImportStatus').getAttribute('role'),'status');
            assert.equal(await page.locator('#docImportStatus').getAttribute('aria-live'),'polite');
            state='COMPLETED';
            await page.waitForFunction(() => document.querySelector('#docRegMapTableBody')?.textContent.includes('CP'));
            assert.equal(await cancel.count(),0);
            await button.click();
            await cancel.click();
            await page.waitForFunction(text => document.getElementById('docImportStatus').textContent.includes(text),cancelledLabel);
            assert.equal(await button.isDisabled(),false);
            assert.equal(calls.filter(call => call === 'POST /api/documents/map-regulation').length,2);
            assert.equal(calls.filter(call => call.endsWith('/cancel')).length,1);
            assert.ok(calls.includes('GET /api/analysis-adapters/' + id));
        } finally { await page.close(); }
    });
}

for (const locale of ['de','en']) {
    test(locale + ': native document failures and expiry are announced in forced colors', async () => {
        const page = await browser.newPage({locale, viewport:{width:320,height:680}, forcedColors:'active', reducedMotion:'reduce'});
        const messages = await applicationMessages(locale);
        const origin = 'https://taxonomy.fixture';
        const cases = [
            {mode:'ai-extract', path:'extract-ai', status:422, body:{error:'AI_EXTRACTION_FAILED',message:'AI-assisted extraction could not be completed'}, key:'failed'},
            {mode:'reg-map', path:'map-regulation', status:422, body:{error:'REGULATION_MAPPING_FAILED',message:'Regulation mapping could not be completed'}, key:'failed'},
            {mode:'reg-map', path:'map-regulation', status:410, body:{code:'ANALYSIS_EXPIRED'}, key:'expired'}
        ];
        const requests = [];
        try {
            await page.route(origin + '/**', async route => {
                const path = new URL(route.request().url()).pathname;
                if (path === '/') return route.fulfill({contentType:'text/html',body:'<!doctype html><html><body></body></html>'});
                const current = cases[requests.length];
                requests.push([route.request().method(),path]);
                await route.fulfill({status:current.status,contentType:'application/json',body:JSON.stringify(current.body)});
            });
            await page.goto(origin);
            await mountDocumentImport(page,locale);
            assert.equal(await page.evaluate(() => matchMedia('(forced-colors: active)').matches),true);
            await page.locator('#docImportFile').setInputFiles({name:'notice.pdf',mimeType:'application/pdf',buffer:Buffer.from('controlled archive')});
            const button = page.locator('#docImportUploadBtn');
            const status = page.locator('#docImportStatus');
            assert.equal(await status.getAttribute('aria-live'),'polite');
            for (const current of cases) {
                await page.locator('input[name="importMode"][value="' + current.mode + '"]').check();
                await button.focus();
                await page.keyboard.press('Enter');
                await page.waitForFunction(() => !document.getElementById('docImportUploadBtn').disabled);
                assert.equal((await status.textContent()).trim(),'❌ ' + messages['analysis.adapter.' + current.key]);
                assert.deepEqual(requests.at(-1),['POST','/api/documents/' + current.path]);
                assert.equal(await page.locator('#docAiResultPanel').isVisible(),false);
                assert.equal(await page.locator('#docRegMapResultPanel').isVisible(),false);
            }
            assert.equal(requests.length,cases.length);
        } finally { await page.close(); }
    });
}

test('reload during the first physical document request discovers the admitted operation using GET only', async () => {
    const page = await browser.newPage(), origin = 'https://taxonomy.fixture';
    const id = '8e370454-53d6-4312-b531-05a4bfab1704', source = 'a'.repeat(40);
    const calls = [];
    let token, completed = false, release, submitted;
    const admission = new Promise(resolve => { submitted = resolve; });
    const original = new Promise(resolve => { release = resolve; });
    try {
        await page.route(origin + '/**', async route => {
            const req = route.request(), path = new URL(req.url()).pathname;
            if (path === '/') return route.fulfill({contentType:'text/html',body:'<html><body></body></html>'});
            calls.push(req.method() + ' ' + path);
            if (req.method() === 'POST') {
                assert.equal(path,'/api/documents/map-regulation');
                token = req.headers()['x-analysis-observer'];
                assert.match(token,/^[0-9a-f-]{36}$/);
                submitted();
                await original;
                return route.abort().catch(() => {});
            }
            assert.ok(path === '/api/analysis-adapters/observations/' + token || path === '/api/analysis-adapters/' + id);
            const status = completed ? 200 : 202;
            const body = completed ? {fileName:'notice.pdf',totalPages:1,matches:[{nodeCode:'CP',confidence:.91}]}
                : {operationId:id,sourceCommit:source,sequence:0,state:'RUNNING'};
            await route.fulfill({status,headers:{'Content-Type':'application/json','Retry-After':'1',
                'X-Analysis-Operation-Id':id,'X-Analysis-Source-Commit':source},body:JSON.stringify(body)});
        });
        await page.goto(origin);
        await mountDocumentImport(page,'en');
        await page.locator('#docImportFile').setInputFiles({name:'notice.pdf',mimeType:'application/pdf',buffer:Buffer.from('private source text')});
        await page.locator('input[name="importMode"][value="reg-map"]').check();
        await page.locator('#docImportUploadBtn').click();
        const cancel = page.getByRole('button',{name:'Cancel analysis'});
        await admission;
        const saved = await page.evaluate(() => window.AnalysisAdapterApi.savedDocument());
        assert.deepEqual(Object.keys(saved).sort(),['action','created','token']);
        assert.equal(saved.token,token);
        const beforeReload = calls.length;
        await page.reload();
        await mountDocumentImport(page,'en',true);
        await cancel.waitFor();
        assert.equal(await page.evaluate(() => window.AnalysisAdapterApi.savedDocument().id),id);
        completed = true;
        await page.waitForFunction(() => document.querySelector('#docRegMapTableBody')?.textContent.includes('CP'));
        assert.equal(calls.filter(call => call.startsWith('POST')).length,1);
        assert.ok(calls.slice(beforeReload).every(call => call.startsWith('GET')));
        assert.equal(await page.evaluate(() => window.AnalysisAdapterApi.savedDocument()),null);
    } finally {
        release();
        await page.close();
    }
});

for (const surface of ['node','leaf','editor-focus','source-focus']) {
    test('reopening the same ' + surface + ' action after reload uses only its original scoped observation', async () => {
        const page = await browser.newPage(), origin = 'https://taxonomy.fixture';
        const id = '04474a8c-3188-4191-871e-c409ee4f0ff7', source = 'b'.repeat(40);
        const messages = await applicationMessages('de');
        let token, release, submitted, wrongSource = false;
        const original = new Promise(resolve => { release = resolve; });
        const admission = new Promise(resolve => { submitted = resolve; });
        const calls = [];
        try {
            await page.route(origin + '/**', async route => {
                const request = route.request(), path = new URL(request.url()).pathname;
                if (path === '/') return route.fulfill({contentType:'text/html',body:'<html><body></body></html>'});
                calls.push([request.method(),path]);
                if (!path.startsWith('/api/analysis-adapters/')) {
                    token = request.headers()['x-analysis-observer']; submitted();
                    await original;
                    return route.abort().catch(() => {});
                }
                assert.equal(request.method(),'GET');
                assert.equal(path,wrongSource ? '/api/analysis-adapters/' + id : '/api/analysis-adapters/observations/' + token);
                await route.fulfill({status:200,headers:{'Content-Type':'application/json',
                    'X-Analysis-Operation-Id':id,'X-Analysis-Source-Commit':wrongSource ? 'c'.repeat(40) : source},body:JSON.stringify({native:'original result'})});
            });
            async function reopen() {
                await page.evaluate(messages => {
                    document.documentElement.lang = 'de';
                    window.TaxonomyI18n = {t:key=>messages[key] || key,resolveUrl:path=>path};
                },messages);
                await installBrowserApiFixture(page,resources);
                for (const file of ['analysis-adapter-api.js','architecture-editor-api.js','architecture-source-focus-api.js'])
                    await page.addScriptTag({path:path.join(resources,'static/js/api',file)});
                await page.evaluate(surface => {
                    const policy = {onPending:() => {}, isCurrent:() => true};
                    const scope = {repositoryId:'repo',workspaceScopeKey:'test',branch:'main',revision:1};
                    const payload = {sourceId:'CP',businessText:'private request must not enter sessionStorage'};
                    const call = surface === 'node' ? window.AnalysisApi.analyzeNode(payload,policy).then(r => r.json())
                        : surface === 'leaf' ? window.AnalysisApi.justifyLeaf(payload,policy).then(r => r.json())
                        : surface === 'editor-focus' ? window.ArchitectureEditorApi.suggest({...payload,context:scope},policy)
                        : window.ArchitectureSourceFocusApi.suggest({...payload,source:scope,useAi:true},policy);
                    call.then(result => { window.retainedNativeResult = result; }).catch(error => { window.nativeError = error.message; });
                },surface);
            }
            await page.goto(origin);
            await reopen();
            await admission;
            assert.ok(token);
            assert.doesNotMatch(await page.evaluate(() => JSON.stringify(sessionStorage)),/private request|businessText/);
            const beforeReload = calls.length;
            await page.reload();
            await reopen();
            await page.waitForFunction(() => window.retainedNativeResult?.native === 'original result',null,{timeout:5000});
            assert.deepEqual(calls.slice(beforeReload),[['GET','/api/analysis-adapters/observations/' + token]]);
            if (surface === 'leaf') {
                wrongSource = true;
                await page.reload();
                await reopen();
                await page.waitForFunction(() => typeof window.nativeError === 'string',null,{timeout:5000});
                assert.equal(await page.evaluate(() => window.nativeError),messages['analysis.adapter.stale']);
                assert.equal(await page.evaluate(() => window.retainedNativeResult),undefined);
                assert.deepEqual(calls.at(-1),['GET','/api/analysis-adapters/' + id]);
            }
        } finally {
            release();
            await page.close();
        }
    });
}

test('coverage dashboard pins the source and clears departed working-copy data', async () => {
    const page = await browser.newPage();
    try {
        await page.setContent('<base href="https://taxonomy.test/"><div id="coverageDashboardContent"></div>');
        await page.evaluate(() => {
            window.TaxonomyI18n = {t: key => key};
            window.TaxonomyUtils = {escapeHtml: value => String(value)};
            window.selection = {repositoryId:'repo-a', workspaceId:'A1', branch:'draft', commit:'a'.repeat(40)};
            window.TaxonomyAppChrome = {getContext: () => ({architecture:window.selection})};
            window.captured = [];
            window.__taxonomyFixtureFetch = async (input, options) => {
                const url = new URL(input, document.baseURI);
                window.captured.push([url.searchParams.get('repositoryId'), url.searchParams.get('workspaceId'),
                    url.searchParams.get('branch'), url.searchParams.get('commitId'), new Headers(options.headers).get('X-Taxonomy-Workspace-Id')]);
                const stats = {totalNodes:2,coveredNodes:1,uncoveredNodes:1,coveragePercentage:50,totalRequirements:1,
                    avgRequirementsPerNode:1,topCovered:[{nodeCode:'only-' + window.selection.workspaceId,requirementCount:1}],gapCandidates:[]};
                return Response.json(stats);
            };
        });
        await installBrowserApiFixture(page, resources);
        await page.addScriptTag({path:path.join(resources, 'static/js/relations/taxonomy-coverage.js')});
        await page.evaluate(() => window.TaxonomyCoverage.loadCoverageDashboard());
        assert.match(await page.locator('#coverageDashboardContent').innerText(), /only-A1/);
        await page.evaluate(() => document.dispatchEvent(new CustomEvent('taxonomy:repository-context-changed', {detail:{selectionChanged:false}})));
        assert.match(await page.locator('#coverageDashboardContent').innerText(), /only-A1/);
        await page.evaluate(() => {
            window.selection = {repositoryId:'repo-b', workspaceId:'B1', branch:'main', commit:'b'.repeat(40)};
            document.dispatchEvent(new CustomEvent('taxonomy:repository-context-changed', {detail:{selectionChanged:true}}));
        });
        assert.equal(await page.locator('#coverageDashboardContent').innerText(), '');
        await page.evaluate(() => window.TaxonomyCoverage.loadCoverageDashboard());
        assert.match(await page.locator('#coverageDashboardContent').innerText(), /only-B1/);
        assert.deepEqual(await page.evaluate(() => window.captured), [
            ['repo-a','A1','draft','a'.repeat(40),'A1'], ['repo-b','B1','main','b'.repeat(40),'B1']]);
    } finally {
        await page.close();
    }
});

test('relation browser pins the selected source and clears departed relations and actions', async () => {
    const page = await browser.newPage();
    try {
        await page.route('http://taxonomy.fixture/', route => route.fulfill({contentType:'text/html', body:
            '<details id="relationsBrowser" open><div id="relationsTableContainer"></div></details>'}));
        await page.goto('http://taxonomy.fixture/');
        await page.evaluate(() => {
            window.TaxonomyI18n = {t:key=>key};
            window.TaxonomyUtils = {escapeHtml:String};
            window.selection = {repositoryId:'repo-a', workspaceId:'A1', branch:'draft'};
            window.TaxonomyArchitectureWorkspace = {ready:async()=>true};
            window.TaxonomyArchitectureWorkflow = {getScope:()=>window.selection};
            window.requests = [];
            window.__taxonomyFixtureFetch = async (input, options) => {
                const url = new URL(input, document.baseURI);
                const source = ['repositoryId','workspaceId','branch'].map(k=>url.searchParams.get(k));
                window.requests.push([...source, new Headers(options.headers).get('X-Taxonomy-Workspace-Id')]);
                return Response.json([{id:1, sourceCode:'only-' + window.selection.workspaceId,
                    targetCode:'shared-endpoint', relationType:'SUPPORTS'}]);
            };
        });
        await installBrowserApiFixture(page, resources);
        await page.addScriptTag({path:path.join(resources, 'static/js/relations/taxonomy-relations.js')});
        await page.evaluate(() => window.TaxonomyRelations.loadRelations());
        assert.match(await page.locator('#relationsTableContainer').innerText(), /only-A1/);
        await page.evaluate(() => document.dispatchEvent(new CustomEvent('taxonomy:repository-context-changed', {detail:{selectionChanged:false}})));
        assert.equal(await page.locator('.relation-delete-btn').count(), 1);
        await page.evaluate(() => {
            window.selection = {repositoryId:'repo-b', workspaceId:'B1', branch:'main'};
            document.dispatchEvent(new CustomEvent('taxonomy:repository-context-changed', {detail:{selectionChanged:true}}));
        });
        assert.equal(await page.locator('#relationsTableContainer').innerText(), '');
        assert.equal(await page.locator('.relation-delete-btn').count(), 0);
        await page.evaluate(() => window.TaxonomyRelations.loadRelations());
        assert.match(await page.locator('#relationsTableContainer').innerText(), /only-B1/);
        assert.deepEqual(await page.evaluate(()=>window.requests), [['repo-a','A1','draft','A1'],['repo-b','B1','main','B1']]);
    } finally {
        await page.close();
    }
});

test('a relation command binds its preflight workspace and ETag and aborts a superseded selection', async () => {
    const page = await browser.newPage();
    try {
        await page.route('http://taxonomy.fixture/', route=>route.fulfill({contentType:'text/html', body:
            '<meta name="_csrf" content="fixture"><meta name="_csrf_header" content="X-CSRF-TOKEN">' +
            '<input id="newRelSource" value="A"><input id="newRelTarget" value="B"><input id="newRelType" value="SUPPORTS">' +
            '<button id="createRelationSubmit">Save</button><div id="createRelationError"></div>'}));
        await page.goto('http://taxonomy.fixture/');
        await page.evaluate(() => {
            window.selection = {repositoryId:'repo-a',workspaceId:'A1',branch:'draft'};
            window.TaxonomyArchitectureWorkspace = {ready:async()=>true};
            window.TaxonomyArchitectureWorkflow = {getScope:()=>window.selection};
            window.TaxonomyI18n = {t:key=>key};
            window.commandRequests = [];
            window.__taxonomyFixtureFetch = async (input, options) => {
                const url = new URL(input, document.baseURI), headers = new Headers(options.headers);
                if (options.method === 'PUT') {
                    window.commandRequests.push([url.searchParams.get('repositoryId'),url.searchParams.get('workspaceId'),
                        url.searchParams.get('branch'),headers.get('X-Taxonomy-Workspace-Id'),headers.get('If-Match')]);
                    return Response.json({},{headers:{ETag:'"' + 'b'.repeat(40) + '"'}});
                }
                if (window.switchDuringPreflight) window.selection = {repositoryId:'repo-b',workspaceId:'B1',branch:'main'};
                return Response.json([],{headers:{'X-Taxonomy-Repository':'repo-a','X-Taxonomy-Workspace-Id':'A1',
                    'X-Taxonomy-Branch':'draft',ETag:'"' + 'a'.repeat(40) + '"'}});
            };
        });
        await installBrowserApiFixture(page, resources);
        await page.addScriptTag({path:path.join(resources, 'static/js/relations/taxonomy-relations-git-commands.js')});
        await page.locator('#createRelationSubmit').click();
        await page.waitForFunction(()=>window.commandRequests.length || document.getElementById('createRelationError').textContent);
        assert.deepEqual(await page.evaluate(()=>window.commandRequests), [['repo-a','A1','draft','A1','"'+ 'a'.repeat(40)+'"']], await page.locator('#createRelationError').innerText());
        await page.evaluate(()=>{ window.switchDuringPreflight = true; });
        await page.locator('#createRelationSubmit').click();
        await page.waitForFunction(()=>document.getElementById('createRelationError').textContent.includes('superseded'));
        assert.equal(await page.evaluate(()=>window.commandRequests.length), 1);
    } finally {
        await page.close();
    }
});
