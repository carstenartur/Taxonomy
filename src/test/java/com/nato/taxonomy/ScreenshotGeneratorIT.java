package com.nato.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
 * Screenshots 15–26 require {@code GEMINI_API_KEY} in the environment; they are
 * skipped gracefully when the key is absent.
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

        String geminiKey = System.getenv("GEMINI_API_KEY");

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
                .withStartupTimeout(Duration.ofSeconds(120))
                // Use /api/ai-status (public) instead of /api/diagnostics (returns 401 when
                // ADMIN_PASSWORD is configured) as the container readiness check.
                .waitingFor(Wait.forHttp("/api/ai-status").forStatusCode(200).forPort(8080));

        if (geminiKey != null && !geminiKey.isBlank()) {
            appContainer = appContainer.withEnv("GEMINI_API_KEY", geminiKey);
        }

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
        textarea.clear();
        textarea.sendKeys(REQUIREMENT_TEXT);
        driver.findElement(By.id("analyzeBtn")).click();
        wait(120).until(ExpectedConditions.textMatches(
                By.id("statusArea"), java.util.regex.Pattern.compile("(?i)complete|error")));
    }

    private void js(String script, Object... args) {
        ((JavascriptExecutor) driver).executeScript(script, args);
    }

    /**
     * Closes a Bootstrap modal directly via DOM manipulation, bypassing the fade animation.
     * This avoids timing issues when waiting for the Bootstrap animation to complete.
     */
    private void closeModalViaDOM(String modalId) {
        js("var el = document.getElementById('" + modalId + "'); " +
                "el.classList.remove('show'); el.style.display='none'; " +
                "document.querySelectorAll('.modal-backdrop').forEach(b => b.remove()); " +
                "document.body.classList.remove('modal-open'); document.body.style.overflow='';");
        wait(5).until(ExpectedConditions.invisibilityOfElementLocated(By.id(modalId)));
    }

    /** Makes the admin lock button visible (it is hidden until the AI status check resolves). */
    private void showAdminLockButton() {
        js("document.getElementById('adminLockBtn').classList.remove('d-none');");
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("adminLockBtn")));
    }

    /** Unlocks admin mode via the REST endpoint and reveals the admin-only panels. */
    private void unlockAdmin() {
        js("fetch('/api/admin/unlock', {method:'POST', " +
                "headers:{'Content-Type':'application/json'}, " +
                "body: JSON.stringify({password:'testpassword123'})})");
        // Wait for admin panels to become visible after unlock
        wait(10).until(d -> {
            js("document.querySelectorAll('.admin-only').forEach(el => el.classList.remove('d-none'));");
            List<WebElement> panels = driver.findElements(By.cssSelector(".admin-only"));
            return !panels.isEmpty() && panels.stream().anyMatch(WebElement::isDisplayed);
        });
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
        // Take element screenshot of the right panel column
        saveElementScreenshot(
                driver.findElement(By.cssSelector(".col-lg-5")),
                "03-right-panel-default.png");
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
        saveElementScreenshot(driver.findElement(By.id("proposalsPanel")), "12-relation-proposals-panel.png");
    }

    @Test
    @Order(13)
    void captureProposeRelationsModal() throws IOException {
        // Scroll to the taxonomy tree so the proposal buttons become visible
        WebElement taxonomyTree = driver.findElement(By.id("taxonomyTree"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'start'});", taxonomyTree);
        // Wait for at least one proposal button to be present in the DOM
        wait(15).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".proposal-btn")));
        // Use JS click to work around any overlay or scroll positioning issues
        WebElement proposeBtn = driver.findElement(By.cssSelector(".proposal-btn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", proposeBtn);
        js("arguments[0].click();", proposeBtn);
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("proposeRelationsModal")));
        saveScreenshot("13-propose-relations-modal.png");
        closeModalViaDOM("proposeRelationsModal");
    }

    @Test
    @Order(14)
    void captureAdminLockButton() throws IOException {
        showAdminLockButton();
        saveScreenshot("14-navbar-admin-lock.png");
    }

    // ── Screenshots 15–26: require GEMINI_API_KEY ─────────────────────────────

    @Test
    @Order(15)
    void captureScoredTaxonomyTree() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        WebElement interactiveCb = driver.findElement(By.id("interactiveMode"));
        if (interactiveCb.isSelected()) {
            interactiveCb.click();
            wait(3).until(ExpectedConditions.elementSelectionStateToBe(interactiveCb, false));
        }

        runAnalysis();
        saveScreenshot("15-scored-taxonomy-tree.png");
    }

    @Test
    @Order(16)
    void captureInteractiveMode() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        WebElement interactiveCb = driver.findElement(By.id("interactiveMode"));
        if (!interactiveCb.isSelected()) {
            interactiveCb.click();
            wait(3).until(ExpectedConditions.elementSelectionStateToBe(interactiveCb, true));
        }

        WebElement textarea = driver.findElement(By.id("businessText"));
        textarea.clear();
        textarea.sendKeys(REQUIREMENT_TEXT);
        driver.findElement(By.id("analyzeBtn")).click();

        // Wait for at least one toggle to indicate top-level nodes are scored
        wait(60).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".tax-toggle")));
        saveScreenshot("16-interactive-mode.png");

        // Reset to non-interactive
        if (interactiveCb.isSelected()) {
            interactiveCb.click();
        }
    }

    @Test
    @Order(17)
    void captureMatchLegendWithScores() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        WebElement legendCard = driver.findElement(
                By.xpath("//div[contains(@class,'card')]//small[contains(text(),'Match legend')]/ancestor::div[contains(@class,'card')]"));
        saveElementScreenshot(legendCard, "17-match-legend-with-scores.png");
    }

    @Test
    @Order(18)
    void captureLeafJustificationModal() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        // Ensure scores are present — expand the first node to reveal justify buttons
        List<WebElement> toggles = driver.findElements(By.cssSelector(".tax-toggle"));
        if (!toggles.isEmpty()) {
            toggles.get(0).click();
            wait(5).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".tax-justify-btn")));
        }

        List<WebElement> justifyBtns = driver.findElements(By.cssSelector(".tax-justify-btn"));
        if (!justifyBtns.isEmpty()) {
            justifyBtns.get(0).click();
            wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("leafJustificationModal")));
            saveScreenshot("18-leaf-justification-modal.png");
            closeModalViaDOM("leafJustificationModal");
        }
    }

    @Test
    @Order(19)
    void captureStaleResultsWarning() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        // Ensure a completed analysis exists
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        if (!statusText.contains("complete")) {
            WebElement interactiveCb = driver.findElement(By.id("interactiveMode"));
            if (interactiveCb.isSelected()) {
                interactiveCb.click();
                wait(3).until(ExpectedConditions.elementSelectionStateToBe(interactiveCb, false));
            }
            runAnalysis();
        }

        // Modify textarea to trigger the stale-results warning (300 ms debounce)
        driver.findElement(By.id("businessText")).sendKeys(" modified");
        wait(5).until(ExpectedConditions.attributeContains(
                By.id("businessText"), "class", "stale-results"));
        saveScreenshot("19-stale-results-warning.png");
    }

    @Test
    @Order(20)
    void captureArchitectureView() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        WebElement archCb = driver.findElement(By.id("includeArchitectureView"));
        if (!archCb.isSelected()) {
            archCb.click();
            wait(3).until(ExpectedConditions.elementSelectionStateToBe(archCb, true));
        }
        WebElement interactiveCb = driver.findElement(By.id("interactiveMode"));
        if (interactiveCb.isSelected()) {
            interactiveCb.click();
            wait(3).until(ExpectedConditions.elementSelectionStateToBe(interactiveCb, false));
        }

        WebElement textarea = driver.findElement(By.id("businessText"));
        textarea.clear();
        textarea.sendKeys(REQUIREMENT_TEXT);
        driver.findElement(By.id("analyzeBtn")).click();

        wait(120).until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureViewPanel")));
        saveElementScreenshot(driver.findElement(By.id("architectureViewPanel")), "20-architecture-view.png");

        if (archCb.isSelected()) {
            archCb.click();
        }
    }

    @Test
    @Order(21)
    void captureGraphExplorerUpstream() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        WebElement input = driver.findElement(By.id("graphNodeInput"));
        input.clear();
        input.sendKeys("BP-1");
        driver.findElement(By.id("graphUpstreamBtn")).click();

        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("graphResultsArea")));
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "21-graph-explorer-upstream.png");
    }

    @Test
    @Order(22)
    void captureGraphExplorerFailure() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        WebElement input = driver.findElement(By.id("graphNodeInput"));
        input.clear();
        input.sendKeys("BP-1");
        driver.findElement(By.id("graphFailureBtn")).click();

        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("graphResultsArea")));
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "22-graph-explorer-failure.png");
    }

    @Test
    @Order(23)
    void captureExportButtons() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        wait(10).until(ExpectedConditions.visibilityOfElementLocated(By.id("exportGroup")));
        saveElementScreenshot(driver.findElement(By.id("exportGroup")), "23-export-buttons.png");
    }

    @Test
    @Order(24)
    void captureAdminModal() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        showAdminLockButton();
        js("new bootstrap.Modal(document.getElementById('adminModal')).show();");
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("adminModal")));
        saveScreenshot("24-admin-modal.png");
        closeModalViaDOM("adminModal");
    }

    @Test
    @Order(25)
    void captureLlmDiagnostics() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        unlockAdmin();

        WebElement diagPanel = driver.findElement(By.id("diagnosticsPanel"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", diagPanel);
        openDetails(diagPanel);
        wait(5).until(ExpectedConditions.visibilityOf(diagPanel));
        saveElementScreenshot(diagPanel, "25-llm-diagnostics.png");
    }

    @Test
    @Order(26)
    void capturePromptTemplateEditor() throws IOException {
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null,
                "Skipping: GEMINI_API_KEY not set");

        WebElement promptEditor = driver.findElement(By.id("promptEditor"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", promptEditor);
        openDetails(promptEditor);
        wait(5).until(ExpectedConditions.visibilityOf(promptEditor));
        saveElementScreenshot(promptEditor, "26-prompt-template-editor.png");
    }
}
