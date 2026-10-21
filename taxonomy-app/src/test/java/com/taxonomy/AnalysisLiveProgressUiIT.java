package com.taxonomy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.UriComponentsBuilder;
import com.taxonomy.testsupport.BrowserSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-browser component contract. The application/use-case and authorization boundaries have separate Java tests. */
@Tag("ui-acceptance")
@Tag("browser")
class AnalysisLiveProgressUiIT {
    static final String ID = "cb2a3d71-e849-4a50-9855-1f9cb8f81402";
    private static final AtomicReference<String> STATUS = new AtomicReference<>("RUNNING");
    private static final AtomicReference<String> WORK = new AtomicReference<>("");
    private static final AtomicReference<String> PHASE = new AtomicReference<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
    private static final AtomicInteger DETAIL_REQUESTS = new AtomicInteger();
    private static final AtomicInteger CANCEL_REQUESTS = new AtomicInteger();
    private static final AtomicInteger UNEXPECTED_REQUESTS = new AtomicInteger();
    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static BrowserSession session;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;
    private static String origin;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", AnalysisLiveProgressUiIT::serve);
        server.start();
        session = BrowserSession.open(server.getAddress().getPort(), Path.of("target/analysis-ui-evidence/downloads"));
        origin = session.origin();
        driver = session.driver();
        wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    @AfterAll static void stop() throws Exception {
        try { if (session != null) session.close(); }
        finally {
            if (server != null) server.stop(0);
            if (serverExecutor != null) serverExecutor.shutdownNow();
        }
    }

    @BeforeEach void reset() {
        STATUS.set("RUNNING"); WORK.set(""); PHASE.set(null); SEQUENCE.set(1);
        ((HasCdp) new Augmenter().augment(driver)).executeCdpCommand("Emulation.clearDeviceMetricsOverride", Map.of());
        ((HasCdp) new Augmenter().augment(driver)).executeCdpCommand("Emulation.setEmulatedMedia", Map.of("features", List.of()));
        DETAIL_REQUESTS.set(0); CANCEL_REQUESTS.set(0); UNEXPECTED_REQUESTS.set(0);
        driver.get(origin + "/fixture");
        wait.until(browser -> browser.findElement(By.id("analysisLiveProgress")).getText().contains("START")
                || browser.findElement(By.id("llmCommLogContent")).getText().contains("STARTED"));
    }

    @Test void pendingCallMemoryWarningAndPartialScoresAreVisibleBeforeCompletion() throws Exception {
        wait.until(browser -> browser.findElement(By.id("llmCommLogContent")).getText().contains("STARTED"));
        assertThat(driver.findElement(By.id("analysisLiveProgress")).getText())
                .contains("LLM-Antwort", "Speicherwarnung", "94%").doesNotContain("IN_MEMORY", "Server-Lebenszeichen");
        assertThat(driver.executeScript("return document.getElementById('analysisProgressDiagnostics').open")).isEqualTo(false);
        driver.findElement(By.cssSelector("#analysisProgressDiagnostics summary")).click();
        assertThat(driver.findElement(By.id("analysisProgressDiagnostics")).getText()).contains("Server-Lebenszeichen", "94%");
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

    @Test void aCallExpandedWhilePendingLoadsDetailsWhenItsResponseArrives() {
        var summary = driver.findElement(By.cssSelector("#llmCommLogContent summary"));
        summary.click();
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript(
                "return document.querySelector('#llmCommLogContent details').open")));
        wait.until(browser -> browser.findElement(By.id("llmCommLogContent")).getText().contains("diagnostic-prompt"));
        assertThat(DETAIL_REQUESTS.get()).isEqualTo(1);
        assertThat(driver.findElement(By.id("llmCommLogContent")).getText()).doesNotContain("diagnostic-response");
        SEQUENCE.set(2);
        wait.until(browser -> browser.findElement(By.id("llmCommLogContent")).getText().contains("diagnostic-response"));
        assertThat(DETAIL_REQUESTS.get()).isEqualTo(2);
        long observations = ((Number) driver.executeScript("return window.progressObservations")).longValue();
        // A second real observation of the same durable revision must not load details again.
        wait.until(browser -> ((Number) driver.executeScript("return window.progressObservations")).longValue() > observations);
        wait.until(browser -> browser.findElement(By.id("partialScores")).getText().contains("80"));
        assertThat(DETAIL_REQUESTS.get()).isEqualTo(2);
        assertThat(driver.findElements(By.cssSelector("#llmCommLogContent img"))).isEmpty();
        assertThat(driver.executeScript("return window.injected || false")).isEqualTo(false);
    }

    @Test void authoritativeResponseFinishesThePanelBeforeTheNextPoll() {
        driver.executeScript("window.monitor.finish('SUCCESS')");
        assertThat(driver.findElement(By.id("analysisLiveProgress")).getText())
                .contains("Analyse beendet", "Vollständiges Ergebnis empfangen.");
        assertThat(driver.findElement(By.cssSelector("#analysisLiveProgress button")).isEnabled()).isFalse();
        assertThat(CANCEL_REQUESTS.get()).isZero();
    }

    @Test void finalHttpResponseRefreshesTheLastPendingLogWithoutReplacingFinalScores() {
        driver.findElement(By.cssSelector("#llmCommLogContent summary")).click();
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript(
                "return document.querySelector('#llmCommLogContent details').open")));
        wait.until(browser -> browser.findElement(By.id("llmCommLogContent")).getText().contains("diagnostic-prompt"));
        assertThat(DETAIL_REQUESTS.get()).isEqualTo(1);
        assertThat(driver.findElement(By.id("llmCommLogContent")).getText()).doesNotContain("diagnostic-response");
        // Freeze periodic observation: only the authoritative HTTP completion may fetch next.
        driver.executeScript("window.monitor.stop()");
        String previousScores = driver.findElement(By.id("partialScores")).getText();
        STATUS.set("COMPLETED"); SEQUENCE.set(2);
        driver.executeScript("window.monitor.finish('SUCCESS')");
        assertThat(driver.findElement(By.cssSelector("#analysisLiveProgress button")).isEnabled()).isFalse();
        wait.until(browser -> browser.findElement(By.id("llmCommLogContent")).getText()
                .contains("diagnostic-response"));
        assertThat(driver.findElement(By.id("llmCommLogContent")).getText()).contains("COMPLETED");
        assertThat(driver.findElement(By.id("partialScores")).getText()).isEqualTo(previousScores);
        assertThat(driver.findElement(By.id("analysisLiveProgress")).getText())
                .contains("Vollständiges Ergebnis empfangen.");
        assertThat(DETAIL_REQUESTS.get()).isEqualTo(2);
        assertThat(CANCEL_REQUESTS.get()).isZero();
    }

    @Test void explicitCancellationIsPinnedAndNeverStartsAnotherAnalysis() {
        driver.findElement(By.cssSelector("#analysisLiveProgress button")).click();
        wait.until(browser -> CANCEL_REQUESTS.get() == 1);
        wait.until(browser -> !browser.findElement(By.cssSelector("#analysisLiveProgress button")).isEnabled());
        assertThat(STATUS.get()).isEqualTo("CANCELLED");
        assertThat(UNEXPECTED_REQUESTS.get()).isZero();
    }

    @Test void sharedTaxonomyBarsRemainVisibleAtMobileWidthAndPreserveKeyboardDisclosureFocus() throws Exception {
        WORK.set("""
                ,"nodeProgress":{"total":12,"assessed":4,"excluded":3,"open":5,"taxonomies":[
                  {"root":"BP","nameEn":"Business processes","nameDe":"Geschäftsprozesse mit einer langen Bezeichnung","total":8,"assessed":3,"excluded":2,"open":3,"positive":2,"allocatedPercent":0.125,"rootPercent":80},
                  {"root":"IP","nameEn":"Information products","nameDe":"Informationsprodukte","total":4,"assessed":1,"excluded":1,"open":2,"positive":1,"allocatedPercent":20,"rootPercent":20}]},
                 "relationProgress":{"totalSources":3,"assessedSources":2,"totalSearches":10,"completedSearches":4,
                  "unresolvedSearches":2,"pendingSearches":4,"calls":8,"maxCalls":12,"verifiedRelations":99,"step":"NAVIGATE","current":null,
                  "taxonomies":[{"root":"IP","total":4,"completed":2,"unresolved":1,"pending":1},{"root":"BP","total":6,"completed":2,"unresolved":1,"pending":3}],
                  "sourceTaxonomies":[{"root":"BP","total":2,"completed":1,"unresolved":1},{"root":"IP","total":1,"completed":0,"unresolved":0}]}
                """);
        SEQUENCE.incrementAndGet();
        wait.until(browser -> browser.findElements(By.cssSelector("#analysisWorkProgress progress")).size() == 4);
        assertThat(driver.findElements(By.cssSelector("progress[data-taxonomy-root]"))).hasSize(2);
        assertThat(driver.findElements(By.cssSelector("progress[data-progress-kind='relations'][data-taxonomy-root]"))).isEmpty();
        assertThat(driver.executeScript("return document.getElementById('analysisTaxonomyProgressDetails').open")).isEqualTo(true);
        assertThat(driver.findElement(By.cssSelector("progress[data-progress-kind='nodes'][data-taxonomy-root='BP']")).isDisplayed()).isTrue();
        assertThat(driver.findElement(By.cssSelector("progress[data-progress-kind='nodes'][data-taxonomy-root='BP']")).getAttribute("value")).isEqualTo("5");
        var target = driver.findElement(By.cssSelector("progress[data-progress-kind='nodes'][data-taxonomy-root='IP']"));
        assertThat(target.getAttribute("value")).isEqualTo("2");
        assertThat(target.getAttribute("max")).isEqualTo("4");
        assertThat(target.getAccessibleName()).contains("IP");
        assertThat(target.getAttribute("aria-valuetext")).contains("ungeklärt", "offen");
        assertThat(driver.findElement(By.cssSelector("progress[data-taxonomy-root='BP']")).getAttribute("data-relations-completed")).isEqualTo("1");
        assertThat(driver.findElement(By.id("analysis-taxonomy-counts-BP")).getText()).contains("Gesamt", "Verarbeitet", "Nichtnull", "Verteilt", "0,125%");
        assertThat(driver.findElement(By.id("analysisWorkProgress")).getText()).contains(
                "99 durch KI verifizierte Beziehungsnachweise",
                "Mehrere Nachweise können dieselbe Beziehung belegen.",
                "Die Vorschlagsliste enthält separat gespeicherte Katalogvorschläge.",
                "3 durch übergeordnete Entscheidung ausgeschlossen");
        var cdp = (HasCdp) new Augmenter().augment(driver);
        cdp.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of("width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
        cdp.executeCdpCommand("Emulation.setEmulatedMedia", Map.of("features", List.of(
                Map.of("name", "forced-colors", "value", "active"), Map.of("name", "prefers-reduced-motion", "value", "reduce"))));
        assertThat(driver.executeScript("return matchMedia('(forced-colors: active)').matches && matchMedia('(prefers-reduced-motion: reduce)').matches")).isEqualTo(true);
        assertThat(driver.executeScript("""
                return [...document.querySelectorAll('#analysisWorkProgress progress')].every(p => {
                    const b=p.getBoundingClientRect(), s=getComputedStyle(p);
                    return b.width > 0 && b.right <= innerWidth && b.left >= 0 && s.animationName === 'none' && s.borderTopStyle === 'solid';
                }) && document.documentElement.scrollWidth <= innerWidth;
                """)).isEqualTo(true);
        Files.createDirectories(Path.of("target", "analysis-ui-evidence"));
        byte[] forcedColors = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        Files.write(Path.of("target", "analysis-ui-evidence", "taxonomy-target-progress-mobile-forced-colors.png"), forcedColors);
        var painted = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(forcedColors));
        var bounds = driver.findElement(By.cssSelector("progress[data-progress-kind='nodes']:not([data-taxonomy-root])")).getRect();
        assertThat(painted.getRGB(bounds.getX() + bounds.getWidth() / 4, bounds.getY() + bounds.getHeight() / 2))
                .as("Known progress has a visible filled segment in forced colors")
                .isNotEqualTo(painted.getRGB(bounds.getX() + 3 * bounds.getWidth() / 4, bounds.getY() + bounds.getHeight() / 2));
        driver.findElement(By.id("analysisTaxonomyProgressToggle")).sendKeys(Keys.SPACE);
        wait.until(browser -> Boolean.FALSE.equals(driver.executeScript("return document.getElementById('analysisTaxonomyProgressDetails').open")));
        long observations = ((Number) driver.executeScript("return progressObservations")).longValue();
        driver.executeScript("document.documentElement.lang='en'"); SEQUENCE.incrementAndGet();
        wait.until(browser -> ((Number) driver.executeScript("return progressObservations")).longValue() > observations);
        assertThat(driver.executeScript("return document.activeElement.id")).isEqualTo("analysisTaxonomyProgressToggle");
        assertThat(driver.executeScript("return document.getElementById('analysisTaxonomyProgressDetails').open")).isEqualTo(false);
        assertThat(driver.findElement(By.cssSelector("progress[data-progress-kind='nodes'][data-taxonomy-root='BP']")).getAttribute("aria-valuetext"))
                .contains("directly assessed", "excluded by parent decision", "open");
        driver.findElement(By.id("analysisTaxonomyProgressToggle")).sendKeys(Keys.SPACE);
        wait.until(browser -> browser.findElement(By.cssSelector("progress[data-progress-kind='nodes'][data-taxonomy-root='BP']")).isDisplayed());
        assertThat(driver.findElement(By.id("analysisWorkProgress")).getText()).contains("Business processes");
    }

    @Test void emptyPlanningAndStoppedRelationsNeverBecomeSuccessfulFullBars() {
        WORK.set("""
                ,"relationProgress":{"totalSources":0,"assessedSources":0,"totalSearches":0,"completedSearches":0,
                  "unresolvedSearches":0,"pendingSearches":0,"calls":0,"maxCalls":12,"verifiedRelations":0,"step":"SOURCES","current":null,"taxonomies":[]}
                """);
        SEQUENCE.incrementAndGet();
        wait.until(browser -> !browser.findElements(By.cssSelector("progress[data-progress-kind='relations']")).isEmpty());
        assertThat(driver.executeScript("return document.querySelector('progress[data-progress-kind=relations]').position")).isEqualTo(-1L);
        assertThat(driver.findElement(By.cssSelector("progress[data-progress-kind='relations']")).getAttribute("aria-valuetext")).contains("noch nicht bekannt");
        WORK.set("""
                ,"relationProgress":{"totalSources":3,"assessedSources":2,"totalSearches":10,"completedSearches":4,
                  "unresolvedSearches":2,"pendingSearches":4,"calls":8,"maxCalls":12,"verifiedRelations":99,"step":"PAUSED","current":null,
                  "taxonomies":[{"root":"IP","total":10,"completed":4,"unresolved":2,"pending":4}]}
                """);
        STATUS.set("CANCELLED"); SEQUENCE.incrementAndGet();
        wait.until(browser -> "CANCELLED".equals(browser.findElement(By.id("analysisWorkProgress")).getAttribute("data-run-status")));
        assertThat(driver.findElement(By.cssSelector("progress[data-progress-kind='relations'][data-taxonomy-root='IP']")).getAttribute("value")).isEqualTo("4");
        assertThat(driver.findElement(By.id("analysisLiveProgress")).getText()).contains("Abgebrochen", "unvollständig");
        assertThat(driver.findElement(By.id("analysisLiveProgress")).getText()).doesNotContain("Vollständiges Ergebnis empfangen");
    }

    @Test void eightSharedTaxonomyBarsStayWithinThePanelAtDesktopAndMobileWidths() throws Exception {
        var roots = List.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
        var english = List.of("Business processes", "Business roles", "Capabilities", "COI services",
                "Communications services", "Communications resources", "Information products", "User applications");
        var german = List.of("Geschäftsprozesse", "Geschäftsrollen", "Fähigkeiten", "COI-Dienste",
                "Kommunikationsdienste", "Kommunikationsressourcen", "Informationsprodukte", "Benutzeranwendungen");
        var nodes = new java.util.ArrayList<String>();
        var targets = new java.util.ArrayList<String>();
        var sources = new java.util.ArrayList<String>();
        for (int index = 0; index < roots.size(); index++) {
            nodes.add("""
                    {"root":"%s","nameEn":"%s","nameDe":"%s","total":100,"assessed":35,"excluded":25,"open":40,"positive":20,"allocatedPercent":37.5,"rootPercent":80}
                    """.formatted(roots.get(index), english.get(index), german.get(index)));
            targets.add("{\"root\":\"%s\",\"total\":10,\"completed\":4,\"unresolved\":1,\"pending\":5}".formatted(roots.get(index)));
            sources.add("{\"root\":\"%s\",\"total\":5,\"completed\":2,\"unresolved\":1}".formatted(roots.get(index)));
        }
        driver.executeScript("window.TaxonomyState={taxonomyData:JSON.parse(arguments[0]).map(n=>({...n,code:n.root}))}", "[" + String.join(",", nodes) + "]");
        WORK.set("""
                ,"nodeProgress":{"total":800,"assessed":280,"excluded":200,"open":320,"taxonomies":[%s]},
                 "relationProgress":{"totalSources":40,"assessedSources":32,"totalSearches":80,"completedSearches":32,
                  "unresolvedSearches":8,"pendingSearches":40,"calls":12,"maxCalls":24,"verifiedRelations":7,"step":"PAUSED","current":null,"taxonomies":[%s],"sourceTaxonomies":[%s]}
                """.formatted(String.join(",", nodes), String.join(",", targets), String.join(",", sources)));
        PHASE.set("FINISHED"); STATUS.set("PARTIAL"); SEQUENCE.incrementAndGet();
        wait.until(browser -> browser.findElements(By.cssSelector("#analysisWorkProgress progress")).size() == 10
                && "PARTIAL".equals(browser.findElement(By.id("analysisWorkProgress")).getAttribute("data-run-status")));
        var cdp = (HasCdp) new Augmenter().augment(driver);
        for (var size : List.of(Map.of("width", 1280, "height", 1200), Map.of("width", 390, "height", 844))) {
            cdp.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of("width", size.get("width"), "height", size.get("height"), "deviceScaleFactor", 1, "mobile", false));
            assertThat(driver.findElements(By.cssSelector("progress[data-taxonomy-root]"))).hasSize(8).allSatisfy(bar -> {
                assertThat(bar.isDisplayed()).isTrue();
                assertThat(bar.getAttribute("data-positive")).isEqualTo("20");
                assertThat(bar.getAttribute("data-relations-completed")).isEqualTo("2");
            });
            assertThat(driver.executeScript("""
                    return [...document.querySelectorAll('#analysisWorkProgress progress')].every(p => {
                        const b=p.getBoundingClientRect();return b.width > 0 && b.right <= innerWidth && b.left >= 0;
                    }) && document.documentElement.scrollWidth <= innerWidth;
                    """)).isEqualTo(true);
            var bounds = (Map<?, ?>) cdp.executeCdpCommand("Page.getLayoutMetrics", Map.of()).get("cssContentSize");
            var screenshot = cdp.executeCdpCommand("Page.captureScreenshot", Map.of("format", "png", "captureBeyondViewport", true,
                    "clip", Map.of("x", 0, "y", 0, "width", bounds.get("width"), "height", bounds.get("height"), "scale", 1)));
            Path evidence = Path.of("target", "analysis-ui-evidence", size.get("width") == 390 ? "taxonomy-eight-progress-mobile.png" : "taxonomy-eight-progress-desktop.png");
            Files.createDirectories(evidence.getParent());
            Files.write(evidence, Base64.getDecoder().decode((String) screenshot.get("data")));
            String geometry = (String) driver.executeScript("""
                    return JSON.stringify({viewportWidth:innerWidth, pageHeight:document.documentElement.scrollHeight,
                        panelHeight:document.getElementById('analysisLiveProgress').getBoundingClientRect().height,
                        progressHeight:document.getElementById('analysisWorkProgress').getBoundingClientRect().height,
                        maximumTaxonomyRowHeight:Math.max(...[...document.querySelectorAll('.analysis-taxonomy-progress .analysis-work-meter')].map(p=>p.getBoundingClientRect().height))}, null, 2);
                    """);
            Files.writeString(evidence.resolveSibling(evidence.getFileName().toString().replace(".png", ".json")), geometry);
        }
    }

    static void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/fixture")) {
            send(exchange, "text/html", """
                    <!doctype html><html lang="de"><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <meta name="_csrf" content="fixture-token"><meta name="_csrf_header" content="X-CSRF-TOKEN">
                    <link rel="stylesheet" href="/progress.css">
                    <style>body{font:16px sans-serif;margin:2rem}.alert{padding:1rem;border:1px solid #999}pre{white-space:pre-wrap}</style>
                    </head><body><div id="statusArea"></div><div id="partialScores"></div><div id="llmCommLogContent"></div>
                    <script src="/api-client.js"></script><script src="/analysis-session-api.js"></script>
                    <script>window.__TaxonomyAnalysisSessionContext={runtime:{workspaceId:'workspace-a',analysisGeneration:1}};</script>
                    <script src="/analysis-session-routing.js"></script><script src="/progress.js"></script><script>
                    window.progressObservations=0;
                    document.addEventListener('taxonomy:analysis-progress',function(){window.progressObservations++;});
                    window.monitor=TaxonomyAnalysisProgress.start('%s',function(s){
                        document.getElementById('partialScores').textContent=JSON.stringify(s.rawScores);
                    });</script></body></html>
                    """.formatted(ID));
            return;
        }
        String script = switch (path) {
            case "/progress.js" -> "static/js/core/taxonomy-analysis-progress.js";
            case "/api-client.js" -> "static/js/api/taxonomy-api-client.js";
            case "/analysis-session-api.js" -> "static/js/api/analysis-session-api.js";
            case "/analysis-session-routing.js" -> "static/js/core/taxonomy-analysis-session-api-routing.js";
            case "/js/security/taxonomy-role-surface.js" -> "static/js/security/taxonomy-role-surface.js";
            case "/js/security/taxonomy-ui-semantics.js" -> "static/js/security/taxonomy-ui-semantics.js";
            default -> null;
        };
        if (path.equals("/progress.css")) {
            send(exchange, "text/css", new ClassPathResource("static/css/taxonomy.css").getContentAsString(StandardCharsets.UTF_8));
            return;
        }
        if (script != null) {
            send(exchange, "application/javascript", new ClassPathResource(script)
                    .getContentAsString(StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/api/account/me")) {
            send(exchange, "application/json", """
                    {"username":"fixture-user","roles":[],"administrator":false,"architectureMutationAllowed":false}
                    """);
            return;
        }
        if (path.equals("/favicon.ico")) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
        // Admission polling adds its own flag; scope depends on the workspace parameter, not query order.
        var query = UriComponentsBuilder.fromUri(exchange.getRequestURI()).build().getQueryParams();
        boolean pinned = "workspace-a".equals(exchange.getRequestHeaders().getFirst("X-Taxonomy-Workspace-Id"))
                && List.of("workspace-a").equals(query.get("workspaceId"));
        if (!pinned) { UNEXPECTED_REQUESTS.incrementAndGet(); exchange.sendResponseHeaders(403, -1); exchange.close(); return; }
        if (path.endsWith("/cancel") && exchange.getRequestMethod().equals("POST")) {
            if (!"fixture-token".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"))) UNEXPECTED_REQUESTS.incrementAndGet();
            CANCEL_REQUESTS.incrementAndGet(); STATUS.set("CANCELLED"); SEQUENCE.incrementAndGet();
        } else if (path.contains("/calls/") && exchange.getRequestMethod().equals("GET")) {
            DETAIL_REQUESTS.incrementAndGet();
            String response = SEQUENCE.get() == 1 ? "" : "diagnostic-response";
            send(exchange, "application/json", "{\"prompt\":\"diagnostic-prompt <img src=x onerror=window.injected=true>\",\"response\":\"" + response + "\",\"truncated\":true}");
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
                 "calls":[{"id":1,"provider":"MOCK","node":"CP","status":"%s","startedAt":%d,"durationMillis":1000}]%s}
                """.formatted(ID, seq, STATUS.get(), PHASE.get() == null ? seq == 1 ? "LLM_REQUEST" : "SCORING" : PHASE.get(), now, now - 5000,
                now - 1000, seq == 1 ? 0 : 1, seq == 1 ? "{}" : "{\"CP\":80}", seq == 1 ? "STARTED" : "COMPLETED", now - 2000, WORK.get()));
    }

    private static void send(HttpExchange exchange, String type, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }
}
