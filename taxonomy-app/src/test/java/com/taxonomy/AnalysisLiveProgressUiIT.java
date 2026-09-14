package com.taxonomy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-browser component contract. The application/use-case and authorization boundaries have separate Java tests. */
@Tag("ui-acceptance")
class AnalysisLiveProgressUiIT {
    private static final String ID = "cb2a3d71-e849-4a50-9855-1f9cb8f81402";
    private static final AtomicReference<String> STATUS = new AtomicReference<>("RUNNING");
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
    private static final AtomicInteger DETAIL_REQUESTS = new AtomicInteger();
    private static final AtomicInteger CANCEL_REQUESTS = new AtomicInteger();
    private static final AtomicInteger UNEXPECTED_REQUESTS = new AtomicInteger();
    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static Network network;
    private static ContainerTestUtils.BrowserSession session;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;
    private static String origin;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", AnalysisLiveProgressUiIT::serve);
        server.start();
        Testcontainers.exposeHostPorts(server.getAddress().getPort());
        origin = "http://host.testcontainers.internal:" + server.getAddress().getPort();
        network = Network.newNetwork();
        session = ContainerTestUtils.startBrowser(network);
        driver = session.driver();
        wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    @AfterAll static void stop() throws Exception {
        try { ContainerTestUtils.closeAll(session, network); }
        finally {
            if (server != null) server.stop(0);
            if (serverExecutor != null) serverExecutor.shutdownNow();
        }
    }

    @BeforeEach void reset() {
        STATUS.set("RUNNING"); SEQUENCE.set(1);
        DETAIL_REQUESTS.set(0); CANCEL_REQUESTS.set(0); UNEXPECTED_REQUESTS.set(0);
        driver.get(origin + "/fixture");
        wait.until(browser -> browser.findElement(By.id("analysisLiveProgress")).getText().contains("START")
                || browser.findElement(By.id("llmCommLogContent")).getText().contains("STARTED"));
    }

    @Test void pendingCallMemoryWarningAndPartialScoresAreVisibleBeforeCompletion() throws Exception {
        wait.until(browser -> browser.findElement(By.id("llmCommLogContent")).getText().contains("STARTED"));
        assertThat(driver.findElement(By.id("analysisLiveProgress")).getText())
                .contains("LLM-Antwort", "Speicherwarnung", "94%", "Server-Lebenszeichen");
        assertThat(DETAIL_REQUESTS.get()).isZero();
        SEQUENCE.set(2);
        wait.until(browser -> browser.findElement(By.id("partialScores")).getText().contains("80"));
        assertThat(STATUS.get()).isEqualTo("RUNNING");
        driver.findElement(By.cssSelector("#llmCommLogContent summary")).click();
        wait.until(browser -> browser.findElement(By.id("llmCommLogContent")).getText().contains("diagnostic-prompt"));
        assertThat(DETAIL_REQUESTS.get()).isEqualTo(1);
        assertThat(driver.findElements(By.cssSelector("#llmCommLogContent img"))).isEmpty();
        assertThat(driver.executeScript("return window.injected || false")).isEqualTo(false);
        Files.createDirectories(Path.of("target", "analysis-ui-evidence"));
        Files.write(Path.of("target", "analysis-ui-evidence", "live-progress.png"),
                ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
        STATUS.set("COMPLETED"); SEQUENCE.incrementAndGet();
        wait.until(browser -> !browser.findElement(By.cssSelector("#analysisLiveProgress button")).isEnabled());
        assertThat(driver.findElement(By.id("analysisLiveProgress")).getAttribute("aria-live")).isEqualTo("polite");
    }

    @Test void explicitCancellationIsPinnedAndNeverStartsAnotherAnalysis() {
        driver.findElement(By.cssSelector("#analysisLiveProgress button")).click();
        wait.until(browser -> CANCEL_REQUESTS.get() == 1);
        wait.until(browser -> !browser.findElement(By.cssSelector("#analysisLiveProgress button")).isEnabled());
        assertThat(STATUS.get()).isEqualTo("CANCELLED");
        assertThat(UNEXPECTED_REQUESTS.get()).isZero();
    }

    private static void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/fixture")) {
            send(exchange, "text/html", """
                    <!doctype html><html lang="de"><head><meta charset="utf-8">
                    <meta name="_csrf" content="fixture-token"><meta name="_csrf_header" content="X-CSRF-TOKEN">
                    <style>body{font:16px sans-serif;margin:2rem}.alert{padding:1rem;border:1px solid #999}pre{white-space:pre-wrap}</style>
                    </head><body><div id="statusArea"></div><div id="partialScores"></div><div id="llmCommLogContent"></div>
                    <script src="/progress.js"></script><script>
                    window.__TaxonomyAnalysisSessionContext={runtime:{workspaceId:'workspace-a',analysisGeneration:1}};
                    window.monitor=TaxonomyAnalysisProgress.start('%s',function(s){
                        document.getElementById('partialScores').textContent=JSON.stringify(s.rawScores);
                    });</script></body></html>
                    """.formatted(ID));
            return;
        }
        if (path.equals("/progress.js")) {
            send(exchange, "application/javascript", new ClassPathResource("static/js/core/taxonomy-analysis-progress.js")
                    .getContentAsString(StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/favicon.ico")) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
        boolean pinned = "workspace-a".equals(exchange.getRequestHeaders().getFirst("X-Taxonomy-Workspace-Id"))
                && "workspaceId=workspace-a".equals(exchange.getRequestURI().getRawQuery());
        if (!pinned) { UNEXPECTED_REQUESTS.incrementAndGet(); exchange.sendResponseHeaders(403, -1); exchange.close(); return; }
        if (path.endsWith("/cancel") && exchange.getRequestMethod().equals("POST")) {
            if (!"fixture-token".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"))) UNEXPECTED_REQUESTS.incrementAndGet();
            CANCEL_REQUESTS.incrementAndGet(); STATUS.set("CANCELLED"); SEQUENCE.incrementAndGet();
        } else if (path.contains("/calls/") && exchange.getRequestMethod().equals("GET")) {
            DETAIL_REQUESTS.incrementAndGet();
            send(exchange, "application/json", "{\"prompt\":\"diagnostic-prompt <img src=x onerror=window.injected=true>\",\"response\":\"diagnostic-response\",\"truncated\":true}");
            return;
        } else if (!path.equals("/api/analysis-runs/" + ID) || !exchange.getRequestMethod().equals("GET")) {
            UNEXPECTED_REQUESTS.incrementAndGet();
        }
        long now = System.currentTimeMillis();
        int seq = SEQUENCE.get();
        send(exchange, "application/json", """
                {"operationId":"%s","sequence":%d,"status":"%s","phase":"%s","node":"CP",
                 "serverTime":%d,"startedAt":%d,"lastActivityAt":%d,"evaluatedNodes":%d,
                 "rawScores":%s,"memory":{"percent":94,"warning":true},"databaseStorage":"FILE",
                 "indexStorage":"FILESYSTEM_OR_EXTERNAL","omittedCalls":0,
                 "calls":[{"id":1,"provider":"MOCK","node":"CP","status":"%s","startedAt":%d,"durationMillis":1000}]}
                """.formatted(ID, seq, STATUS.get(), seq == 1 ? "LLM_REQUEST" : "SCORING", now, now - 5000,
                now - 1000, seq == 1 ? 0 : 1, seq == 1 ? "{}" : "{\"CP\":80}", seq == 1 ? "STARTED" : "COMPLETED", now - 2000));
    }

    private static void send(HttpExchange exchange, String type, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }
}
