package com.taxonomy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
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

    @Test void recordedReasonLengthSurvivesDraftReloadAndBothExportRequests() {
        loadReasonEvidenceScripts();
        @SuppressWarnings("unchecked")
        var payload = (Map<String, Object>) browser.executeScript("""
            TaxonomyScoring.fixtureApplyAnalysisResult({scores:{'fixture-leaf':42},
                reasons:{'fixture-leaf':'The saved decision reason.'},status:'SUCCESS',provider:'MOCK',
                analysisReasonLength:'detailed'}, {text:'fixture requirement'});
            return __TaxonomyAnalysisSessionContext.currentPayload();
            """);
        assertThat(payload.get("analysisReasonLength")).isEqualTo("detailed");
        open();
        loadReasonEvidenceScripts();
        browser.executeScript("__TaxonomyAnalysisSessionContext.applyDraft({version:4,payload:arguments[0]});", payload);
        assertThat(browser.executeScript("return TaxonomyState.lastAnalysisReasonLength")).isEqualTo("detailed");
        assertThat(browser.executeScript("return TaxonomyState.currentReasons['fixture-leaf']"))
                .isEqualTo("The saved decision reason.");
        assertThat(capturedReasonLengthExports()).containsEntry("scores", "detailed").containsEntry("decision", "detailed");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedReasonLengthExports() {
        return (Map<String, Object>) browser.executeAsyncScript("""
            const done=arguments[arguments.length-1], requests={};
            const fetchBefore=window.fetch;
            window.fetch=async (url, options)=>{
                requests.scores=JSON.parse(options.body);
                return new Response(JSON.stringify(requests.scores),{status:200,headers:{'Content-Type':'application/json'}});
            };
            window.TaxonomyDecisionExport={open:dialog=>{requests.report=dialog;}};
            TaxonomyExport.exportJson(TaxonomyState.currentScores,TaxonomyState.currentReasons,'fixture requirement','MOCK');
            TaxonomyBrowse.fixtureHandleExport('exportDecisionReportDocx');
            window.TaxonomyApiClient={requestBlob:async(url,options)=>{
                requests.decision=JSON.parse(options.body); throw new Error('fixture stops before download');
            }};
            requests.report.submit({format:'docx',options:{}}).catch(()=>{}).finally(()=>{
                window.fetch=fetchBefore;
                done({scores:requests.scores.analysisReasonLength,decision:requests.decision.analysisReasonLength});
            });
            """);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void individualManualReasonEditsClearProvenanceInDraftAndExports(boolean accessibleDialog) {
        loadReasonEvidenceScripts();
        browser.executeScript("""
            const S=TaxonomyState;
            S.interactiveMode=true;S.storedBusinessText='fixture requirement';
            S.lastAnalysisProvider='fixture-provider';S.analysisProviderId='fixture-provider';
            S.analysisOperationId='fixture-operation';
            window.fetch=async()=>({ok:true,json:async()=>({scores:{'fixture-parent':50},
                reasons:{'fixture-parent':'Original generated reason'},analysisReasonLength:'detailed',
                analysisProviderId:'fixture-provider',analysisOperationId:'fixture-operation'})});
            TaxonomyBrowse.renderView(S.taxonomyData,null);
            document.querySelector('[data-taxonomy-container="CP"] .tax-container-toggle').click();
            """);
        var pencil = By.cssSelector("[data-code='fixture-parent'] > .tax-manual-btn");
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> !browser.findElements(pencil).isEmpty());
        assertThat(browser.executeScript("return TaxonomyState.lastAnalysisReasonLength")).isEqualTo("detailed");
        if (accessibleDialog) browser.executeScript("TaxonomyUtils.fixtureInstallManualScoreDialog();");
        browser.findElement(pencil).click();
        if (accessibleDialog) {
            var input = browser.findElement(By.id("taxonomyAccessibleDialogInput"));
            input.sendKeys("65");
            browser.findElement(By.id("taxonomyAccessibleDialogConfirm")).click();
        } else {
            var prompt = browser.switchTo().alert();
            prompt.sendKeys("65");
            prompt.accept();
        }
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> Boolean.TRUE.equals(browser.executeScript(
                "return TaxonomyState.currentReasons['fixture-parent'] !== 'Original generated reason'")));
        assertThat(browser.executeScript("return TaxonomyState.currentScores['fixture-parent']")).isEqualTo(65L);
        assertThat(browser.executeScript("return TaxonomyState.lastAnalysisReasonLength")).isNull();
        browser.executeScript("""
            TaxonomyScoring.acceptAnalysisReasonLength({analysisReasonLength:'detailed',
                reasons:{'fixture-leaf':'Later generated reason'}},false);
            Object.assign(TaxonomyState.currentReasons,{'fixture-leaf':'Later generated reason'});
            """);
        assertThat(browser.executeScript("return __TaxonomyAnalysisSessionContext.currentPayload().analysisReasonLength")).isNull();
        assertThat(capturedReasonLengthExports()).containsEntry("scores", null).containsEntry("decision", null);
    }

    @ParameterizedTest
    @CsvSource({"complete,detailed,false", "error,detailed,false", "complete,detailed,true", "error,detailed,true",
            "complete,,false", "error,,false"})
    void terminalOnlyStreamingRetainsRecordedReasonsWithoutRelabelingLegacyContributions(
            String terminal, String recordedLength, boolean earlierUnrecordedReasons) {
        loadReasonEvidenceScripts();
        browser.executeScript("""
            window.EventSource=class {
                constructor(){this.handlers={};window.fixtureStream=this;}
                addEventListener(type,handler){this.handlers[type]=handler;}
                close(){this.closed=true;}
                emit(type,payload){this.handlers[type]({data:JSON.stringify(payload)});}
            };
            TaxonomyState.lastAnalysisReasonLength='short';
            TaxonomyScoring.runStreamingAnalysis();
            fixtureStream.emit('phase',{message:'Processing',analysisReasonLength:'detailed'});
            if(arguments[2]) fixtureStream.emit('scores',{scores:{'fixture-zero':0},
                reasons:{'fixture-zero':'Earlier reason without recorded setting'}});
            const scores={'fixture-leaf':42},reasons={'fixture-leaf':'Frozen terminal reason'};
            if(arguments[2]) scores['fixture-zero']=0;
            const payload={totalScores:scores,partialScores:scores,rawScores:scores,effectiveScores:scores,
                reasons,status:arguments[0]==='complete'?'SUCCESS':'PARTIAL',errorMessage:'Fixture partial result'};
            if(arguments[1]!==null)payload.analysisReasonLength=arguments[1];
            fixtureStream.emit(arguments[0],payload);
            """, terminal, recordedLength, earlierUnrecordedReasons);
        assertThat(browser.executeScript("return TaxonomyState.currentReasons['fixture-leaf']")).isEqualTo("Frozen terminal reason");
        assertThat(browser.executeScript("return TaxonomyState.currentScores['fixture-leaf']")).isEqualTo(42L);
        String expected = earlierUnrecordedReasons ? null : recordedLength;
        assertThat(browser.executeScript("return TaxonomyState.lastAnalysisReasonLength")).isEqualTo(expected);
        assertThat(browser.executeScript("return __TaxonomyAnalysisSessionContext.currentPayload().analysisReasonLength")).isEqualTo(expected);
        assertThat(capturedReasonLengthExports()).containsEntry("scores", expected).containsEntry("decision", expected);
        if (earlierUnrecordedReasons) assertThat(browser.executeScript("return TaxonomyState.currentReasons['fixture-zero']"))
                .isEqualTo("Earlier reason without recorded setting");
    }

    @Test void legacyDraftAndManualScoresClearAFormerGenerationSetting() {
        loadReasonEvidenceScripts();
        @SuppressWarnings("unchecked")
        var values = (Map<String, Object>) browser.executeScript("""
            const C=__TaxonomyAnalysisSessionContext,S=TaxonomyState;
            S.lastAnalysisReasonLength='detailed';
            C.applyDraft({version:1,payload:{draftState:'ACTIVE',businessText:'fixture requirement',
                lastAnalyzedText:'fixture requirement',scores:{'fixture-leaf':42},reasons:{'fixture-leaf':'Old reason'}}});
            const legacy=S.lastAnalysisReasonLength;
            S.lastAnalysisReasonLength='detailed';
            TaxonomyBrowse.fixtureEnterManualScoringMode();
            const manual=S.lastAnalysisReasonLength;
            S.lastAnalysisReasonLength='detailed';
            C.applyDraft({version:2,payload:{draftState:'EMPTY',analysisReasonLength:'detailed'}});
            return {legacy:legacy,manual:manual,empty:S.lastAnalysisReasonLength};
            """);
        assertThat(values).containsOnlyKeys("legacy", "manual", "empty");
        assertThat(values.values()).containsOnlyNulls();
    }

    @Test void combiningReasonsFromDifferentRunsDoesNotClaimOneGenerationSetting() {
        @SuppressWarnings("unchecked")
        var values = (Map<String, Object>) browser.executeScript("""
            const S=TaxonomyState,accept=TaxonomyScoring.acceptAnalysisReasonLength;
            accept({analysisReasonLength:'detailed',reasons:{a:'First reason'}},true);
            S.currentReasons={a:'First reason'};
            accept({analysisReasonLength:'detailed',reasons:{b:'Second reason'}},false);
            const same=S.lastAnalysisReasonLength;
            Object.assign(S.currentReasons,{b:'Second reason'});
            accept({analysisReasonLength:'short',reasons:{c:'Third reason'}},false);
            const mixed=S.lastAnalysisReasonLength;
            Object.assign(S.currentReasons,{c:'Third reason'});
            accept({analysisReasonLength:'detailed',reasons:{d:'Fourth reason'}},false);
            return {same,mixed,later:S.lastAnalysisReasonLength};
            """);
        assertThat(values).containsEntry("same", "detailed")
                .containsEntry("mixed", null).containsEntry("later", null);
    }

    private void loadReasonEvidenceScripts() {
        var failure = browser.executeAsyncScript("""
            const done=arguments[arguments.length-1];
            (async()=>{
                for(const src of ['/js/core/taxonomy-analysis-session-core.js',
                    '/js/core/taxonomy-analysis-session-draft.js','/js/shared/taxonomy-export.js']) {
                    await new Promise((resolve,reject)=>{const script=document.createElement('script');
                        script.src=src;script.onload=resolve;script.onerror=reject;document.head.appendChild(script);});
                }
            })().then(()=>done(null),e=>done(String(e)));
            """);
        assertThat(failure).isNull();
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

    @ParameterizedTest @ValueSource(strings={"renderTreeDiagram","renderTreeCanvas","renderDecisionMap"})
    void diagramRedrawRetainsRealZoomPanScrollAndBranchChoices(String renderer) {
        loadDiagramEvidenceScripts();
        browser.executeScript("""
            const S=TaxonomyState;
            S.currentView=arguments[0]==='renderDecisionMap'?'decision':'tree';
            S.currentScores={'fixture-parent':70,'fixture-leaf':60,'fixture-zero':0,'fixture-bp-parent':50};
            """,renderer);
        renderDiagram(renderer);
        boolean canvas=renderer.equals("renderTreeCanvas");
        var branch=browser.findElement(By.cssSelector(canvas?"#taxonomyTree canvas":"[data-diagram-node='fixture-parent']"));
        browser.executeScript("arguments[0].focus();",branch);
        new Actions(browser).sendKeys(Keys.HOME,Keys.ENTER).perform();
        waitDiagram(renderer);
        assertThat(branch.getDomAttribute("aria-expanded")).isEqualTo("false");
        diagramControl("views.diagram.zoomIn").click();
        var beforePan=diagramViewport();
        var surface=browser.findElement(By.cssSelector("#taxonomyTree svg, #taxonomyTree canvas"));
        new Actions(browser).moveToElement(surface).clickAndHold().moveByOffset(37,23).release().perform();
        var before=diagramViewport();
        assertThat(((Number)before.get("k")).doubleValue()).isGreaterThan(1);
        assertThat(before).as("native pointer drag changes the actual D3 viewport").isNotEqualTo(beforePan);
        browser.executeScript("""
            const host=document.getElementById('taxonomyTree');
            host.style.width='500px';host.style.maxHeight='260px';host.style.overflow='auto';
            document.body.style.minHeight='2400px';
            const horizontalScroller=host.querySelector('.decision-tree-wrapper')||host;
            horizontalScroller.scrollLeft=70;host.scrollTop=60;window.scrollTo(0,180);
            TaxonomyState.currentScores['fixture-zero']=15;
            """);
        var scrollBefore=diagramScroll();
        assertThat(((Number)scrollBefore.get("left")).doubleValue()).isGreaterThan(0);
        assertThat(((Number)scrollBefore.get("top")).doubleValue()).isGreaterThan(0);
        assertThat(((Number)scrollBefore.get("pageY")).doubleValue()).isGreaterThan(0);
        renderDiagram(renderer);
        assertThat(diagramViewport()).as("a score update retains zoom and pan").isEqualTo(before);
        assertThat(diagramScroll()).as("a full replacement retains local and document scrolling").isEqualTo(scrollBefore);
        branch=browser.findElement(By.cssSelector(canvas?"#taxonomyTree canvas":"[data-diagram-node='fixture-parent']"));
        if(canvas){browser.executeScript("arguments[0].focus();",branch);new Actions(browser).sendKeys(Keys.HOME).perform();}
        assertThat(branch.getDomAttribute("aria-expanded")).as("the manually closed branch stays closed").isEqualTo("false");

        browser.findElement(By.cssSelector(".tax-diagram-collapse-all")).click();
        renderDiagram(renderer);
        if(canvas){
            browser.findElement(By.cssSelector("#taxonomyTree canvas")).sendKeys(Keys.END);
            assertThat(browser.findElement(By.cssSelector("#taxonomyTree canvas")).getDomAttribute("data-diagram-node")).isEqualTo("fixture-bp-parent");
            assertThat(browser.findElement(By.cssSelector("#taxonomyTree canvas")).getDomAttribute("aria-expanded")).isEqualTo("false");
        } else {
            assertThat(browser.findElements(By.cssSelector("[data-diagram-node]:not([aria-hidden='true'])"))).hasSize(3);
        }
        browser.findElement(By.cssSelector(".tax-diagram-expand-all")).click();
        renderDiagram(renderer);
        if(canvas){
            browser.findElement(By.cssSelector("#taxonomyTree canvas")).sendKeys(Keys.END);
            assertThat(browser.findElement(By.cssSelector("#taxonomyTree canvas")).getDomAttribute("data-diagram-node")).isEqualTo("fixture-bp-leaf");
        } else {
            assertThat(browser.findElements(By.cssSelector("[data-diagram-node]:not([aria-hidden='true'])"))).hasSize(6);
        }
        diagramControl("views.diagram.reset").click();
        renderDiagram(renderer);
        assertThat(browser.switchTo().activeElement().getText()).as("redrawing keeps the focused toolbar control usable").isEqualTo("views.diagram.reset");
    }

    @Test void sunburstKeepsItsBranchAcrossViewChangesFallsBackAndResetsForANewAnalysis() {
        loadDiagramEvidenceScripts();
        browser.executeScript("""
            const S=TaxonomyState;
            S.taxonomyData=[{code:'fixture-diagram-root',name:'Authored diagram fixture',taxonomyContainer:false,
                children:S.taxonomyData[0].children}];
            S.currentView='sunburst';
            TaxonomyBrowse.renderView(S.taxonomyData,null);
            document.querySelector('[data-diagram-node="fixture-parent"]').focus();
            """);
        new Actions(browser).sendKeys(Keys.ENTER).perform();
        assertThat(diagramControl("views.back").isEnabled()).isTrue();
        assertThat(browser.findElements(By.cssSelector("[data-diagram-node]:not([aria-hidden='true'])"))).hasSize(3);
        browser.executeScript("""
            const S=TaxonomyState;
            S.currentView='list';TaxonomyBrowse.renderView(S.taxonomyData,null);
            S.currentView='sunburst';TaxonomyBrowse.renderView(S.taxonomyData,{'fixture-leaf':75});
            """);
        assertThat(diagramControl("views.back").isEnabled()).isTrue();
        assertThat(browser.findElements(By.cssSelector("[data-diagram-node]:not([aria-hidden='true'])"))).hasSize(3);
        browser.executeScript("""
            const S=TaxonomyState;
            S.taxonomyData[0].children=S.taxonomyData[0].children.filter(node=>node.code!=='fixture-parent');
            TaxonomyBrowse.renderView(S.taxonomyData,null);
            """);
        assertThat(diagramControl("views.back").isEnabled()).as("the surviving ancestor remains the focused branch").isTrue();
        assertThat(browser.findElement(By.cssSelector("[data-diagram-node][tabindex='0']")).getDomAttribute("data-diagram-node")).isEqualTo("fixture-diagram-root");
        browser.executeScript("TaxonomyBrowse.resetDiagramContext();TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null);");
        assertThat(diagramControl("views.back").isEnabled()).as("a new analysis starts at the overview").isFalse();
    }

    @Test void sunburstResizesWithoutFeedingBackIntoItsObservedContainer() {
        loadDiagramEvidenceScripts();
        browser.executeScript("""
            window.diagramResizeErrors=[];
            window.addEventListener('error',event=>window.diagramResizeErrors.push(event.message));
            const host=document.getElementById('taxonomyTree');
            host.style.cssText='width:360px;max-height:none;height:auto;overflow:visible';
            TaxonomyState.currentView='sunburst';
            TaxonomyBrowse.renderView(TaxonomyState.taxonomyData,null);
            """);
        for (int width : new int[]{220, 470, 320}) {
            browser.executeScript("document.getElementById('taxonomyTree').style.width=arguments[0]+'px'", width);
            new WebDriverWait(browser, Duration.ofSeconds(10)).until(d -> Boolean.TRUE.equals(browser.executeScript("""
                const host=document.getElementById('taxonomyTree'),svg=host.querySelector('svg');
                return Number(svg.getAttribute('width'))===host.clientWidth
                    && Number(svg.getAttribute('height'))===host.clientWidth;
                """)));
            browser.executeAsyncScript("""
                const done=arguments[arguments.length-1];
                requestAnimationFrame(()=>requestAnimationFrame(()=>done(null)));
                """);
        }
        assertThat(browser.executeScript("return window.diagramResizeErrors"))
                .as("resizing a mobile diagram must settle without browser layout errors")
                .isEqualTo(java.util.List.of());
    }

    private void loadDiagramEvidenceScripts() {
        var failure=browser.executeAsyncScript("""
            const done=arguments[arguments.length-1];
            (async()=>{
                for(const src of ['/d3.js','/js/core/taxonomy-views.js']){
                    await new Promise((resolve,reject)=>{const script=document.createElement('script');
                        script.src=src;script.onload=resolve;script.onerror=reject;document.head.append(script);});
                }
                await new Promise((resolve,reject)=>{const css=document.createElement('link');
                    css.rel='stylesheet';css.href='/css/taxonomy.css';css.onload=resolve;css.onerror=reject;document.head.append(css);});
                document.getElementById('taxonomyTree').style.width='900px';
            })().then(()=>done(null),error=>done(String(error)));
            """);
        assertThat(failure).isNull();
    }

    private void renderDiagram(String renderer) {
        browser.executeScript("""
            const host=document.getElementById('taxonomyTree');
            TaxonomyViews.prepareDiagramContext(host,{analysisKey:'browser-reading-fixture',
                view:arguments[0]==='renderDecisionMap'?'decision':'tree',treeRoot:'fixture-roots'});
            TaxonomyViews[arguments[0]](host,TaxonomyState.taxonomyData,TaxonomyState.currentScores);
            """,renderer);
        waitDiagram(renderer);
    }

    private void waitDiagram(String renderer) {
        if(!renderer.equals("renderTreeCanvas")) new WebDriverWait(browser,Duration.ofSeconds(10)).until(d->
                "true".equals(browser.findElement(By.id("taxonomyTree")).getDomAttribute("data-transitions-complete")));
    }

    private WebElement diagramControl(String key) {
        return browser.findElement(By.xpath("//div[contains(@class,'tax-diagram-controls')]/button[normalize-space()='"+key+"']"));
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> diagramViewport() {
        return (Map<String,Object>)browser.executeScript("const p=d3.zoomTransform(document.querySelector('#taxonomyTree svg, #taxonomyTree canvas'));return {x:p.x,y:p.y,k:p.k};");
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> diagramScroll() {
        return (Map<String,Object>)browser.executeScript("""
            const host=document.getElementById('taxonomyTree');
            const horizontalScroller=host.querySelector('.decision-tree-wrapper')||host;
            return {left:horizontalScroller.scrollLeft,top:host.scrollTop,hostLeft:host.scrollLeft,
                innerTop:horizontalScroller.scrollTop,pageX:window.scrollX,pageY:window.scrollY};
            """);
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
        if(path.equals("/d3.js")){
            send(exchange,"application/javascript",new ClassPathResource("META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js")
                    .getContentAsString(StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/fixture")) {
            String template = new ClassPathResource("templates/index.html").getContentAsString(StandardCharsets.UTF_8);
            var input = Pattern.compile("<input[^>]*id=\"interactiveMode\"[^>]*>").matcher(template);
            if (!input.find()) throw new IOException("Product interactive-mode control is missing");
            send(exchange,"text/html", """
                <!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/css/taxonomy-ergonomics.css"></head><body>
                <div id="taxonomyTree"></div><div id="statusArea"></div>%s<input id="businessText" value="fixture requirement">
                <script>
                window.TaxonomyI18n={t:(key,...args)=>key+(args.length?': '+args.join(' | '):''),getLocale:()=> 'en'};
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
                source=source.replace(marker,marker+" fixtureExpandAll: expandAll, fixtureCollapseAll: collapseAll, fixtureHandleExport: handleExport, fixtureEnterManualScoringMode: enterManualScoringMode,");
            }
            if (path.endsWith("/taxonomy-scoring.js")) {
                source=source.replace("window.TaxonomyScoring = {", "window.TaxonomyScoring = { fixtureApplyAnalysisResult: applyAnalysisResult,");
            }
            if (path.endsWith("/taxonomy-utils.js")) {
                source=source.replace("escapeHtml: escapeHtml,", "fixtureInstallManualScoreDialog: installManualScoreDialog, escapeHtml: escapeHtml,");
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
