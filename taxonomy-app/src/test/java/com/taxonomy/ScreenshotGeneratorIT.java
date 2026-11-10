package com.taxonomy;

import com.taxonomy.acceptance.OnnxReferenceEvaluation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates documentation screenshots for docs/images/.
 * <p>
 * Opt-in: run from the repository root with {@code ./mvnw -B verify -Pscreenshots}.
 * <p>
 * Screenshots 1–14 do not require an LLM provider.
 * Screenshots 15–40 use mock LLM mode ({@code LLM_MOCK=true}) to produce
 * realistic scores without calling any real LLM API.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "generateScreenshots", matches = "true")
@Tag("docker-required")
class ScreenshotGeneratorIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String REQUIREMENT_TEXT =
            "Provide an integrated communication platform for hospital staff, enabling real-time voice " +
            "and data exchange between departments, with a clinical dashboard application for patient " +
            "handoff tracking and team coordination";

    /**
     * DSL content committed on the {@code draft} branch during {@link #buildGitHistory()}.
     * Uses actual taxonomy codes with canonical titles from the imported catalogue.
     * Normal user workflows do NOT modify these imported titles.
     */
    private static final String DRAFT_DSL =
            "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
            "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n}\n\n" +
            "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
            "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n";

    /**
     * DSL content committed on the {@code feature-voice} branch during {@link #buildGitHistory()}.
     * Deliberately differs from {@link #DRAFT_DSL} by adding new architecture elements,
     * relations, and a local annotation — without modifying canonical taxonomy titles.
     * This reflects realistic user workflows where imported taxonomy data remains
     * read-only and users modify architecture overlays (relations, views, extensions).
     */
    private static final String FEATURE_VOICE_DSL =
            "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
            "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n  x-alias: \"SecVoice\";\n}\n\n" +
            "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
            "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
            "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
            "relation CO-1011 USES CR-1047 {\n  status: proposed;\n}\n";

    /** Guard flag: {@link #buildGitHistory()} runs at most once per test class execution. */
    private static boolean gitHistoryBuilt;

    /** Actual persisted example version used by the DSL history capture. */
    private static String draftFixtureCommitId;

    private static String graphFixtureCommitId;
    private static String graphFixtureScope;
    private static SavedGraphHttpFixture.Saved graphFixture;

    /** Guard flag: {@link #buildRichGitHistory()} runs at most once per test class execution. */
    private static boolean richGitHistoryBuilt;

    /** Stores the commit ID captured during the rich history build for cherry-pick tests. */
    private static String cherryPickSourceCommitId;

    private static final Path OUTPUT_DIR = resolveOutputDir();

    /**
     * Uses {@code TAXONOMY_DOCS_IMAGE_DIR}, then {@code taxonomy.docs.image.dir}, otherwise
     * the repository's {@code docs/images/} folder. CI uses a fresh directory shared by all capture owners.
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
        String configured = System.getenv("TAXONOMY_DOCS_IMAGE_DIR");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("taxonomy.docs.image.dir");
        }
        return configured == null || configured.isBlank() ? resolveDefaultOutputDir() : Path.of(configured);
    }

    private static Path resolveDefaultOutputDir() {
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
    private static ContainerTestUtils.BrowserSession browserSession;
    private static WebDriver driver;

    @RegisterExtension
    static final TestWatcher captureFailureEvidence = new TestWatcher() {
        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            if (driver == null) return;
            Path evidence = resolveDefaultOutputDir().toAbsolutePath().getParent().getParent()
                    .resolve("target/screenshot-failures");
            String name = context.getRequiredTestMethod().getName();
            try {
                Files.createDirectories(evidence);
                Files.writeString(evidence.resolve(name + ".html"), driver.getPageSource());
                Files.write(evidence.resolve(name + ".png"),
                        ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
            } catch (Exception diagnosticFailure) {
                cause.addSuppressed(diagnosticFailure);
            }
        }
    };

    @BeforeAll
    static void startContainers() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        // Use the same Maven-provisioned bundle and read-only container mount as the ONNX ITs.
        Path modelDirectory = OnnxReferenceEvaluation.modelDirectory();
        network = Network.newNetwork();

        GenericContainer<?> appContainer = ContainerTestUtils.appContainer(network, "hsqldb",
                        "jdbc:hsqldb:mem:screenshot-fixtures;shutdown=true", "sa", "")
                .withEnv("TAXONOMY_FEATURES_MULTI_REPOSITORY_API_ENABLED", "true")
                .withEnv("LLM_PROVIDER", "LOCAL_ONNX")
                .withEnv("LLM_MOCK", "true")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "true")
                .withEnv("TAXONOMY_EMBEDDING_MODEL_PROFILE", OnnxReferenceEvaluation.MODEL_PROFILE)
                .withEnv("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false")
                .withFileSystemBind(modelDirectory.toString(), "/models",
                        org.testcontainers.containers.BindMode.READ_ONLY)
                .withEnv("TAXONOMY_EMBEDDING_MODEL_DIR", "/models")
                .withEnv("TAXONOMY_GIT_BOOTSTRAP", "true")
                // Wait for the taxonomy to be fully loaded and search index built.
                // The /api/status/startup endpoint returns {"status":"ready"} once
                // AppInitializationStateService transitions to READY — works for both
                // synchronous (default) and asynchronous initialization modes.
                .waitingFor(Wait.forHttp("/api/status/startup")
                        .forStatusCode(200)
                        .forPort(8080)
                        .withBasicCredentials("admin", ContainerTestUtils.TEST_ADMIN_PASSWORD)
                        .forResponsePredicate(body -> body.contains("\"status\":\"ready\""))
                        .withStartupTimeout(Duration.ofSeconds(180)));

        app = appContainer;
        app.start();

        // The page checks embedding status once when it loads. Wait for the real
        // node index first so semantic/hybrid controls start with usable backends.
        Wait.forHttp("/api/embedding/status")
                .forStatusCode(200)
                .forPort(8080)
                .withBasicCredentials("admin", ContainerTestUtils.TEST_ADMIN_PASSWORD)
                .forResponsePredicate(ScreenshotGeneratorIT::embeddingBackendReady)
                .withStartupTimeout(Duration.ofMinutes(10))
                .waitUntilReady(app);

        browserSession = ContainerTestUtils.startBrowser(network);
        driver = browserSession.driver();
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));

        // Login via form (Spring Security requires authentication)
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.name("username")));
        driver.findElement(By.name("username")).sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys("admin");
        driver.findElement(By.cssSelector("button[type='submit'], input[type='submit']")).click();

        // Load the application and wait for the taxonomy tree to be FULLY RENDERED
        driver.get(ContainerTestUtils.APP_ORIGIN + "/");
        new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // Wait for the data-view-rendered attribute which is set by renderView() after
        // the taxonomy data has been fetched and the view has been fully rendered.
        // This is more reliable than waiting for CSS selectors like .tax-node which
        // may not yet exist during view transitions.
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(d -> {
                    String rendered = d.findElement(By.id("taxonomyTree"))
                            .getAttribute("data-view-rendered");
                    return rendered != null && !rendered.isEmpty();
                });
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("analysisTaskProgress")));
        // Dismiss the onboarding overlay if it is present (it blocks clicks on all UI elements)
        List<WebElement> dismissBtns = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissBtns.isEmpty()) {
            dismissBtns.get(0).click();
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
    }

    private static boolean embeddingBackendReady(String body) {
        JsonNode status = JSON.readTree(body);
        Assertions.assertNotNull(status, "Embedding readiness returned no status");
        JsonNode indexState = status.path("indexState");
        String state = indexState.isString() ? indexState.stringValue() : "";
        Assertions.assertFalse(status.path("enabled").isBoolean() && !status.path("enabled").booleanValue(),
                "Screenshot embeddings are disabled: " + body);
        Assertions.assertFalse("FAILED".equals(state),
                "Screenshot node search index failed to initialize: " + body);
        // Enabled indexes initially report DISABLED until the ApplicationReadyEvent callback runs.
        // Node search remains usable while relation indexing finishes or reports PARTIAL.
        return List.of("INDEXING_RELATIONS", "READY", "PARTIAL").contains(state)
                && List.of("enabled", "modelAvailable", "available", "semanticReady")
                        .stream().allMatch(field -> status.path(field).isBoolean() && status.path(field).booleanValue())
                && status.path("indexedNodes").isIntegralNumber()
                && status.path("indexedNodes").asLong() > 0;
    }

    @AfterAll
    static void stopContainers() throws Exception {
        try {
            if (graphFixture != null) {
                driver.get(ContainerTestUtils.APP_ORIGIN + "/");
                SavedGraphHttpFixture.remove(ScreenshotGeneratorIT::requestFixture, graphFixture);
            }
        } finally {
            ContainerTestUtils.closeAll(browserSession, app, network);
        }
    }

    // ── Helper methods ─────────────────────────────────────────────────────────

    private void saveScreenshot(String filename) throws IOException {
        File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), OUTPUT_DIR.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
    }

    private void saveElementScreenshot(WebElement element, String filename) throws IOException {
        // Scroll element into viewport to avoid Chrome renderer timeout on large/off-screen elements
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", element);
        File src = element.getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), OUTPUT_DIR.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
    }

    private WebDriverWait wait(int seconds) {
        return new WebDriverWait(driver, Duration.ofSeconds(seconds));
    }

    /** Inspect the real renderer's terminal state; never turn missing output into invented data. */
    private void waitForResult(String containerId, String resultSelector, int seconds) {
        wait(seconds).withMessage(() -> "Expected " + resultSelector + " in " + containerId
                + ": " + driver.findElement(By.id(containerId)).getAttribute("innerHTML"))
                .until(d -> {
                    WebElement content = d.findElement(By.id(containerId));
                    if ("true".equals(content.getAttribute("aria-busy"))) return false;
                    if (!content.findElements(By.cssSelector(".spinner-border")).isEmpty()) return false;
                    Assertions.assertTrue(content.findElements(By.cssSelector(
                                    ":scope > .alert-warning, :scope > .alert-danger, " +
                                    ":scope > .text-danger, :scope > [role='alert']")).isEmpty(),
                            "Capture failed in " + containerId + ": " + content.getAttribute("textContent"));
                    return !content.findElements(By.cssSelector(resultSelector)).isEmpty();
                });
    }

    private void waitForSuccessfulAnalysis() {
        wait(120).withMessage(() -> "Analysis did not finish: "
                + driver.findElement(By.id("statusArea")).getText()).until(d -> {
            String status = (String) ((JavascriptExecutor) d).executeScript(
                    "return window.TaxonomyState && window.TaxonomyState.lastAnalysisStatus;");
            if (status == null || "IN_PROGRESS".equals(status)) return false;
            Assertions.assertEquals("SUCCESS", status,
                    "Analysis failed: " + d.findElement(By.id("statusArea")).getText());
            return !d.findElements(By.cssSelector("#statusArea .alert-success")).isEmpty();
        });
    }

    private boolean hasSuccessfulAnalysis() {
        return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript("""
                const state = window.TaxonomyState;
                const input = document.getElementById('businessText');
                const status = document.getElementById('statusArea');
                const staleWarning = status?.dataset.analysisSessionMessage === 'stale'
                    && status.getClientRects().length > 0;
                return !!state && state.lastAnalysisStatus === 'SUCCESS'
                    && input?.value === arguments[0] && state.storedBusinessText === arguments[0]
                    && !input.classList.contains('stale-results') && !staleWarning
                    && Object.values(state.currentScores || {}).some(score => score > 0);
                """, REQUIREMENT_TEXT));
    }

    private void clickVisible(WebElement element) {
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", element);
        wait(5).until(ExpectedConditions.elementToBeClickable(element)).click();
    }

    private void closeSearchPanel() {
        WebElement panel = driver.findElement(By.id("searchPanel"));
        if (panel.getAttribute("open") != null) {
            clickVisible(panel.findElement(By.cssSelector(":scope > summary")));
            wait(5).until(d -> panel.getAttribute("open") == null);
        }
    }

    /** Capture the actual diagram scrollport after fitting a readable, collapsed overview. */
    private void saveDiagramScreenshot(String view, String filename, boolean scored) throws IOException {
        org.openqa.selenium.Dimension originalSize = driver.manage().window().getSize();
        boolean completed = false;
        try {
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 1800));
            closeSearchPanel();
            String button = switch (view) {
                case "sunburst" -> "viewSunburst";
                case "tree" -> "viewTree";
                default -> "viewDecision";
            };
            clickVisible(driver.findElement(By.id(button)));
            wait(10).until(ExpectedConditions.attributeContains(By.id("taxonomyTree"), "data-view-rendered", view));
            new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("diagramRelevanceFilter")))
                    .selectByValue(scored ? "positive" : "all");
            wait(10).until(ExpectedConditions.attributeContains(By.id("taxonomyTree"), "data-view-rendered", view));
            if (!"sunburst".equals(view)) {
                clickVisible(driver.findElement(By.cssSelector("#taxonomyTree .tax-diagram-collapse-all")));
                if ("tree".equals(view)) {
                    WebElement surface = driver.findElement(By.cssSelector("#taxonomyTree canvas, #taxonomyTree > svg"));
                    // The native diagram keyboard opens just the selected root's first branch level.
                    WebElement rootControl = "canvas".equals(surface.getTagName()) ? surface
                            : surface.findElement(By.cssSelector(".tax-diagram-node"));
                    rootControl.sendKeys(org.openqa.selenium.Keys.HOME);
                    rootControl.sendKeys(org.openqa.selenium.Keys.SPACE);
                }
                if (!"tree-canvas".equals(driver.findElement(By.id("taxonomyTree")).getAttribute("data-view-rendered"))) {
                    waitForTransitionsComplete();
                }
                String fit = (String) ((JavascriptExecutor) driver).executeScript(
                        "return window.TaxonomyI18n.t('views.diagram.fit');");
                WebElement fitButton = driver.findElements(By.cssSelector("#taxonomyTree .tax-diagram-controls > button"))
                        .stream().filter(element -> fit.equals(element.getText())).findFirst().orElseThrow();
                clickVisible(fitButton);
            }
            WebElement tree = driver.findElement(By.id("taxonomyTree"));
            js("arguments[0].scrollIntoView({behavior:'instant', block:'start'});", tree);
            Map<?, ?> layout = (Map<?, ?>) ((JavascriptExecutor) driver).executeScript("""
                    const tree = arguments[0], body = tree.closest('.card-body');
                    const bounds = body.getBoundingClientRect();
                    const clip = {left:Math.max(0,bounds.left+body.clientLeft),
                        top:Math.max(0,bounds.top+body.clientTop),
                        right:Math.min(innerWidth,bounds.left+body.clientLeft+body.clientWidth),
                        bottom:Math.min(innerHeight,bounds.top+body.clientTop+body.clientHeight)};
                    const inside = box => box.width > 0 && box.height > 0
                        && box.left >= clip.left-2 && box.right <= clip.right+2
                        && box.top >= clip.top-2 && box.bottom <= clip.bottom+2;
                    const controls = tree.querySelector('.tax-diagram-controls');
                    const canvas = tree.querySelector('canvas');
                    const svg = tree.querySelector('svg');
                    let occupied = [], labelHeight = null;
                    if (canvas) {
                        const box = canvas.getBoundingClientRect();
                        // Fit transforms the complete logical canvas; avoid copying a potentially huge pixel buffer.
                        const zoom=window.d3.zoomTransform(canvas), dpr=devicePixelRatio||1;
                        const width=canvas.width/dpr*zoom.k, height=canvas.height/dpr*zoom.k;
                        occupied.push({left:box.left+zoom.x,right:box.left+zoom.x+width,
                            top:box.top+zoom.y,bottom:box.top+zoom.y+height,width,height});
                        labelHeight = 12*zoom.k;
                    } else if (arguments[1]==='sunburst') {
                        if (svg) occupied.push(svg.getBoundingClientRect().toJSON());
                    } else {
                        occupied = Array.from(tree.querySelectorAll('g.tv-node, g.dm-node, text.dm-area-caption'))
                            .map(node=>node.getBoundingClientRect().toJSON());
                        if (arguments[1]==='tree') labelHeight=12*window.d3.zoomTransform(svg).k;
                    }
                    return {fits:!!controls && inside(controls.getBoundingClientRect())
                            && occupied.length>0 && occupied.every(inside)
                            && (labelHeight===null || labelHeight>=9),
                        clip,scrollport:bounds.toJSON(),controls:controls?.getBoundingClientRect().toJSON(),
                        occupiedCount:occupied.length,occupiedOutside:occupied.filter(box=>!inside(box)),labelHeight};
                    """, tree, view);
            Assertions.assertEquals(Boolean.TRUE, layout.get("fits"),
                    "Diagram content and controls must fit the visible scrollport: " + layout);
            saveElementScreenshot(tree.findElement(By.xpath("ancestor::div[contains(@class,'card-body')][1]")), filename);
            completed = true;
        } finally {
            if (completed) {
                driver.manage().window().setSize(originalSize);
                new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("diagramRelevanceFilter")))
                        .selectByValue("all");
                safeClick(By.id("viewList"));
            }
        }
    }

    private void saveScoredUaScreenshot(String filename, boolean expanded) throws IOException {
        org.openqa.selenium.Dimension originalSize = driver.manage().window().getSize();
        boolean descriptions = driver.findElement(By.id("showDescriptions")).isSelected();
        String originalFilter = new org.openqa.selenium.support.ui.Select(
                driver.findElement(By.id("diagramRelevanceFilter"))).getFirstSelectedOption().getAttribute("value");
        boolean completed = false;
        try {
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 1800));
            closeSearchPanel();
            new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("diagramRelevanceFilter")))
                    .selectByValue("positive");
            clickVisible(driver.findElement(By.id("viewList")));
            if (descriptions) clickVisible(driver.findElement(By.id("showDescriptions")));
            clickVisible(driver.findElement(By.id("collapseAll")));
            Map<?, ?> evidence = (Map<?, ?>) ((JavascriptExecutor) driver).executeScript("""
                    const state=window.TaxonomyState;
                    const root=state.taxonomyData.find(node=>node.code==='UA');
                    function positiveLeaf(node,path) {
                        const next=path.concat(node.code);
                        if (!(node.children||[]).length && state.currentScores[node.code]>0) return next;
                        for (const child of node.children||[]) { const found=positiveLeaf(child,next);if(found)return found; }
                        return null;
                    }
                    return {rootScore:state.currentScores.UA,path:root?positiveLeaf(root,[]):null};
                    """);
            Assertions.assertTrue(evidence.get("rootScore") instanceof Number score && score.doubleValue() > 0,
                    "The documentation branch must have a real positive root assessment: " + evidence);
            Assertions.assertInstanceOf(List.class, evidence.get("path"), "A positive UA leaf must exist: " + evidence);
            List<?> path = (List<?>) evidence.get("path");
            WebElement root = driver.findElement(By.cssSelector(
                    "#taxonomyTree > [data-taxonomy-container='UA'] > .tax-container-heading > .tax-container-toggle"));
            clickVisible(root);
            if (expanded) {
                for (int index = 1; index < path.size() - 1; index++) {
                    By selector = By.cssSelector("#taxonomyTree .tax-node[data-code='" + path.get(index) + "']");
                    WebElement node = wait(5).until(ExpectedConditions.visibilityOfElementLocated(selector));
                    if (!"true".equals(node.getAttribute("aria-expanded"))) {
                        clickVisible(node.findElement(By.cssSelector(":scope > .tax-node-header > .tax-toggle")));
                    }
                }
            }
            String visibleCode = String.valueOf(path.get(expanded ? path.size() - 1 : 1));
            Assertions.assertEquals(Boolean.TRUE, ((JavascriptExecutor) driver).executeScript(
                    "return window.TaxonomyState.currentScores[arguments[0]] > 0;", visibleCode),
                    "The visible UA row must have a real positive score: " + visibleCode);
            WebElement positive = wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(
                    "#taxonomyTree .tax-node[data-code='" + visibleCode + "'] .tax-pct")));
            WebElement area = driver.findElement(By.cssSelector("#taxonomyTree > [data-taxonomy-container='UA']"));
            js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", area);
            Map<?, ?> layout = (Map<?, ?>) ((JavascriptExecutor) driver).executeScript("""
                    const area=arguments[0].getBoundingClientRect(), row=arguments[1].getBoundingClientRect();
                    const body=arguments[1].closest('.card-body').getBoundingClientRect();
                    const inside=box=>box.top>=Math.max(body.top,0)-2
                        && box.bottom<=Math.min(body.bottom,innerHeight)+2
                        && box.left>=Math.max(body.left,0)-2 && box.right<=Math.min(body.right,innerWidth)+2;
                    return {fits:inside(area)&&inside(row),area:area.toJSON(),positive:row.toJSON(),scrollport:body.toJSON()};
                    """, area, positive);
            Assertions.assertEquals(Boolean.TRUE, layout.get("fits"), "Positive UA evidence must be visible: " + layout);
            saveElementScreenshot(area, filename);
            completed = true;
        } finally {
            if (completed) {
                if (descriptions) clickVisible(driver.findElement(By.id("showDescriptions")));
                new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("diagramRelevanceFilter")))
                        .selectByValue(originalFilter);
                driver.manage().window().setSize(originalSize);
            }
        }
    }

    private void selectSearchMode(String mode) {
        if (!"fulltext".equals(mode)) {
            js("window.TaxonomySearch.checkEmbeddingStatus();");
        }
        By option = By.cssSelector("#searchModeSelect option[value='" + mode + "']");
        wait(180).withMessage(() -> "Search mode " + mode + " is unavailable: "
                + driver.findElement(By.id("embeddingStatusBadge")).getAttribute("textContent"))
                .until(d -> d.findElement(option).isEnabled());
        new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("searchModeSelect")))
                .selectByValue(mode);
    }

    private void loadCurrentDsl(String expectedBlock) {
        wait(15).until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d)
                .executeScript("return !!window.dslCmView;")));
        js("window.__screenshotPreviousDslDocument = window.dslCmView.state.doc;");
        clickVisible(driver.findElement(By.id("dslLoadCurrentBtn")));
        wait(15).withMessage(() -> "DSL load failed: "
                + driver.findElement(By.id("dslStatusArea")).getText() + "; " + dslCaptureDiagnostic()).until(d -> {
            WebElement status = d.findElement(By.id("dslStatusArea"));
            String classes = status.getAttribute("class");
            Assertions.assertFalse(classes.contains("alert-danger"), () -> status.getText() + "; " + dslCaptureDiagnostic());
            return classes.contains("alert-success") && Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript("""
                    const view=window.dslCmView;
                    return view.state.doc!==window.__screenshotPreviousDslDocument
                        && view.state.doc.toString().split('\\n').some(line=>line.startsWith(arguments[0]+' '));
                    """, expectedBlock));
        });
    }

    private String dslCaptureDiagnostic() {
        return String.valueOf(((JavascriptExecutor) driver).executeScript("""
                const text=window.dslCmView?.state.doc.toString()||'';
                return JSON.stringify({beginning:text.slice(0,1400),
                    relationLines:text.split('\\n').filter(line=>line.includes('relation')).slice(0,8)
                        .map(line=>line.slice(0,240))});
                """));
    }

    private void openOperationalStatus() {
        // The onboarding module creates this disclosure in #applicationSystemStatus.
        WebElement status = wait(30).until(ExpectedConditions.visibilityOfElementLocated(
                By.id("operationalContextDetails")));
        openDetails(status);
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#operationalContextDetails .operational-context-content")));
    }

    private void refreshVersionHistory() {
        safeClick(By.cssSelector("[data-versions-tab=\"history\"]"));
        safeClick(By.id("versionsRefreshBtn"));
        wait(15).withMessage(() -> "Version history did not load: "
                + driver.findElement(By.id("versionsTimeline")).getText()).until(d -> {
            WebElement timeline = d.findElement(By.id("versionsTimeline"));
            String state = timeline.getAttribute("data-state");
            Assertions.assertFalse("error".equals(state) || "empty".equals(state),
                    "Persisted version fixtures must render: " + timeline.getText());
            return "loaded".equals(state);
        });
    }

    private static Map<?, ?> requestFixture(String url, String method, String contentType, String body) {
        return requestFixture(url, method, contentType, body, Map.of());
    }

    private static Map<?, ?> requestFixture(String url, String method, String contentType, String body,
                                     Map<String, String> headers) {
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
                "var url=arguments[0], method=arguments[1], type=arguments[2], body=arguments[3], headers=arguments[4], done=arguments[5];" +
                "var options={method:method,headers:headers};" +
                "if (body !== null) { options.headers['Content-Type']=type; options.body=body; }" +
                "fetch(url, options).then(async function(response) {" +
                "  var text=await response.text();" +
                "  if (!response.ok) throw new Error('HTTP '+response.status+': '+text);" +
                "  done({data:JSON.parse(text)});" +
                "}).catch(function(error) { done({error:String(error)}); });",
                url, method, contentType, body, headers);
        Assertions.assertInstanceOf(Map.class, result, "Fixture request returned no response: " + url);
        Map<?, ?> response = (Map<?, ?>) result;
        Assertions.assertNull(response.get("error"), "Fixture request failed: " + url + " " + response);
        Assertions.assertInstanceOf(Map.class, response.get("data"), "Expected JSON object: " + url);
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        Assertions.assertNull(data.get("error"), "Fixture operation failed: " + url + " " + data);
        return data;
    }

    /**
     * Waits for the taxonomy to be ready by monitoring the UI startup banner.
     * The banner is hidden (gets {@code d-none} class) when the JS
     * {@code pollStartupStatus()} detects {@code initialized: true} from
     * {@code /api/status/startup}.  This is event-driven: no manual API polling
     * needed — Selenium just waits for the DOM change triggered by the app's
     * own 5-second polling loop.
     */
    private void waitForTaxonomyReadyViaUI() {
        wait(300).until(d -> {
            try {
                WebElement banner = d.findElement(By.id("startupBanner"));
                return banner.getAttribute("class").contains("d-none");
            } catch (org.openqa.selenium.NoSuchElementException e) {
                // Banner element not in DOM — assume already loaded
                return true;
            }
        });
    }

    /** Runs a standard (non-interactive) analysis and waits up to 120 s for completion. */
    private void runAnalysis() {
        navigateToTab("analyze");
        wait(20).withMessage("Analysis source must be ready before the screenshot workflow starts")
                .until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                        "return window.TaxonomyAnalysisSession?.state?.().ready === true;")));
        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", textarea);
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                textarea, REQUIREMENT_TEXT);
        WebElement analyzeBtn = driver.findElement(By.id("analyzeBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", analyzeBtn);
        // Observe replacement of the real score envelope to reject stale previous results.
        js("window.__screenshotPreviousScores = window.TaxonomyState.currentScores;");
        js("arguments[0].click();", analyzeBtn);
        wait(15).withMessage("Analyze must start a new score envelope").until(d ->
                Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                        "return window.TaxonomyState.currentScores !== window.__screenshotPreviousScores;")));
        waitForSuccessfulAnalysis();
    }

    private void js(String script, Object... args) {
        ((JavascriptExecutor) driver).executeScript(script, args);
    }

    /** Scrolls the element into the viewport and clicks via JavaScript — prevents ElementClickIntercepted. */
    private void safeClick(WebElement element) {
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", element);
        js("arguments[0].click();", element);
    }

    /** Finds the element by locator, scrolls it into view and clicks via JavaScript. */
    private void safeClick(By locator) {
        safeClick(driver.findElement(locator));
    }

    /** Reveal the real node action through its visible on-demand action menu. */
    private WebElement revealNodeAction(WebElement action) {
        WebElement node = action.findElement(By.xpath(
                "ancestor::*[contains(concat(' ', normalize-space(@class), ' '), ' tax-node ')][1]"));
        safeClick(node.findElement(By.cssSelector(":scope > .tax-node-header .tax-node-actions-toggle")));
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("taxonomyActionsDialog")));
        return wait(5).until(ExpectedConditions.visibilityOf(action));
    }

    /** Closes the actual Bootstrap modal and waits for its lifecycle event. */
    private void closeModalAndWait(String modalId) {
        js("var modal=bootstrap.Modal.getInstance(document.getElementById(arguments[0]));" +
                "if (!modal) throw new Error('Modal is not open: ' + arguments[0]); modal.hide();", modalId);
        wait(5).until(ExpectedConditions.attributeToBe(
                By.id(modalId), "data-modal-visible", "false"));
    }

    private void showAdminSettings() {
        // Element captures can leave the document scrolled deep into a full-width view.
        // Return to the actual shared menu before asking Selenium to click it.
        js("window.scrollTo({top:0, left:0, behavior:'instant'});");
        WebElement summary = wait(10).until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("#applicationTools > summary")));
        wait(5).until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript("""
                const bounds = arguments[0].getBoundingClientRect();
                return bounds.top >= 0 && bounds.bottom <= innerHeight
                    && bounds.left >= 0 && bounds.right <= innerWidth;
                """, summary)));
        WebElement menu = driver.findElement(By.id("applicationTools"));
        if (menu.getAttribute("open") == null) {
            summary.click();
        }
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("adminNavTab")));
    }

    /**
     * Forces the interactive-mode checkbox OFF via direct JS property assignment and fires the
     * {@code change} event so the taxonomy-browse.js module-level variable is also updated.
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
        resetPageState("en");
    }

    private void resetPageState(String language) {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/?lang=" + language);
        wait(15).until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // Wait for the data-view-rendered attribute which is set by renderView() after
        // the taxonomy data has been fetched and the view has been fully rendered.
        // This is more reliable than waiting for CSS selectors like .tax-node which
        // may not yet exist during view transitions.
        wait(30).until(d -> {
            String rendered = d.findElement(By.id("taxonomyTree"))
                    .getAttribute("data-view-rendered");
            return rendered != null && !rendered.isEmpty();
        });
        // The task hierarchy and welcome dialog initialize after the translation bundle.
        wait(30).until(ExpectedConditions.presenceOfElementLocated(By.id("analysisTaskProgress")));
        wait(10).until(ExpectedConditions.attributeToBe(By.tagName("html"), "lang", language));
        // Dismiss the onboarding overlay if it reappears after page reload
        List<WebElement> dismissBtns = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissBtns.isEmpty()) {
            dismissBtns.get(0).click();
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
    }

    /**
     * Waits for D3 tree/treemap transition animations to complete.
     * Uses the {@code data-transitions-complete} attribute set by the JS
     * after {@code transition.end()} resolves — no Thread.sleep() needed.
     */
    private void waitForTransitionsComplete() {
        wait(10).until(ExpectedConditions.attributeToBe(
                By.id("taxonomyTree"), "data-transitions-complete", "true"));
    }

    /**
     * Builds a realistic Git history with branches by committing DSL content via the REST API.
     * <p>
     * Creates:
     * <ol>
     *   <li>A commit on {@code draft} with {@link #DRAFT_DSL} (base architecture)</li>
     *   <li>A {@code feature-voice} branch forked from {@code draft}</li>
     *   <li>A commit on {@code feature-voice} with {@link #FEATURE_VOICE_DSL}
     *       (modified title + extra element)</li>
     * </ol>
     * This gives downstream screenshot tests real data for version history, diffs,
     * branch compare, merge previews, and the DSL editor.
     * <p>
     * Guarded by {@link #gitHistoryBuilt} — runs at most once per test class execution.
     */
    private void buildGitHistory() {
        if (gitHistoryBuilt) return;
        draftFixtureCommitId = commitAndWait("draft", "Initial+architecture+definition", DRAFT_DSL);
        createBranch("feature-voice", "draft");
        commitAndWait("feature-voice", "Add+Voice+Gateway+component", FEATURE_VOICE_DSL);
        gitHistoryBuilt = true;
    }

    /**
     * Builds a rich, realistic Git history with 12+ commits across 4 branches.
     * <p>
     * This creates a history that experienced Git users can relate to:
     * <ol>
     *   <li>Multiple sequential commits on {@code draft} (architecture evolution)</li>
     *   <li>A {@code feature-voice} branch with its own commit series</li>
     *   <li>A {@code review} branch where accepted changes are collected</li>
     *   <li>A {@code hotfix/urgent-fix} branch for emergency changes</li>
     * </ol>
     * The history is realistic: it shows incremental architecture refinement across
     * branches, typical of how experienced teams work with the tool.
     * <p>
     * Also captures {@link #cherryPickSourceCommitId} for downstream cherry-pick tests.
     * <p>
     * Guarded by {@link #richGitHistoryBuilt} — runs at most once per test class execution.
     */
    private void buildRichGitHistory() {
        // Ensure base history exists first
        buildGitHistory();
        if (richGitHistoryBuilt) return;

        // ── 1. Additional commits on draft (architecture evolution) ──

        String draftV2 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element BP-1327 type Process {\n  title: \"Enable\";\n  description: \"Mission enablement process\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CR-1047 SUPPORTS BP-1327 {\n  status: proposed;\n}\n";

        commitAndWait("draft", "Add+process+layer+BP-1327", draftV2);

        String draftV3 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element BP-1327 type Process {\n  title: \"Enable\";\n  description: \"Mission enablement process\";\n}\n\n" +
                "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CR-1047 SUPPORTS BP-1327 {\n  status: accepted;\n}\n\n" +
                "relation CO-1011 USES CR-1047 {\n  status: proposed;\n}\n";

        commitAndWait("draft", "Accept+CR-1047+supports+BP-1327%2C+add+Voice+Gateway", draftV3);

        String draftV4 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element BP-1327 type Process {\n  title: \"Enable\";\n  description: \"Mission enablement process\";\n}\n\n" +
                "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
                "element IP-1659 type InformationProduct {\n  title: \"Situation Reports\";\n  description: \"Aggregated operational situation data\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CR-1047 SUPPORTS BP-1327 {\n  status: accepted;\n}\n\n" +
                "relation CO-1011 USES CR-1047 {\n  status: proposed;\n}\n\n" +
                "relation BP-1327 PRODUCES IP-1659 {\n  status: proposed;\n}\n";

        commitAndWait("draft", "Add+information+product+IP-1659+with+PRODUCES+relation", draftV4);

        // ── 2. Create review branch and add commits there ──

        createBranch("review", "draft");

        String reviewV1 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element BP-1327 type Process {\n  title: \"Enable\";\n  description: \"Mission enablement process\";\n}\n\n" +
                "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
                "element IP-1659 type InformationProduct {\n  title: \"Situation Reports\";\n  description: \"Aggregated operational situation data\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CR-1047 SUPPORTS BP-1327 {\n  status: accepted;\n}\n\n" +
                "relation CO-1011 USES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation BP-1327 PRODUCES IP-1659 {\n  status: accepted;\n}\n";

        commitAndWait("review", "Review%3A+accept+all+proposed+relations", reviewV1);

        String reviewV2 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element BP-1327 type Process {\n  title: \"Enable\";\n  description: \"Mission enablement process\";\n}\n\n" +
                "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
                "element IP-1659 type InformationProduct {\n  title: \"Situation Reports\";\n  description: \"Aggregated operational situation data\";\n}\n\n" +
                "element UA-1015 type Application {\n  title: \"Air Applications\";\n  description: \"Airborne communication systems\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CR-1047 SUPPORTS BP-1327 {\n  status: accepted;\n}\n\n" +
                "relation CO-1011 USES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation BP-1327 PRODUCES IP-1659 {\n  status: accepted;\n}\n\n" +
                "relation UA-1015 USES CO-1011 {\n  status: proposed;\n}\n";

        cherryPickSourceCommitId = commitAndWait("review",
                "Add+UA-1015+Air+Applications+with+voice+gateway+dependency", reviewV2);

        // ── 3. Create hotfix branch from draft with urgent change ──

        createBranch("hotfix/urgent-fix", "draft");

        String hotfixV1 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n  x-priority: \"critical\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element BP-1327 type Process {\n  title: \"Enable\";\n  description: \"Mission enablement process\";\n}\n\n" +
                "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
                "element IP-1659 type InformationProduct {\n  title: \"Situation Reports\";\n  description: \"Aggregated operational situation data\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CR-1047 SUPPORTS BP-1327 {\n  status: accepted;\n}\n\n" +
                "relation CO-1011 USES CR-1047 {\n  status: proposed;\n}\n\n" +
                "relation BP-1327 PRODUCES IP-1659 {\n  status: proposed;\n}\n";

        commitAndWait("hotfix%2Furgent-fix", "Hotfix%3A+mark+Secure+Voice+as+critical+priority", hotfixV1);

        // ── 4. More commits on feature-voice (parallel development) ──

        String featureV2 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n  x-alias: \"SecVoice\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
                "element CO-1056 type Component {\n  title: \"Transmission Services\";\n  description: \"Data transmission middleware\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CO-1011 USES CR-1047 {\n  status: proposed;\n}\n\n" +
                "relation CO-1056 SUPPORTS CO-1011 {\n  status: proposed;\n}\n";

        commitAndWait("feature-voice", "Add+Transmission+Services+CO-1056+supporting+Voice+Gateway", featureV2);

        String featureV3 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n  x-alias: \"SecVoice\";\n  x-note: \"Reviewed 2026-Q1\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
                "element CO-1056 type Component {\n  title: \"Transmission Services\";\n  description: \"Data transmission middleware\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CO-1011 USES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CO-1056 SUPPORTS CO-1011 {\n  status: accepted;\n}\n";

        commitAndWait("feature-voice", "Accept+all+relations+and+add+review+annotation", featureV3);

        // ── 5. Another commit on draft (continuing main line) ──

        // Keep the view insertion away from reviewV2's UA-1015 addition so the
        // cherry-pick example combines independent changes without a text conflict.
        String draftV5 =
                "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n\n" +
                "view \"Communication Architecture\" {\n  include: CP-1023, CR-1047, CO-1011, BP-1327, IP-1659;\n  layout: hierarchical;\n}\n\n" +
                "element CP-1023 type Capability {\n  title: \"Secure Voice\";\n  description: \"Encrypted voice communication\";\n}\n\n" +
                "element CR-1047 type CoreService {\n  title: \"Core Communication Services\";\n}\n\n" +
                "element BP-1327 type Process {\n  title: \"Enable\";\n  description: \"Mission enablement process\";\n}\n\n" +
                "element CO-1011 type Component {\n  title: \"Voice Gateway\";\n  description: \"SIP/RTP gateway for voice traffic\";\n}\n\n" +
                "element IP-1659 type InformationProduct {\n  title: \"Situation Reports\";\n  description: \"Aggregated operational situation data\";\n}\n\n" +
                "relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n\n" +
                "relation CR-1047 SUPPORTS BP-1327 {\n  status: accepted;\n}\n\n" +
                "relation CO-1011 USES CR-1047 {\n  status: proposed;\n}\n\n" +
                "relation BP-1327 PRODUCES IP-1659 {\n  status: proposed;\n}\n";

        commitAndWait("draft", "Define+Communication+Architecture+view", draftV5);
        richGitHistoryBuilt = true;
    }

    private void createBranch(String name, String fromBranch) {
        Map<?, ?> branch = requestFixture("/api/dsl/branches?name="
                + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                + "&fromBranch=" + fromBranch, "POST", null, null);
        Assertions.assertEquals(name, branch.get("branch"), "Branch fixture must be persisted");
    }

    /** Commits the authored DSL fixture and verifies the real operation result. */
    private String commitAndWait(String branch, String messageEncoded, String dslContent) {
        Map<?, ?> result = requestFixture("/api/dsl/commit?branch=" + branch + "&message=" + messageEncoded,
                "POST", "text/plain", dslContent);
        Assertions.assertInstanceOf(String.class, result.get("commitId"), "Commit fixture: " + result);
        String commitId = (String) result.get("commitId");
        Assertions.assertFalse(commitId.isBlank(), "Commit fixture must supply a persisted commit ID");
        return commitId;
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
           "  m.removeAttribute('data-modal-visible');" +
           "  m.removeAttribute('data-content-ready');" +
           "});" +
           "document.body.classList.remove('modal-open');" +
           "document.body.style.overflow='';" +
           "document.body.style.paddingRight='';");
        // Wait until all backdrops are gone before continuing
        wait(3).until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector(".modal-backdrop")));
    }

    /** Opens the real Bootstrap modal and waits for its lifecycle event. */
    private void showModalAndWait(String modalId) {
        js("bootstrap.Modal.getOrCreateInstance(document.getElementById(arguments[0])).show();", modalId);
        wait(10).until(ExpectedConditions.attributeToBe(
                By.id(modalId), "data-modal-visible", "true"));
    }

    /** Opens a <details> element if it is currently closed. */
    private void openDetails(WebElement details) {
        if (details.getAttribute("open") == null) {
            safeClick(details.findElement(By.cssSelector(":scope > summary")));
        }
        wait(5).until(ExpectedConditions.attributeToBe(details, "open", "true"));
    }

    /** Uses Tools for administrator-only destinations and the common router otherwise. */
    private void navigateToTab(String page) {
        if ("admin".equals(page) || "preferences".equals(page)) {
            showAdminSettings();
            String destination = "admin".equals(page) ? "adminNavTab" : "preferencesNavTab";
            wait(10).until(ExpectedConditions.elementToBeClickable(By.id(destination))).click();
        } else {
            js("if (window.navigateToPage) { window.navigateToPage(arguments[0]); }", page);
        }
        boolean architectureSubtab = "relations".equals(page) || "export".equals(page);
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(
                By.id("tab-" + (architectureSubtab ? "architecture" : page))));
        if (architectureSubtab) {
            wait(5).until(ExpectedConditions.visibilityOfElementLocated(
                    By.id("subtab-arch-" + page)));
        }
    }

    // ── Screenshots 1–14: no LLM required ─────────────────────────────────────

    @Test
    @Order(1)
    void captureFullPageLayout() throws IOException {
        resetPageState();
        // Wait for actual taxonomy nodes to be rendered (not just the container div with loading spinner)
        wait(15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree .tax-node")));
        saveScreenshot("01-full-page-layout.png");
    }

    @Test
    @Order(2)
    void captureLeftPanelListView() throws IOException {
        // Use Expand All to make the tree show nodes with their action buttons
        safeClick(By.id("expandAll"));
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
        safeClick(By.id("viewTabs"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewTabs"), "class", "btn-primary"));
        saveScreenshot("05-tabs-view.png");
        safeClick(By.id("viewList"));
    }

    @Test
    @Order(6)
    void captureSunburstView() throws IOException {
        saveDiagramScreenshot("sunburst", "06-sunburst-view.png", false);
    }

    @Test
    @Order(7)
    void captureTreeView() throws IOException {
        saveDiagramScreenshot("tree", "07-tree-view.png", false);
    }

    @Test
    @Order(8)
    void captureDecisionMapView() throws IOException {
        safeClick(By.id("viewDecision"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewDecision"), "class", "btn-primary"));
        wait(10).until(ExpectedConditions.attributeToBe(By.id("taxonomyTree"), "data-view-rendered", "decision"));
        saveScreenshot("08-decision-map-view.png");
        safeClick(By.id("viewList"));
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

    private WebElement visibleMatchLegend() {
        WebElement legend = wait(5).until(ExpectedConditions.visibilityOfElementLocated(
                By.id("analysisScoreLegend")));
        List<WebElement> swatches = legend.findElements(By.className("legend-box"));
        Assertions.assertEquals(List.of("0%", "25%", "50%", "75%", "100%"),
                swatches.stream().map(WebElement::getText).toList());
        Assertions.assertTrue(swatches.stream().allMatch(WebElement::isDisplayed),
                "Score meanings must be visible without expanding another panel");
        Assertions.assertTrue(legend.getRect().getHeight() <= 40,
                "Desktop legend must remain a compact row: " + legend.getRect());
        return legend;
    }

    @Test
    @Order(10)
    void captureMatchLegend() throws IOException {
        saveElementScreenshot(visibleMatchLegend(), "10-match-legend.png");
    }

    @Test
    @Order(11)
    void captureGraphExplorerPanel() throws IOException {
        navigateToTab("graph");
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        input.clear();
        input.sendKeys("BP-1040");
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
    void captureProposeRelationsModal() throws IOException {
        // Clean up any leftover modal state from a previous failed retry run
        resetModalState();
        navigateToTab("analyze");
        // Proposals are exposed through each node's visible action-menu button.
        wait(15).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".proposal-btn")));
        WebElement proposeBtn = revealNodeAction(driver.findElement(By.cssSelector(".proposal-btn")));
        // Clear stale content-ready signal before clicking
        js("var m = document.getElementById('proposeRelationsModal');" +
           "if (m) m.removeAttribute('data-content-ready');");
        safeClick(proposeBtn);
        // Wait for the click handler to set modal content (data-content-ready="true")
        wait(10).until(ExpectedConditions.attributeToBe(
                By.id("proposeRelationsModal"), "data-content-ready", "true"));
        // Wait for modal to be fully shown via Bootstrap API
        wait(10).until(ExpectedConditions.attributeToBe(
                By.id("proposeRelationsModal"), "data-modal-visible", "true"));
        saveScreenshot("13-propose-relations-modal.png");
        closeModalAndWait("proposeRelationsModal");
        js("window.scrollTo(0, 0);");
    }

    @Test
    @Order(14)
    void captureAdminSettings() throws IOException {
        showAdminSettings();
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("preferencesNavTab")));
        js("window.scrollTo(0, 0);");
        // The Tools dropdown is absolutely positioned below the navigation row.
        // A navigation-element screenshot would clip its administration links.
        saveScreenshot("14-navbar-settings.png");
        driver.findElement(By.cssSelector("#applicationTools > summary")).click();
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
        saveScoredUaScreenshot("15-scored-taxonomy-tree.png", false);
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

        // Interactive mode has initialized when the product marks expandable nodes unevaluated.
        wait(15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree .tax-has-unevaluated")));
        saveScreenshot("16-interactive-mode.png");

        // Reset to non-interactive using JS direct manipulation to avoid stale-element issues
        forceNonInteractiveMode();
    }

    @Test
    @Order(17)
    void captureMatchLegendWithScores() throws IOException {
        forceNonInteractiveMode();
        runAnalysis();
        WebElement legend = visibleMatchLegend();
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", legend);
        saveScreenshot("17-match-legend-with-scores.png");
    }

    @Test
    @Order(18)
    void captureLeafJustificationModal() throws IOException {
        // Reload the page for a clean JS state
        resetPageState();
        // Clean up any leftover Bootstrap modal state from a previous failed retry run
        resetModalState();

        // 1. Run a full non-interactive analysis to score ALL nodes (including leaf nodes).
        //    The mock LLM always returns scores > 0 for non-BR nodes, so leaves will be scored > 0.
        forceNonInteractiveMode();
        runAnalysis();

        // Render the scored list using the state produced by the successful analysis.
        safeClick(By.id("viewList"));

        // Expand all nodes so the leaf-level justify buttons are visible in the DOM.
        js("document.getElementById('expandAll').click();");

        // Wait for at least one leaf justify button to appear.
        wait(10).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".tax-justify-btn.btn-outline-info")));
        List<WebElement> justifyBtns = driver.findElements(
                By.cssSelector(".tax-justify-btn.btn-outline-info"));
        Assertions.assertFalse(justifyBtns.isEmpty(),
                "No leaf justify buttons found after non-interactive analysis + expand-all. " +
                "The mock LLM may not be producing scores > 0 for leaf nodes.");

        safeClick(revealNodeAction(justifyBtns.get(0)));
        // Wait for the /api/justify-leaf mock call to populate the modal body text.
        // The data-modal-loaded attribute is set by the JS after the content is populated,
        // which is more reliable than getText() which returns empty on hidden modals.
        wait(15).until(ExpectedConditions.attributeToBe(
                By.id("leafJustificationModalBody"), "data-modal-loaded", "true"));
        // Wait for Bootstrap's shown.bs.modal event (data-modal-visible set by global listener)
        wait(10).until(ExpectedConditions.attributeToBe(
                By.id("leafJustificationModal"), "data-modal-visible", "true"));
        saveScreenshot("18-leaf-justification-modal.png");
        closeModalAndWait("leafJustificationModal");
        js("window.scrollTo(0, 0);");
        // Reset to non-interactive for subsequent tests
        forceNonInteractiveMode();
    }

    @Test
    @Order(19)
    void captureStaleResultsWarning() throws IOException {
        // Ensure a completed non-interactive analysis exists
        if (!hasSuccessfulAnalysis()) {
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

    /** Build an accepted canonical graph from real catalogue identities, separate from history examples. */
    private void selectSavedGraphModel(String language) {
        if (graphFixtureCommitId == null) {
            buildGitHistory();
            Object catalogue = ((JavascriptExecutor) driver).executeAsyncScript("""
                    const done=arguments[arguments.length-1];
                    fetch('/api/taxonomy').then(async response=>{
                        if(!response.ok)throw new Error('Catalogue HTTP '+response.status);
                        const selected={};
                        function visit(nodes){for(const node of nodes){
                            if(['CP-1023','CR-1047','BP-1327','BP-1000'].includes(node.code))
                                selected[node.code]=node.nameEn;
                            visit(node.children||[]);
                        }}
                        visit(await response.json());done(selected);
                    }).catch(error=>done({error:String(error)}));
                    """);
            Assertions.assertInstanceOf(Map.class, catalogue);
            Map<?, ?> names = (Map<?, ?>) catalogue;
            Assertions.assertNull(names.get("error"), "Read real catalogue identities: " + names);
            String dsl = SavedGraphFixtureDsl.create(names);
            SavedGraphHttpFixture.Saved graph = SavedGraphHttpFixture.create(ScreenshotGeneratorIT::requestFixture,
                    "screenshot-graph", dsl, 3);
            graphFixture = graph;
            graphFixtureScope = graph.scope(); graphFixtureCommitId = graph.commitId();
        }
        // Open the actual saved version in the visible working editor, with exact source pins.
        driver.get(ContainerTestUtils.APP_ORIGIN + "/?architectureSource=working&" + graphFixtureScope
                + "&commit=" + graphFixtureCommitId + "&lang=" + language + "#architecture");
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureWorkingSource")));
        wait(30).until(ExpectedConditions.textToBePresentInElementLocated(
                By.id("applicationContextSummaryStand"), graphFixtureCommitId.substring(0, 12)));
        Assertions.assertEquals("working", driver.findElement(By.id("architectureSource")).getAttribute("value"));
    }

    private void assertSavedGraphEvidence() {
        WebElement evidence = driver.findElement(By.cssSelector("#graphResultsContent .graph-evidence"));
        Assertions.assertTrue(evidence.isDisplayed());
        Assertions.assertTrue(evidence.getText().contains(graphFixtureCommitId));
        Assertions.assertTrue(evidence.getText().contains("screenshot-graph"));
        Assertions.assertTrue(driver.findElement(By.id("graphResultsContent")).getText().contains("CR-1047"),
                "The graph must render the saved accepted relation, not an empty global fallback");
    }

    @Test
    @Order(21)
    void captureGraphExplorerUpstream() throws IOException {
        try {
            selectSavedGraphModel("en");
            navigateToTab("graph");
            WebElement input = driver.findElement(By.id("graphNodeInput"));
            js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", input);
            js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", input);
            js("arguments[0].value = 'BP-1327'; arguments[0].dispatchEvent(new Event('input'));", input);
            WebElement upstreamBtn = driver.findElement(By.id("graphUpstreamBtn"));
            js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", upstreamBtn);
            js("arguments[0].click();", upstreamBtn);

            waitForResult("graphResultsContent", "#graphViewTable", 30);
            assertSavedGraphEvidence();
            saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "21-graph-explorer-upstream.png");
        } finally {
            resetPageState();
        }
    }

    @Test
    @Order(22)
    void captureGraphExplorerFailure() throws IOException {
        try {
            selectSavedGraphModel("en");
            navigateToTab("graph");
            WebElement input = driver.findElement(By.id("graphNodeInput"));
            js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", input);
            js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", input);
            js("arguments[0].value = 'BP-1327'; arguments[0].dispatchEvent(new Event('input'));", input);

            WebElement failureBtn = driver.findElement(By.id("graphFailureBtn"));
            js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", failureBtn);
            js("arguments[0].click();", failureBtn);

            waitForResult("graphResultsContent", "#graphViewTable", 30);
            assertSavedGraphEvidence();
            saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "22-graph-explorer-failure.png");
        } finally {
            resetPageState();
        }
    }

    @Test
    @Order(23)
    void captureExportButtons() throws IOException {
        // Ensure a completed analysis exists so export buttons are visible
        navigateToTab("analyze");
        if (!hasSuccessfulAnalysis()) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        navigateToTab("export");
        // Two-phase wait: first ensure the element exists in the DOM, then wait for CSS visibility
        wait(10).until(ExpectedConditions.presenceOfElementLocated(By.id("exportGroup")));
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
        navigateToTab("admin");
        WebElement diagPanel = driver.findElement(By.id("diagnosticsPanel"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", diagPanel);
        openDetails(diagPanel);
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("refreshDiagnostics")));
        safeClick(By.id("refreshDiagnostics"));
        waitForResult("diagnosticsContent", "table", 10);
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
        wait(15).withMessage(() -> "Prompt template did not load: "
                + driver.findElement(By.id("promptStatusMsg")).getText()).until(d -> {
            Assertions.assertTrue(d.findElements(By.cssSelector("#promptStatusMsg .text-danger")).isEmpty(),
                    "Prompt template load failed: " + d.findElement(By.id("promptStatusMsg")).getText());
            String template = d.findElement(By.id("promptTextarea")).getAttribute("value");
            return !d.findElements(By.cssSelector("#promptSelect option")).isEmpty()
                    && template != null && !template.isBlank();
        });
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
        waitForResult("coverageDashboardContent", ".badge", 10);
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

        // Ensure the <details> panel is open (it may have been closed by tab navigation)
        WebElement panel = driver.findElement(By.id("coverageDashboard"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", panel);
        openDetails(panel);

        // POST coverage record directly via fetch (avoids window.prompt dialog).
        // A sentinel variable signals completion; check response.ok to confirm the record was saved.
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
           "}).then(function(r) { window._coverageRecorded = r.ok; })" +
           "  .catch(function() { window._coverageRecorded = false; });",
           REQUIREMENT_TEXT);
        wait(10).until(d ->
                (Boolean) ((JavascriptExecutor) d).executeScript("return window._coverageRecorded === true;"));

        // Reload the dashboard so it picks up the newly recorded data
        js("if (window.TaxonomyCoverage) window.TaxonomyCoverage.loadCoverageDashboard();");
        waitForResult("coverageDashboardContent", ".coverage-node-link", 10);

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
        waitForResult("proposalsTableContainer",
                ".proposal-table, :scope > .text-muted:not([role='status'])", 10);
        saveElementScreenshot(panel, "28-proposal-review-queue.png");
    }

    @Test
    @Order(29)
    void captureSearchFullText() throws IOException {
        waitForTaxonomyReadyViaUI(); // Wait until startup banner is hidden (taxonomy index ready)
        navigateToTab("analyze");
        // Open the search panel
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        openDetails(searchPanel);
        selectSearchMode("fulltext");
        // Enter search query
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", searchInput);
        js("arguments[0].value = 'voice communications'; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        // Click search button
        js("document.getElementById('searchBtn').click();");
        waitForResult("searchResultsArea", ".search-result-item", 30);
        saveElementScreenshot(searchPanel, "29-search-fulltext.png");
    }

    @Test
    @Order(30)
    void captureSearchSemantic() throws IOException {
        waitForTaxonomyReadyViaUI();
        navigateToTab("analyze");
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        openDetails(searchPanel);
        selectSearchMode("semantic");
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", searchInput);
        js("arguments[0].value = 'secure data exchange'; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        js("document.getElementById('searchBtn').click();");
        waitForResult("searchResultsArea", ".search-result-item", 30);
        saveElementScreenshot(searchPanel, "30-search-semantic.png");
    }

    @Test
    @Order(31)
    void captureSearchHybrid() throws IOException {
        waitForTaxonomyReadyViaUI();
        navigateToTab("analyze");
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        openDetails(searchPanel);
        selectSearchMode("hybrid");
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", searchInput);
        js("arguments[0].value = 'command and control'; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        js("document.getElementById('searchBtn').click();");
        waitForResult("searchResultsArea", ".search-result-item", 30);
        saveElementScreenshot(searchPanel, "31-search-hybrid.png");
    }

    @Test
    @Order(33)
    void captureExportTab() throws IOException {
        // Ensure a completed analysis exists so export buttons are visible
        navigateToTab("analyze");
        if (!hasSuccessfulAnalysis()) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        navigateToTab("export");
        // Two-phase wait: first ensure the element exists in the DOM, then wait for CSS visibility
        wait(10).until(ExpectedConditions.presenceOfElementLocated(By.id("exportGroup")));
        wait(10).until(ExpectedConditions.visibilityOfElementLocated(By.id("exportGroup")));
        org.openqa.selenium.Dimension originalSize = driver.manage().window().getSize();
        boolean completed = false;
        try {
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 1800));
            WebElement pane = driver.findElement(By.id("subtab-arch-export"));
            js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", pane);
            Map<?, ?> layout = (Map<?, ?>) ((JavascriptExecutor) driver).executeScript("""
                    const pane=arguments[0].getBoundingClientRect();
                    const groups=['exportSvg','exportReportMd','importJson'].map(id=>({id,
                        bounds:document.getElementById(id).getBoundingClientRect().toJSON()}));
                    return {fits:pane.top>=-2 && pane.bottom<=innerHeight+2 && pane.left>=-2 && pane.right<=innerWidth+2
                            && groups.every(group=>group.bounds.width>0 && group.bounds.height>0
                                && group.bounds.top>=pane.top && group.bounds.bottom<=pane.bottom
                                && group.bounds.left>=pane.left && group.bounds.right<=pane.right),
                        pane:pane.toJSON(),groups};
                    """, pane);
            Assertions.assertEquals(Boolean.TRUE, layout.get("fits"), "All export groups must fit the capture: " + layout);
            saveElementScreenshot(pane, "33-export-tab.png");
            completed = true;
        } finally {
            if (completed) driver.manage().window().setSize(originalSize);
        }
    }

    @Test
    @Order(34)
    void captureDslEditorPanel() throws IOException {
        // Ensure Git history with branches exists for realistic DSL content
        buildGitHistory();
        navigateToTab("dsl-editor");
        loadCurrentDsl("element");
        saveScreenshot("34-dsl-editor-panel.png");
    }

    // ── Screenshots 35–40: Extended visual guide ──────────────────────────────

    @Test
    @Order(35)
    void captureScoredUaBranchExpanded() throws IOException {
        // Ensure analysis is complete (reuse scores from test 15)
        navigateToTab("analyze");
        if (!hasSuccessfulAnalysis()) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        // Retain the historical filename while showing a genuinely positive UA path.
        saveScoredUaScreenshot("35-scored-bp-tree-expanded.png", true);
    }

    @Test
    @Order(36)
    void captureProposalAccepted() throws IOException {
        // DSL history can leave the selected workspace projection stale. Use the
        // relation panel's real recovery action before creating a review proposal.
        navigateToTab("relations");
        openDetails(driver.findElement(By.id("relationsBrowser")));
        js("window.TaxonomyRelations.loadRelations();");
        WebElement projectionAction = wait(30).withMessage(() ->
                "Relation projection did not become readable or offer recovery: "
                        + driver.findElement(By.id("relationsTableContainer")).getText()).until(d -> {
            WebElement content = d.findElement(By.id("relationsTableContainer"));
            if ("true".equals(content.getAttribute("aria-busy"))) return null;
            Assertions.assertTrue(content.findElements(By.cssSelector(".alert-danger")).isEmpty(),
                    "Relation projection failed: " + content.getText());
            List<WebElement> rebuild = content.findElements(By.cssSelector(".alert .btn-outline-primary"));
            if (!rebuild.isEmpty() && rebuild.get(0).isDisplayed() && rebuild.get(0).isEnabled()) {
                return rebuild.get(0);
            }
            return content.findElements(By.cssSelector(".relations-table, :scope > .text-muted.small.p-2"))
                    .isEmpty() ? null : content;
        });
        if (!"relationsTableContainer".equals(projectionAction.getAttribute("id"))) {
            safeClick(projectionAction);
        }
        waitForResult("relationsTableContainer", ".relations-table, :scope > .text-muted.small.p-2", 30);
        Map<?, ?> projection = requestFixture("/api/architecture/relations/projection/readiness", "GET", null, null);
        Assertions.assertEquals("READY", projection.get("readinessState"),
                "Proposal creation needs the selected workspace projection: " + projection);
        Assertions.assertNotNull(projection.get("currentHeadCommit"), "Projection must identify its Git head");
        Assertions.assertEquals(projection.get("currentHeadCommit"), projection.get("projectedCommit"),
                "The readable projection must match the selected branch head");

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
        waitForResult("proposalsTableContainer", ".proposal-table tbody tr", 10);
        Assertions.assertTrue((Boolean) ((JavascriptExecutor) driver).executeScript(
                "return Array.from(document.querySelectorAll('#proposalsTableContainer tbody tr')).some(function(row) {" +
                "  return row.textContent.includes(window._acceptedSourceCode)" +
                "    && row.textContent.includes(window._acceptedTargetCode)" +
                "    && row.textContent.includes('ACCEPTED'); });"),
                "The accepted proposal must be visible in the review queue");
        saveElementScreenshot(panel, "36-proposal-accepted.png");
    }

    @Test
    @Order(39)
    void captureScoredSunburst() throws IOException {
        // Ensure analysis is complete
        navigateToTab("analyze");
        if (!hasSuccessfulAnalysis()) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        saveDiagramScreenshot("sunburst", "39-scored-sunburst.png", true);
    }

    @Test
    @Order(40)
    void captureDslWithRelations() throws IOException {
        buildGitHistory();
        Assertions.assertNotNull(draftFixtureCommitId, "The authored draft version must have been persisted");
        Map<?, ?> version = requestFixture("/api/dsl/git/commit/" + draftFixtureCommitId, "GET", null, null);
        Assertions.assertEquals(draftFixtureCommitId, version.get("commitId"));
        Assertions.assertInstanceOf(String.class, version.get("dslText"));
        String savedDsl = (String) version.get("dslText");
        Assertions.assertEquals(DRAFT_DSL, savedDsl, "The example version must retain the committed DSL text");
        navigateToTab("dsl-editor");
        wait(15).until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d)
                .executeScript("return !!window.dslCmView;")));
        // Load the retained version through its existing history action. Load Current exports
        // materialized catalogue state and does not promise to contain this workspace example.
        org.openqa.selenium.support.ui.Select branches =
                new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("dslBranchSelect")));
        branches.selectByValue("feature-voice");
        wait(15).until(d -> d.findElements(By.cssSelector("#dslHistoryBody tr td:nth-child(2)"))
                .stream().anyMatch(cell -> "feature-voice".equals(cell.getText())));
        branches.selectByValue("draft");
        By loadVersion = By.cssSelector("#dslHistoryBody .dsl-load-commit[data-commit-id='" + draftFixtureCommitId + "']");
        WebElement load = wait(15).withMessage(() -> "Persisted draft version is absent from history: "
                + driver.findElement(By.id("dslHistoryBody")).getText()).until(ExpectedConditions.elementToBeClickable(loadVersion));
        js("window.__screenshotPreviousDslDocument = window.dslCmView.state.doc;");
        clickVisible(load);
        wait(20).withMessage(() -> "Saved DSL version did not load its relation block: "
                + driver.findElement(By.id("dslStatusArea")).getText() + "; " + dslCaptureDiagnostic()).until(d -> {
            WebElement status = d.findElement(By.id("dslStatusArea"));
            Assertions.assertFalse(status.getAttribute("class").contains("alert-danger"),
                    () -> status.getText() + "; " + dslCaptureDiagnostic());
            return status.getAttribute("class").contains("alert-success")
                    && Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript("""
                        const view=window.dslCmView;
                        return view.state.doc!==window.__screenshotPreviousDslDocument
                            && view.state.doc.toString()===arguments[0]
                            && /^relation\\s/m.test(view.state.doc.toString());
                        """, savedDsl));
        });
        js("""
                const view=window.dslCmView;
                const match=/^relation\\s/m.exec(view.state.doc.toString());
                view.dispatch({selection:{anchor:match.index},
                    effects:view.constructor.scrollIntoView(match.index,{y:'center'})});
                """);
        wait(10).withMessage(this::dslCaptureDiagnostic).until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript("""
                const view=window.dslCmView, port=view.scrollDOM.getBoundingClientRect();
                return Array.from(view.contentDOM.querySelectorAll('.cm-line')).some(line=>{
                    const box=line.getBoundingClientRect();
                    return /^relation\\s/.test(line.textContent) && box.top>=port.top && box.bottom<=port.bottom;
                });
                """)));
        WebElement editor = driver.findElement(By.id("dslEditorContainer"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", editor);
        saveScreenshot("40-dsl-editor-with-relations.png");
    }

    // ── Screenshots 41–44: Versions Tab, Git Status Bar, Context Bar ──────────

    @Test
    @Order(41)
    void captureVersionsTabHistory() throws IOException {
        // Ensure Git history with branches exists for realistic version timeline
        buildGitHistory();
        navigateToTab("versions");
        // Wait for successful load via language-independent data-state attribute
        refreshVersionHistory();
        // Ensure the History sub-tab is active
        safeClick(By.cssSelector("[data-versions-tab=\"history\"]"));
        saveScreenshot("41-versions-tab-history.png");
    }

    @Test
    @Order(42)
    void captureVersionsTabSaveVersion() throws IOException {
        navigateToTab("versions");
        // Switch to the Save Version sub-tab
        safeClick(By.cssSelector("[data-versions-tab=\"save\"]"));
        // Fill in example data for the screenshot
        js("var t = document.getElementById('versionTitle');" +
           "if (t) { t.value = 'Baseline after review round 2'; t.dispatchEvent(new Event('input')); }" +
           "var d = document.getElementById('versionDescription');" +
           "if (d) { d.value = 'Incorporates feedback from architecture board meeting'; d.dispatchEvent(new Event('input')); }");
        saveScreenshot("42-versions-tab-save.png");
        // Reset to History sub-tab
        safeClick(By.cssSelector("[data-versions-tab=\"history\"]"));
    }

    @Test
    @Order(43)
    void captureGitStatusBar() throws IOException {
        // Navigate to the Analyze tab (default) so the full layout is visible
        navigateToTab("analyze");
        openOperationalStatus();
        // Wait for the Git status bar to become visible (it polls /api/git/state)
        wait(15).until(d -> {
            WebElement bar = d.findElement(By.id("gitStatusBar"));
            Assertions.assertTrue(bar.findElements(By.cssSelector(".dot.error")).isEmpty(),
                    "Git status failed: " + bar.getText());
            return bar.isDisplayed() && !bar.findElements(By.cssSelector(".git-branch")).isEmpty();
        });
        saveElementScreenshot(driver.findElement(By.id("gitStatusBar")),
                "43-git-status-bar.png");
    }

    @Test
    @Order(44)
    void captureContextBar() throws IOException {
        openOperationalStatus();
        // The context bar polls /api/context/current. Wait for the render signal attribute.
        wait(30).until(ExpectedConditions.attributeToBe(
                By.id("contextBar"), "data-context-rendered", "true"));
        wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("contextBar")));
        saveElementScreenshot(driver.findElement(By.id("contextBar")),
                "44-context-bar.png");
    }

    // ── Screenshots 45–47: Workspace UI Elements ──────────────────────────────

    @Test
    @Order(45)
    void captureWorkspaceUserBadge() throws IOException {
        navigateToTab("analyze");
        openOperationalStatus();
        wait(5).until(d -> {
            WebElement badge = d.findElement(By.id("workspaceUserBadge"));
            return badge.isDisplayed() && !badge.getText().isEmpty();
        });
        saveElementScreenshot(driver.findElement(By.id("operationalContextDetails")),
                "45-workspace-user-badge.png");
        driver.findElement(By.cssSelector("#operationalContextDetails > summary")).click();
    }

    @Test
    @Order(46)
    void captureVariantCreationModal() throws IOException {
        navigateToTab("versions");
        // Open the Create Variant modal
        showModalAndWait("createVariantModal");
        // Pre-fill the variant name input for the screenshot
        js("var input = document.getElementById('variantNameInput');" +
           "if (input) { input.value = 'feature-voice-services'; input.dispatchEvent(new Event('input')); }");
        saveScreenshot("46-variant-creation-modal.png");
        closeModalAndWait("createVariantModal");
    }

    @Test
    @Order(47)
    void captureVariantsBrowserTab() throws IOException {
        // Ensure Git history with branches exists so the variants browser shows real branches
        buildGitHistory();
        navigateToTab("versions");
        // Switch to the Variants sub-tab
        safeClick(By.cssSelector("[data-versions-tab=\"variants\"]"));
        // Wait for the variants browser to load
        waitForResult("variantsBrowser", ".variant-card", 30);
        saveScreenshot("47-variants-browser-tab.png");
        // Reset to History sub-tab
        safeClick(By.cssSelector("[data-versions-tab=\"history\"]"));
    }

    // Captures 48–65 and 67–68 were retired: they constructed output DOM instead of running workflows.
    // Their retained PNGs are historical illustrations; see .github/copilot-ref-screenshots.md.

    @Test
    @Order(66)
    void captureVersionsTimeline() throws IOException {
        navigateToTab("versions");
        // Switch to History sub-tab
        safeClick(By.cssSelector("[data-versions-tab=\"history\"]"));
        // Wait for the timeline to be populated via language-independent data-state attribute
        refreshVersionHistory();
        saveScreenshot("66-versions-timeline.png");
    }

    // ── Screenshot 69: Scored Decision Map (complements the empty-state 08) ───

    @Test
    @Order(69)
    void captureDecisionMapScored() throws IOException {
        // Ensure analysis is complete so the decision map has scored nodes to display
        navigateToTab("analyze");
        if (!hasSuccessfulAnalysis()) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        saveDiagramScreenshot("decision", "69-decision-map-scored.png", true);
    }

    // ── Screenshot 70: Swagger UI (API Documentation) ─────────────────────────

    @Test
    @Order(70)
    void captureSwaggerUi() throws IOException {
        // Navigate to Swagger UI
        driver.get(ContainerTestUtils.APP_ORIGIN + "/swagger-ui.html");

        wait(30).withMessage("Swagger UI must load before its documentation capture")
                .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".swagger-ui .info")));

        // Wait for at least one operation block (endpoint) to render
        wait(15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".swagger-ui .opblock")));

        // Collapse all expanded tag sections for a cleaner overview screenshot
        js("document.querySelectorAll('.swagger-ui .opblock-tag-section.is-open')" +
           ".forEach(function(s) {" +
           "  var h = s.querySelector('.opblock-tag'); if (h) h.click();" +
           "});");

        // Wait for collapse animations to complete
        wait(5).until(d -> {
            Long openCount = (Long) ((JavascriptExecutor) d).executeScript(
                    "return document.querySelectorAll('.swagger-ui .opblock-tag-section.is-open').length;");
            return openCount != null && openCount == 0;
        });

        // Scroll to top to capture the API info + tag overview
        js("window.scrollTo(0, 0);");

        saveScreenshot("70-swagger-ui.png");

        // Return to main application for subsequent tests
        resetPageState();
    }

    // ── Screenshots 71–74: Rich Git History & Cherry-Pick Workflow ─────────────

    @Test
    @Order(71)
    void captureRichVersionTimeline() throws IOException {
        // Build a rich history with 12+ commits across 4 branches
        buildRichGitHistory();
        navigateToTab("versions");
        // Switch to History sub-tab
        safeClick(By.cssSelector("[data-versions-tab=\"history\"]"));
        // Wait for the timeline to load with real data
        refreshVersionHistory();
        saveScreenshot("71-rich-version-timeline.png");
    }

    @Test
    @Order(72)
    void captureRichVariantsBrowser() throws IOException {
        // Show the variants panel with multiple branches (draft, feature-voice, review, hotfix)
        buildRichGitHistory();
        navigateToTab("versions");
        // Switch to the Variants sub-tab
        safeClick(By.cssSelector("[data-versions-tab=\"variants\"]"));
        // Wait for the variants browser to load (matches working Test 47 pattern)
        waitForResult("variantsBrowser", ".variant-card", 30);
        saveScreenshot("72-rich-variants-browser.png");
    }

    @Test
    @Order(73)
    void captureCherryPickWorkflowBefore() throws IOException {
        // Show the version timeline on draft BEFORE cherry-picking a commit from review
        buildRichGitHistory();
        Assertions.assertNotNull(cherryPickSourceCommitId,
                "Cherry-pick source commit ID not captured during buildRichGitHistory()");
        navigateToTab("versions");
        safeClick(By.cssSelector("[data-versions-tab=\"history\"]"));
        refreshVersionHistory();
        saveScreenshot("73-cherry-pick-before.png");
    }

    @Test
    @Order(74)
    void captureCherryPickWorkflowExecute() throws IOException {
        // Execute a REAL cherry-pick via the REST API and capture the result
        buildRichGitHistory();
        Assertions.assertNotNull(cherryPickSourceCommitId,
                "Cherry-pick source commit ID not captured during buildRichGitHistory()");

        Map<?, ?> result = requestFixture("/api/dsl/cherry-pick?commitId=" + cherryPickSourceCommitId
                + "&targetBranch=draft", "POST", null, null);
        Assertions.assertInstanceOf(String.class, result.get("commitId"),
                "Cherry-pick must persist a commit: " + result);
        Assertions.assertEquals(cherryPickSourceCommitId, result.get("cherryPickedFrom"));
        Assertions.assertEquals("draft", result.get("targetBranch"));

        // Navigate to versions to show the timeline with the cherry-picked commit
        navigateToTab("versions");
        safeClick(By.cssSelector("[data-versions-tab=\"history\"]"));
        refreshVersionHistory();
        saveScreenshot("74-cherry-pick-after.png");
    }

    // ── Screenshot 75: Diagram Policy Preference ────────────────────────────

    @Test
    @Order(75)
    void capturePreferencesDiagramPolicy() throws IOException {
        // Navigate to the Preferences tab (admin-only)
        navigateToTab("preferences");

        // The diagram policy <select> should be visible in the Diagram Configuration card
        wait(10).until(ExpectedConditions.visibilityOfElementLocated(By.id("pref-diagram-policy")));

        // Scroll to the diagram policy select so it's visible in the screenshot
        WebElement policySelect = driver.findElement(By.id("pref-diagram-policy"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", policySelect);
        saveScreenshot("75-preferences-diagram-policy.png");
    }

    @Test
    @Order(76)
    void captureCurrentGraphEntry() throws IOException {
        resetPageState("de");
        try {
            selectSavedGraphModel("de");
            navigateToTab("analyze");
            // Catalogue navigation uses the same explicit saved-model action available to users.
            safeClick(By.id("gapSelectModel"));
            wait(10).until(ExpectedConditions.textToBePresentInElementLocated(
                    By.id("gapModelSource"), graphFixtureCommitId.substring(0, 12)));
            safeClick(By.id("viewList"));
            wait(10).until(ExpectedConditions.attributeToBe(
                    By.id("taxonomyTree"), "data-view-rendered", "list"));
            WebElement processRoot = driver.findElement(By.cssSelector(
                    "#taxonomyTree [data-taxonomy-container='BP'] > .tax-container-heading > .tax-container-toggle"));
            if (!"true".equals(processRoot.getAttribute("aria-expanded"))) {
                safeClick(processRoot);
            }
            By actions = By.cssSelector(
                    "#taxonomyTree .tax-node[data-code='BP-1000'] .tax-node-actions-toggle");
            wait(10).until(ExpectedConditions.visibilityOfElementLocated(actions));
            safeClick(actions);
            By graphAction = By.cssSelector("#taxonomyActionsDialog .graph-explore-btn");
            wait(5).until(ExpectedConditions.visibilityOfElementLocated(graphAction));
            Assertions.assertEquals("BP-1000", driver.findElement(graphAction).getAttribute("data-code"));
            safeClick(graphAction);
            wait(10).until(ExpectedConditions.visibilityOfElementLocated(By.id("tab-graph")));
            waitForResult("graphResultsContent", "#graphViewTable", 30);
            Assertions.assertEquals("BP-1000", driver.findElement(By.id("graphNodeInput")).getAttribute("value"));
            wait(5).until(d -> d.findElement(By.id("graphNodeInput")).equals(d.switchTo().activeElement()));
            assertSavedGraphEvidence();
            saveScreenshot("graph-entry-current-de.png");
        } finally {
            resetPageState();
        }
    }

    @Test
    @Order(77)
    void captureCurrentDatabaseSetup() throws IOException {
        org.openqa.selenium.Dimension originalSize = driver.manage().window().getSize();
        try {
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(1440, 1100));
            for (String language : List.of("de", "en")) {
                resetPageState(language);
                showAdminSettings();
                safeClick(By.id("databaseSetupMenuAction"));
                wait(15).until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupDialog")));
                new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("databaseSetupType")))
                        .selectByValue("postgres");
                safeClick(By.id("databaseSetupNext"));
                wait(5).until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupHost")));
                driver.findElement(By.id("databaseSetupHost")).sendKeys("postgres.database.svc.cluster.local");
                Assertions.assertTrue(driver.findElement(By.id("databaseSetupTls")).isSelected());
                Assertions.assertFalse(driver.findElement(By.id("databaseSetupResult")).isDisplayed(),
                        "Connection preparation must not claim a connection test or deployment result");
                saveElementScreenshot(driver.findElement(By.id("databaseSetupDialog")),
                        "database-setup-current-" + language + ".png");
                safeClick(By.id("databaseSetupClose"));
                wait(5).until(ExpectedConditions.invisibilityOfElementLocated(By.id("databaseSetupDialog")));
            }
        } finally {
            driver.manage().window().setSize(originalSize);
            resetPageState();
        }
    }

    @Test
    @Order(78)
    void captureCurrentDiagramPrintControls() throws IOException {
        try {
            for (String language : List.of("de", "en")) {
                resetPageState(language);
                forceNonInteractiveMode();
                runAnalysis();
                safeClick(By.id("viewDecision"));
                wait(10).until(ExpectedConditions.attributeToBe(
                        By.id("taxonomyTree"), "data-view-rendered", "decision"));
                new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("diagramRelevanceFilter")))
                        .selectByValue("positive");
                waitForTransitionsComplete();
                By controls = By.cssSelector("#taxonomyTree .tax-diagram-controls");
                wait(10).until(ExpectedConditions.visibilityOfElementLocated(controls));
                new org.openqa.selenium.support.ui.Select(driver.findElement(By.cssSelector(
                        "#taxonomyTree .tax-diagram-print-mode"))).selectByValue("taxonomy");
                new org.openqa.selenium.support.ui.Select(driver.findElement(By.cssSelector(
                        "#taxonomyTree .tax-diagram-print-paper"))).selectByValue("A0");
                Assertions.assertTrue(driver.findElement(By.cssSelector(
                        "#taxonomyTree .tax-diagram-relevance input")).isSelected());
                saveElementScreenshot(driver.findElement(controls),
                        "diagram-print-controls-" + language + ".png");
            }
        } finally {
            resetPageState();
        }
    }

    @Test
    @Order(79)
    void captureRootRelevanceClarification() throws IOException {
        org.openqa.selenium.Dimension originalSize = driver.manage().window().getSize();
        Path savedFile = Files.createTempFile("taxonomy-demo-r0-", ".json");
        boolean completed = false;
        try {
            // Show all eight collapsed root summaries and the unresolved question without inner scrolling.
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(1680, 1800));
            resetPageState("de");
            WebElement input = driver.findElement(By.id("businessText"));
            input.clear();
            input.sendKeys("produktdemo");
            safeClick(By.id("analyzeBtn"));
            wait(60).until(ExpectedConditions.visibilityOfElementLocated(By.id("productDemoBanner")));
            var route = java.util.regex.Pattern.compile("/projects/(\\d+)/requirements/(\\d+)")
                    .matcher(driver.getCurrentUrl());
            Assertions.assertTrue(route.find(), "Product demo must open its actual requirement page");
            String projectId = route.group(1);
            String requirementId = route.group(2);
            wait(15).until(ExpectedConditions.elementToBeClickable(By.id("reanalyzeRequirement")));
            safeClick(By.id("reanalyzeRequirement"));
            By selectedSnapshot = By.cssSelector("#snapshotList [data-snapshot-id].active");
            wait(180).withMessage(() -> "R0 analysis did not produce a persisted snapshot: "
                    + driver.findElement(By.id("detailError")).getText())
                    .until(ExpectedConditions.presenceOfElementLocated(selectedSnapshot));
            String snapshotId = driver.findElement(selectedSnapshot).getAttribute("data-snapshot-id");
            Map<?, ?> snapshot = requestFixture("/api/projects/" + projectId + "/snapshots/" + snapshotId,
                    "GET", null, null);
            Map<?, ?> summary = (Map<?, ?>) snapshot.get("summary");
            Map<?, ?> analysis = (Map<?, ?>) snapshot.get("analysis");
            Assertions.assertEquals("PARTIAL", summary.get("status"));
            Assertions.assertEquals("DEMO.TIME-TRACKING-V1.R0", summary.get("provider"));
            Map<?, ?> requirement = requestFixture("/api/projects/" + projectId + "/requirements/" + requirementId,
                    "GET", null, null);
            Map<String, Object> export = new LinkedHashMap<>();
            export.put("requirement", ((Map<?, ?>) requirement.get("currentVersion")).get("text"));
            export.put("analysisStatus", analysis.get("status"));
            for (String field : List.of("scores", "rawScores", "reasons", "provider", "analysisCoverage",
                    "analysisScope", "analysisLanguage", "analysisReasonLength", "copilot",
                    "analysisOperationId", "analysisProviderId")) {
                if (analysis.get(field) != null) export.put(field, analysis.get(field));
            }
            Map<?, ?> saved = requestFixture("/api/scores/export", "POST", "application/json",
                    JSON.writeValueAsString(export));
            Map<?, ?> scores = (Map<?, ?>) saved.get("scores");
            Assertions.assertFalse(scores.containsKey("CO"), "An unresolved root has no numeric score");
            for (var expected : Map.of("BP", 40, "CR", 45, "UA", 70).entrySet()) {
                Assertions.assertEquals(expected.getValue().doubleValue(),
                        ((Number) scores.get(expected.getKey())).doubleValue(), "Retained demo root " + expected.getKey());
            }
            Map<?, ?> coverage = (Map<?, ?>) saved.get("analysisCoverage");
            Map<?, ?> communication = (Map<?, ?>) ((Map<?, ?>) coverage.get("nodes")).get("CO");
            Assertions.assertEquals("UNKNOWN", communication.get("state"));
            Assertions.assertNull(communication.get("score"));
            String reason = String.valueOf(communication.get("reason"));
            Assertions.assertTrue(reason.startsWith("CLARIFICATION:"));
            Files.writeString(savedFile, JSON.writeValueAsString(saved));
            ((org.openqa.selenium.remote.RemoteWebDriver) driver)
                    .setFileDetector(new org.openqa.selenium.remote.LocalFileDetector());
            for (String language : List.of("de", "en")) {
                resetPageState(language);
                navigateToTab("export");
                // Selenium's real file upload invokes the production importer and its HTTP validation.
                js("window.__screenshotPreviousImportScores = window.TaxonomyState.currentRawScores;");
                driver.findElement(By.id("importJsonFile")).sendKeys(savedFile.toAbsolutePath().toString());
                wait(30).until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript("""
                        const state = window.TaxonomyState;
                        return state.currentRawScores !== window.__screenshotPreviousImportScores
                            && state.lastAnalysisStatus === 'PARTIAL'
                            && state.analysisCoverage?.nodes?.CO?.state === 'UNKNOWN'
                            && !Object.prototype.hasOwnProperty.call(state.currentScores, 'CO')
                            && !Object.prototype.hasOwnProperty.call(state.currentRawScores, 'CO');
                        """)));
                navigateToTab("analyze");
                safeClick(By.id("viewList"));
                wait(10).until(ExpectedConditions.attributeToBe(
                        By.id("taxonomyTree"), "data-view-rendered", "list"));
                List<WebElement> rootToggles = driver.findElements(By.cssSelector(
                        "#taxonomyTree > .tax-container > .tax-container-heading > .tax-container-toggle"));
                Assertions.assertFalse(rootToggles.isEmpty(), "Imported catalogue must expose root controls");
                for (WebElement toggle : rootToggles) {
                    if ("true".equals(toggle.getAttribute("aria-expanded"))) {
                        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", toggle);
                        wait(5).until(ExpectedConditions.elementToBeClickable(toggle)).click();
                        wait(5).until(ExpectedConditions.attributeToBe(toggle, "aria-expanded", "false"));
                    }
                }
                WebElement question = wait(10).until(ExpectedConditions.visibilityOfElementLocated(
                        By.id("tax-container-clarification-CO")));
                Assertions.assertEquals(reason.substring("CLARIFICATION:".length()).trim(), question.getText());
                Map<?, ?> importedScores = (Map<?, ?>) ((JavascriptExecutor) driver)
                        .executeScript("return window.TaxonomyState.currentScores;");
                for (String code : List.of("BP", "CR", "UA")) {
                    Assertions.assertEquals(((Number) scores.get(code)).doubleValue(),
                            ((Number) importedScores.get(code)).doubleValue(), "Imported demo root " + code);
                }
                WebElement rootToggle = driver.findElement(By.cssSelector(
                        "#taxonomyTree [data-taxonomy-container='CO'] > .tax-container-heading > .tax-container-toggle"));
                rootToggle.sendKeys(org.openqa.selenium.Keys.SPACE);
                wait(5).until(ExpectedConditions.attributeToBe(rootToggle, "aria-expanded", "true"));
                rootToggle.sendKeys(org.openqa.selenium.Keys.SPACE);
                wait(5).until(ExpectedConditions.attributeToBe(rootToggle, "aria-expanded", "false"));
                Assertions.assertTrue(question.isDisplayed(), "The unresolved root question remains visible when collapsed");
                Map<?, ?> layout = (Map<?, ?>) ((JavascriptExecutor) driver).executeScript("""
                        const tree = document.getElementById('taxonomyTree');
                        const body = tree.closest('.card-body');
                        return {scrollHeight:body.scrollHeight, clientHeight:body.clientHeight,
                            scrollWidth:body.scrollWidth, clientWidth:body.clientWidth, scrollTop:body.scrollTop,
                            viewportWidth:innerWidth, viewportHeight:innerHeight,
                            roots:Array.from(tree.querySelectorAll(':scope > .tax-container')).map(root => ({
                                code:root.dataset.taxonomyContainer,
                                expanded:root.querySelector(':scope > .tax-container-heading > .tax-container-toggle')
                                    ?.getAttribute('aria-expanded')}))};
                        """);
                Assertions.assertTrue(
                        ((Number) layout.get("scrollHeight")).doubleValue()
                                <= ((Number) layout.get("clientHeight")).doubleValue() + 1
                        && ((Number) layout.get("scrollWidth")).doubleValue()
                                <= ((Number) layout.get("clientWidth")).doubleValue() + 1,
                        "Collapsed root evidence must fit without clipping: " + layout);
                saveElementScreenshot(driver.findElement(By.cssSelector("#leftPanel > .card")),
                        "root-relevance-clarification-" + language + ".png");
            }
            completed = true;
        } finally {
            Files.deleteIfExists(savedFile);
            // Preserve the failing layout for the TestWatcher's PNG and page-source evidence.
            if (completed) {
                driver.manage().window().setSize(originalSize);
                resetPageState();
            }
        }
    }
}
