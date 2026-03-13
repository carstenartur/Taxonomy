package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * Generates documentation screenshots for docs/images/.
 * <p>
 * Opt-in: only runs when the {@code generateScreenshots} system property is set.
 * Run with: {@code mvn verify -DgenerateScreenshots -Dtest=ScreenshotGeneratorIT}
 * <p>
 * Screenshots 1–14 do not require an LLM provider.
 * Screenshots 15–40 use mock LLM mode ({@code LLM_MOCK=true}) to produce
 * realistic scores without calling any real LLM API.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "generateScreenshots", matches = ".*")
class ScreenshotGeneratorIT {

    private static final String REQUIREMENT_TEXT =
            "Provide secure voice communications between HQ and deployed forces";

    private static final String FALLBACK_DSL_TEXT =
            "meta\\n  language \"taxdsl\"\\n  version \"1.0\"\\n  namespace \"default\"\\n\\n" +
            "element CP type Capability\\n  title \"Capability Packages\"\\n\\n" +
            "element CR type CoreService\\n  title \"Core Services\"\\n\\n" +
            "relation CP REALIZES CR\\n  status accepted\\n";

    private static final Path OUTPUT_DIR = resolveOutputDir();

    /**
     * Resolves the output directory for screenshots to the {@code docs/images/} folder at the
     * repository root.
     *
     * <p>Maven Failsafe forks a JVM with {@code workingDirectory=${project.basedir}}, which is the
     * module directory (e.g. {@code taxonomy-app/}), not the repository root.  We use the
     * {@code project.basedir} system property (injected via the Failsafe {@code
     * systemPropertyVariables} configuration) to navigate up one level to the repo root where
     * {@code docs/images/} lives.
     *
     * <p>Falls back to {@code ../docs/images} relative to CWD when the system property is absent
     * (e.g. when running from an IDE with module dir as CWD), or to {@code docs/images} directly
     * when the CWD is already the repo root.
     */
    private static Path resolveOutputDir() {
        String basedir = System.getProperty("project.basedir");
        if (basedir != null) {
            // taxonomy-app/ -> parent = repo root -> repo root/docs/images
            Path moduleDir = Path.of(basedir);
            Path repoRoot = moduleDir.getParent();
            if (repoRoot != null) {
                return repoRoot.resolve("docs/images");
            }
        }
        // Fallback: if a docs/ directory exists relative to CWD, we are at the repo root
        if (java.nio.file.Files.isDirectory(Path.of("docs"))) {
            return Path.of("docs/images");
        }
        // Last fallback: CWD is the module dir, navigate up one level
        return Path.of("../docs/images");
    }

    private static Network network;
    private static GenericContainer<?> app;
    private static BrowserWebDriverContainer<?> chrome;
    private static WebDriver driver;

    @BeforeAll
    static void startContainers() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        network = Network.newNetwork();

        GenericContainer<?> appContainer = new GenericContainer<>(
                new ImageFromDockerfile()
                        .withFileFromPath("app.jar", ContainerTestUtils.findApplicationJar())
                        .withDockerfileFromBuilder(builder -> builder
                                .from("eclipse-temurin:17-jre-alpine")
                                .workDir("/app")
                                .copy("app.jar", "app.jar")
                                .expose(8080)
                                .entryPoint("java", "-jar", "app.jar")
                                .build()))
                .withNetwork(network)
                .withNetworkAliases("app")
                .withExposedPorts(8080)
                .withEnv("ADMIN_PASSWORD", "testpassword123")
                .withEnv("LLM_MOCK", "true")
                .withStartupTimeout(Duration.ofSeconds(120))
                // Use /api/ai-status (public) instead of /api/diagnostics (returns 401 when
                // ADMIN_PASSWORD is configured) as the container readiness check.
                .waitingFor(Wait.forHttp("/api/ai-status").forStatusCode(200).forPort(8080));

        app = appContainer;
        app.start();

        chrome = new BrowserWebDriverContainer<>()
                .withNetwork(network)
                .withCapabilities(new ChromeOptions());
        chrome.start();

        driver = chrome.getWebDriver();
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900));

        // Load the application and wait for the taxonomy tree to be FULLY RENDERED
        driver.get("http://app:8080/");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // The #taxonomyTree div is always in the HTML (with a loading spinner).
        // Wait for actual taxonomy nodes to appear — they are rendered by the JS
        // loadTaxonomy() fetch from /api/taxonomy, which runs asynchronously.
        // Use 60s here because this is the first load after container startup —
        // the taxonomy Excel import may still be running in the background.
        new WebDriverWait(driver, Duration.ofSeconds(60))
                .until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("#taxonomyTree .tax-node")));
        // Dismiss the onboarding overlay if it is present (it blocks clicks on all UI elements)
        List<WebElement> dismissBtns = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissBtns.isEmpty()) {
            dismissBtns.get(0).click();
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
    }

    @AfterAll
    static void stopContainers() {
        if (chrome != null) chrome.stop();
        if (app != null) app.stop();
        if (network != null) network.close();
    }

    // ── Helper methods ─────────────────────────────────────────────────────────

    private void saveScreenshot(String filename) throws IOException {
        File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), OUTPUT_DIR.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
    }

    private void saveElementScreenshot(WebElement element, String filename) throws IOException {
        File src = element.getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), OUTPUT_DIR.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
    }

    private WebDriverWait wait(int seconds) {
        return new WebDriverWait(driver, Duration.ofSeconds(seconds));
    }

    /** Runs a standard (non-interactive) analysis and waits up to 120 s for completion. */
    private void runAnalysis() {
        navigateToTab("analyze");
        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", textarea);
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                textarea, REQUIREMENT_TEXT);
        WebElement analyzeBtn = driver.findElement(By.id("analyzeBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", analyzeBtn);
        js("arguments[0].click();", analyzeBtn);
        wait(120).until(ExpectedConditions.textMatches(
                By.id("statusArea"), java.util.regex.Pattern.compile("(?i)complete|error")));
    }

    private void js(String script, Object... args) {
        ((JavascriptExecutor) driver).executeScript(script, args);
    }

    /**
     * Closes a Bootstrap modal directly via DOM manipulation, bypassing the fade animation.
     * This avoids timing issues when waiting for the Bootstrap animation to complete.
     * Also disposes Bootstrap's internal modal instance to fully reset its state.
     */
    private void closeModalViaDOM(String modalId) {
        js("var el = document.getElementById(arguments[0]); " +
                "if (el) { " +
                "  try { var inst = bootstrap.Modal.getInstance(el); if (inst) { inst.dispose(); } } catch(e) {} " +
                "  el.classList.remove('show'); el.style.display='none'; " +
                "  el.removeAttribute('aria-modal'); el.setAttribute('aria-hidden','true'); " +
                "} " +
                "document.querySelectorAll('.modal-backdrop').forEach(b => b.remove()); " +
                "document.querySelectorAll('.modal.show').forEach(m => { m.classList.remove('show'); m.style.display='none'; }); " +
                "document.body.classList.remove('modal-open'); " +
                "document.body.style.overflow=''; " +
                "document.body.style.paddingRight='';",
                modalId);
        wait(5).until(ExpectedConditions.invisibilityOfElementLocated(By.id(modalId)));
        // Brief pause to let the browser settle after DOM manipulation
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Makes the admin lock button visible (it is hidden until the AI status check resolves). */
    private void showAdminLockButton() {
        // Wait for the page's async AI status check to complete.
        // The badge starts empty and d-none; after checkAiStatus() resolves (success, failure,
        // or catch), it gets text content.  We check for non-empty text rather than Selenium
        // visibility because the badge may remain hidden by CSS (d-none) if the JS that removes
        // d-none has not executed yet in the rendering pipeline.
        wait(15).until(d -> {
            String text = (String) ((JavascriptExecutor) d).executeScript(
                    "var b = document.getElementById('aiStatusBadge'); return b ? b.textContent : '';");
            return text != null && !text.trim().isEmpty();
        });
        js("var btn = document.getElementById('adminLockBtn');" +
           "if (btn) { btn.classList.remove('d-none'); btn.style.display = ''; " +
           "btn.scrollIntoView({behavior:'instant', block:'center'}); }");
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("adminLockBtn")));
    }

    /** Unlocks admin mode via the REST endpoint and reveals the admin-only panels. */
    private void unlockAdmin() {
        js("fetch('/api/admin/verify', {method:'POST', " +
                "headers:{'Content-Type':'application/json'}, " +
                "body: JSON.stringify({password:'testpassword123'})})" +
                ".then(r => r.json()).then(data => { " +
                "  if (data.valid) { " +
                "    sessionStorage.setItem('adminToken', 'testpassword123'); " +
                "    document.body.classList.add('admin-unlocked'); " +
                "    document.querySelectorAll('.admin-only').forEach(el => { el.style.display = ''; el.classList.remove('d-none'); }); " +
                "  } " +
                "});");
        // Wait for admin panels to become visible after unlock, re-applying DOM changes each poll
        wait(15).until(d -> {
            js("document.body.classList.add('admin-unlocked');");
            js("document.querySelectorAll('.admin-only').forEach(el => { el.style.display = ''; el.classList.remove('d-none'); });");
            List<WebElement> panels = driver.findElements(By.cssSelector(".admin-only"));
            return !panels.isEmpty() && panels.stream().anyMatch(WebElement::isDisplayed);
        });
        // Trigger diagnostics load — the REST unlock above doesn't go through the UI's
        // initAdminModal() flow, so loadDiagnostics() is never called automatically.
        // Click the refresh button which has a click listener bound to loadDiagnostics().
        js("var btn = document.getElementById('refreshDiagnostics'); if (btn) btn.click();");
    }

    /**
     * Forces the interactive-mode checkbox OFF via direct JS property assignment and fires the
     * {@code change} event so the taxonomy.js module-level variable is also updated.
     * Uses a JS-based wait to avoid StaleElementReferenceException from page re-renders.
     */
    private void forceNonInteractiveMode() {
        js("var cb = document.getElementById('interactiveMode');" +
           "if (cb) { cb.checked = false; cb.dispatchEvent(new Event('change')); }");
        wait(3).until(d -> (Boolean) ((JavascriptExecutor) d).executeScript(
                "var cb = document.getElementById('interactiveMode');" +
                "return !!cb && cb.checked === false;"));
    }

    /** Reloads the page and waits for the taxonomy tree to be fully rendered. */
    private void resetPageState() {
        driver.get("http://app:8080/");
        wait(30).until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // The #taxonomyTree div is always in the HTML (with a loading spinner).
        // Wait for actual taxonomy nodes to appear — they are rendered by the JS
        // loadTaxonomy() fetch from /api/taxonomy, which runs asynchronously.
        wait(30).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree .tax-node")));
        // Dismiss the onboarding overlay if it reappears after page reload
        List<WebElement> dismissBtns = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissBtns.isEmpty()) {
            dismissBtns.get(0).click();
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
    }

    /**
     * Cleans up any leftover Bootstrap modal state (backdrops, stuck modals, body overflow).
     * Must be called at the start of any test that shows a modal when running in a retry session,
     * because failed modal tests in Run 1 leave stale state inherited by the Run 2 retry.
     */
    private void resetModalState() {
        js("document.querySelectorAll('.modal-backdrop').forEach(function(b) { b.remove(); });" +
           "document.querySelectorAll('.modal.show').forEach(function(m) {" +
           "  try { var i = bootstrap.Modal.getInstance(m); if (i) i.dispose(); } catch(e) {}" +
           "  m.classList.remove('show'); m.style.display='none';" +
           "  m.removeAttribute('aria-modal'); m.setAttribute('aria-hidden','true');" +
           "});" +
           "document.body.classList.remove('modal-open');" +
           "document.body.style.overflow='';" +
           "document.body.style.paddingRight='';");
        // Wait until all backdrops are gone before continuing
        wait(3).until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector(".modal-backdrop")));
    }

    /**
     * Shows a Bootstrap modal using direct DOM manipulation, bypassing Bootstrap's JS animation.
     * This is a reliable fallback for when Bootstrap is loaded from CDN and may not be available,
     * or when Bootstrap's show() animation does not complete within Selenium's wait window.
     * Call this AFTER any action that should have triggered Bootstrap's own show() method.
     */
    private void showModalViaDOM(String modalId) {
        js("(function(id) {" +
           "  var el = document.getElementById(id);" +
           "  if (!el) return;" +
           "  el.style.display = 'block';" +
           "  el.style.opacity = '1';" +
           "  el.classList.add('show');" +
           "  el.removeAttribute('aria-hidden');" +
           "  el.setAttribute('aria-modal', 'true');" +
           "  document.body.classList.add('modal-open');" +
           "  if (!document.querySelector('.modal-backdrop')) {" +
           "    var bd = document.createElement('div');" +
           "    bd.className = 'modal-backdrop show';" +
           "    document.body.appendChild(bd);" +
           "  }" +
           "})(arguments[0]);", modalId);
        // Verify DOM manipulation took effect via JS.  Selenium's visibilityOfElementLocated
        // is unreliable here because Bootstrap's ".modal.fade" CSS transition can make the
        // element appear as zero-opacity during the transition period, even though our inline
        // style.opacity = '1' should override it.  A JS-based check avoids that race.
        wait(10).until(d -> (Boolean) ((JavascriptExecutor) d).executeScript(
                "var el = document.getElementById(arguments[0]);" +
                "return el != null && el.style.display === 'block' && el.classList.contains('show');",
                modalId));
    }

    /** Opens a <details> element if it is currently closed. */
    private void openDetails(WebElement details) {
        js("arguments[0].setAttribute('open', '');", details);
        wait(5).until(ExpectedConditions.attributeContains(details, "open", ""));
    }

    /** Navigates to a named page tab using the client-side navigateToPage() function. */
    private void navigateToTab(String page) {
        js("if (window.navigateToPage) { window.navigateToPage(arguments[0]); }", page);
        wait(5).until(d -> {
            WebElement pane = d.findElement(By.id("tab-" + page));
            return !pane.getAttribute("class").contains("d-none");
        });
    }

    // ── Screenshots 1–14: no LLM required ─────────────────────────────────────

    @Test
    @Order(1)
    void captureFullPageLayout() throws IOException {
        driver.get("http://app:8080/");
        wait(30).until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // Wait for actual taxonomy nodes to be rendered (not just the container div with loading spinner)
        wait(60).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree .tax-node")));
        // Dismiss the onboarding overlay if present
        List<WebElement> dismissBtns = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissBtns.isEmpty()) {
            dismissBtns.get(0).click();
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
        saveScreenshot("01-full-page-layout.png");
    }

    @Test
    @Order(2)
    void captureLeftPanelListView() throws IOException {
        // Use Expand All to make the tree show nodes with their action buttons
        driver.findElement(By.id("expandAll")).click();
        wait(5).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".tax-toggle")));
        // Take element screenshot of just the left panel card
        saveElementScreenshot(
                driver.findElement(By.cssSelector(".col-lg-7 .card")),
                "02-left-panel-list-view.png");
    }

    @Test
    @Order(3)
    void captureRightPanelDefault() throws IOException {
        // Scroll back to top so both panels are in view, then take a full-viewport screenshot.
        // Element screenshot of .col-lg-5 is unreliable because the flex column collapses after
        // the tree expansion in test 2.
        js("window.scrollTo(0, 0);");
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("businessText")));
        saveScreenshot("03-right-panel-default.png");
    }

    @Test
    @Order(4)
    void captureAnalysisPanelEmpty() throws IOException {
        navigateToTab("analyze");
        driver.findElement(By.id("businessText")).clear();
        wait(2).until(ExpectedConditions.textToBe(By.id("businessText"), ""));
        saveElementScreenshot(
                driver.findElement(By.xpath("//textarea[@id='businessText']/ancestor::div[contains(@class,'card')][1]")),
                "04-analysis-panel-empty.png");
    }

    @Test
    @Order(5)
    void captureTabsView() throws IOException {
        driver.findElement(By.id("viewTabs")).click();
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewTabs"), "class", "btn-primary"));
        saveScreenshot("05-tabs-view.png");
        driver.findElement(By.id("viewList")).click();
    }

    @Test
    @Order(6)
    void captureSunburstView() throws IOException {
        driver.findElement(By.id("viewSunburst")).click();
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));
        saveScreenshot("06-sunburst-view.png");
        driver.findElement(By.id("viewList")).click();
    }

    @Test
    @Order(7)
    void captureTreeView() throws IOException {
        driver.findElement(By.id("viewTree")).click();
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewTree"), "class", "btn-primary"));
        saveScreenshot("07-tree-view.png");
        driver.findElement(By.id("viewList")).click();
    }

    @Test
    @Order(8)
    void captureDecisionMapView() throws IOException {
        driver.findElement(By.id("viewDecision")).click();
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewDecision"), "class", "btn-primary"));
        saveScreenshot("08-decision-map-view.png");
        driver.findElement(By.id("viewList")).click();
    }

    @Test
    @Order(9)
    void captureListViewWithDescriptions() throws IOException {
        WebElement desc = driver.findElement(By.id("showDescriptions"));
        if (!desc.isSelected()) {
            desc.click();
            wait(5).until(ExpectedConditions.elementSelectionStateToBe(desc, true));
        }
        saveScreenshot("09-list-view-descriptions.png");
    }

    @Test
    @Order(10)
    void captureMatchLegend() throws IOException {
        WebElement legendCard = driver.findElement(
                By.xpath("//div[contains(@class,'card')]//small[contains(text(),'Match legend')]/ancestor::div[contains(@class,'card')]"));
        saveElementScreenshot(legendCard, "10-match-legend.png");
    }

    @Test
    @Order(11)
    void captureGraphExplorerPanel() throws IOException {
        navigateToTab("graph");
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        input.clear();
        input.sendKeys("BP");
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "11-graph-explorer-panel.png");
    }

    @Test
    @Order(12)
    void captureRelationProposalsPanel() throws IOException {
        navigateToTab("relations");
        WebElement panel = driver.findElement(By.id("proposalsPanel"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", panel);
        saveElementScreenshot(panel, "12-relation-proposals-panel.png");
    }

    @Test
    @Order(13)
    void captureProposeRelationsModal() throws IOException, InterruptedException {
        // Clean up any leftover modal state from a previous failed retry run
        resetModalState();
        navigateToTab("analyze");
        // Scroll to the taxonomy tree so the proposal buttons become visible
        WebElement taxonomyTree = driver.findElement(By.id("taxonomyTree"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'start'});", taxonomyTree);
        // Wait for at least one proposal button to be present in the DOM
        wait(15).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".proposal-btn")));
        // Use JS click to work around any overlay or scroll positioning issues
        WebElement proposeBtn = driver.findElement(By.cssSelector(".proposal-btn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", proposeBtn);
        js("arguments[0].click();", proposeBtn);
        // Brief pause to allow the event handler to set modal content
        Thread.sleep(1000);
        // Show modal via DOM manipulation (reliable fallback when Bootstrap CDN is slow/unavailable)
        showModalViaDOM("proposeRelationsModal");
        saveScreenshot("13-propose-relations-modal.png");
        closeModalViaDOM("proposeRelationsModal");
        js("window.scrollTo(0, 0);");
    }

    @Test
    @Order(14)
    void captureAdminLockButton() throws IOException {
        showAdminLockButton();
        saveScreenshot("14-navbar-admin-lock.png");
    }

    // ── Screenshots 15–27: LLM-dependent (use mock LLM — no real API key needed) ──

    @Test
    @Order(15)
    void captureScoredTaxonomyTree() throws IOException {
        WebElement interactiveCb = driver.findElement(By.id("interactiveMode"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", interactiveCb);
        if (interactiveCb.isSelected()) {
            js("arguments[0].click();", interactiveCb);
            wait(3).until(ExpectedConditions.elementSelectionStateToBe(interactiveCb, false));
        }

        runAnalysis();
        saveScreenshot("15-scored-taxonomy-tree.png");
    }

    @Test
    @Order(16)
    void captureInteractiveMode() throws IOException {
        WebElement interactiveCb = driver.findElement(By.id("interactiveMode"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", interactiveCb);
        if (!interactiveCb.isSelected()) {
            js("arguments[0].click();", interactiveCb);
            wait(3).until(ExpectedConditions.elementSelectionStateToBe(interactiveCb, true));
        }

        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                textarea, REQUIREMENT_TEXT);
        WebElement analyzeBtn = driver.findElement(By.id("analyzeBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", analyzeBtn);
        js("arguments[0].click();", analyzeBtn);

        // Wait for at least one toggle to indicate top-level nodes are scored
        wait(60).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".tax-toggle")));
        saveScreenshot("16-interactive-mode.png");

        // Reset to non-interactive using JS direct manipulation to avoid stale-element issues
        forceNonInteractiveMode();
    }

    @Test
    @Order(17)
    void captureMatchLegendWithScores() throws IOException {
        // Full-page screenshot so the legend is shown in context with the scored tree visible,
        // making this visually distinct from the element-only screenshot 10 (captureMatchLegend).
        saveScreenshot("17-match-legend-with-scores.png");
    }

    @Test
    @Order(18)
    void captureLeafJustificationModal() throws IOException, InterruptedException {
        // Reload the page for a clean JS state
        resetPageState();
        // Clean up any leftover Bootstrap modal state from a previous failed retry run
        resetModalState();

        // 1. Run a full non-interactive analysis to score ALL nodes (including leaf nodes).
        //    The mock LLM always returns scores > 0 for non-BR nodes, so leaves will be scored > 0.
        forceNonInteractiveMode();
        runAnalysis();

        // 2. Set storedBusinessText via the window test helper so that buildNodeEl creates
        //    leaf justify buttons when the tree is re-rendered.  storedBusinessText is normally
        //    a closure variable only set by runInteractiveAnalysis(); the window helper is
        //    exposed specifically to support this screenshot-test scenario.
        js("window._setStoredBusinessText(arguments[0]);", REQUIREMENT_TEXT);

        // 3. Re-render the taxonomy list view with currentScores AND storedBusinessText now set.
        //    buildNodeEl will create .tax-justify-btn.btn-outline-info for every leaf node
        //    whose score is > 0.
        js("window._renderViewWithCurrentScores();");

        // 4. Expand all nodes so the leaf-level justify buttons are visible in the DOM.
        js("document.getElementById('expandAll').click();");

        // 5. Wait for at least one leaf justify button to appear.
        wait(10).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".tax-justify-btn.btn-outline-info")));
        List<WebElement> justifyBtns = driver.findElements(
                By.cssSelector(".tax-justify-btn.btn-outline-info"));
        Assertions.assertFalse(justifyBtns.isEmpty(),
                "No leaf justify buttons found after non-interactive analysis + expand-all. " +
                "The mock LLM may not be producing scores > 0 for leaf nodes.");

        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", justifyBtns.get(0));
        js("arguments[0].click();", justifyBtns.get(0));
        // Wait for the /api/justify-leaf mock call to populate the modal body text, which
        // is more reliable than a fixed sleep and confirms the API round-trip has completed.
        wait(15).until(d -> {
            List<WebElement> body = d.findElements(By.id("leafJustificationModalBody"));
            return !body.isEmpty() && !body.get(0).getText().isEmpty();
        });
        // Force show via DOM in case Bootstrap CDN is unavailable or animation did not fire
        showModalViaDOM("leafJustificationModal");
        saveScreenshot("18-leaf-justification-modal.png");
        closeModalViaDOM("leafJustificationModal");
        js("window.scrollTo(0, 0);");
        // Reset to non-interactive for subsequent tests
        forceNonInteractiveMode();
    }

    @Test
    @Order(19)
    void captureStaleResultsWarning() throws IOException {
        // Ensure a completed non-interactive analysis exists
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        if (!statusText.contains("complete")) {
            // Force off interactive mode via JS to avoid stale-element or JS-variable race
            forceNonInteractiveMode();
            runAnalysis();
        }

        // Modify textarea via JS to trigger the stale-results warning (300 ms debounce)
        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", textarea);
        js("arguments[0].value = arguments[0].value + ' modified'; arguments[0].dispatchEvent(new Event('input'));",
                textarea);
        wait(5).until(ExpectedConditions.attributeContains(
                By.id("businessText"), "class", "stale-results"));
        saveScreenshot("19-stale-results-warning.png");
    }

    @Test
    @Order(20)
    void captureArchitectureView() throws IOException {
        // Reload page for a clean state — test 18/19 leave the page with a streaming analysis
        // that does NOT include architectureView. A fresh load avoids stale SSE connections.
        resetPageState();

        // Disable interactive mode so a full analysis runs
        forceNonInteractiveMode();

        // Enable architecture view
        WebElement archCb = driver.findElement(By.id("includeArchitectureView"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", archCb);
        if (!archCb.isSelected()) {
            js("arguments[0].click();", archCb);
            wait(3).until(ExpectedConditions.elementSelectionStateToBe(archCb, true));
        }

        // Switch to a non-list/tabs view (sunburst) so that clicking Analyze calls runAnalysis()
        // instead of runStreamingAnalysis(). Only runAnalysis() (POST /api/analyze) sends
        // includeArchitectureView:true and renders the architectureViewPanel on completion.
        driver.findElement(By.id("viewSunburst")).click();
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));

        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                textarea, REQUIREMENT_TEXT);
        WebElement analyzeBtn = driver.findElement(By.id("analyzeBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", analyzeBtn);
        js("arguments[0].click();", analyzeBtn);

        // Wait for the analysis to complete — runAnalysis() POSTs and waits for JSON response
        wait(120).until(ExpectedConditions.textMatches(
                By.id("statusArea"), java.util.regex.Pattern.compile("(?i)complete|error")));
        // Navigate to Architecture tab to see the panel
        navigateToTab("architecture");
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureViewPanel")));
        saveElementScreenshot(driver.findElement(By.id("architectureViewPanel")), "20-architecture-view.png");

        // Reset: navigate back to analyze, switch back to list view and uncheck architecture view
        navigateToTab("analyze");
        driver.findElement(By.id("viewList")).click();
        if (archCb.isSelected()) {
            js("arguments[0].click();", archCb);
        }
    }

    @Test
    @Order(21)
    void captureGraphExplorerUpstream() throws IOException {
        navigateToTab("graph");
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", input);
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", input);
        js("arguments[0].value = 'BP-1'; arguments[0].dispatchEvent(new Event('input'));", input);
        WebElement upstreamBtn = driver.findElement(By.id("graphUpstreamBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", upstreamBtn);
        js("arguments[0].click();", upstreamBtn);

        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("graphResultsArea")));
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "21-graph-explorer-upstream.png");
    }

    @Test
    @Order(22)
    void captureGraphExplorerFailure() throws IOException {
        navigateToTab("graph");
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", input);
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", input);
        js("arguments[0].value = 'BP-1'; arguments[0].dispatchEvent(new Event('input'));", input);

        WebElement failureBtn = driver.findElement(By.id("graphFailureBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", failureBtn);
        js("arguments[0].click();", failureBtn);

        // Wait for the failure impact results to load.
        // The results are rendered inside graphViewTable (display:none by default) by wrapWithGraphToggle(),
        // so Selenium's textToBePresentInElementLocated cannot see the text. Instead, check innerHTML
        // via JavaScript: the loading spinner sets innerHTML to contain "spinner-border", and once the
        // API response arrives, wrapWithGraphToggle replaces it with toggle buttons + graph/table divs.
        wait(30).until(d -> {
            String html = (String) ((JavascriptExecutor) d).executeScript(
                    "var el = document.getElementById('graphResultsContent');" +
                    "return el ? el.innerHTML : '';");
            return html != null
                    && !html.contains("spinner-border")
                    && html.contains("graphViewTable");
        });
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "22-graph-explorer-failure.png");
    }

    @Test
    @Order(23)
    void captureExportButtons() throws IOException {
        // Ensure a completed analysis exists so export buttons are visible
        navigateToTab("analyze");
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        if (!statusText.contains("complete")) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        navigateToTab("export");
        wait(10).until(ExpectedConditions.visibilityOfElementLocated(By.id("exportGroup")));
        // Wait for the export group to have a non-zero size (not collapsed)
        wait(5).until(d -> {
            WebElement eg = d.findElement(By.id("exportGroup"));
            return eg.getSize().getWidth() > 0 && eg.getSize().getHeight() > 0;
        });
        saveElementScreenshot(driver.findElement(By.id("exportGroup")), "23-export-buttons.png");
    }

    @Test
    @Order(24)
    void captureLlmDiagnostics() throws IOException {
        unlockAdmin();
        navigateToTab("admin");

        WebElement diagPanel = driver.findElement(By.id("diagnosticsPanel"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", diagPanel);
        openDetails(diagPanel);
        wait(5).until(ExpectedConditions.visibilityOf(diagPanel));
        // Wait for diagnostics content to load (no longer shows "Loading…" placeholder)
        wait(10).until(d -> {
            WebElement content = d.findElement(By.id("diagnosticsContent"));
            String text = content.getText();
            return text != null && !text.isEmpty() && !text.contains("Loading");
        });
        saveElementScreenshot(diagPanel, "24-llm-diagnostics.png");
    }

    @Test
    @Order(25)
    void capturePromptTemplateEditor() throws IOException {
        navigateToTab("admin");
        WebElement promptEditor = driver.findElement(By.id("promptEditor"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", promptEditor);
        openDetails(promptEditor);
        wait(5).until(ExpectedConditions.visibilityOf(promptEditor));
        saveElementScreenshot(promptEditor, "25-prompt-template-editor.png");
    }

    // ── Screenshots 26–27: Requirement Coverage Dashboard ─────────────────────

    @Test
    @Order(26)
    void captureCoverageDashboardEmpty() throws IOException {
        navigateToTab("admin");
        WebElement panel = driver.findElement(By.id("coverageDashboard"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", panel);
        openDetails(panel);
        // Wait for the content area to finish its initial load (spinner disappears)
        wait(10).until(d -> {
            String text = d.findElement(By.id("coverageDashboardContent")).getText();
            return text != null && !text.contains("Loading");
        });
        saveElementScreenshot(panel, "26-coverage-dashboard-empty.png");
    }

    @Test
    @Order(27)
    void captureCoverageDashboardWithData() throws IOException {
        // Ensure scores are available — run a fresh analysis if window._getCurrentScores() is empty
        String hasScores = (String) ((JavascriptExecutor) driver).executeScript(
                "var s = typeof window._getCurrentScores === 'function' ? window._getCurrentScores() : null;" +
                "return (s && Object.keys(s).length > 0) ? 'yes' : 'no';");
        if (!"yes".equals(hasScores)) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        navigateToTab("admin");

        // POST coverage record directly via fetch (avoids window.prompt dialog).
        // A sentinel variable signals completion.
        js("window._coverageRecorded = false;" +
           "fetch('/api/coverage/record', {" +
           "  method: 'POST'," +
           "  headers: {'Content-Type': 'application/json'}," +
           "  body: JSON.stringify({" +
           "    requirementId: 'REQ-001'," +
           "    requirementText: arguments[0]," +
           "    scores: (typeof window._getCurrentScores === 'function' ? window._getCurrentScores() : {})," +
           "    minScore: 50" +
           "  })" +
           "}).then(function() { window._coverageRecorded = true; });",
           REQUIREMENT_TEXT);
        wait(10).until(d ->
                (Boolean) ((JavascriptExecutor) d).executeScript("return window._coverageRecorded === true;"));

        // Reload the dashboard so it picks up the newly recorded data
        js("if (window.TaxonomyCoverage) window.TaxonomyCoverage.loadCoverageDashboard();");
        wait(10).until(d -> {
            String text = d.findElement(By.id("coverageDashboardContent")).getText();
            return text != null && !text.contains("Loading") && !text.isEmpty();
        });

        WebElement panel = driver.findElement(By.id("coverageDashboard"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", panel);
        saveElementScreenshot(panel, "27-coverage-dashboard-data.png");
    }

    // ── Screenshots 28–34: Additional screenshots for documentation completeness ──

    @Test
    @Order(28)
    void captureProposalReviewQueue() throws IOException {
        navigateToTab("relations");
        WebElement panel = driver.findElement(By.id("proposalsPanel"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", panel);
        // Click the "All" filter to show all proposals (pending, accepted, rejected)
        WebElement allFilter = driver.findElement(By.id("filterAll"));
        js("arguments[0].click();", allFilter);
        wait(10).until(d -> {
            WebElement container = d.findElement(By.id("proposalsTableContainer"));
            String text = container.getText();
            // Positive condition: either proposals are shown (table rows) or a "no proposals" message
            return text != null && !text.isEmpty() && !text.contains("Loading");
        });
        saveElementScreenshot(panel, "28-proposal-review-queue.png");
    }

    @Test
    @Order(29)
    void captureSearchFullText() throws IOException {
        navigateToTab("analyze");
        // Open the search panel
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        openDetails(searchPanel);
        // Set search mode to full-text
        js("document.getElementById('searchModeSelect').value = 'fulltext';");
        // Enter search query
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", searchInput);
        js("arguments[0].value = 'voice communications'; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        // Click search button
        js("document.getElementById('searchBtn').click();");
        wait(10).until(d -> {
            WebElement results = d.findElement(By.id("searchResultsArea"));
            return results.isDisplayed() && !results.getText().isEmpty();
        });
        saveElementScreenshot(searchPanel, "29-search-fulltext.png");
    }

    @Test
    @Order(30)
    void captureSearchSemantic() throws IOException {
        navigateToTab("analyze");
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        openDetails(searchPanel);
        // Set search mode to semantic
        js("document.getElementById('searchModeSelect').value = 'semantic';");
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", searchInput);
        js("arguments[0].value = 'secure data exchange'; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        js("document.getElementById('searchBtn').click();");
        wait(10).until(d -> {
            WebElement results = d.findElement(By.id("searchResultsArea"));
            return results.isDisplayed() && !results.getText().isEmpty();
        });
        saveElementScreenshot(searchPanel, "30-search-semantic.png");
    }

    @Test
    @Order(31)
    void captureSearchHybrid() throws IOException {
        navigateToTab("analyze");
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        openDetails(searchPanel);
        // Set search mode to hybrid
        js("document.getElementById('searchModeSelect').value = 'hybrid';");
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", searchInput);
        js("arguments[0].value = 'command and control'; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        js("document.getElementById('searchBtn').click();");
        wait(10).until(d -> {
            WebElement results = d.findElement(By.id("searchResultsArea"));
            return results.isDisplayed() && !results.getText().isEmpty();
        });
        saveElementScreenshot(searchPanel, "31-search-hybrid.png");
    }

    @Test
    @Order(32)
    void captureSearchGraph() throws IOException {
        navigateToTab("analyze");
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        openDetails(searchPanel);
        // Set search mode to graph
        js("document.getElementById('searchModeSelect').value = 'graph';");
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", searchInput);
        js("arguments[0].value = 'intelligence processing'; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        js("document.getElementById('searchBtn').click();");
        wait(10).until(d -> {
            WebElement results = d.findElement(By.id("searchResultsArea"));
            return results.isDisplayed() && !results.getText().isEmpty();
        });
        saveElementScreenshot(searchPanel, "32-search-graph.png");
    }

    @Test
    @Order(33)
    void captureExportTab() throws IOException {
        // Ensure a completed analysis exists so export buttons are visible
        navigateToTab("analyze");
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        if (!statusText.contains("complete")) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        navigateToTab("export");
        wait(10).until(ExpectedConditions.visibilityOfElementLocated(By.id("exportGroup")));
        // Take a full-tab screenshot showing all export options
        saveScreenshot("33-export-tab.png");
    }

    @Test
    @Order(34)
    void captureDslEditorPanel() throws IOException {
        navigateToTab("dsl-editor");
        // Directly fetch DSL export and populate textarea (bypasses stale JS init after tab navigation)
        js("fetch('/api/dsl/export').then(r => r.text()).then(t => {" +
           "  var ta = document.getElementById('dslEditorTextarea');" +
           "  if (ta && t && t.trim().length > 0) { ta.value = t; }" +
           "});");
        try {
            wait(15).until(d -> {
                WebElement ta = d.findElement(By.id("dslEditorTextarea"));
                String val = ta.getAttribute("value");
                return val != null && !val.isBlank() && val.length() > 50;
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Fallback: inject representative DSL for the screenshot
            js("document.getElementById('dslEditorTextarea').value = '" + FALLBACK_DSL_TEXT + "';");
        }
        saveScreenshot("34-dsl-editor-panel.png");
    }

    // ── Screenshots 35–40: Extended visual guide ──────────────────────────────

    @Test
    @Order(35)
    void captureFullScoredBPTreeExpanded() throws IOException {
        // Ensure analysis is complete (reuse scores from test 15)
        navigateToTab("analyze");
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        if (!statusText.contains("complete")) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        // Switch to list view and expand all nodes
        driver.findElement(By.id("viewList")).click();
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewList"), "class", "btn-primary"));
        js("document.getElementById('expandAll').click();");
        wait(5).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".tax-toggle")));
        // Increase viewport height to capture more of the tree
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 2000));
        wait(2).until(d -> true); // brief settle
        // Scroll to the taxonomy tree and capture an element screenshot
        WebElement tree = driver.findElement(By.id("taxonomyTree"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'start'});", tree);
        saveElementScreenshot(tree, "35-scored-bp-tree-expanded.png");
        // Reset viewport
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900));
    }

    @Test
    @Order(36)
    void captureProposalAccepted() throws IOException {
        // Discover real taxonomy codes at runtime — never hardcode codes that may not exist.
        // Fetch /api/taxonomy, walk the tree to find the first child under the CP root and
        // the first child under the CR root.  Store them in window globals for later tests.
        js("window._proposalCreated = false; window._proposalError = null;" +
           "fetch('/api/taxonomy').then(r => r.json()).then(function(roots) {" +
           "  function firstChild(roots, prefix) {" +
           "    for (var i = 0; i < roots.length; i++) {" +
           "      if (roots[i].code === prefix && roots[i].children && roots[i].children.length > 0)" +
           "        return roots[i].children[0].code;" +
           "    }" +
           "    return null;" +
           "  }" +
           "  var src = firstChild(roots, 'CP');" +
           "  var tgt = firstChild(roots, 'CR');" +
           "  if (!src || !tgt) throw new Error('Cannot find CP or CR child nodes in taxonomy');" +
           "  window._acceptedSourceCode = src;" +
           "  window._acceptedTargetCode = tgt;" +
           "  return fetch('/api/proposals/from-hypothesis', {" +
           "    method: 'POST'," +
           "    headers: {'Content-Type': 'application/json'}," +
           "    body: JSON.stringify({" +
           "      sourceCode: src, targetCode: tgt," +
           "      relationType: 'REALIZES', confidence: 0.85," +
           "      rationale: src + ' provides the capability that ' + tgt + ' implements as a core service'" +
           "    })" +
           "  });" +
           "}).then(r => { if (!r.ok) throw new Error('Proposal creation failed: ' + r.status); return r.json(); })" +
           ".then(function(data) {" +
           "  window._createdProposalId = data.id;" +
           "  return fetch('/api/proposals/' + data.id + '/accept', {method: 'POST'});" +
           "}).then(r => { if (!r.ok) throw new Error('Proposal accept failed: ' + r.status); return r.json(); })" +
           ".then(function() { window._proposalCreated = true; })" +
           ".catch(function(err) { window._proposalError = err.message || String(err); });");
        wait(30).until(d -> {
            Boolean done = (Boolean) ((JavascriptExecutor) d).executeScript(
                    "return window._proposalCreated === true;");
            if (Boolean.TRUE.equals(done)) return true;
            String err = (String) ((JavascriptExecutor) d).executeScript(
                    "return window._proposalError;");
            if (err != null) throw new RuntimeException("Proposal creation/accept failed: " + err);
            return false;
        });

        // Navigate to the relations tab and filter to show all proposals
        navigateToTab("relations");
        WebElement panel = driver.findElement(By.id("proposalsPanel"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", panel);
        // Click the "Accepted" filter button to show accepted proposals
        js("var btn = document.getElementById('filterAccepted'); if (btn) btn.click();");
        wait(10).until(d -> {
            WebElement container = d.findElement(By.id("proposalsTableContainer"));
            String text = container.getText();
            return text != null && !text.isEmpty() && !text.contains("Loading");
        });
        saveElementScreenshot(panel, "36-proposal-accepted.png");
    }

    @Test
    @Order(37)
    void captureGraphWithAcceptedRelation() throws IOException {
        // Read the source code stored by test 36 (discovered from live taxonomy data).
        String sourceCode = (String) ((JavascriptExecutor) driver).executeScript(
                "return window._acceptedSourceCode || 'CP';");
        navigateToTab("graph");
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", input);
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", input);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                input, sourceCode);

        // Click Downstream to show the accepted relation
        WebElement downstreamBtn = driver.findElement(By.id("graphDownstreamBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", downstreamBtn);
        js("arguments[0].click();", downstreamBtn);

        wait(30).until(d -> {
            String html = (String) ((JavascriptExecutor) d).executeScript(
                    "var el = document.getElementById('graphResultsContent');" +
                    "return el ? el.innerHTML : '';");
            return html != null
                    && !html.contains("spinner-border")
                    && html.contains("graphViewTable");
        });
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")),
                "37-graph-with-accepted-relation.png");
    }

    @Test
    @Order(38)
    void captureDetailedArchitectureView() throws IOException {
        // Reload page for clean state
        resetPageState();
        forceNonInteractiveMode();

        // Enable architecture view
        WebElement archCb = driver.findElement(By.id("includeArchitectureView"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", archCb);
        if (!archCb.isSelected()) {
            js("arguments[0].click();", archCb);
            wait(3).until(ExpectedConditions.elementSelectionStateToBe(archCb, true));
        }

        // Switch to sunburst view so Analyze calls POST /api/analyze (not streaming)
        driver.findElement(By.id("viewSunburst")).click();
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));

        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                textarea, REQUIREMENT_TEXT);
        WebElement analyzeBtn = driver.findElement(By.id("analyzeBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", analyzeBtn);
        js("arguments[0].click();", analyzeBtn);

        wait(120).until(ExpectedConditions.textMatches(
                By.id("statusArea"), java.util.regex.Pattern.compile("(?i)complete|error")));
        // Navigate to architecture tab
        navigateToTab("architecture");
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureViewPanel")));
        saveElementScreenshot(driver.findElement(By.id("architectureViewPanel")),
                "38-architecture-view-detailed.png");

        // Reset: switch back to list view and uncheck architecture view
        navigateToTab("analyze");
        driver.findElement(By.id("viewList")).click();
        archCb = driver.findElement(By.id("includeArchitectureView"));
        if (archCb.isSelected()) {
            js("arguments[0].click();", archCb);
        }
    }

    @Test
    @Order(39)
    void captureScoredSunburst() throws IOException {
        // Ensure analysis is complete
        navigateToTab("analyze");
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        if (!statusText.contains("complete")) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        // Switch to sunburst view — scores are already computed, so the sunburst will be coloured
        driver.findElement(By.id("viewSunburst")).click();
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));
        saveScreenshot("39-scored-sunburst.png");
        // Reset to list view
        driver.findElement(By.id("viewList")).click();
    }

    @Test
    @Order(40)
    void captureDslWithRelations() throws IOException {
        navigateToTab("dsl-editor");
        // Fetch the DSL export which should now include relation blocks from accepted proposals
        js("fetch('/api/dsl/export').then(r => r.text()).then(t => {" +
           "  var ta = document.getElementById('dslEditorTextarea');" +
           "  if (ta && t && t.trim().length > 0) { ta.value = t; }" +
           "});");
        try {
            wait(15).until(d -> {
                WebElement ta = d.findElement(By.id("dslEditorTextarea"));
                String val = ta.getAttribute("value");
                return val != null && !val.isBlank() && val.contains("relation");
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Fallback: inject DSL with relation blocks for the screenshot
            js("document.getElementById('dslEditorTextarea').value = '" + FALLBACK_DSL_TEXT + "';");
        }
        saveScreenshot("40-dsl-editor-with-relations.png");
    }
}
