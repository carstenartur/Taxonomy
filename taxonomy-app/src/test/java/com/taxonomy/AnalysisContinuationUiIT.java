package com.taxonomy;

import com.sun.net.httpserver.*;
import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.By;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.core.io.ClassPathResource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real product recovery controls; HTTP boundary fixtures never invoke a provider. */
@Tag("ui-acceptance") @Tag("browser")
class AnalysisContinuationUiIT {
    static final String ID = "ed706ff4-2a5e-44f4-bfc4-e43a9fa77cf9", KEY = "a".repeat(64);
    static HttpServer server; static BrowserSession session; static RemoteWebDriver browser;
    static final AtomicInteger writes = new AtomicInteger(), reads = new AtomicInteger();
    static final AtomicReference<String> decision = new AtomicReference<>();
    static final java.util.List<String> relationWrites = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0); server.createContext("/", AnalysisContinuationUiIT::serve); server.start();
        session = BrowserSession.open(server.getAddress().getPort(), Path.of("target/continuation-ui-evidence/downloads")); browser = session.driver();
    }
    @AfterAll static void close() throws Exception { try { if (session != null) session.close(); } finally { if (server != null) server.stop(0); } }
    @ParameterizedTest @CsvSource({"de,SKIP", "en,SKIP", "de,RETRY", "en,RETRY"})
    void anUncertainQuestionRequiresTheExplicitRevisionBoundDecision(String language, String action) {
        writes.set(0); reads.set(0); decision.set(null);
        browser.get(session.origin() + "/fixture?lang=" + language);
        var wait = new WebDriverWait(browser, Duration.ofSeconds(15));
        wait.until(driver -> driver.findElement(By.id("analysisRecoveryDialog")).isDisplayed());
        String text = browser.findElement(By.id("analysisRecoveryDialog")).getText();
        assertThat(text).contains(language.equals("de") ? "verarbeitet oder berechnet" : "processed or billed");
        assertThat(text).contains(language.equals("de") ? "explizite Entscheidung" : "explicit decision");
        assertThat(writes.get()).isZero(); assertThat(reads.get()).isZero();
        browser.findElement(By.id(action.equals("SKIP") ? "analysisRecoverySkip" : "analysisRecoveryRetry")).click();
        // The write is counted before the response is sent; wait for the browser to apply it.
        wait.until(driver -> writes.get() == 1 && !Boolean.TRUE.equals(browser.executeScript("return document.getElementById('analysisRecoveryDialog').open"))
                && !"RUNNING".equals(browser.executeScript("return window.__TaxonomyAnalysisSessionContext.S.analysisRecovery.state")));
        assertThat(decision.get()).contains("\"continuationAction\":\"" + action + "\"", "\"continuationVersion\":7", "\"continuationQuestion\":\"" + KEY + "\"");
        String expectedState = action.equals("SKIP") ? "COMPLETED_WITH_GAPS" : "COMPLETED";
        assertThat(writes.get()).isEqualTo(1); assertThat(reads.get()).isZero();
        assertThat(browser.executeScript("return window.__TaxonomyAnalysisSessionContext.S.analysisRecovery.state"))
                .isEqualTo(expectedState);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"de", "en"})
    void normalNodeOnlyAnalysisCanExplicitlyAddRelationsWithoutChangingRetainedScores(String language) {
        relationWrites.clear();
        browser.get(session.origin() + "/relations-fixture?lang=" + language);
        var wait = new WebDriverWait(browser, Duration.ofSeconds(15));
        browser.findElement(By.id("analyzeBtn")).click();
        wait.until(driver -> relationWrites.size() == 1 && driver.findElement(By.id("extendAnalysisRelationsBtn")).isEnabled());
        var source = mapper.readTree(relationWrites.getFirst());
        assertThat(source.path("resumable").asBoolean()).isTrue();
        assertThat(source.path("analysisScope").path("mode").asString()).isEqualTo("TAXONOMIES_ONLY");
        String original = (String) browser.executeScript("return JSON.stringify([TaxonomyState.currentRawScores,TaxonomyState.currentReasons])");
        for (String edit : java.util.List.of("TaxonomyState.currentRawScores.CP=99;", "TaxonomyState.currentReasons.CP='Manually revised explanation';",
                "TaxonomyState.currentRawScores['CP-new']=84;")) {
            browser.executeScript(edit + "document.dispatchEvent(new Event('taxonomy:view-rendered'));");
            assertThat(browser.findElement(By.id("extendAnalysisRelationsBtn")).isEnabled()).isFalse();
            assertThat(browser.findElement(By.id("analysisRelationsExtensionHint")).getText()).contains(language.equals("de") ? "bearbeitet" : "edited");
            browser.executeScript("TaxonomyAnalysisRecovery.extendRelations();");
            assertThat(relationWrites).hasSize(1);
            browser.executeScript("const original=JSON.parse(arguments[0]);TaxonomyState.currentRawScores=original[0];TaxonomyState.currentReasons=original[1];document.dispatchEvent(new Event('taxonomy:view-rendered'));", original);
            assertThat(browser.findElement(By.id("extendAnalysisRelationsBtn")).isEnabled()).isTrue();
        }
        browser.findElement(By.id("businessText")).sendKeys(" changed");
        assertThat(browser.findElement(By.id("extendAnalysisRelationsBtn")).isEnabled()).isFalse();
        assertThat(browser.findElement(By.id("analysisRelationsExtensionHint")).getText()).contains(language.equals("de") ? "geändert" : "changed");
        browser.executeScript("const field=document.getElementById('businessText');field.value='retained relations fixture';field.dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('providerSelect').value='GEMINI';");
        var action = browser.findElement(By.id("extendAnalysisRelationsBtn"));
        assertThat(action.getText()).isEqualTo(language.equals("de") ? "Relationssuche ergänzen" : "Add relationship search");
        action.sendKeys(org.openqa.selenium.Keys.ENTER);
        wait.until(driver -> relationWrites.size() == 2 && "FULL".equals(browser.executeScript("return TaxonomyState.lastAnalysisScope?.mode")));
        var extension = mapper.readTree(relationWrites.get(1));
        assertThat(extension.path("relationSourceContinuationId").asString()).isEqualTo(source.path("continuationId").asString());
        assertThat(extension.path("relationSourceVersion").asLong()).isEqualTo(7);
        assertThat(extension.path("continuationId").asString()).isNotEqualTo(source.path("continuationId").asString());
        assertThat(extension.path("provider").asString()).isEqualTo("MOCK");
        assertThat(extension.has("rawScores") || extension.has("scores")).isFalse();
        assertThat(browser.executeScript("return JSON.stringify([TaxonomyState.currentRawScores,TaxonomyState.currentReasons])")).isEqualTo(original);
        assertThat(browser.executeScript("return window.fixtureFollowups")).isEqualTo(0L);
        assertThat(relationWrites).hasSize(2);
    }

    @Test void legacyNodeOnlyEvidenceExplainsWhyRelationsCannotBeAdded() {
        relationWrites.clear(); browser.get(session.origin() + "/relations-fixture?lang=en");
        browser.executeScript("TaxonomyState.lastAnalysisScope={taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'};TaxonomyState.currentRawScores={CP:71.125};TaxonomyRelationsExtension.render();");
        assertThat(browser.findElement(By.id("extendAnalysisRelationsBtn")).isEnabled()).isFalse();
        assertThat(browser.findElement(By.id("analysisRelationsExtensionHint")).getText()).contains("no retained execution source");
        assertThat(relationWrites).isEmpty();
    }

    static void serve(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/relations-fixture")) {
            String language = exchange.getRequestURI().getQuery().endsWith("en") ? "en" : "de";
            String template = new ClassPathResource("templates/index.html").getContentAsString(StandardCharsets.UTF_8);
            var control = java.util.regex.Pattern.compile("<div id=\"analysisRelationsExtension\"[\\s\\S]*?</div>").matcher(template);
            if (!control.find()) throw new java.io.IOException("Product relations extension control is missing");
            send(exchange, "text/html", """
                <!doctype html><html lang="%s"><head><meta charset="utf-8"></head><body>
                <input id="businessText" value="retained relations fixture"><input type="checkbox" id="includeArchitectureView">
                <select id="providerSelect"><option>MOCK</option><option>GEMINI</option></select>
                <button id="analyzeBtn" onclick="TaxonomyScoring.runAnalysis()">Analyze</button><button id="copilotBtn">Copilot</button>
                <div id="taxonomyTree"></div><div id="statusArea"></div>%s
                <script>
                window.TaxonomyI18n={t:key=>key,getLocale:()=>'%s'};window.TaxonomyUtils={escapeHtml:value=>String(value??'')};
                window.TaxonomyState={taxonomyData:[{code:'CP',taxonomyRoot:'CP',nameEn:'Capabilities',children:[]}],currentRawScores:{},currentScoreDetails:{},currentReasons:{},currentView:'list'};
                window.__TaxonomyAnalysisSessionContext={S:TaxonomyState,runtime:{workspaceId:'workspace-a',analysisGeneration:0},language:()=>'%s'};
                window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'workspace-a'}),saveNow:async()=>true};
                window.TaxonomyAnalysisScope={read:()=>({taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'}),acceptResult:scope=>{TaxonomyState.lastAnalysisScope=scope;},restrictsGlobalAnalysis:()=>true};
                window.TaxonomyBrowse={renderView:()=>document.dispatchEvent(new Event('taxonomy:view-rendered')),clearStatus:()=>{},showStatus:()=>{}};
                window.TaxonomyAnalysisProgress={start:()=>({acceptsResult:()=>true,finish:()=>{}})};
                window.fixtureFollowups=0;window.TaxonomyAnalysis={runCopilotFlow:async()=>{window.fixtureFollowups++;},renderPartialCopilot:()=>{}};
                window.TaxonomyAnalysisSessionApi={request:async(url,options)=>fetch(url,options)};
                </script><script src="/scoring.js"></script><script src="/viewport.js"></script><script src="/recovery.js"></script><script src="/extension.js"></script>
                </body></html>
                """.formatted(language, control.group(), language, language)); return;
        }
        if (path.equals("/fixture")) {
            String language = exchange.getRequestURI().getQuery().endsWith("en") ? "en" : "de";
            send(exchange, "text/html", """
                <!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/recovery.css"></head><body>
                <input id="businessText" value="requirement"><button id="analyzeBtn"></button><button id="copilotBtn"></button>
                <script>const state={recoveryContext:{id:'%s',workspaceId:'workspace-a',distributed:true,request:{businessText:'requirement',provider:'MOCK',analysisScope:{taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'}}},
                  analysisRecovery:{id:'%s',version:7,state:'PAUSED',completedCalls:1,openQuestions:[{key:'%s',nodes:['CP'],attempts:1,outcomeUncertain:true,error:'OUTCOME_UNCERTAIN'}]}};
                window.__TaxonomyAnalysisSessionContext={S:state,runtime:{workspaceId:'workspace-a',analysisGeneration:1},language:()=>'%s'};
                window.TaxonomyAnalysisSession={state:()=>({ready:true}),saveNow:async()=>true};
                window.TaxonomyAnalysisScope={restrictsGlobalAnalysis:()=>true};window.TaxonomyAnalysis={renderPartialCopilot:()=>{}};
                window.TaxonomyAnalysisSessionApi={request:async(url,options)=>fetch(url,options)};
                window.TaxonomyScoring={runAnalysis:({continuation})=>fetch('/api/analyze',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(continuation)}).then(r=>r.json())};
                </script><script src="/viewport.js"></script><script src="/recovery.js"></script><script>TaxonomyAnalysisRecovery.open();</script></body></html>
                """.formatted(ID, ID, KEY, language)); return;
        }
        if (path.equals("/recovery.css")) {
            send(exchange, "text/css", new ClassPathResource("static/css/taxonomy-analysis-recovery.css").getContentAsString(StandardCharsets.UTF_8)); return;
        }
        String script = path.equals("/viewport.js") ? "static/js/core/taxonomy-recovery-viewport.js"
                : path.equals("/recovery.js") ? "static/js/core/taxonomy-analysis-recovery.js"
                : path.equals("/scoring.js") ? "static/js/core/taxonomy-scoring.js"
                : path.equals("/extension.js") ? "static/js/core/taxonomy-relations-extension.js" : null;
        if (script != null) { send(exchange, "application/javascript", new ClassPathResource(script).getContentAsString(StandardCharsets.UTF_8)); return; }
        if (path.equals("/api/analyze") && exchange.getRequestMethod().equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var input = mapper.readTree(body);
            if ("retained relations fixture".equals(input.path("businessText").asString())) {
                relationWrites.add(body);
                boolean extension = input.hasNonNull("relationSourceContinuationId");
                var result = new java.util.LinkedHashMap<String,Object>();
                result.put("rawScores", java.util.Map.of("CP",71.125)); result.put("scores",java.util.Map.of("CP",71.125));
                result.put("reasons", java.util.Map.of("CP","Original explanation.\nUnchanged."));
                result.put("status","SUCCESS");result.put("provider","MOCK");result.put("analysisProviderId","MOCK");
                result.put("analysisScope",java.util.Map.of("taxonomyRoots",java.util.List.of("CP"),"mode",extension?"FULL":"TAXONOMIES_ONLY"));
                result.put("recovery",java.util.Map.of("id",input.path("continuationId").asString(),"version",7,"state","COMPLETED","completedCalls",1,"openQuestions",java.util.List.of()));
                result.put("analysisCoverage",java.util.Map.of("failedOrBlockedNodes",0,"nodes",java.util.Map.of()));
                send(exchange,"application/json",mapper.writeValueAsString(result));return;
            }
            decision.set(body); writes.incrementAndGet();
            boolean skip = body.contains("\"continuationAction\":\"SKIP\"");
            send(exchange, "application/json", "{\"status\":\"" + (skip ? "PARTIAL" : "SUCCESS") + "\",\"recovery\":{\"id\":\"" + ID + "\",\"version\":8,\"state\":\"" + (skip ? "COMPLETED_WITH_GAPS" : "COMPLETED") + "\",\"openQuestions\":[]}}"); return;
        }
        if (path.startsWith("/api/analysis-continuations/")) { reads.incrementAndGet(); send(exchange,"application/json","{}"); return; }
        exchange.sendResponseHeaders(204,-1);exchange.close();
    }
    static void send(HttpExchange exchange,String type,String content) throws java.io.IOException {
        byte[] body=content.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");
        exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    }
}
