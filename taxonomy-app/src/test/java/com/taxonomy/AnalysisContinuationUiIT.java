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
    static void serve(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/fixture")) {
            String language = exchange.getRequestURI().getQuery().endsWith("en") ? "en" : "de";
            send(exchange, "text/html", """
                <!doctype html><html><head><meta charset="utf-8"></head><body>
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
        String script = path.equals("/viewport.js") ? "static/js/core/taxonomy-recovery-viewport.js"
                : path.equals("/recovery.js") ? "static/js/core/taxonomy-analysis-recovery.js" : null;
        if (script != null) { send(exchange, "application/javascript", new ClassPathResource(script).getContentAsString(StandardCharsets.UTF_8)); return; }
        if (path.equals("/api/analyze") && exchange.getRequestMethod().equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); decision.set(body); writes.incrementAndGet();
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
