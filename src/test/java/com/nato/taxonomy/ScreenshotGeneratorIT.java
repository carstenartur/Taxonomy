package com.nato.taxonomy;

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
 * Screenshots 15–27 use mock LLM mode ({@code LLM_MOCK=true}) to produce
 * realistic scores without calling any real LLM API.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "generateScreenshots", matches = ".*")
class ScreenshotGeneratorIT {

    private static final String REQUIREMENT_TEXT =
            "Provide secure voice communications between HQ and deployed forces";

    private static final Path OUTPUT_DIR = Path.of("docs/images");

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
                        .withFileFromPath("app.jar", Path.of("target/taxonomy-1.0.0-SNAPSHOT.jar"))
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

        // Load the application and wait for the taxonomy tree to be present
        driver.get("http://app:8080/");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
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

    // ── Screenshots 1–14: no LLM required ─────────────────────────────────────

    @Test
    @Order(1)
    void captureFullPageLayout() throws IOException {
        driver.get("http://app:8080/");
        wait(20).until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
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
        driver.findElement(By.id("businessText")).clear();
        saveElementScreenshot(driver.findElement(By.cssSelector(".card.shadow-sm")), "04-analysis-panel-empty.png");
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
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        input.clear();
        input.sendKeys("BP");
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "11-graph-explorer-panel.png");
    }

    @Test
    @Order(12)
    void captureRelationProposalsPanel() throws IOException {
        WebElement panel = driver.findElement(By.id("proposalsPanel"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", panel);
        saveElementScreenshot(panel, "12-relation-proposals-panel.png");
    }

    @Test
    @Order(13)
    void captureProposeRelationsModal() throws IOException, InterruptedException {
        // Clean up any leftover modal state from a previous failed retry run
        resetModalState();
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
        // Reload the page to ensure clean JS state. After reload interactiveMode defaults to true
        // (the checkbox has the checked attribute in HTML and the JS closure initialises to true).
        resetPageState();

        // Verify interactive mode is ON — leaf justify buttons only appear when storedBusinessText
        // is set, which only happens during runInteractiveAnalysis(), not runStreamingAnalysis().
        js("var cb = document.getElementById('interactiveMode');" +
           "if (cb && !cb.checked) { cb.checked = true; cb.dispatchEvent(new Event('change')); }");

        // Run interactive analysis: sets storedBusinessText and renders tree without scores
        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                textarea, REQUIREMENT_TEXT);
        js("document.getElementById('analyzeBtn').click();");
        // Interactive analysis renders the tree immediately, marking parent nodes as unevaluated
        wait(10).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".tax-has-unevaluated")));

        // Expand parent nodes level by level until leaf justify buttons appear.
        // Each expand triggers an API call to /api/analyze-node which scores child nodes;
        // leaf children with score > 0 get the .btn-outline-info justify button.
        for (int depth = 0; depth < 4; depth++) {
            List<WebElement> unevalToggles = driver.findElements(
                    By.cssSelector(".tax-has-unevaluated > .tax-node-header > .tax-toggle"));
            if (unevalToggles.isEmpty()) break;
            js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", unevalToggles.get(0));
            js("arguments[0].click();", unevalToggles.get(0));
            // Wait for the evaluation API call to complete (tax-evaluating class is removed on finish)
            wait(15).until(d -> driver.findElements(By.cssSelector(".tax-evaluating")).isEmpty());
            if (!driver.findElements(By.cssSelector(".tax-justify-btn.btn-outline-info")).isEmpty()) {
                break; // Leaf justify buttons found — no need to drill deeper
            }
        }

        List<WebElement> justifyBtns = driver.findElements(
                By.cssSelector(".tax-justify-btn.btn-outline-info"));
        if (justifyBtns.isEmpty()) {
            // Fallback: run a non-interactive full analysis first to guarantee scores, then
            // reload and retry the interactive expansion loop.
            forceNonInteractiveMode();
            runAnalysis();
            resetPageState();

            js("var cb = document.getElementById('interactiveMode');" +
               "if (cb && !cb.checked) { cb.checked = true; cb.dispatchEvent(new Event('change')); }");

            WebElement textarea2 = driver.findElement(By.id("businessText"));
            js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea2);
            js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                    textarea2, REQUIREMENT_TEXT);
            js("document.getElementById('analyzeBtn').click();");
            wait(10).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".tax-has-unevaluated")));

            for (int depth = 0; depth < 4; depth++) {
                List<WebElement> unevalToggles = driver.findElements(
                        By.cssSelector(".tax-has-unevaluated > .tax-node-header > .tax-toggle"));
                if (unevalToggles.isEmpty()) break;
                js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", unevalToggles.get(0));
                js("arguments[0].click();", unevalToggles.get(0));
                wait(15).until(d -> driver.findElements(By.cssSelector(".tax-evaluating")).isEmpty());
                if (!driver.findElements(By.cssSelector(".tax-justify-btn.btn-outline-info")).isEmpty()) {
                    break;
                }
            }
            justifyBtns = driver.findElements(By.cssSelector(".tax-justify-btn.btn-outline-info"));
        }

        Assertions.assertFalse(justifyBtns.isEmpty(),
                "No leaf justify buttons found — screenshot cannot be captured. " +
                "The mock LLM may not be producing scores > 0 for leaf nodes.");
        if (!justifyBtns.isEmpty()) {
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
        }
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
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureViewPanel")));
        saveElementScreenshot(driver.findElement(By.id("architectureViewPanel")), "20-architecture-view.png");

        // Reset: switch back to list view and uncheck architecture view
        driver.findElement(By.id("viewList")).click();
        if (archCb.isSelected()) {
            js("arguments[0].click();", archCb);
        }
    }

    @Test
    @Order(21)
    void captureGraphExplorerUpstream() throws IOException {
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
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", input);
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", input);
        js("arguments[0].value = 'BP-1'; arguments[0].dispatchEvent(new Event('input'));", input);
        WebElement failureBtn = driver.findElement(By.id("graphFailureBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", failureBtn);
        js("arguments[0].click();", failureBtn);

        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("graphResultsArea")));
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "22-graph-explorer-failure.png");
    }

    @Test
    @Order(23)
    void captureExportButtons() throws IOException {
        // Ensure a completed analysis exists so export buttons are visible
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        if (!statusText.contains("complete")) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        js("window.scrollTo(0, 0);");
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
}
