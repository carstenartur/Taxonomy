package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-browser budgets for representative and 1,000-result taxonomy searches. */
@Tag("ui-acceptance")
class TaxonomyLargeResultBudgetIT {

    private static final String ADMIN_PASSWORD = "Large-Result-Budget-2026!";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Network network;
    private static GenericContainer<?> application;
    private static ContainerTestUtils.BrowserSession browserSession;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;
    private static BudgetPolicy policy;
    private static Path evidencePath;
    private static final List<Map<String, Object>> evidence = new ArrayList<>();

    @BeforeAll
    static void startApplicationAndBrowser() throws Exception {
        Path root = findRepositoryRoot();
        policy = JSON.readValue(
                root.resolve(".github/large-result-budget.json").toFile(),
                BudgetPolicy.class);
        assertThat(policy.schemaVersion()).isEqualTo(1);
        evidencePath = root.resolve(
                "target/ui-verification/large-results/report.json");

        network = Network.newNetwork();
        application = ContainerTestUtils.appContainer(network)
                .withEnv("TAXONOMY_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false")
                .withEnv("TAXONOMY_INIT_ASYNC", "true")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "false")
                .withEnv("LLM_MOCK", "true");
        application.start();

        browserSession = ContainerTestUtils.startBrowser(network);
        driver = browserSession.driver();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        wait = new WebDriverWait(driver, Duration.ofSeconds(120));
        login();
        openAnalyzer();
        installSyntheticSearchBoundary();
    }

    @AfterAll
    static void stopApplicationAndBrowser() throws Exception {
        writeEvidence();
        ContainerTestUtils.closeAll(browserSession, application, network);
    }

    @Test
    void representativeAndLargeResultSetsStayBoundedAndNavigable() throws Exception {
        long initialTaxonomyNodes = number(execute(
                "return document.querySelectorAll('#taxonomyTree .tax-node').length"));
        long deferredContainers = number(execute(
                "return document.querySelectorAll('#taxonomyTree [data-render-state=\"deferred\"]').length"));
        assertThat(initialTaxonomyNodes)
                .as("initial taxonomy DOM nodes")
                .isLessThanOrEqualTo(policy.maxInitialTaxonomyDomNodes());
        assertThat(deferredContainers)
                .as("deferred taxonomy child containers")
                .isPositive();

        for (Scenario scenario : policy.scenarios()) {
            Map<String, Object> metrics = exerciseScenario(
                    scenario, initialTaxonomyNodes, deferredContainers);
            evidence.add(metrics);
            writeEvidence();
            assertScenario(metrics, scenario);
        }
    }

    private static Map<String, Object> exerciseScenario(
            Scenario scenario,
            long initialTaxonomyNodes,
            long deferredContainers) {
        driver.manage().window().setSize(new Dimension(1440, 1000));
        execute("document.documentElement.style.fontSize='';"
                + "const panel=document.querySelector('#searchPanel');"
                + "panel.open=true;"
                + "const area=document.querySelector('#searchResultsArea');"
                + "area.innerHTML=''; area.style.display='none'; area.scrollTop=0;");

        Map<?, ?> baseline = map(execute("""
                const area = document.querySelector('#searchResultsArea');
                return {
                  documentHeight: document.documentElement.scrollHeight,
                  heap: performance.memory ? performance.memory.usedJSHeapSize : null,
                  areaNodes: area.querySelectorAll('*').length
                };
                """));

        execute("""
                window.__taxonomySearchLongTasks = [];
                window.__taxonomyBudgetInstall(arguments[0]);
                window.__taxonomySearchStarted = performance.now();
                window.TaxonomySearch.performSearch(
                    'budget-' + arguments[0], 'fulltext', arguments[0]);
                """, scenario.resultCount());

        wait.until(browser -> scenario.resultCount() == number(execute(
                "return Number(document.querySelector('#searchResultsArea').dataset.totalResults || 0)")));
        wait.until(browser -> number(execute(
                "return document.querySelectorAll('#searchResultsArea .search-result-item').length")) > 0);
        sleep(150);

        Map<?, ?> measured = map(execute("""
                const area = document.querySelector('#searchResultsArea');
                const list = area.querySelector('.search-results-list');
                const tasks = window.__taxonomySearchLongTasks || [];
                const heap = performance.memory ? performance.memory.usedJSHeapSize : null;
                return {
                  totalResults: Number(area.dataset.totalResults || 0),
                  renderedResults: area.querySelectorAll('.search-result-item').length,
                  resultAreaDomNodes: area.querySelectorAll('*').length,
                  resultAreaClientHeight: area.clientHeight,
                  resultAreaScrollHeight: area.scrollHeight,
                  resultListHeight: list ? Math.ceil(list.getBoundingClientRect().height) : 0,
                  documentHeight: document.documentElement.scrollHeight,
                  renderDurationMs: performance.now() - window.__taxonomySearchStarted,
                  longestTaskMs: tasks.length ? Math.max(...tasks) : 0,
                  heap: heap,
                  truncatedNames: area.querySelectorAll(
                    '.search-result-name.text-truncate').length,
                  summaryText: document.querySelector('#searchResultSummary')?.textContent || '',
                  filterText: document.querySelector('#searchActiveFilters')?.textContent || ''
                };
                """));

        Map<?, ?> interaction = map(executeAsync("""
                const done = arguments[arguments.length - 1];
                const started = performance.now();
                document.querySelector('[data-search-result-nav="next"]').click();
                requestAnimationFrame(() => requestAnimationFrame(() => done({
                  latencyMs: performance.now() - started,
                  currentIndex: Number(document.querySelector('#searchResultsArea')
                    .dataset.currentIndex || -1),
                  activeClass: document.activeElement?.className || '',
                  path: document.querySelector('#searchCurrentPath')?.textContent || ''
                })));
                """));

        Map<?, ?> returnContext = map(executeAsync("""
                const done = arguments[arguments.length - 1];
                const area = document.querySelector('#searchResultsArea');
                area.scrollTop = 100;
                document.querySelector('[data-search-result-nav="summary"]').click();
                requestAnimationFrame(() => done({
                  activeId: document.activeElement?.id || '',
                  areaScrollTop: area.scrollTop
                }));
                """));

        Map<String, Object> zoomEvidence = scenario.resultCount() >= 1000
                ? verifyResponsiveZoom()
                : Map.of();

        long heapBefore = nullableNumber(baseline.get("heap"), -1);
        long heapAfter = nullableNumber(measured.get("heap"), -1);
        Long heapIncrease = heapBefore >= 0 && heapAfter >= 0
                ? Math.max(0L, heapAfter - heapBefore)
                : null;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("scenario", scenario.id());
        metrics.put("resultCount", scenario.resultCount());
        metrics.put("initialTaxonomyDomNodes", initialTaxonomyNodes);
        metrics.put("deferredTaxonomyContainers", deferredContainers);
        metrics.put("totalResults", number(measured.get("totalResults")));
        metrics.put("renderedResults", number(measured.get("renderedResults")));
        metrics.put("resultAreaDomNodes", number(measured.get("resultAreaDomNodes")));
        metrics.put("resultAreaClientHeightPx", number(measured.get("resultAreaClientHeight")));
        metrics.put("resultAreaScrollHeightPx", number(measured.get("resultAreaScrollHeight")));
        metrics.put("resultListHeightPx", number(measured.get("resultListHeight")));
        metrics.put("documentHeightIncreasePx", Math.max(0L,
                number(measured.get("documentHeight"))
                        - number(baseline.get("documentHeight"))));
        metrics.put("renderDurationMs", decimal(measured.get("renderDurationMs")));
        metrics.put("longestTaskMs", decimal(measured.get("longestTaskMs")));
        metrics.put("heapIncreaseBytes", heapIncrease);
        metrics.put("truncatedNames", number(measured.get("truncatedNames")));
        metrics.put("summaryText", measured.get("summaryText"));
        metrics.put("filterText", measured.get("filterText"));
        metrics.put("interactionLatencyMs", decimal(interaction.get("latencyMs")));
        metrics.put("currentIndex", number(interaction.get("currentIndex")));
        metrics.put("activeClass", interaction.get("activeClass"));
        metrics.put("currentPath", interaction.get("path"));
        metrics.put("returnActiveId", returnContext.get("activeId"));
        metrics.put("returnAreaScrollTop", number(returnContext.get("areaScrollTop")));
        metrics.put("responsiveZoom", zoomEvidence);
        return metrics;
    }

    private static Map<String, Object> verifyResponsiveZoom() {
        driver.manage().window().setSize(new Dimension(390, 844));
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String zoom : List.of("200%", "400%")) {
            execute("document.documentElement.style.fontSize=arguments[0]", zoom);
            sleep(100);
            long overflow = number(execute("""
                    const panel = document.querySelector('#searchPanel');
                    const area = document.querySelector('#searchResultsArea');
                    return Math.max(
                      0,
                      panel.scrollWidth - panel.clientWidth,
                      area.scrollWidth - area.clientWidth);
                    """));
            evidence.put(zoom, overflow);
        }
        execute("document.documentElement.style.fontSize=''");
        driver.manage().window().setSize(new Dimension(1440, 1000));
        return evidence;
    }

    private static void assertScenario(Map<String, Object> metrics, Scenario scenario) {
        assertThat(number(metrics.get("totalResults")))
                .as(scenario.id() + " total result count")
                .isEqualTo(scenario.resultCount());
        assertThat(number(metrics.get("renderedResults")))
                .as(scenario.id() + " rendered result window")
                .isLessThanOrEqualTo(policy.resultWindowSize());
        assertThat(number(metrics.get("resultAreaDomNodes")))
                .as(scenario.id() + " result-area DOM nodes")
                .isLessThanOrEqualTo(policy.maxResultAreaDomNodes());
        assertThat(number(metrics.get("resultAreaClientHeightPx")))
                .as(scenario.id() + " result-area height")
                .isLessThanOrEqualTo(policy.maxResultAreaClientHeightPx());
        assertThat(number(metrics.get("documentHeightIncreasePx")))
                .as(scenario.id() + " document-height increase")
                .isLessThanOrEqualTo(policy.maxDocumentHeightIncreasePx());
        assertThat(decimal(metrics.get("renderDurationMs")))
                .as(scenario.id() + " render duration")
                .isLessThanOrEqualTo(policy.maxRenderDurationMs());
        assertThat(decimal(metrics.get("longestTaskMs")))
                .as(scenario.id() + " longest browser task")
                .isLessThanOrEqualTo(policy.maxLongestTaskMs());
        assertThat(decimal(metrics.get("interactionLatencyMs")))
                .as(scenario.id() + " next-result interaction")
                .isLessThanOrEqualTo(policy.maxInteractionLatencyMs());
        Object heap = metrics.get("heapIncreaseBytes");
        if (heap != null) {
            assertThat(number(heap))
                    .as(scenario.id() + " JavaScript heap increase")
                    .isLessThanOrEqualTo(policy.maxHeapIncreaseBytes());
        }
        assertThat(number(metrics.get("truncatedNames")))
                .as(scenario.id() + " collapsed result descriptions")
                .isEqualTo(number(metrics.get("renderedResults")));
        assertThat(number(metrics.get("currentIndex"))).isZero();
        assertThat(String.valueOf(metrics.get("activeClass")))
                .contains("search-result-item");
        assertThat(String.valueOf(metrics.get("currentPath")))
                .contains("BUDGET-0001");
        assertThat(metrics.get("returnActiveId")).isEqualTo("searchResultSummary");
        assertThat(number(metrics.get("returnAreaScrollTop"))).isZero();
        assertThat(String.valueOf(metrics.get("summaryText")))
                .contains(Integer.toString(scenario.resultCount()));
        assertThat(String.valueOf(metrics.get("filterText")))
                .contains("budget-" + scenario.resultCount());

        Object zoom = metrics.get("responsiveZoom");
        if (zoom instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                assertThat(number(entry.getValue()))
                        .as(scenario.id() + " horizontal overflow at " + entry.getKey())
                        .isLessThanOrEqualTo(policy.maxHorizontalOverflowPx());
            }
        }
    }

    private static void login() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();
        wait.until(browser -> !browser.getCurrentUrl().endsWith("/login"));
    }

    private static void openAnalyzer() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
        wait.until(browser -> !browser.findElements(
                By.cssSelector("#taxonomyTree .tax-node")).isEmpty());
        List<WebElement> dismissButtons = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissButtons.isEmpty() && dismissButtons.getFirst().isDisplayed()) {
            dismissButtons.getFirst().click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(
                    By.id("onboardingOverlay")));
        }
        execute("document.querySelector('#searchPanel').open=true");
    }

    private static void installSyntheticSearchBoundary() {
        execute("""
                window.__taxonomyBudgetOriginalFetch = window.fetch.bind(window);
                window.__taxonomySearchLongTasks = [];
                if (window.PerformanceObserver
                    && PerformanceObserver.supportedEntryTypes
                    && PerformanceObserver.supportedEntryTypes.includes('longtask')) {
                  window.__taxonomySearchLongTaskObserver = new PerformanceObserver(entries => {
                    entries.getEntries().forEach(entry => {
                      window.__taxonomySearchLongTasks.push(entry.duration);
                    });
                  });
                  window.__taxonomySearchLongTaskObserver.observe({entryTypes: ['longtask']});
                }
                window.__taxonomyBudgetInstall = function (count) {
                  const original = window.__taxonomyBudgetOriginalFetch;
                  window.fetch = function (input, init) {
                    const raw = input instanceof Request ? input.url : String(input);
                    const url = new URL(raw, window.location.href);
                    if (url.pathname === '/api/search') {
                      const nodes = Array.from({length: count}, (_, index) => ({
                        code: 'BUDGET-' + String(index + 1).padStart(4, '0'),
                        nameEn: 'Synthetic bounded result ' + String(index + 1)
                          + ' with an intentionally long description-like label',
                        matchPercentage: 100 - (index % 100)
                      }));
                      return Promise.resolve(new Response(JSON.stringify(nodes), {
                        status: 200,
                        headers: {'Content-Type': 'application/json'}
                      }));
                    }
                    return original(input, init);
                  };
                };
                """);
    }

    private static void writeEvidence() throws Exception {
        if (evidencePath == null) return;
        Files.createDirectories(evidencePath.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("policy", policy);
        report.put("scenarios", evidence);
        Files.writeString(
                evidencePath,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    }

    private static Object execute(String script, Object... arguments) {
        return javascript().executeScript(script, arguments);
    }

    private static Object executeAsync(String script, Object... arguments) {
        return javascript().executeAsyncScript(script, arguments);
    }

    private static JavascriptExecutor javascript() {
        return driver;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(Object value) {
        return (Map<?, ?>) value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static long nullableNumber(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static double decimal(Object value) {
        return ((Number) value).doubleValue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while collecting browser evidence", exception);
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            ".github/large-result-budget.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }

    record BudgetPolicy(
            int schemaVersion,
            int resultWindowSize,
            long maxInitialTaxonomyDomNodes,
            long maxResultAreaDomNodes,
            long maxResultAreaClientHeightPx,
            long maxDocumentHeightIncreasePx,
            double maxRenderDurationMs,
            double maxLongestTaskMs,
            double maxInteractionLatencyMs,
            long maxHeapIncreaseBytes,
            long maxHorizontalOverflowPx,
            List<Scenario> scenarios) {
    }

    record Scenario(String id, int resultCount) {
    }
}
