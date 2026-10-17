package com.taxonomy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic source-tree browser regressions. Authored codes are fixtures, not catalogue evidence. */
@Tag("ui-acceptance") @Tag("browser")
class TaxonomyBrowseDecisionUiIT {
    private static HttpServer server;
    private static BrowserSession session;
    private static RemoteWebDriver browser;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", TaxonomyBrowseDecisionUiIT::serve);
        server.start();
        session = BrowserSession.open(server.getAddress().getPort(), Path.of("target/browse-decision-evidence/downloads"));
        browser = session.driver();
    }
    @AfterAll static void close() throws Exception {
        try { if (session != null) session.close(); }
        finally { if (server != null) server.stop(0); }
    }
    @BeforeEach void open() {
        browser.get(session.origin() + "/fixture");
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> Boolean.TRUE.equals(browser.executeScript("return !!window.fixtureReady")));
    }

    @Test void areaHeadingSummarizesDeferredActualDescendantsWithoutFabricatingAreaRelevance() {
        browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-toggle")).click();
        browser.executeScript("TaxonomyState.currentScores={'fixture-leaf':42,'fixture-zero':0}; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,TaxonomyState.currentScores);");
        var summary = summary("CP");
        assertThat(summary.getDomAttribute("data-assessed-count")).isEqualTo("2");
        assertThat(summary.getDomAttribute("data-total-count")).isEqualTo("4");
        assertThat(summary.getDomAttribute("data-positive-count")).isEqualTo("1");
        assertThat(summary.getText()).contains("42");
        assertThat(browser.findElements(By.cssSelector("[data-code='fixture-leaf']"))).isEmpty();
        assertThat(browser.findElements(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-score"))).isEmpty();
        assertThat(browser.executeScript("return Object.hasOwn(TaxonomyState.currentScores,'CP')")).isEqualTo(false);
    }

    @Test void storedAreaZeroAndUnassessedDescendantsRemainDistinct() {
        browser.executeScript("TaxonomyState.currentScores={CP:0}; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,TaxonomyState.currentScores);");
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-score")).getText()).contains("0");
        assertThat(summary("CP").getDomAttribute("data-assessed-count")).isEqualTo("0");
        browser.executeScript("TaxonomyState.currentScores.CP=80; TaxonomyScoring.applyScoreToNode('CP',80);");
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-score")).getDomAttribute("class")).contains("tax-container-score-positive");
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-score")).getCssValue("background-color")).isNotEqualTo("rgba(0, 0, 0, 0)");
    }

    @Test void manualClosedBranchAndSelectedTabSurviveRendersAndAutomaticExpansion() {
        browser.executeScript("TaxonomyState.currentView='tabs'; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null);");
        browser.findElement(By.id("tax-tab-BP")).click();
        browser.findElement(By.cssSelector("[data-taxonomy-container='BP'] .tax-container-toggle")).click();
        browser.executeScript("TaxonomyState.currentScores={'fixture-leaf':42}; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,TaxonomyState.currentScores); TaxonomyScoring.expandNodeByCode('BP'); TaxonomyScoring.expandMatched(TaxonomyState.currentScores);");
        assertThat(browser.findElement(By.id("tax-tab-BP")).getDomAttribute("aria-selected")).isEqualTo("true");
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='BP'] .tax-container-toggle")).getDomAttribute("aria-expanded")).isEqualTo("false");
    }

    @Test void clearingDerivedAnalysisForANewRunKeepsTheSelectedTaxonomyTab() {
        browser.executeScript("TaxonomyState.currentView='tabs'; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null);");
        browser.findElement(By.id("tax-tab-BP")).click();
        browser.executeScript("TaxonomyScoring.clearDerivedAnalysisView();");
        assertThat(browser.executeScript("return TaxonomyState.currentView")).isEqualTo("tabs");
        assertThat(browser.findElement(By.id("tax-tab-BP")).getDomAttribute("aria-selected")).isEqualTo("true");
    }

    @Test void manuallyOpenedActualBranchSurvivesUnrelatedRerender() {
        browser.findElement(By.cssSelector("[data-code='fixture-parent'] > .tax-node-header > .tax-toggle")).click();
        browser.executeScript("TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null);");
        assertThat(browser.findElement(By.cssSelector("[data-code='fixture-parent']")).getDomAttribute("aria-expanded")).isEqualTo("true");
        assertThat(browser.findElement(By.cssSelector("[data-code='fixture-leaf']")).isDisplayed()).isTrue();
        assertThat(browser.findElements(By.cssSelector("[data-code='fixture-bp-leaf']"))).isEmpty();
    }

    @Test void incrementalDescendantEvidenceRefreshesContainerSummaryWithoutOpeningClosedBranch() {
        browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-toggle")).click();
        browser.executeScript("TaxonomyState.currentScores={'fixture-leaf':42}; TaxonomyScoring.applyScoreToNode('fixture-leaf',42);");
        var summary = summary("CP");
        assertThat(summary.getDomAttribute("data-positive-count")).isEqualTo("1");
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-toggle")).getDomAttribute("aria-expanded")).isEqualTo("false");
    }

    @Test void descendantSummaryUsesEffectiveRelevanceAndPreservesRawSuitabilityAndReasons() {
        browser.executeScript("TaxonomyState.currentScores={'fixture-parent':10,'fixture-leaf':9}; TaxonomyState.currentRawScores={'fixture-parent':10,'fixture-leaf':90}; TaxonomyState.currentReasons={'fixture-leaf':'recorded suitability reason'}; TaxonomyState.currentScoreDetails={'fixture-leaf':{kind:'PRODUCT_SUITABILITY',parentCode:'fixture-parent',effectiveRelevance:9}}; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,TaxonomyState.currentScores);");
        assertThat(summary("CP").getDomAttribute("data-highest-effective-relevance")).isEqualTo("10");
        assertThat(browser.executeScript("return TaxonomyState.currentRawScores['fixture-leaf']")).isEqualTo(90L);
        assertThat(browser.executeScript("return TaxonomyState.currentScores['fixture-leaf']")).isEqualTo(9L);
        assertThat(browser.executeScript("return TaxonomyState.currentReasons['fixture-leaf']")).isEqualTo("recorded suitability reason");
    }

    @Test void pendingProductRelevanceDoesNotCountAsAnAssessedZero() {
        browser.executeScript("TaxonomyState.currentScores={'fixture-leaf':0,'fixture-zero':0}; TaxonomyState.currentRawScores={'fixture-leaf':90,'fixture-zero':0}; TaxonomyState.currentScoreDetails={'fixture-leaf':{kind:'PRODUCT_SUITABILITY',parentCode:'fixture-parent'}}; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,TaxonomyState.currentScores);");
        var summary = summary("CP");
        assertThat(summary.getDomAttribute("data-assessed-count")).isEqualTo("1");
        assertThat(summary.getDomAttribute("data-positive-count")).isEqualTo("0");
        assertThat(summary.getText()).contains("0");
    }

    @Test void terminalReconciliationNeverRendersMissingEvidenceAsZero() {
        browser.executeScript("TaxonomyState.currentScores={'fixture-zero':0}; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,TaxonomyState.currentScores); document.querySelector(\"[data-code='fixture-parent'] > .tax-node-header > .tax-toggle\").click(); TaxonomyScoring.syncVisibleScoreNodes();");
        assertThat(browser.findElements(By.cssSelector("[data-code='fixture-missing'] > .tax-node-header > .tax-pct"))).isEmpty();
        assertThat(browser.findElement(By.cssSelector("[data-code='fixture-missing']")).getDomAttribute("aria-label")).doesNotContain("Score: 0");
        assertThat(browser.findElement(By.cssSelector("[data-code='fixture-zero'] > .tax-node-header > .tax-pct")).getText()).contains("0");
    }

    @Test void defaultCompleteModeAndExplicitInteractiveUnassessedStateAreVisible() {
        assertThat(browser.executeScript("return TaxonomyState.interactiveMode")).isEqualTo(false);
        assertThat(browser.findElement(By.id("interactiveMode")).isSelected()).isFalse();
        browser.executeScript("TaxonomyState.interactiveMode=true; TaxonomyState.storedBusinessText='fixture requirement'; TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null);");
        assertThat(summary("CP").getText()).contains("browse.container.interactive.unassessed");
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-toggle")).getDomAttribute("aria-expanded")).isEqualTo("false");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void ordinaryAnalysisRefreshesSourceSummariesOnceForEachChangedPollingBatch(boolean closeBranches) {
        installBranchFixture(48);
        browser.executeScript("""
            window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'fixture-workspace'})};
            window.TaxonomyAnalysisProgress={start:(operation,onSnapshot)=>{
                window.fixtureSnapshot=onSnapshot; return {acceptsResult:()=>true};
            }};
            window.fetch=(url)=>{window.fixturePostedUrl=url;return new Promise(()=>{});};
            const architecture=document.createElement('input');architecture.type='checkbox';architecture.id='includeArchitectureView';document.body.append(architecture);
            TaxonomyScoring.runAnalysis();
            if(arguments[0])TaxonomyBrowse.fixtureCollapseAll();
            """,closeBranches);
        @SuppressWarnings("unchecked")
        Map<String,Object> result=(Map<String,Object>)browser.executeScript("""
            const originalRefresh=TaxonomyBrowse.refreshContainerSummaries;
            let refreshes=0;
            TaxonomyBrowse.refreshContainerSummaries=function(scores){refreshes++;return originalRefresh(scores);};
            const originalContainer=TaxonomyUtils.isTaxonomyContainer;
            let sourceChecks=0;
            TaxonomyUtils.isTaxonomyContainer=function(node){sourceChecks++;return originalContainer(node);};
            const nodes=[];const visit=items=>items.forEach(node=>{if(!node.taxonomyContainer)nodes.push(node);visit(node.children||[]);});
            visit(TaxonomyState.taxonomyData);
            const rawScores=Object.fromEntries(nodes.slice(0,250).map(node=>[node.code,50]));
            const renderedBefore=document.querySelectorAll('#taxonomyTree .tax-node').length;
            const originalQuery=Element.prototype.querySelectorAll;
            let scans=0;
            Element.prototype.querySelectorAll=function(selector){if(selector==='[role="treeitem"]')scans++;return originalQuery.call(this,selector);};
            try {fixtureSnapshot({rawScores});} finally {Element.prototype.querySelectorAll=originalQuery;}
            return {refreshes,sourceChecks,scans,changed:Object.keys(rawScores).length,renderedBefore,
                tabStops:document.querySelectorAll('#taxonomyTree [role="treeitem"][tabindex="0"]').length,
                rendered:document.querySelectorAll('#taxonomyTree .tax-node').length};
            """);
        assertThat(browser.executeScript("return window.fixturePostedUrl")).isEqualTo("/api/analyze");
        assertThat(result.get("changed")).isEqualTo(250L);
        assertThat(result.get("refreshes")).as("one source summary pass per polling batch").isEqualTo(1L);
        assertThat(result.get("scans")).as("all positive-path ancestor updates share one tab-stop scan per tree").isEqualTo(2L);
        assertThat(((Number)result.get("sourceChecks")).intValue()).as("source traversal is bounded by this two-area hierarchy").isBetween(288, 576);
        assertThat(result.get("renderedBefore")).as("the first actual level was already materialized").isEqualTo(96L);
        if (closeBranches) {
            assertThat(result.get("rendered")).as("polling does not materialize additional closed source descendants").isEqualTo(result.get("renderedBefore"));
        } else {
            assertThat(((Number)result.get("rendered")).intValue()).as("positive paths become available automatically").isBetween(97,288);
            assertThat(result.get("tabStops")).as("both expanded areas retain a keyboard entry point").isEqualTo(2L);
            assertThat(browser.findElement(By.cssSelector("[data-code='fixture-CP-47-leaf-1'] .tax-pct")).getText()).contains("50");
        }
        assertThat(summary("CP").getDomAttribute("data-assessed-count")).isEqualTo("144");
        assertThat(summary("BP").getDomAttribute("data-assessed-count")).isEqualTo("106");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lateInteractiveResponsePreservesANewerExplicitCloseOnSuccessAndFailure(boolean fail) {
        browser.executeScript("""
            TaxonomyState.interactiveMode=true;TaxonomyState.storedBusinessText='fixture requirement';
            TaxonomyState.lastAnalysisProvider='fixture-provider';TaxonomyState.analysisProviderId='fixture-provider';
            TaxonomyState.analysisOperationId='fixture-operation';
            window.fetch=url=>new Promise((resolve,reject)=>{window.fixtureRequest=url;window.fixtureResolve=resolve;window.fixtureReject=reject;});
            TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null);
            document.querySelector('[data-taxonomy-container="CP"] .tax-container-toggle').click();
            TaxonomyBrowse.fixtureCollapseAll();
            """);
        assertThat(browser.executeScript("return window.fixtureRequest")).asString().contains("provider=fixture-provider", "operationId=fixture-operation");
        assertThat(browser.executeScript("return TaxonomyState.browseExpansion.get('CP')")).isEqualTo(false);
        browser.executeScript(fail
                ? "window.fixtureReject(new Error('fixture failure'));"
                : "window.fixtureResolve({ok:true,json:async()=>({scores:{'fixture-parent':50},reasons:{'fixture-parent':'fixture recorded reason'},analysisProviderId:'fixture-provider',analysisOperationId:'fixture-operation'})});");
        new WebDriverWait(browser,Duration.ofSeconds(10)).until(d->Boolean.TRUE.equals(browser.executeScript("return !document.querySelector('[data-taxonomy-container=CP]').classList.contains('tax-evaluating')")));
        assertThat(browser.executeScript("return TaxonomyState.browseExpansion.get('CP')")).as("late response must not record an automatic reopen as a manual choice").isEqualTo(false);
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-toggle")).getDomAttribute("aria-expanded")).isEqualTo("false");
        assertThat(browser.executeScript("return TaxonomyState.analysisProviderId")).isEqualTo("fixture-provider");
        assertThat(browser.executeScript("return TaxonomyState.analysisOperationId")).isEqualTo("fixture-operation");
        browser.executeScript("TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,TaxonomyState.currentScores);");
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='CP'] .tax-container-toggle")).getDomAttribute("aria-expanded")).isEqualTo("false");
        if (!fail) {
            assertThat(summary("CP").getDomAttribute("data-assessed-count")).isEqualTo("1");
            assertThat(browser.executeScript("return TaxonomyState.currentReasons['fixture-parent']")).isEqualTo("fixture recorded reason");
        } else {
            assertThat(browser.executeScript("return TaxonomyState.evaluatedNodes.has('CP')")).isEqualTo(false);
        }
    }

    @Test void bulkReadingActionsSynchronizeTabStopsOncePerTreeAndRecoverHiddenFocus() {
        installBranchFixture(24);
        @SuppressWarnings("unchecked")
        Map<String,Object> result=(Map<String,Object>)browser.executeScript("""
            const originalQuery=Element.prototype.querySelectorAll;
            let scans=0;
            Element.prototype.querySelectorAll=function(selector){if(selector==='[role="treeitem"]')scans++;return originalQuery.call(this,selector);};
            try {
                TaxonomyBrowse.fixtureExpandAll();
                const expandedScans=scans;
                const trees=document.querySelectorAll('#taxonomyTree [role="tree"]').length;
                const nodes=document.querySelectorAll('#taxonomyTree .tax-node').length;
                const expanded=Array.from(document.querySelectorAll('#taxonomyTree .tax-node[aria-expanded]')).every(node=>node.getAttribute('aria-expanded')==='true');
                document.querySelector('[data-code="fixture-CP-0-leaf-0"]').focus();
                scans=0;
                TaxonomyBrowse.fixtureCollapseAll();
                const collapsedScans=scans;
                const focused=document.activeElement;
                const recovered=focused===document.querySelector('[data-taxonomy-container="CP"] .tax-container-toggle');
                const closed=Array.from(TaxonomyState.browseExpansion.values()).every(value=>value===false);
                TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,TaxonomyState.currentScores);
                return {trees,nodes,expanded,expandedScans,collapsedScans,recovered,closed,
                    lazyAfterRender:document.querySelectorAll('#taxonomyTree .tax-node').length};
            } finally {Element.prototype.querySelectorAll=originalQuery;}
            """);
        assertThat(result.get("trees")).isEqualTo(2L);
        assertThat(result.get("nodes")).isEqualTo(144L);
        assertThat(result.get("expanded")).isEqualTo(true);
        assertThat(result.get("expandedScans")).as("Expand All scans each tree once after the batch").isEqualTo(2L);
        assertThat(result.get("collapsedScans")).as("Collapse All and focus recovery share the final tree synchronization").isEqualTo(2L);
        assertThat(result.get("recovered")).isEqualTo(true);
        assertThat(result.get("closed")).isEqualTo(true);
        assertThat(result.get("lazyAfterRender")).isEqualTo(0L);
    }

    private void installBranchFixture(int branches) {
        browser.executeScript("""
            const branches=arguments[0];
            TaxonomyState.taxonomyData=['CP','BP'].map(code=>({code,name:'Authored fixture '+code,level:0,taxonomyContainer:true,
                children:Array.from({length:branches},(_,i)=>({code:'fixture-'+code+'-'+i,name:'Fixture branch '+i,level:1,
                    children:Array.from({length:2},(_,j)=>({code:'fixture-'+code+'-'+i+'-leaf-'+j,name:'Fixture leaf '+j,level:2,children:[]}))}))}));
            TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null);
            """,branches);
    }

    private WebElement summary(String code) {
        var matches = browser.findElements(By.cssSelector("[data-taxonomy-container='" + code + "'] .tax-container-summary"));
        assertThat(matches).as("heading exposes a summary of actual source descendants").hasSize(1);
        return matches.getFirst();
    }

    private static void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/fixture")) {
            String template = new ClassPathResource("templates/index.html").getContentAsString(StandardCharsets.UTF_8);
            var input = Pattern.compile("<input[^>]*id=\"interactiveMode\"[^>]*>").matcher(template);
            if (!input.find()) throw new IOException("Product interactive-mode control is missing");
            send(exchange,"text/html", """
                <!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/css/taxonomy-ergonomics.css"></head><body>
                <div id="taxonomyTree"></div><div id="statusArea"></div>%s<input id="businessText" value="fixture requirement">
                <script>
                window.TaxonomyI18n={t:(key,...args)=>key+(args.length?': '+args.join(' | '):'')};
                const nativeAdd=document.addEventListener.bind(document);
                document.addEventListener=function(type,...args){if(type!=='DOMContentLoaded')nativeAdd(type,...args)};
                </script>
                <script data-taxonomy-analysis-session></script><script src="/js/shared/taxonomy-utils.js"></script><script src="/js/core/taxonomy-state.js"></script>
                <script src="/js/core/taxonomy-browse.js"></script><script src="/js/core/taxonomy-scoring.js"></script>
                <script>
                document.addEventListener=nativeAdd;
                const leaf=(code,children=[])=>({code,name:code,level:children.length?1:2,children});
                TaxonomyState.taxonomyData=[{code:'CP',name:'Fixture capabilities',level:0,taxonomyContainer:true,children:[leaf('fixture-parent',[leaf('fixture-leaf'),leaf('fixture-zero')]),leaf('fixture-missing')]},
                  {code:'BP',name:'Fixture processes',level:0,taxonomyContainer:true,children:[leaf('fixture-bp-parent',[leaf('fixture-bp-leaf')])]}];
                TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null); window.fixtureReady=true;
                </script></body></html>
                """.formatted(input.group()));
            return;
        }
        if (path.startsWith("/js/") || path.startsWith("/css/")) {
            var resource = new ClassPathResource("static"+path);
            String source=resource.getContentAsString(StandardCharsets.UTF_8);
            if (path.endsWith("/taxonomy-browse.js")) {
                // Expose private product handlers only in this test-served copy; no runtime test hooks.
                String marker="window.TaxonomyBrowse = {";
                if (!source.contains(marker)) throw new IOException("Browse fixture export marker is missing");
                source=source.replace(marker,marker+" fixtureExpandAll: expandAll, fixtureCollapseAll: collapseAll,");
            }
            send(exchange,path.endsWith(".css")?"text/css":"application/javascript",source);
            return;
        }
        exchange.sendResponseHeaders(404,-1); exchange.close();
    }
    private static void send(HttpExchange exchange,String type,String text) throws IOException {
        byte[] content=text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");
        exchange.sendResponseHeaders(200,content.length); exchange.getResponseBody().write(content); exchange.close();
    }
}
