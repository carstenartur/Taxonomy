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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
@EnabledIfSystemProperty(named = "generateScreenshots", matches = ".*")
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

    /** Guard flag: {@link #buildRichGitHistory()} runs at most once per test class execution. */
    private static boolean richGitHistoryBuilt;

    /** Stores the commit ID captured during the rich history build for cherry-pick tests. */
    private static String cherryPickSourceCommitId;

    private static final Path OUTPUT_DIR = resolveOutputDir();

    /**
     * Uses {@code TAXONOMY_DOCS_IMAGE_DIR} when configured, otherwise the repository's
     * {@code docs/images/} folder. CI uses a fresh directory shared by all capture owners.
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

        GenericContainer<?> appContainer = ContainerTestUtils.appContainer(network)
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
        ContainerTestUtils.closeAll(browserSession, app, network);
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
        return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript(
                "var state=window.TaxonomyState; return !!state && state.lastAnalysisStatus === 'SUCCESS'" +
                " && Object.values(state.currentScores || {}).some(function(score) { return score > 0; });"));
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
        safeClick(By.id("dslLoadCurrentBtn"));
        wait(15).withMessage(() -> "DSL load failed: "
                + driver.findElement(By.id("dslStatusArea")).getText()).until(d -> {
            WebElement status = d.findElement(By.id("dslStatusArea"));
            String classes = status.getAttribute("class");
            Assertions.assertFalse(classes.contains("alert-danger"), status.getText());
            return classes.contains("alert-success");
        });
        String content = (String) ((JavascriptExecutor) driver).executeScript(
                "return window.dslCmView.state.doc.toString();");
        Assertions.assertTrue(content.contains(expectedBlock + " "),
                "Loaded DSL must include a real " + expectedBlock + " block");
    }

    private void openOperationalStatus() {
        WebElement status = driver.findElement(By.id("operationalContextDetails"));
        if (status.getAttribute("open") == null) {
            driver.findElement(By.cssSelector("#operationalContextDetails > summary")).click();
        }
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

    private Map<?, ?> requestFixture(String url, String method, String contentType, String body) {
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
                "var url=arguments[0], method=arguments[1], type=arguments[2], body=arguments[3], done=arguments[4];" +
                "var options={method:method};" +
                "if (body !== null) { options.headers={'Content-Type':type}; options.body=body; }" +
                "fetch(url, options).then(async function(response) {" +
                "  var text=await response.text();" +
                "  if (!response.ok) throw new Error('HTTP '+response.status+': '+text);" +
                "  done({data:JSON.parse(text)});" +
                "}).catch(function(error) { done({error:String(error)}); });",
                url, method, contentType, body);
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

    /** Closes the actual Bootstrap modal and waits for its lifecycle event. */
    private void closeModalAndWait(String modalId) {
        js("var modal=bootstrap.Modal.getInstance(document.getElementById(arguments[0]));" +
                "if (!modal) throw new Error('Modal is not open: ' + arguments[0]); modal.hide();", modalId);
        wait(5).until(ExpectedConditions.attributeToBe(
                By.id(modalId), "data-modal-visible", "false"));
    }

    private void showAdminSettings() {
        WebElement menu = driver.findElement(By.id("applicationSettings"));
        if (menu.getAttribute("open") == null) {
            driver.findElement(By.cssSelector("#applicationSettings > summary")).click();
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
        driver.get(ContainerTestUtils.APP_ORIGIN + "/");
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
        commitAndWait("draft", "Initial+architecture+definition", DRAFT_DSL);
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
        js("arguments[0].setAttribute('open', '');", details);
        wait(5).until(ExpectedConditions.attributeContains(details, "open", ""));
    }

    /** Uses Settings for administrator-only destinations and the common router otherwise. */
    private void navigateToTab(String page) {
        if ("admin".equals(page) || "preferences".equals(page)) {
            WebElement menu = driver.findElement(By.id("applicationSettings"));
            if (menu.getAttribute("open") == null) {
                driver.findElement(By.cssSelector("#applicationSettings > summary")).click();
            }
            String destination = "admin".equals(page) ? "adminNavTab" : "preferencesNavTab";
            wait(10).until(ExpectedConditions.visibilityOfElementLocated(By.id(destination)));
            driver.findElement(By.id(destination)).click();
        } else {
            js("if (window.navigateToPage) { window.navigateToPage(arguments[0]); }", page);
        }
        wait(5).until(d -> {
            WebElement pane = d.findElement(By.id("tab-" + page));
            return !pane.getAttribute("class").contains("d-none");
        });
    }

    // ── Screenshots 1–14: no LLM required ─────────────────────────────────────

    @Test
    @Order(1)
    void captureFullPageLayout() throws IOException {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/");
        wait(15).until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // Wait for actual taxonomy nodes to be rendered (not just the container div with loading spinner)
        wait(15).until(ExpectedConditions.presenceOfElementLocated(
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
        safeClick(By.id("viewSunburst"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));
        wait(10).until(ExpectedConditions.attributeToBe(By.id("taxonomyTree"), "data-view-rendered", "sunburst"));
        saveScreenshot("06-sunburst-view.png");
        safeClick(By.id("viewList"));
    }

    @Test
    @Order(7)
    void captureTreeView() throws IOException {
        safeClick(By.id("viewTree"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewTree"), "class", "btn-primary"));
        wait(10).until(ExpectedConditions.attributeContains(By.id("taxonomyTree"), "data-view-rendered", "tree"));
        // Wait for D3 SVG tree nodes to be fully rendered (avoids capturing mid-transition state
        // where node labels overlap because the layout animation has not completed).
        wait(10).until(d -> {
            Long nodeCount = (Long) ((JavascriptExecutor) d).executeScript(
                    "return document.querySelectorAll('#taxonomyTree svg g.tv-node').length;");
            return nodeCount != null && nodeCount > 0;
        });
        // Wait for D3 tree transitions to complete (event-driven via data-transitions-complete)
        waitForTransitionsComplete();
        saveScreenshot("07-tree-view.png");
        safeClick(By.id("viewList"));
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
        // Scroll to the taxonomy tree so the proposal buttons become visible
        WebElement taxonomyTree = driver.findElement(By.id("taxonomyTree"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'start'});", taxonomyTree);
        // Wait for at least one proposal button to be present in the DOM
        wait(15).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".proposal-btn")));
        // Use JS click to work around any overlay or scroll positioning issues
        WebElement proposeBtn = driver.findElement(By.cssSelector(".proposal-btn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", proposeBtn);
        // Clear stale content-ready signal before clicking
        js("var m = document.getElementById('proposeRelationsModal');" +
           "if (m) m.removeAttribute('data-content-ready');");
        js("arguments[0].click();", proposeBtn);
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
        WebElement navbar = driver.findElement(By.cssSelector("nav.navbar"));
        saveElementScreenshot(navbar, "14-navbar-settings.png");
        driver.findElement(By.cssSelector("#applicationSettings > summary")).click();
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

        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", justifyBtns.get(0));
        js("arguments[0].click();", justifyBtns.get(0));
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

    @Test
    @Order(21)
    void captureGraphExplorerUpstream() throws IOException {
        navigateToTab("graph");
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", input);
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", input);
        js("arguments[0].value = 'BP-1327'; arguments[0].dispatchEvent(new Event('input'));", input);
        WebElement upstreamBtn = driver.findElement(By.id("graphUpstreamBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", upstreamBtn);
        js("arguments[0].click();", upstreamBtn);

        waitForResult("graphResultsContent", "#graphViewTable", 30);
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "21-graph-explorer-upstream.png");
    }

    @Test
    @Order(22)
    void captureGraphExplorerFailure() throws IOException {
        navigateToTab("graph");
        WebElement input = driver.findElement(By.id("graphNodeInput"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", input);
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", input);
        js("arguments[0].value = 'BP-1327'; arguments[0].dispatchEvent(new Event('input'));", input);

        WebElement failureBtn = driver.findElement(By.id("graphFailureBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", failureBtn);
        js("arguments[0].click();", failureBtn);

        waitForResult("graphResultsContent", "#graphViewTable", 30);
        saveElementScreenshot(driver.findElement(By.id("graphExplorerPanel")), "22-graph-explorer-failure.png");
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
        js("var btn = document.getElementById('refreshDiagnostics'); if (btn) btn.click();");

        WebElement diagPanel = driver.findElement(By.id("diagnosticsPanel"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", diagPanel);
        openDetails(diagPanel);
        wait(5).until(ExpectedConditions.visibilityOf(diagPanel));
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
        // Take a full-tab screenshot showing all export options
        saveScreenshot("33-export-tab.png");
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
    void captureFullScoredBPTreeExpanded() throws IOException {
        // Ensure analysis is complete (reuse scores from test 15)
        navigateToTab("analyze");
        if (!hasSuccessfulAnalysis()) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        // Switch to list view and expand all nodes
        safeClick(By.id("viewList"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewList"), "class", "btn-primary"));
        // Turn descriptions OFF so each node shows only code + name + score bar (much more compact)
        js("var cb = document.getElementById('showDescriptions'); if (cb && cb.checked) { cb.click(); }");
        wait(2).until(d -> true); // brief settle after toggling descriptions
        js("document.getElementById('expandAll').click();");
        wait(5).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".tax-toggle")));
        // Remove the max-height CSS constraint so the full expanded tree renders without clipping
        js("var cardBody = document.querySelector('#taxonomyTree').closest('.card-body');" +
           " if (cardBody) { cardBody.style.maxHeight = 'none'; cardBody.style.overflow = 'visible'; }");
        // Use a moderate viewport height (2000px) to capture the top portion of the expanded
        // tree without creating an excessively tall/narrow image that is hard to read in
        // GitHub documentation.  Also hide the right panel to give the tree full width.
        js("var rightCol = document.querySelector('.col-lg-5');" +
           " if (rightCol) rightCol.style.display = 'none';" +
           " var leftCol = document.querySelector('.col-lg-7');" +
           " if (leftCol) { leftCol.className = leftCol.className.replace('col-lg-7', 'col-12'); }");
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 2000));
        wait(2).until(d -> true); // brief settle
        // Scroll to the top and capture a full-page screenshot (avoids the narrow-strip problem
        // caused by element screenshots on very tall elements)
        js("window.scrollTo(0, 0);");
        saveScreenshot("35-scored-bp-tree-expanded.png");
        // Reset viewport and layout
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900));
        js("var rightCol = document.querySelector('.col-lg-5');" +
           " if (rightCol) rightCol.style.display = '';" +
           " var leftCol = document.querySelector('.col-12');" +
           " if (leftCol) { leftCol.className = leftCol.className.replace('col-12', 'col-lg-7'); }");
        // Restore max-height constraint and descriptions
        js("var cardBody = document.querySelector('#taxonomyTree').closest('.card-body');" +
           " if (cardBody) { cardBody.style.maxHeight = '82vh'; cardBody.style.overflow = 'auto'; }");
        js("var cb = document.getElementById('showDescriptions'); if (cb && !cb.checked) { cb.click(); }");
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
        // Switch to sunburst view — scores are already computed, so the sunburst will be coloured
        safeClick(By.id("viewSunburst"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));
        // Wait for the sunburst SVG to actually finish rendering
        wait(10).until(ExpectedConditions.attributeToBe(By.id("taxonomyTree"), "data-view-rendered", "sunburst"));
        // Scroll sunburst into viewport before capturing — by test 39 the page may have scrolled
        js("document.getElementById('taxonomyTree').scrollIntoView({behavior:'instant', block:'center'});");
        saveScreenshot("39-scored-sunburst.png");
        // Reset to list view
        safeClick(By.id("viewList"));
    }

    @Test
    @Order(40)
    void captureDslWithRelations() throws IOException {
        navigateToTab("dsl-editor");
        loadCurrentDsl("relation");
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
        // Switch to the decision map view — with scores, it renders the D3 treemap + decision table
        safeClick(By.id("viewDecision"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewDecision"), "class", "btn-primary"));
        wait(10).until(ExpectedConditions.attributeToBe(By.id("taxonomyTree"), "data-view-rendered", "decision"));
        // Wait for D3 decision map transitions to complete (event-driven via data-transitions-complete)
        waitForTransitionsComplete();
        saveScreenshot("69-decision-map-scored.png");
        safeClick(By.id("viewList"));
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
}
