package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
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
@Tag("docker-required")
class TaxonomyLargeResultBudgetIT {

    private static final String ADMIN_PASSWORD = "Large-Result-Budget-2026!";
    private static final String NAVIGATION_COMPLETE_EVENT =
            "taxonomy:search-navigation-complete";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Network network;
    private static GenericContainer<?> application;
    private static ContainerTestUtils.BrowserSession browserSession;
    private static RemoteWebDriver driver;
    private static HasCdp cdp;
    private static WebDriverWait wait;
    private static BudgetPolicy policy;
    private static Path evidencePath;
    private static String realTaxonomyCode;
    private static final List<Map<String, Object>> evidence = new ArrayList<>();
    private static Map<String, Object> concurrencyEvidence = Map.of();

    @BeforeAll
    static void startApplicationAndBrowser() throws Exception {
        Path root = findRepositoryRoot();
        policy = JSON.readValue(
                root.resolve(".github/large-result-budget.json").toFile(),
                BudgetPolicy.class);
        assertThat(policy.schemaVersion()).isEqualTo(1);
        assertThat(policy.responsiveProfiles())
                .extracting(ResponsiveProfile::id)
                .containsExactly(
                        "mobile-portrait",
                        "mobile-landscape",
                        "zoom-200",
                        "zoom-400");
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

        // performance.memory otherwise caches/bucketizes values and can report
        // a zero delta even when the live heap grows beyond the budget.
        browserSession = ContainerTestUtils.startBrowser(network, "--enable-precise-memory-info");
        driver = browserSession.driver();
        var augmented = new Augmenter().augment(driver);
        assertThat(augmented)
                .as("Selenium browser must expose CDP device-metrics control")
                .isInstanceOf(HasCdp.class);
        cdp = (HasCdp) augmented;
        driver.manage().window().setSize(new Dimension(1440, 1000));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        wait = new WebDriverWait(driver, Duration.ofSeconds(120));
        login();
        openAnalyzer();
        installSyntheticSearchBoundary();
        realTaxonomyCode = String.valueOf(execute(
                "return window.__taxonomyBudgetRealCode || ''"));
        assertThat(realTaxonomyCode)
                .as("real taxonomy code used by large-result navigation evidence")
                .isNotBlank();
    }

    @AfterAll
    static void stopApplicationAndBrowser() throws Exception {
        writeEvidence();
        clearDeviceMetricsOverride();
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

    @Test
    void dslEditorLoadsOnDemandAndPreservesDraftAcrossNavigation() {
        try {
            openAnalyzer();
            assertThat(execute("return Boolean(window.dslCmView)"))
                    .as("analyzer startup must not create the hidden DSL editor")
                    .isEqualTo(Boolean.FALSE);
            assertThat(number(execute("""
                    return performance.getEntriesByType('resource').filter(entry =>
                      new URL(entry.name).pathname === '/api/dsl/export').length;
                    """)))
                    .as("analyzer startup must not fetch the hidden catalogue document")
                    .isZero();

            execute("""
                    window.__taxonomyBudgetEditorReady = 0;
                    document.querySelector('#dslEditorContainer').addEventListener('cm-ready', () => {
                      window.__taxonomyBudgetEditorReady++;
                    });
                    window.navigateToPage('dsl-editor');
                    """);
            wait.until(browser -> Boolean.TRUE.equals(execute(
                    "return Boolean(window.dslCmView?.state.doc.length)")));
            execute("window.__taxonomyBudgetEditor = window.dslCmView");
            WebElement editor = driver.findElement(
                    By.cssSelector("#dslEditorContainer .cm-content"));
            editor.click();
            editor.sendKeys(Keys.chord(Keys.CONTROL, Keys.END));
            editor.sendKeys("\n# retained local editor draft");
            String draft = String.valueOf(execute(
                    "return window.dslCmView.state.doc.toString()"));
            assertThat(draft).endsWith("# retained local editor draft");

            execute("window.navigateToPage('analyze'); window.navigateToPage('dsl-editor')");
            assertThat(execute("return window.dslCmView === window.__taxonomyBudgetEditor"))
                    .as("returning to the editor reuses its document and history")
                    .isEqualTo(Boolean.TRUE);
            assertThat(execute("return window.dslCmView.state.doc.toString()"))
                    .isEqualTo(draft);
            assertThat(number(execute("return window.__taxonomyBudgetEditorReady")))
                    .as("the editor is initialized once per document")
                    .isEqualTo(1);

            // Leave this document first: navigating to its current hash alone
            // can reuse the existing editor and miss a broken deep-link startup.
            driver.get("about:blank");
            driver.get(ContainerTestUtils.APP_ORIGIN + "/#dsl-editor");
            wait.until(browser -> Boolean.TRUE.equals(execute(
                    "return Boolean(window.dslCmView?.state.doc.length)")));
            assertThat(execute("return '__taxonomyBudgetEditor' in window"))
                    .as("the direct link loaded a new document, not the old editor")
                    .isEqualTo(Boolean.FALSE);
            assertThat(driver.findElement(By.id("dslEditorContainer")).isDisplayed())
                    .as("a direct DSL link initializes the visible editor")
                    .isTrue();
        } finally {
            openAnalyzer();
            installSyntheticSearchBoundary();
        }
    }

    @Test
    void newestSearchOwnsResultsAcrossStaleSuccessAndFailure() throws Exception {
        Map<String, Object> staleSuccess = exerciseSearchRace(
                "race-slow-success");
        Map<String, Object> staleFailure = exerciseSearchRace(
                "race-slow-failure");

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("staleSuccess", staleSuccess);
        result.put("staleFailure", staleFailure);
        concurrencyEvidence = result;
        writeEvidence();

        for (Map.Entry<String, Object> entry : result.entrySet()) {
            Map<?, ?> observation = map(entry.getValue());
            assertThat(observation.get("activeQuery"))
                    .as(entry.getKey() + " active query")
                    .isEqualTo("race-fast");
            assertThat(number(observation.get("totalResults")))
                    .as(entry.getKey() + " total results")
                    .isEqualTo(3);
            assertThat(observation.get("hasError"))
                    .as(entry.getKey() + " stale failure visibility")
                    .isEqualTo(Boolean.FALSE);
        }
    }

    @Test
    void renderDurationDoesNotIncludeDelayedDriverObservation() {
        captureSearchRender(3, 0);
        Map<?, ?> first = readSearchRenderTiming();
        // Deliberately stall the test driver after the browser has finished. This
        // must not become application render time, even beyond the entire budget.
        sleep((long) policy.maxRenderDurationMs() + 200);
        Map<?, ?> delayed = readSearchRenderTiming();
        assertThat(decimal(delayed.get("renderDurationMs")))
                .as("render duration remains frozen after completion")
                .isEqualTo(decimal(first.get("renderDurationMs")))
                .isPositive();
        assertThat(decimal(delayed.get("driverObservationDelayMs")))
                .as("late WebDriver observation is recorded separately")
                .isGreaterThan(policy.maxRenderDurationMs());
    }

    @Test
    void renderDurationStillIncludesSlowSearchResponses() {
        int responseDelay = (int) policy.maxRenderDurationMs() + 100;
        captureSearchRender(3, responseDelay);
        assertThat(decimal(readSearchRenderTiming().get("renderDurationMs")))
                .as("a genuinely delayed response must still exceed the unchanged render budget")
                .isGreaterThanOrEqualTo(responseDelay);
    }

    @Test
    void longestTaskDoesNotIncludeBrowserWorkAfterRenderCompletion() {
        captureSearchRender(3, 0);
        Map<?, ?> first = readSearchRenderTiming();
        int blockingMillis = (int) policy.maxLongestTaskMs() + 100;
        double observedBlock = decimal(executeAsync("""
                const done = arguments[arguments.length - 1];
                const blockingMillis = arguments[0];
                // A real browser task, not unobservable synchronous WebDriver work.
                setTimeout(() => {
                  const started = performance.now();
                  while (performance.now() - started < blockingMillis) { }
                  done(performance.now() - started);
                }, 0);
                """, blockingMillis));
        assertThat(observedBlock).isGreaterThanOrEqualTo(blockingMillis);
        Map<?, ?> afterBrowserWork = readSearchRenderTiming();
        assertThat(afterBrowserWork.get("longestTaskMs"))
                .as("completed search does not acquire a later unrelated browser task")
                .isEqualTo(first.get("longestTaskMs"));
        assertThat(afterBrowserWork.get("longTasks")).isEqualTo(first.get("longTasks"));
        assertThat(afterBrowserWork.get("renderDurationMs")).isEqualTo(first.get("renderDurationMs"));
    }

    @Test
    void longestTaskStillIncludesSlowSynchronousSearchRendering() {
        int blockingMillis = (int) policy.maxLongestTaskMs() + 100;
        execute("""
                const blockingMillis = arguments[0];
                const original = window.TaxonomySearch.performSearch;
                window.__taxonomyBudgetOriginalSearch = original;
                window.TaxonomySearch.performSearch = function (...args) {
                  const started = performance.now();
                  while (performance.now() - started < blockingMillis) { }
                  return original.apply(this, args);
                };
                """, blockingMillis);
        try {
            captureSearchRender(3, 0);
            Map<?, ?> timing = readSearchRenderTiming();
            assertThat(decimal(timing.get("longestTaskMs")))
                    .as("a slow real search task still violates the unchanged long-task budget")
                    .isGreaterThanOrEqualTo(blockingMillis);
        } finally {
            execute("""
                    window.TaxonomySearch.performSearch = window.__taxonomyBudgetOriginalSearch;
                    delete window.__taxonomyBudgetOriginalSearch;
                    """);
        }
    }

    /** Capture completion in the browser, not when a later WebDriver poll reads it. */
    private static void captureSearchRender(int resultCount, int responseDelayMillis) {
        Map<?, ?> completion = map(executeAsync("""
                const done = arguments[arguments.length - 1];
                const count = arguments[0];
                const area = document.querySelector('#searchResultsArea');
                document.querySelector('#analysisSecondaryTools').open = true;
                document.querySelector('#searchPanel').open = true;
                area.innerHTML = '';
                delete area.dataset.totalResults;
                window.__taxonomyBudgetInstall(count, arguments[1]);
                window.__taxonomySearchRenderTiming = null;
                if (!window.PerformanceObserver
                    || !PerformanceObserver.supportedEntryTypes.includes('longtask')) {
                  done({error: 'Browser does not support long-task measurements'});
                  return;
                }
                const tasks = [];
                const recordTasks = entries => entries.forEach(entry => tasks.push({
                  startTime: entry.startTime, duration: entry.duration
                }));
                const taskObserver = new PerformanceObserver(entries => recordTasks(entries.getEntries()));
                taskObserver.observe({entryTypes: ['longtask']});
                let frame = 0, paintedFrame = 0, startTimer = 0, settled = false, startedAt = 0;
                const finish = value => {
                  if (settled) return;
                  settled = true;
                  observer.disconnect();
                  clearTimeout(deadline);
                  clearTimeout(startTimer);
                  cancelAnimationFrame(frame);
                  cancelAnimationFrame(paintedFrame);
                  if (value.error) {
                    taskObserver.disconnect();
                    done(value);
                    return;
                  }
                  // Long-task records for the last rendering turn are queued after its rAF
                  // callbacks. Drain them in the next task, without extending the measured window.
                  setTimeout(() => {
                    recordTasks(taskObserver.takeRecords());
                    taskObserver.disconnect();
                    const longTasks = tasks.filter(entry => entry.startTime < value.completedAt
                      && entry.startTime + entry.duration > startedAt);
                    // Keep the whole duration of overlapping tasks: do not clip a slow task
                    // at the boundary and accidentally turn a real budget failure green.
                    const timing = Object.freeze({...value, startedAt,
                      longestTaskMs: Math.max(0, ...longTasks.map(entry => entry.duration)),
                      longTasks: Object.freeze(longTasks.map(entry => Object.freeze(entry)))});
                    window.__taxonomySearchRenderTiming = timing;
                    done(timing);
                  }, 0);
                };
                const observer = new MutationObserver(() => {
                  if (Number(area.dataset.totalResults) !== count
                      || !area.querySelector('.search-result-item')) return;
                  observer.disconnect();
                  frame = requestAnimationFrame(function layoutReady() {
                    const list = area.querySelector('.search-results-list');
                    if (!list || list.getBoundingClientRect().height <= 0) {
                      finish({error: 'Search result window has no visible layout'});
                      return;
                    }
                    // The next animation frame follows a rendering opportunity for
                    // this exact result window. Queueing/layout/paint stay timed.
                    paintedFrame = requestAnimationFrame(function paintReady() {
                      const completedAt = performance.now();
                      finish({renderDurationMs: completedAt - startedAt, completedAt});
                    });
                  });
                });
                const deadline = setTimeout(() => finish({error: 'Search rendering did not finish'}), 20000);
                observer.observe(area, {childList: true, subtree: true, attributes: true,
                  attributeFilter: ['data-total-results']});
                // Start in a native browser task so synchronous search work is observable,
                // separate from the WebDriver command and the preceding fixture setup.
                startTimer = setTimeout(() => {
                  startedAt = performance.now();
                  try {
                    window.TaxonomySearch.performSearch('budget-' + count, 'fulltext', count);
                  } catch (error) {
                    finish({error: String(error)});
                  }
                }, 0);
                """, resultCount, responseDelayMillis));
        assertThat(completion.containsKey("error"))
                .as("browser-owned search render completion: %s", completion)
                .isFalse();
        assertThat(decimal(completion.get("renderDurationMs"))).isPositive();
    }

    private static Map<?, ?> readSearchRenderTiming() {
        return map(execute("""
                const timing = window.__taxonomySearchRenderTiming;
                if (!timing) throw new Error('No completed search render measurement');
                return {
                  renderDurationMs: timing.renderDurationMs,
                  startedAt: timing.startedAt,
                  completedAt: timing.completedAt,
                  longestTaskMs: timing.longestTaskMs,
                  longTasks: timing.longTasks,
                  driverObservationDelayMs: performance.now() - timing.completedAt
                };
                """));
    }

    private static Map<String, Object> exerciseScenario(
            Scenario scenario,
            long initialTaxonomyNodes,
            long deferredContainers) {
        clearDeviceMetricsOverride();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        execute("const secondaryTools=document.querySelector('#analysisSecondaryTools');"
                + "secondaryTools.open=true;"
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

        captureSearchRender(scenario.resultCount(), 0);

        wait.until(browser -> scenario.resultCount() == number(execute(
                "return Number(document.querySelector('#searchResultsArea').dataset.totalResults || 0)")));
        wait.until(browser -> number(execute(
                "return document.querySelectorAll('#searchResultsArea .search-result-item').length")) > 0);
        sleep(150);
        Map<?, ?> renderTiming = readSearchRenderTiming();

        Map<?, ?> measured = map(execute("""
                const area = document.querySelector('#searchResultsArea');
                const list = area.querySelector('.search-results-list');
                const heap = performance.memory ? performance.memory.usedJSHeapSize : null;
                const names = Array.from(area.querySelectorAll('.search-result-name'));
                const clipped = names.filter(name => {
                  const style = getComputedStyle(name);
                  return style.whiteSpace === 'nowrap'
                    && style.textOverflow === 'ellipsis'
                    && (style.overflowX === 'hidden' || style.overflow === 'hidden')
                    && name.scrollWidth > name.clientWidth;
                });
                return {
                  totalResults: Number(area.dataset.totalResults || 0),
                  renderedResults: area.querySelectorAll('.search-result-item').length,
                  resultAreaDomNodes: area.querySelectorAll('*').length,
                  resultAreaClientHeight: area.clientHeight,
                  resultAreaScrollHeight: area.scrollHeight,
                  resultListHeight: list ? Math.ceil(list.getBoundingClientRect().height) : 0,
                  documentHeight: document.documentElement.scrollHeight,
                  heap: heap,
                  truncationClassNames: area.querySelectorAll(
                    '.search-result-name.text-truncate').length,
                  clippedNames: clipped.length,
                  maxNameOverflowPx: names.length
                    ? Math.max(...names.map(name => Math.max(0,
                        name.scrollWidth - name.clientWidth)))
                    : 0,
                  summaryText: document.querySelector('#searchResultSummary')?.textContent || '',
                  filterText: document.querySelector('#searchActiveFilters')?.textContent || ''
                };
                """));

        Map<?, ?> interaction = map(executeAsync("""
                const done = arguments[arguments.length - 1];
                const started = performance.now();
                const eventName = arguments[0];
                const onComplete = event => {
                  if (!event.detail || event.detail.action !== 'next') return;
                  document.removeEventListener(eventName, onComplete);
                  const selected = document.querySelector(
                    '#searchResultsArea .search-result-item[aria-current="true"]');
                  const highlighted = document.querySelector('.search-highlight')
                    ?.closest('.tax-node');
                  done({
                    latencyMs: performance.now() - started,
                    currentIndex: Number(document.querySelector('#searchResultsArea')
                      .dataset.currentIndex || -1),
                    focusConfirmed: event.detail.focusConfirmed === true,
                    focusTarget: event.detail.focusTarget || '',
                    activeClass: event.detail.activeElementClass || '',
                    activeTag: event.detail.activeElementTag || '',
                    selectedCode: selected?.dataset.code || '',
                    highlightedCode: highlighted?.dataset.code || '',
                    path: document.querySelector('#searchCurrentPath')?.textContent || ''
                  });
                };
                document.addEventListener(eventName, onComplete);
                document.querySelector('[data-search-result-nav="next"]').click();
                """, NAVIGATION_COMPLETE_EVENT));

        Map<?, ?> returnContext = map(executeAsync("""
                const done = arguments[arguments.length - 1];
                const eventName = arguments[0];
                const area = document.querySelector('#searchResultsArea');
                const onComplete = event => {
                  if (!event.detail || event.detail.action !== 'summary') return;
                  document.removeEventListener(eventName, onComplete);
                  done({
                    focusConfirmed: event.detail.focusConfirmed === true,
                    focusTarget: event.detail.focusTarget || '',
                    activeId: event.detail.activeElementId || '',
                    activeTag: event.detail.activeElementTag || '',
                    areaScrollTop: area.scrollTop
                  });
                };
                document.addEventListener(eventName, onComplete);
                area.scrollTop = 100;
                document.querySelector('[data-search-result-nav="summary"]').click();
                """, NAVIGATION_COMPLETE_EVENT));

        Map<String, Object> responsiveEvidence = scenario.resultCount() >= 1000
                ? verifyResponsiveProfiles()
                : Map.of();

        long heapBefore = nullableNumber(baseline.get("heap"), -1);
        long heapAfter = nullableNumber(measured.get("heap"), -1);
        Long heapIncrease = heapBefore >= 0 && heapAfter >= 0
                ? Math.max(0L, heapAfter - heapBefore)
                : null;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("scenario", scenario.id());
        metrics.put("resultCount", scenario.resultCount());
        metrics.put("realTaxonomyCode", realTaxonomyCode);
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
        metrics.put("renderDurationMs", decimal(renderTiming.get("renderDurationMs")));
        metrics.put("driverObservationDelayMs", decimal(renderTiming.get("driverObservationDelayMs")));
        metrics.put("searchStartedAtMs", decimal(renderTiming.get("startedAt")));
        metrics.put("searchCompletedAtMs", decimal(renderTiming.get("completedAt")));
        metrics.put("longTasks", renderTiming.get("longTasks"));
        metrics.put("longestTaskMs", decimal(renderTiming.get("longestTaskMs")));
        metrics.put("heapBeforeBytes", heapBefore);
        metrics.put("heapAfterBytes", heapAfter);
        metrics.put("heapIncreaseBytes", heapIncrease);
        metrics.put("truncationClassNames", number(measured.get("truncationClassNames")));
        metrics.put("clippedNames", number(measured.get("clippedNames")));
        metrics.put("maxNameOverflowPx", number(measured.get("maxNameOverflowPx")));
        metrics.put("summaryText", measured.get("summaryText"));
        metrics.put("filterText", measured.get("filterText"));
        metrics.put("interactionLatencyMs", decimal(interaction.get("latencyMs")));
        metrics.put("currentIndex", number(interaction.get("currentIndex")));
        metrics.put("interactionFocusConfirmed", interaction.get("focusConfirmed"));
        metrics.put("interactionFocusTarget", interaction.get("focusTarget"));
        metrics.put("activeClass", interaction.get("activeClass"));
        metrics.put("activeTag", interaction.get("activeTag"));
        metrics.put("selectedCode", interaction.get("selectedCode"));
        metrics.put("highlightedCode", interaction.get("highlightedCode"));
        metrics.put("currentPath", interaction.get("path"));
        metrics.put("returnFocusConfirmed", returnContext.get("focusConfirmed"));
        metrics.put("returnFocusTarget", returnContext.get("focusTarget"));
        metrics.put("returnActiveId", returnContext.get("activeId"));
        metrics.put("returnActiveTag", returnContext.get("activeTag"));
        metrics.put("returnAreaScrollTop", number(returnContext.get("areaScrollTop")));
        metrics.put("responsiveProfiles", responsiveEvidence);
        return metrics;
    }

    private static Map<String, Object> exerciseSearchRace(String staleQuery) {
        clearDeviceMetricsOverride();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        execute("""
                window.__taxonomyBudgetInstall(3);
                window.TaxonomySearch.performSearch(
                    arguments[0], 'fulltext', 3);
                window.TaxonomySearch.performSearch(
                    'race-fast', 'fulltext', 3);
                """, staleQuery);

        wait.until(browser -> String.valueOf(execute(
                "return document.querySelector('#searchActiveFilters')?.textContent || ''"))
                .contains("race-fast"));
        sleep(350);

        Map<?, ?> observation = map(execute("""
                const area = document.querySelector('#searchResultsArea');
                return {
                  activeQuery: document.querySelector('#searchActiveFilters')
                    ?.textContent.includes('race-fast') ? 'race-fast' : '',
                  totalResults: Number(area.dataset.totalResults || 0),
                  hasError: Boolean(area.querySelector('.text-danger')),
                  summary: document.querySelector('#searchResultSummary')?.textContent || ''
                };
                """));
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("staleQuery", staleQuery);
        evidence.put("activeQuery", observation.get("activeQuery"));
        evidence.put("totalResults", number(observation.get("totalResults")));
        evidence.put("hasError", observation.get("hasError"));
        evidence.put("summary", observation.get("summary"));
        return evidence;
    }

    private static Map<String, Object> verifyResponsiveProfiles() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        try {
            for (ResponsiveProfile profile : policy.responsiveProfiles()) {
                cdp.executeCdpCommand(
                        "Emulation.setDeviceMetricsOverride",
                        Map.of(
                                "width", profile.cssWidth(),
                                "height", profile.cssHeight(),
                                "deviceScaleFactor", profile.deviceScaleFactor(),
                                "mobile", profile.mobile(),
                                "screenWidth", profile.cssWidth(),
                                "screenHeight", profile.cssHeight()));
                sleep(120);
                Map<?, ?> measured = map(execute("""
                        const panel = document.querySelector('#searchPanel');
                        const area = document.querySelector('#searchResultsArea');
                        return {
                          innerWidth: window.innerWidth,
                          innerHeight: window.innerHeight,
                          devicePixelRatio: window.devicePixelRatio,
                          visualViewportScale: window.visualViewport
                            ? window.visualViewport.scale : 1,
                          horizontalOverflowPx: Math.max(
                            0,
                            document.documentElement.scrollWidth
                              - document.documentElement.clientWidth,
                            panel.scrollWidth - panel.clientWidth,
                            area.scrollWidth - area.clientWidth)
                        };
                        """));

                LinkedHashMap<String, Object> profileEvidence = new LinkedHashMap<>();
                profileEvidence.put("cssWidth", profile.cssWidth());
                profileEvidence.put("cssHeight", profile.cssHeight());
                profileEvidence.put("deviceScaleFactor", profile.deviceScaleFactor());
                profileEvidence.put("mobile", profile.mobile());
                profileEvidence.put("innerWidth", number(measured.get("innerWidth")));
                profileEvidence.put("innerHeight", number(measured.get("innerHeight")));
                profileEvidence.put(
                        "measuredDevicePixelRatio",
                        decimal(measured.get("devicePixelRatio")));
                profileEvidence.put(
                        "visualViewportScale",
                        decimal(measured.get("visualViewportScale")));
                profileEvidence.put(
                        "horizontalOverflowPx",
                        number(measured.get("horizontalOverflowPx")));
                evidence.put(profile.id(), profileEvidence);
            }
        } finally {
            clearDeviceMetricsOverride();
            driver.manage().window().setSize(new Dimension(1440, 1000));
        }
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
                .isPositive()
                .isLessThanOrEqualTo(policy.maxRenderDurationMs());
        assertThat(decimal(metrics.get("longestTaskMs")))
                .as(scenario.id() + " longest browser task; measured entries: %s", metrics.get("longTasks"))
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
        assertThat(number(metrics.get("truncationClassNames")))
                .as(scenario.id() + " result labels with truncation styling")
                .isEqualTo(number(metrics.get("renderedResults")));
        assertThat(number(metrics.get("clippedNames")))
                .as(scenario.id() + " geometrically clipped result labels")
                .isEqualTo(number(metrics.get("renderedResults")));
        assertThat(number(metrics.get("maxNameOverflowPx")))
                .as(scenario.id() + " maximum hidden label width")
                .isPositive();
        assertThat(number(metrics.get("currentIndex"))).isZero();
        assertThat(metrics.get("interactionFocusConfirmed"))
                .as(scenario.id() + " confirmed result focus")
                .isEqualTo(Boolean.TRUE);
        assertThat(metrics.get("interactionFocusTarget")).isEqualTo("result");
        assertThat(String.valueOf(metrics.get("activeClass")))
                .contains("search-result-item");
        assertThat(metrics.get("activeTag")).isEqualTo("a");
        assertThat(metrics.get("selectedCode")).isEqualTo(realTaxonomyCode);
        assertThat(metrics.get("highlightedCode")).isEqualTo(realTaxonomyCode);
        assertThat(String.valueOf(metrics.get("currentPath")))
                .contains(realTaxonomyCode)
                .doesNotContain("no result selected");
        assertThat(metrics.get("returnFocusConfirmed"))
                .as(scenario.id() + " confirmed summary focus")
                .isEqualTo(Boolean.TRUE);
        assertThat(metrics.get("returnFocusTarget")).isEqualTo("summary");
        assertThat(metrics.get("returnActiveId")).isEqualTo("searchResultSummary");
        assertThat(metrics.get("returnActiveTag")).isEqualTo("div");
        assertThat(number(metrics.get("returnAreaScrollTop"))).isZero();
        assertThat(String.valueOf(metrics.get("summaryText")))
                .contains(Integer.toString(scenario.resultCount()));
        assertThat(String.valueOf(metrics.get("filterText")))
                .contains("budget-" + scenario.resultCount());

        Object responsive = metrics.get("responsiveProfiles");
        if (responsive instanceof Map<?, ?> values && !values.isEmpty()) {
            for (ResponsiveProfile profile : policy.responsiveProfiles()) {
                Map<?, ?> profileEvidence = map(values.get(profile.id()));
                long innerWidth = number(profileEvidence.get("innerWidth"));
                assertThat(innerWidth)
                        .as(scenario.id() + " CSS viewport width for " + profile.id())
                        .isBetween(
                                Math.max(1L, profile.cssWidth() - 25L),
                                (long) profile.cssWidth());
                assertThat(Math.abs(
                        decimal(profileEvidence.get("measuredDevicePixelRatio"))
                                - profile.deviceScaleFactor()))
                        .as(scenario.id() + " device scale factor for " + profile.id())
                        .isLessThanOrEqualTo(0.1);
                assertThat(number(profileEvidence.get("horizontalOverflowPx")))
                        .as(scenario.id() + " horizontal overflow for " + profile.id())
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
                const realNode = document.querySelector(
                  '#taxonomyTree .tax-node[data-code]');
                if (!realNode || !realNode.dataset.code) {
                  throw new Error('No real taxonomy node is available for navigation evidence');
                }
                window.__taxonomyBudgetRealCode = realNode.dataset.code;
                window.__taxonomyBudgetInstall = function (count, responseDelayMs = 0) {
                  const original = window.__taxonomyBudgetOriginalFetch;
                  const nodes = () => Array.from({length: count}, (_, index) => {
                    const longSuffix = ' — repeated bounded-label evidence segment'.repeat(12);
                    return {
                      code: index === 0
                        ? window.__taxonomyBudgetRealCode
                        : 'BUDGET-' + String(index + 1).padStart(4, '0'),
                      nameEn: (index === 0
                        ? 'Existing taxonomy result ' + window.__taxonomyBudgetRealCode
                        : 'Synthetic bounded result ' + String(index + 1))
                        + longSuffix,
                      matchPercentage: 100 - (index % 100)
                    };
                  });
                  const response = () => new Response(JSON.stringify(nodes()), {
                    status: 200,
                    headers: {'Content-Type': 'application/json'}
                  });
                  window.fetch = function (input, init) {
                    const raw = input instanceof Request ? input.url : String(input);
                    const url = new URL(raw, window.location.href);
                    if (url.pathname === '/api/search') {
                      const query = url.searchParams.get('q') || '';
                      if (query === 'race-slow-success') {
                        return new Promise(resolve => {
                          setTimeout(() => resolve(response()), 220);
                        });
                      }
                      if (query === 'race-slow-failure') {
                        return new Promise((resolve, reject) => {
                          setTimeout(() => reject(new Error('stale search failure')), 220);
                        });
                      }
                      if (responseDelayMs > 0) {
                        return new Promise(resolve => {
                          setTimeout(() => resolve(response()), responseDelayMs);
                        });
                      }
                      return Promise.resolve(response());
                    }
                    return original(input, init);
                  };
                };
                """);
    }

    private static void clearDeviceMetricsOverride() {
        if (cdp == null) return;
        try {
            cdp.executeCdpCommand(
                    "Emulation.clearDeviceMetricsOverride", Map.of());
        } catch (RuntimeException ignored) {
            // Teardown remains best-effort; profile setup/assertions fail normally.
        }
    }

    private static void writeEvidence() throws Exception {
        if (evidencePath == null) return;
        Files.createDirectories(evidencePath.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("policy", policy);
        report.put("scenarios", evidence);
        report.put("concurrency", concurrencyEvidence);
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
            List<Scenario> scenarios,
            List<ResponsiveProfile> responsiveProfiles) {
    }

    record Scenario(String id, int resultCount) {
    }

    record ResponsiveProfile(
            String id,
            int cssWidth,
            int cssHeight,
            double deviceScaleFactor,
            boolean mobile) {
    }
}
