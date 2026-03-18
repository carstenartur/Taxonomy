package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
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
import com.taxonomy.shared.service.AppInitializationStateService;

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
            "Provide an integrated communication platform for hospital staff, enabling real-time voice and data exchange between departments";

    private static final String FALLBACK_DSL_TEXT =
            "meta {\\n  language: \"taxdsl\";\\n  version: \"2.0\";\\n  namespace: \"default\";\\n}\\n\\n" +
            "element CP type Capability {\\n  title: \"Capability Packages\";\\n}\\n\\n" +
            "element CR type CoreService {\\n  title: \"Core Services\";\\n}\\n\\n" +
            "relation CP REALIZES CR {\\n  status: accepted;\\n}\\n";

    /** Regex matching any terminal analysis status (success, error, or unavailable). */
    private static final java.util.regex.Pattern ANALYSIS_DONE_PATTERN =
            java.util.regex.Pattern.compile("(?i)complete|error|not available|unavailable|0 matches");

    /**
     * Fallback search results HTML injected when the embedding model is unavailable and semantic
     * or hybrid search returns no results. Matches the structure produced by
     * {@code renderSearchResults()} in taxonomy-search.js.
     */
    private static final String FALLBACK_SEMANTIC_SEARCH_HTML =
            "<div class=\"small text-muted mb-1\">3 result(s)</div>" +
            "<div class=\"list-group list-group-flush search-results-list\">" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CP-1023\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CP-1023</span> " +
            "<span class=\"search-result-name text-truncate\">Secure Voice Communications</span>" +
            "<span class=\"badge bg-success ms-auto\">89%</span></a>" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CR-1047\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CR-1047</span> " +
            "<span class=\"search-result-name text-truncate\">Data Exchange Services</span>" +
            "<span class=\"badge bg-success ms-auto\">82%</span></a>" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"IP-2001\">" +
            "<span class=\"search-result-code fw-semibold me-1\">IP-2001</span> " +
            "<span class=\"search-result-name text-truncate\">Interoperability Framework</span>" +
            "<span class=\"badge bg-success ms-auto\">76%</span></a>" +
            "</div>";

    /**
     * Fallback hybrid search results HTML (same structure as semantic, different search context).
     */
    private static final String FALLBACK_HYBRID_SEARCH_HTML =
            "<div class=\"small text-muted mb-1\">3 result(s)</div>" +
            "<div class=\"list-group list-group-flush search-results-list\">" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CO-3010\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CO-3010</span> " +
            "<span class=\"search-result-name text-truncate\">Command Operations Center</span>" +
            "<span class=\"badge bg-success ms-auto\">91%</span></a>" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CI-2047\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CI-2047</span> " +
            "<span class=\"search-result-name text-truncate\">Intelligence Processing</span>" +
            "<span class=\"badge bg-success ms-auto\">85%</span></a>" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CR-1023\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CR-1023</span> " +
            "<span class=\"search-result-name text-truncate\">Core Communication Services</span>" +
            "<span class=\"badge bg-success ms-auto\">78%</span></a>" +
            "</div>";

    /**
     * Fallback graph search results HTML injected when graph search returns no results.
     * Matches the structure produced by {@code renderGraphSearchResults()} in taxonomy-search.js.
     */
    private static final String FALLBACK_GRAPH_SEARCH_HTML =
            "<div class=\"small fst-italic mb-2\">Graph analysis: 3 connected nodes found</div>" +
            "<div class=\"small text-muted mb-1\">3 matched node(s)</div>" +
            "<div class=\"list-group list-group-flush search-results-list\">" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"IP-2001\">" +
            "<span class=\"search-result-code fw-semibold me-1\">IP-2001</span> " +
            "<span class=\"search-result-name text-truncate\">Interoperability Framework</span></a>" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CI-2047\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CI-2047</span> " +
            "<span class=\"search-result-name text-truncate\">Command Intelligence Node</span></a>" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CR-1047\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CR-1047</span> " +
            "<span class=\"search-result-name text-truncate\">Data Exchange Services</span></a>" +
            "</div>" +
            "<div class=\"small text-muted mt-2 mb-1\">Top relation types:</div>" +
            "<div class=\"d-flex gap-1 flex-wrap\">" +
            "<span class=\"badge bg-secondary\">REALIZES (3)</span>" +
            "<span class=\"badge bg-secondary\">REQUIRES (2)</span>" +
            "</div>";

    /**
     * Fallback fulltext search results HTML injected when the search index is locked (HTTP 423)
     * and fulltext search returns an error. Matches the structure produced by
     * {@code renderSearchResults()} in taxonomy-search.js.
     */
    private static final String FALLBACK_FULLTEXT_SEARCH_HTML =
            "<div class=\"small text-muted mb-1\">3 result(s)</div>" +
            "<div class=\"list-group list-group-flush search-results-list\">" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CP-1023\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CP-1023</span> " +
            "<span class=\"search-result-name text-truncate\">Secure Voice Communications</span></a>" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"CR-1047\">" +
            "<span class=\"search-result-code fw-semibold me-1\">CR-1047</span> " +
            "<span class=\"search-result-name text-truncate\">Data Exchange Services</span></a>" +
            "<a href=\"#\" class=\"list-group-item list-group-item-action py-1 px-2 d-flex align-items-center search-result-item\" data-code=\"IP-2001\">" +
            "<span class=\"search-result-code fw-semibold me-1\">IP-2001</span> " +
            "<span class=\"search-result-name text-truncate\">Interoperability Framework</span></a>" +
            "</div>";

    /**
     * Fallback upstream graph results HTML injected when the graph query fails (HTTP 423).
     * Contains a {@code graphViewTable} element so downstream waits are satisfied.
     */
    private static final String FALLBACK_UPSTREAM_GRAPH_HTML =
            "<div class=\"graph-view-toggle\">" +
            "<button class=\"btn btn-sm btn-outline-secondary graph-toggle-btn\" data-mode=\"graph\">&#128279; Graph</button>" +
            "<button class=\"btn btn-sm btn-primary graph-toggle-btn\" data-mode=\"table\">&#128202; Table</button>" +
            "</div>" +
            "<div id=\"graphViewGraph\" style=\"display:none;\"></div>" +
            "<div id=\"graphViewTable\">" +
            "<div class=\"graph-stats-row\">" +
            "<div class=\"graph-stat-card\"><span class=\"graph-stat-icon\">&#11014;&#65039;</span>" +
            "<div><div class=\"graph-stat-value text-primary\">UPSTREAM</div><div class=\"graph-stat-label\">Direction</div></div></div>" +
            "<div class=\"graph-stat-card\"><span class=\"graph-stat-icon\">&#128205;</span>" +
            "<div><div class=\"graph-stat-value text-dark\">BP-1327</div><div class=\"graph-stat-label\">Origin</div></div></div>" +
            "<div class=\"graph-stat-card\"><span class=\"graph-stat-icon\">&#128101;</span>" +
            "<div><div class=\"graph-stat-value text-success\">3</div><div class=\"graph-stat-label\">Neighbors</div></div></div>" +
            "<div class=\"graph-stat-card\"><span class=\"graph-stat-icon\">&#128279;</span>" +
            "<div><div class=\"graph-stat-value text-info\">3</div><div class=\"graph-stat-label\">Relations</div></div></div>" +
            "</div>" +
            "<h6 class=\"graph-section-title\">Upstream Elements <span class=\"badge bg-secondary\">3</span></h6>" +
            "<div class=\"table-responsive\"><table class=\"table table-sm table-hover graph-table mb-2\">" +
            "<thead><tr><th>Code</th><th>Title</th><th>Sheet</th><th>Relevance</th><th>Hop</th><th>Reason</th></tr></thead>" +
            "<tbody>" +
            "<tr><td>CP-1023</td><td>Secure Voice Communications</td>" +
            "<td><span class=\"badge bg-light text-dark border\">Capabilities</span></td>" +
            "<td><span class=\"badge bg-success graph-relevance-badge\">85%</span></td>" +
            "<td><span class=\"badge bg-light text-dark border\">hop 1</span></td>" +
            "<td class=\"small text-muted\">capability mapping</td></tr>" +
            "<tr><td>CR-1047</td><td>Data Exchange Services</td>" +
            "<td><span class=\"badge bg-light text-dark border\">Core Services</span></td>" +
            "<td><span class=\"badge bg-primary graph-relevance-badge\">70%</span></td>" +
            "<td><span class=\"badge bg-light text-dark border\">hop 1</span></td>" +
            "<td class=\"small text-muted\">service dependency</td></tr>" +
            "<tr><td>IP-2001</td><td>Interoperability Framework</td>" +
            "<td><span class=\"badge bg-light text-dark border\">Information Products</span></td>" +
            "<td><span class=\"badge bg-primary graph-relevance-badge\">62%</span></td>" +
            "<td><span class=\"badge bg-light text-dark border\">hop 2</span></td>" +
            "<td class=\"small text-muted\">data exchange</td></tr>" +
            "</tbody></table></div>" +
            "</div>";

    /**
     * Fallback downstream graph results HTML injected when the downstream query returns
     * sparse data (single node) or fails. Shows a realistic graph with an accepted relation.
     */
    private static final String FALLBACK_DOWNSTREAM_GRAPH_HTML =
            "<div class=\"graph-view-toggle\">" +
            "<button class=\"btn btn-sm btn-outline-secondary graph-toggle-btn\" data-mode=\"graph\">&#128279; Graph</button>" +
            "<button class=\"btn btn-sm btn-primary graph-toggle-btn\" data-mode=\"table\">&#128202; Table</button>" +
            "</div>" +
            "<div id=\"graphViewGraph\" style=\"display:none;\"></div>" +
            "<div id=\"graphViewTable\">" +
            "<div class=\"graph-stats-row\">" +
            "<div class=\"graph-stat-card\"><span class=\"graph-stat-icon\">&#11015;&#65039;</span>" +
            "<div><div class=\"graph-stat-value text-primary\">DOWNSTREAM</div><div class=\"graph-stat-label\">Direction</div></div></div>" +
            "<div class=\"graph-stat-card\"><span class=\"graph-stat-icon\">&#128205;</span>" +
            "<div><div class=\"graph-stat-value text-dark\">CP-1023</div><div class=\"graph-stat-label\">Origin</div></div></div>" +
            "<div class=\"graph-stat-card\"><span class=\"graph-stat-icon\">&#128101;</span>" +
            "<div><div class=\"graph-stat-value text-success\">2</div><div class=\"graph-stat-label\">Neighbors</div></div></div>" +
            "<div class=\"graph-stat-card\"><span class=\"graph-stat-icon\">&#128279;</span>" +
            "<div><div class=\"graph-stat-value text-info\">2</div><div class=\"graph-stat-label\">Relations</div></div></div>" +
            "</div>" +
            "<h6 class=\"graph-section-title\">Downstream Elements <span class=\"badge bg-secondary\">2</span></h6>" +
            "<div class=\"table-responsive\"><table class=\"table table-sm table-hover graph-table mb-2\">" +
            "<thead><tr><th>Code</th><th>Title</th><th>Sheet</th><th>Relevance</th><th>Hop</th><th>Reason</th></tr></thead>" +
            "<tbody>" +
            "<tr><td>CR-1047</td><td>Data Exchange Services</td>" +
            "<td><span class=\"badge bg-light text-dark border\">Core Services</span></td>" +
            "<td><span class=\"badge bg-success graph-relevance-badge\">85%</span></td>" +
            "<td><span class=\"badge bg-light text-dark border\">hop 1</span></td>" +
            "<td class=\"small text-muted\">accepted relation: REALIZES</td></tr>" +
            "<tr><td>IP-2001</td><td>Interoperability Framework</td>" +
            "<td><span class=\"badge bg-light text-dark border\">Information Products</span></td>" +
            "<td><span class=\"badge bg-primary graph-relevance-badge\">68%</span></td>" +
            "<td><span class=\"badge bg-light text-dark border\">hop 2</span></td>" +
            "<td class=\"small text-muted\">transitive dependency</td></tr>" +
            "</tbody></table></div>" +
            "<h6 class=\"graph-section-title\">Traversed Relationships <span class=\"badge bg-secondary\">2</span></h6>" +
            "<div class=\"table-responsive\"><table class=\"table table-sm table-hover graph-table mb-0\">" +
            "<thead><tr><th>Source</th><th></th><th>Target</th><th>Type</th><th>Relevance</th><th>Hop</th></tr></thead>" +
            "<tbody>" +
            "<tr><td>CP-1023</td><td class=\"text-center\">&rarr;</td><td>CR-1047</td>" +
            "<td><span class=\"badge bg-info text-dark\">REALIZES</span></td>" +
            "<td><span class=\"badge bg-success graph-relevance-badge\">85%</span></td>" +
            "<td><span class=\"badge bg-light text-dark border\">hop 1</span></td></tr>" +
            "<tr><td>CR-1047</td><td class=\"text-center\">&rarr;</td><td>IP-2001</td>" +
            "<td><span class=\"badge bg-info text-dark\">REQUIRES</span></td>" +
            "<td><span class=\"badge bg-primary graph-relevance-badge\">68%</span></td>" +
            "<td><span class=\"badge bg-light text-dark border\">hop 2</span></td></tr>" +
            "</tbody></table></div>" +
            "</div>";

    /**
     * Fallback timeline HTML injected when the version history fails to load (HTTP 423).
     * Matches the structure produced by {@code renderTimelineEntry()} in taxonomy-versions.js.
     */
    private static final String FALLBACK_TIMELINE_HTML =
            "<div class=\"timeline\">" +
            "<div class=\"timeline-entry mb-3 ps-4 position-relative\">" +
            "<span class=\"timeline-dot-current position-absolute\" style=\"left:0;top:6px;width:10px;height:10px;border-radius:50%;display:inline-block;\"></span>" +
            "<div class=\"d-flex justify-content-between align-items-start\"><div>" +
            "<strong class=\"small\">Baseline after review round 2</strong>" +
            "<div class=\"text-muted\" style=\"font-size:0.75rem;\">2025-03-15 14:30 &mdash; admin <code class=\"ms-1\">a1b2c3d</code></div>" +
            "</div></div></div>" +
            "<div class=\"timeline-entry mb-3 ps-4 position-relative\">" +
            "<span class=\"timeline-dot position-absolute\" style=\"left:0;top:6px;width:10px;height:10px;border-radius:50%;display:inline-block;\"></span>" +
            "<div class=\"d-flex justify-content-between align-items-start\"><div>" +
            "<strong class=\"small\">Added CP-1023 REALIZES CR-1047 relation</strong>" +
            "<div class=\"text-muted\" style=\"font-size:0.75rem;\">2025-03-14 09:15 &mdash; admin <code class=\"ms-1\">e4f5a6b</code></div>" +
            "</div></div></div>" +
            "<div class=\"timeline-entry mb-3 ps-4 position-relative\">" +
            "<span class=\"timeline-dot position-absolute\" style=\"left:0;top:6px;width:10px;height:10px;border-radius:50%;display:inline-block;\"></span>" +
            "<div class=\"d-flex justify-content-between align-items-start\"><div>" +
            "<strong class=\"small\">Initial taxonomy materialization</strong>" +
            "<div class=\"text-muted\" style=\"font-size:0.75rem;\">2025-03-13 16:00 &mdash; system <code class=\"ms-1\">c7d8e9f</code></div>" +
            "</div></div></div>" +
            "</div>";

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
                .withStartupTimeout(Duration.ofSeconds(180))
                // Wait for the taxonomy to be fully loaded and search index built.
                // The /api/status/startup endpoint returns {"status":"ready"} once
                // AppInitializationStateService transitions to READY — works for both
                // synchronous (default) and asynchronous initialization modes.
                .waitingFor(Wait.forHttp("/api/status/startup")
                        .forStatusCode(200)
                        .forPort(8080)
                        .withBasicCredentials("admin", "admin")
                        .forResponsePredicate(body -> body.contains("\"status\":\"ready\""))
                        .withStartupTimeout(Duration.ofSeconds(180)));

        app = appContainer;
        app.start();

        chrome = new BrowserWebDriverContainer<>()
                .withNetwork(network)
                .withCapabilities(new ChromeOptions());
        chrome.start();

        driver = chrome.getWebDriver();
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900));

        // Login via form (Spring Security requires authentication)
        driver.get("http://app:8080/login");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.name("username")));
        driver.findElement(By.name("username")).sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys("admin");
        driver.findElement(By.cssSelector("button[type='submit'], input[type='submit']")).click();

        // Load the application and wait for the taxonomy tree to be FULLY RENDERED
        driver.get("http://app:8080/");
        new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // The #taxonomyTree div is always in the HTML (with a loading spinner).
        // Wait for actual taxonomy nodes to appear — they are rendered by the JS
        // loadTaxonomy() fetch from /api/taxonomy, which runs asynchronously.
        // The container startup waited for /api/status/startup to return "ready",
        // so taxonomy data is already available — 15s is sufficient for the JS fetch + render.
        new WebDriverWait(driver, Duration.ofSeconds(15))
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
        // Scroll element into viewport to avoid Chrome renderer timeout on large/off-screen elements
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", element);
        try {
            File src = element.getScreenshotAs(OutputType.FILE);
            Files.copy(src.toPath(), OUTPUT_DIR.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        } catch (org.openqa.selenium.TimeoutException e) {
            if (e.getMessage() != null && e.getMessage().contains("renderer")) {
                // Fallback: take full-page screenshot when element screenshot times out
                File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                Files.copy(src.toPath(), OUTPUT_DIR.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw e;
            }
        }
    }

    private WebDriverWait wait(int seconds) {
        return new WebDriverWait(driver, Duration.ofSeconds(seconds));
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

    /**
     * Waits for embeddings to be ready by monitoring the nav-bar embedding badge.
     * The badge text changes from {@code "🧠 Embeddings: unavailable"} to
     * {@code "🧠 Embeddings: N nodes"} once the embedding index is built.
     * Uses the app's own periodic {@code checkEmbeddingStatus()} polling.
     */
    private void waitForEmbeddingsReadyViaUI() {
        wait(180).until(d -> {
            try {
                WebElement badge = d.findElement(By.id("embeddingStatusBadge"));
                String text = badge.getText();
                return text != null && text.contains("Embeddings:") && !text.contains("unavailable");
            } catch (org.openqa.selenium.NoSuchElementException e) {
                return false;
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
        js("arguments[0].click();", analyzeBtn);
        // First wait for statusArea to become non-empty (analysis started or immediately finished)
        try {
            wait(30).until(d -> {
                String text = d.findElement(By.id("statusArea")).getText();
                return text != null && !text.trim().isEmpty();
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Status area never updated — retry the click once
            js("arguments[0].click();", driver.findElement(By.id("analyzeBtn")));
        }
        wait(120).until(ExpectedConditions.textMatches(
                By.id("statusArea"), ANALYSIS_DONE_PATTERN));
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
        driver.get("http://app:8080/");
        wait(15).until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // The #taxonomyTree div is always in the HTML (with a loading spinner).
        // Wait for actual taxonomy nodes to appear — they are rendered by the JS
        // loadTaxonomy() fetch from /api/taxonomy, which runs asynchronously.
        // The container startup already waited for /api/status/startup to return "ready",
        // so taxonomy data is available — 15s is enough for the JS fetch + render.
        wait(15).until(ExpectedConditions.presenceOfElementLocated(
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
     * Waits for D3 tree/treemap transition animations (300ms) to complete.
     * A 500ms pause ensures all SVG elements have settled into their final positions.
     */
    private void waitForD3Transition() {
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Injects healthy-looking status badge values for AI, Embeddings, and Git Status
     * before taking documentation screenshots.  This replaces "unavailable" / error states
     * that appear when the screenshot environment lacks a real LLM key or when Git operations
     * are locked (HTTP 423).
     */
    private void injectHealthyStatusBadges() {
        // AI status badge → show mock provider as ready (green)
        js("var badge = document.getElementById('aiStatusBadge');" +
           "if (badge) { badge.textContent = '\\uD83D\\uDFE2 AI: Mock (Ready)';" +
           " badge.className = 'badge bg-success ms-auto me-2 fs-6'; }");
        // Embedding status badge → show available (blue)
        js("var badge = document.getElementById('embeddingStatusBadge');" +
           "if (badge) { badge.textContent = '\\uD83E\\uDDE0 Embeddings: 2500 nodes';" +
           " badge.className = 'badge bg-info text-dark'; badge.classList.remove('d-none'); }");
        // Git status bar → show healthy state
        js("var bar = document.getElementById('gitStatusBar');" +
           "if (bar) {" +
           " bar.innerHTML = '<span class=\"git-indicator\">" +
           "\\uD83D\\uDCCE <span class=\"git-branch\">draft</span>" +
           " @ <span class=\"git-sha\">a1b2c3d</span></span>" +
           " <span class=\"git-sep\">\\u2502</span>" +
           " <span class=\"git-indicator\"><span class=\"dot fresh\"></span> Projection: fresh</span>" +
           " <span class=\"git-sep\">\\u2502</span>" +
           " <span class=\"git-indicator\"><span class=\"dot fresh\"></span> Index: fresh</span>" +
           " <span class=\"git-sep\">\\u2502</span>" +
           " <span class=\"git-indicator\">3 versions</span>" +
           " <span class=\"git-sep\">\\u2502</span>" +
           " <span class=\"git-indicator\">1 variant</span>';" +
           " bar.classList.remove('d-none'); }");
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
        // Brief pause to let D3 tree transitions (300ms animation) complete
        waitForD3Transition();
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
        safeClick(By.id("viewSunburst"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));

        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                textarea, REQUIREMENT_TEXT);
        WebElement analyzeBtn = driver.findElement(By.id("analyzeBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", analyzeBtn);
        js("arguments[0].click();", analyzeBtn);

        wait(120).until(ExpectedConditions.textMatches(
                By.id("statusArea"), ANALYSIS_DONE_PATTERN));

        // Check that analysis actually succeeded — if it ended with error/unavailable,
        // the architectureViewPanel is never rendered and waiting for it would just time out.
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        Assumptions.assumeTrue(statusText.contains("complete"),
                "Skipping: analysis did not complete successfully (status: " + statusText + ")");

        // Navigate to Architecture tab to see the panel
        navigateToTab("architecture");
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureViewPanel")));
        saveElementScreenshot(driver.findElement(By.id("architectureViewPanel")), "20-architecture-view.png");

        // Reset: navigate back to analyze, switch back to list view and uncheck architecture view
        navigateToTab("analyze");
        safeClick(By.id("viewList"));
        archCb = driver.findElement(By.id("includeArchitectureView"));
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
        js("arguments[0].value = 'BP-1327'; arguments[0].dispatchEvent(new Event('input'));", input);
        WebElement upstreamBtn = driver.findElement(By.id("graphUpstreamBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", upstreamBtn);
        js("arguments[0].click();", upstreamBtn);

        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("graphResultsArea")));
        // Check if the graph results show an error (e.g., HTTP 423 from locked repository)
        // and inject fallback upstream graph content if needed
        Boolean hasError = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var el = document.getElementById('graphResultsContent');" +
                "return el && el.innerHTML.indexOf('alert-warning') >= 0;");
        if (Boolean.TRUE.equals(hasError)) {
            js("document.getElementById('graphResultsContent').innerHTML = arguments[0];",
                    FALLBACK_UPSTREAM_GRAPH_HTML);
        }
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

        // Wait for the failure impact results to load.
        // Phase 1: wait for graphResultsContent to exist and have content (spinner or results)
        wait(15).until(ExpectedConditions.presenceOfElementLocated(By.id("graphResultsContent")));
        // Phase 2: wait for the spinner to be replaced by graph/table results
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
        waitForTaxonomyReadyViaUI(); // Wait until startup banner is hidden (taxonomy index ready)
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
        try {
            wait(10).until(d -> {
                WebElement results = d.findElement(By.id("searchResultsArea"));
                return results.isDisplayed() && !results.getText().isEmpty();
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Search index may be locked — fall through to fallback injection below
        }
        // Inject fallback results if the search failed (HTTP 423) or returned an error
        Boolean hasResults = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var area = document.getElementById('searchResultsArea');" +
                "return area != null && area.querySelector('.search-result-item') != null;");
        if (!Boolean.TRUE.equals(hasResults)) {
            js("var area = document.getElementById('searchResultsArea');" +
               "area.style.display = 'block';" +
               "area.innerHTML = arguments[0];", FALLBACK_FULLTEXT_SEARCH_HTML);
        }
        saveElementScreenshot(searchPanel, "29-search-fulltext.png");
    }

    @Test
    @Order(30)
    void captureSearchSemantic() throws IOException {
        waitForTaxonomyReadyViaUI();
        // Embedding badge check — if embeddings load, we get real results; if not, fallback below
        try { waitForEmbeddingsReadyViaUI(); } catch (org.openqa.selenium.TimeoutException ignored) { }
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
        try {
            wait(10).until(d -> {
                WebElement results = d.findElement(By.id("searchResultsArea"));
                return results.isDisplayed() && !results.getText().isEmpty();
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Embedding model unavailable — fall through to fallback injection below
        }
        // Inject fallback results if embedding model was unavailable and produced no items
        Boolean hasResults = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var area = document.getElementById('searchResultsArea');" +
                "return area != null && area.querySelector('.search-result-item') != null;");
        if (!Boolean.TRUE.equals(hasResults)) {
            js("var area = document.getElementById('searchResultsArea');" +
               "area.style.display = 'block';" +
               "area.innerHTML = '" + FALLBACK_SEMANTIC_SEARCH_HTML + "';");
        }
        saveElementScreenshot(searchPanel, "30-search-semantic.png");
    }

    @Test
    @Order(31)
    void captureSearchHybrid() throws IOException {
        waitForTaxonomyReadyViaUI();
        try { waitForEmbeddingsReadyViaUI(); } catch (org.openqa.selenium.TimeoutException ignored) { }
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
        try {
            wait(10).until(d -> {
                WebElement results = d.findElement(By.id("searchResultsArea"));
                return results.isDisplayed() && !results.getText().isEmpty();
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Embedding model unavailable — fall through to fallback injection below
        }
        // Inject fallback results if embedding model was unavailable and produced no items
        Boolean hasResults = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var area = document.getElementById('searchResultsArea');" +
                "return area != null && area.querySelector('.search-result-item') != null;");
        if (!Boolean.TRUE.equals(hasResults)) {
            js("var area = document.getElementById('searchResultsArea');" +
               "area.style.display = 'block';" +
               "area.innerHTML = '" + FALLBACK_HYBRID_SEARCH_HTML + "';");
        }
        saveElementScreenshot(searchPanel, "31-search-hybrid.png");
    }

    @Test
    @Order(32)
    void captureSearchGraph() throws IOException {
        waitForTaxonomyReadyViaUI();
        try { waitForEmbeddingsReadyViaUI(); } catch (org.openqa.selenium.TimeoutException ignored) { }
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
        try {
            wait(10).until(d -> {
                WebElement results = d.findElement(By.id("searchResultsArea"));
                return results.isDisplayed() && !results.getText().isEmpty();
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Embedding model unavailable — fall through to fallback injection below
        }
        // Inject fallback results if embedding model was unavailable and produced no items
        Boolean hasResults = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var area = document.getElementById('searchResultsArea');" +
                "return area != null && area.querySelector('.search-result-item') != null;");
        if (!Boolean.TRUE.equals(hasResults)) {
            js("var area = document.getElementById('searchResultsArea');" +
               "area.style.display = 'block';" +
               "area.innerHTML = '" + FALLBACK_GRAPH_SEARCH_HTML + "';");
        }
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
        // Two-phase wait: first ensure the element exists in the DOM, then wait for CSS visibility
        wait(10).until(ExpectedConditions.presenceOfElementLocated(By.id("exportGroup")));
        wait(10).until(ExpectedConditions.visibilityOfElementLocated(By.id("exportGroup")));
        // Take a full-tab screenshot showing all export options
        saveScreenshot("33-export-tab.png");
    }

    @Test
    @Order(34)
    void captureDslEditorPanel() throws IOException {
        navigateToTab("dsl-editor");
        // Fetch DSL export and populate CodeMirror 6 editor (dslEditorContainer + window.dslCmView)
        js("fetch('/api/dsl/export').then(r => r.text()).then(t => {" +
           "  var view = window.dslCmView;" +
           "  if (view && t && t.trim().length > 0) {" +
           "    view.dispatch({changes: {from: 0, to: view.state.doc.length, insert: t}});" +
           "  }" +
           "});");
        try {
            wait(15).until(d -> {
                String content = (String) ((JavascriptExecutor) d).executeScript(
                        "var v = window.dslCmView; return v ? v.state.doc.toString() : '';");
                return content != null && !content.isBlank() && content.length() > 50;
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Fallback: inject representative DSL into CodeMirror editor
            js("var view = window.dslCmView;" +
               "if (view) view.dispatch({changes: {from: 0, to: view.state.doc.length, insert: '" + FALLBACK_DSL_TEXT + "'}});");
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

        try {
            wait(30).until(d -> {
                String html = (String) ((JavascriptExecutor) d).executeScript(
                        "var el = document.getElementById('graphResultsContent');" +
                        "return el ? el.innerHTML : '';");
                return html != null
                        && !html.contains("spinner-border")
                        && html.contains("graphViewTable");
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Graph query failed (HTTP 423) or returned no data — fall through to fallback below
        }
        // Check if the graph results show an error or only a single isolated node;
        // inject richer fallback content showing the accepted relation network
        Boolean hasRichResults = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var el = document.getElementById('graphResultsContent');" +
                "if (!el) return false;" +
                "var rows = el.querySelectorAll('.graph-element-row, tbody tr');" +
                "return rows.length >= 2;");
        if (!Boolean.TRUE.equals(hasRichResults)) {
            js("var el = document.getElementById('graphResultsContent');" +
               "if (el) el.innerHTML = arguments[0];", FALLBACK_DOWNSTREAM_GRAPH_HTML);
        }
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
        safeClick(By.id("viewSunburst"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));

        WebElement textarea = driver.findElement(By.id("businessText"));
        js("arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));", textarea);
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                textarea, REQUIREMENT_TEXT);
        WebElement analyzeBtn = driver.findElement(By.id("analyzeBtn"));
        js("arguments[0].scrollIntoView({behavior:'instant', block:'center'});", analyzeBtn);
        js("arguments[0].click();", analyzeBtn);

        wait(120).until(ExpectedConditions.textMatches(
                By.id("statusArea"), ANALYSIS_DONE_PATTERN));

        // Check that analysis actually succeeded — if it ended with error/unavailable,
        // the architectureViewPanel is never rendered and waiting for it would just time out.
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        Assumptions.assumeTrue(statusText.contains("complete"),
                "Skipping: analysis did not complete successfully (status: " + statusText + ")");

        navigateToTab("architecture");
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureViewPanel")));
        saveElementScreenshot(driver.findElement(By.id("architectureViewPanel")),
                "38-architecture-view-detailed.png");

        // Reset: switch back to list view and uncheck architecture view
        navigateToTab("analyze");
        safeClick(By.id("viewList"));
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
        safeClick(By.id("viewSunburst"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewSunburst"), "class", "btn-primary"));
        // Wait for the sunburst SVG to actually finish rendering
        wait(10).until(ExpectedConditions.attributeToBe(By.id("taxonomyTree"), "data-view-rendered", "sunburst"));
        saveScreenshot("39-scored-sunburst.png");
        // Reset to list view
        safeClick(By.id("viewList"));
    }

    @Test
    @Order(40)
    void captureDslWithRelations() throws IOException {
        navigateToTab("dsl-editor");
        // Fetch the DSL export which should now include relation blocks from accepted proposals
        js("fetch('/api/dsl/export').then(r => r.text()).then(t => {" +
           "  var view = window.dslCmView;" +
           "  if (view && t && t.trim().length > 0) {" +
           "    view.dispatch({changes: {from: 0, to: view.state.doc.length, insert: t}});" +
           "  }" +
           "});");
        try {
            wait(15).until(d -> {
                String content = (String) ((JavascriptExecutor) d).executeScript(
                        "var v = window.dslCmView; return v ? v.state.doc.toString() : '';");
                return content != null && !content.isBlank() && content.contains("relation");
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            // Fallback: inject DSL with relation blocks into CodeMirror editor
            js("var view = window.dslCmView;" +
               "if (view) view.dispatch({changes: {from: 0, to: view.state.doc.length, insert: '" + FALLBACK_DSL_TEXT + "'}});");
        }
        // Inject healthy status badges to avoid capturing "unavailable" / error states
        // (AI badge, Embeddings badge, Git status bar) in the documentation screenshot
        injectHealthyStatusBadges();
        saveScreenshot("40-dsl-editor-with-relations.png");
    }

    // ── Screenshots 41–44: Versions Tab, Git Status Bar, Context Bar ──────────

    @Test
    @Order(41)
    void captureVersionsTabHistory() throws IOException {
        navigateToTab("versions");
        // Wait for the version history timeline to load (contains timeline entries or "No versions")
        wait(15).until(d -> {
            String html = (String) ((JavascriptExecutor) d).executeScript(
                    "var el = document.getElementById('versionsTimeline');" +
                    "return el ? el.innerHTML : '';");
            return html != null && !html.contains("Loading version history");
        });
        // If the timeline shows an error (e.g., HTTP 423 from locked repository),
        // inject fallback timeline entries for a realistic documentation screenshot
        Boolean hasError = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var el = document.getElementById('versionsTimeline');" +
                "return el && el.innerHTML.indexOf('text-danger') >= 0;");
        if (Boolean.TRUE.equals(hasError)) {
            js("var el = document.getElementById('versionsTimeline');" +
               "if (el) el.innerHTML = arguments[0];", FALLBACK_TIMELINE_HTML);
        }
        // Ensure the History sub-tab is active
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'history');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-history') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        // Inject healthy status badges to avoid capturing "unavailable" / error states
        injectHealthyStatusBadges();
        saveScreenshot("41-versions-tab-history.png");
    }

    @Test
    @Order(42)
    void captureVersionsTabSaveVersion() throws IOException {
        navigateToTab("versions");
        // Switch to the Save Version sub-tab
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'save');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-save') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        // Fill in example data for the screenshot
        js("var t = document.getElementById('versionTitle');" +
           "if (t) { t.value = 'Baseline after review round 2'; t.dispatchEvent(new Event('input')); }" +
           "var d = document.getElementById('versionDescription');" +
           "if (d) { d.value = 'Incorporates feedback from architecture board meeting'; d.dispatchEvent(new Event('input')); }");
        saveScreenshot("42-versions-tab-save.png");
        // Reset to History sub-tab
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'history');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-history') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
    }

    @Test
    @Order(43)
    void captureGitStatusBar() throws IOException {
        // Navigate to the Analyze tab (default) so the full layout is visible
        navigateToTab("analyze");
        // Wait for the Git status bar to become visible (it polls /api/git/state)
        wait(15).until(d -> {
            WebElement bar = d.findElement(By.id("gitStatusBar"));
            return bar.isDisplayed() && !bar.getText().isEmpty();
        });
        saveElementScreenshot(driver.findElement(By.id("gitStatusBar")),
                "43-git-status-bar.png");
    }

    @Test
    @Order(44)
    void captureContextBar() throws IOException {
        // The context bar polls /api/context/current. Wait for it to render.
        wait(15).until(d -> {
            WebElement bar = d.findElement(By.id("contextBar"));
            String html = bar.getAttribute("innerHTML");
            return html != null && !html.isEmpty() && html.contains("workspace-bar");
        });
        saveElementScreenshot(driver.findElement(By.id("contextBar")),
                "44-context-bar.png");
    }

    // ── Screenshots 45–51: Workspace UI Elements ──────────────────────────────

    @Test
    @Order(45)
    void captureWorkspaceUserBadge() throws IOException {
        navigateToTab("analyze");
        // The workspace badge is populated by taxonomy-workspace-sync.js
        // and taxonomy-browse.js; force it visible with sample content
        js("var badge = document.getElementById('workspaceUserBadge');" +
           "if (badge) {" +
           "  badge.textContent = 'admin @ draft';" +
           "  badge.classList.remove('d-none');" +
           "}");
        wait(5).until(d -> {
            WebElement badge = d.findElement(By.id("workspaceUserBadge"));
            return badge.isDisplayed() && !badge.getText().isEmpty();
        });
        saveElementScreenshot(driver.findElement(By.cssSelector(".navbar")),
                "45-workspace-user-badge.png");
    }

    @Test
    @Order(46)
    void captureVariantCreationModal() throws IOException {
        navigateToTab("versions");
        // Open the Create Variant modal
        showModalViaDOM("createVariantModal");
        // Pre-fill the variant name input for the screenshot
        js("var input = document.getElementById('variantNameInput');" +
           "if (input) { input.value = 'feature-voice-services'; input.dispatchEvent(new Event('input')); }");
        saveScreenshot("46-variant-creation-modal.png");
        closeModalViaDOM("createVariantModal");
    }

    @Test
    @Order(47)
    void captureVariantsBrowserTab() throws IOException {
        navigateToTab("versions");
        // Switch to the Variants sub-tab
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'variants');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-variants') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        // Explicitly trigger variant loading — the pane was hidden (d-none) so auto-load may not fire
        js("if (window.TaxonomyVariants) { window.TaxonomyVariants.refresh(); }");
        // Wait for the variants browser to load
        wait(30).until(d -> {
            String html = (String) ((JavascriptExecutor) d).executeScript(
                    "var el = document.getElementById('variantsBrowser');" +
                    "return el ? el.innerHTML : '';");
            return html != null && html.length() > 0
                    && !html.contains("Loading variants");
        });
        saveScreenshot("47-variants-browser-tab.png");
        // Reset to History sub-tab
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'history');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-history') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
    }

    @Test
    @Order(48)
    void captureCompareModalBranches() throws IOException {
        // Open the compare modal
        showModalViaDOM("contextCompareModal");
        // Populate the branch dropdowns with sample data if they are empty
        js("var leftSel = document.getElementById('compareLeftBranch');" +
           "var rightSel = document.getElementById('compareRightBranch');" +
           "if (leftSel && leftSel.options.length <= 1) {" +
           "  leftSel.innerHTML = '<option value=\"draft\">draft</option>" +
           "<option value=\"feature-voice\">feature-voice</option>';" +
           "  leftSel.value = 'draft';" +
           "}" +
           "if (rightSel && rightSel.options.length <= 1) {" +
           "  rightSel.innerHTML = '<option value=\"draft\">draft</option>" +
           "<option value=\"feature-voice\">feature-voice</option>';" +
           "  rightSel.value = 'feature-voice';" +
           "}");
        saveScreenshot("48-compare-modal-branches.png");
        closeModalViaDOM("contextCompareModal");
    }

    @Test
    @Order(49)
    void captureCopyBackButton() throws IOException {
        navigateToTab("analyze");
        // Simulate a read-only context in the context bar with a Copy Back button
        js("var bar = document.getElementById('contextBar');" +
           "if (bar) {" +
           "  bar.innerHTML = '<div class=\"context-bar d-flex align-items-center gap-2 px-3 py-1 border-bottom bg-light\">" +
           "<span class=\"badge bg-warning text-dark\">READ-ONLY</span>" +
           "<strong>feature-voice</strong>" +
           "<code class=\"text-muted small\">a3f8c2d</code>" +
           "<span class=\"text-muted small\">from draft</span>" +
           "<span class=\"ms-auto d-flex align-items-center gap-1\">" +
           "<button class=\"btn btn-sm btn-outline-secondary\">&#8592; Back</button>" +
           "<button class=\"btn btn-sm btn-outline-primary\">&#8634; Origin</button>" +
           "<button class=\"btn btn-sm btn-outline-warning\">&#128228; Copy Back</button>" +
           "<button class=\"btn btn-sm btn-outline-success\">&#43; Variant</button>" +
           "<button class=\"btn btn-sm btn-outline-info\">&#8596; Compare</button>" +
           "</span></div>';" +
           "  bar.classList.remove('d-none');" +
           "}");
        saveElementScreenshot(driver.findElement(By.id("contextBar")),
                "49-copy-back-button.png");
    }

    @Test
    @Order(50)
    void captureReadOnlyModeBadge() throws IOException {
        navigateToTab("analyze");
        // Inject a READ-ONLY badge into the git status bar
        js("var bar = document.getElementById('gitStatusBar');" +
           "if (bar) {" +
           "  var span = document.createElement('span');" +
           "  span.id = 'readOnlyBadgeScreenshot';" +
           "  span.innerHTML = '<span class=\"git-sep\">│</span>" +
           "<span class=\"git-indicator\"><span class=\"badge bg-warning text-dark\" style=\"font-size:0.7rem;\">READ-ONLY</span></span>';" +
           "  bar.appendChild(span);" +
           "}");
        wait(5).until(d -> {
            WebElement bar = d.findElement(By.id("gitStatusBar"));
            return bar.isDisplayed() && bar.getText().contains("READ-ONLY");
        });
        saveElementScreenshot(driver.findElement(By.id("gitStatusBar")),
                "50-read-only-mode-badge.png");
        // Clean up injected badge
        js("var el = document.getElementById('readOnlyBadgeScreenshot'); if (el) el.remove();");
    }

    @Test
    @Order(51)
    void captureContextBarWithOrigin() throws IOException {
        navigateToTab("analyze");
        // Render a context bar showing origin info and return button
        js("var bar = document.getElementById('contextBar');" +
           "if (bar) {" +
           "  bar.innerHTML = '<div class=\"context-bar d-flex align-items-center gap-2 px-3 py-1 border-bottom bg-light\">" +
           "<span class=\"badge bg-success\">EDITABLE</span>" +
           "<strong>draft</strong>" +
           "<code class=\"text-muted small\">b7e4f1a</code>" +
           "<span class=\"text-muted small\">from feature-voice</span>" +
           "<span class=\"ms-auto d-flex align-items-center gap-1\">" +
           "<button class=\"btn btn-sm btn-outline-secondary\">&#8592; Back</button>" +
           "<button class=\"btn btn-sm btn-outline-primary\">&#8634; Origin</button>" +
           "<button class=\"btn btn-sm btn-outline-success\">&#43; Variant</button>" +
           "<button class=\"btn btn-sm btn-outline-info\">&#8596; Compare</button>" +
           "</span></div>';" +
           "  bar.classList.remove('d-none');" +
           "}");
        saveElementScreenshot(driver.findElement(By.id("contextBar")),
                "51-context-bar-with-origin.png");
    }

    // ── Screenshots 52–68: New GUI Dialogs ────────────────────────────────────

    @Test
    @Order(52)
    void captureMergeConflictModal() throws IOException {
        navigateToTab("analyze");
        showModalViaDOM("mergeConflictModal");
        // Populate conflict modal with sample content
        js("document.getElementById('conflictOursLabel').textContent = 'Ours (draft)';" +
           "document.getElementById('conflictTheirsLabel').textContent = 'Theirs (feature-voice)';" +
           "document.getElementById('conflictOursContent').textContent = " +
           "'element CP-1023 type Capability {\\n  title: \"Secure Voice\";\\n}';" +
           "document.getElementById('conflictTheirsContent').textContent = " +
           "'element CP-1023 type Capability {\\n  title: \"Secure Voice Service\";\\n}';" +
           "document.getElementById('conflictResolvedContent').value = " +
           "'element CP-1023 type Capability {\\n  title: \"Secure Voice Service\";\\n}';");
        saveScreenshot("52-merge-conflict-modal.png");
        closeModalViaDOM("mergeConflictModal");
    }

    @Test
    @Order(53)
    void captureMergeConflictResolved() throws IOException, InterruptedException {
        navigateToTab("analyze");
        // Simulate a toast notification for resolved conflict
        js("var toastEl = document.getElementById('operationToast');" +
           "var titleEl = document.getElementById('operationToastTitle');" +
           "var bodyEl = document.getElementById('operationToastBody');" +
           "if (toastEl && titleEl && bodyEl) {" +
           "  titleEl.textContent = '\\u2705 Merge Conflict Resolved';" +
           "  bodyEl.textContent = 'Content committed successfully: a3f8c2d';" +
           "  var header = toastEl.querySelector('.toast-header');" +
           "  if (header) { header.className = 'toast-header bg-success text-white'; }" +
           "  toastEl.classList.add('show');" +
           "}");
        Thread.sleep(1000);
        saveScreenshot("53-merge-conflict-resolved.png");
        js("var toastEl = document.getElementById('operationToast');" +
           "if (toastEl) toastEl.classList.remove('show');");
    }

    @Test
    @Order(54)
    void captureCherryPickConflictModal() throws IOException {
        navigateToTab("analyze");
        showModalViaDOM("mergeConflictModal");
        // Populate as cherry-pick conflict
        js("document.getElementById('mergeConflictModalLabel').textContent = " +
           "'\\u26A0\\uFE0F Cherry-Pick Conflict — Manual Resolution Required';" +
           "document.getElementById('conflictOursLabel').textContent = 'Ours (review)';" +
           "document.getElementById('conflictTheirsLabel').textContent = 'Theirs (commit a3f8c2d)';" +
           "document.getElementById('conflictOursContent').textContent = " +
           "'element CP-1023 type Capability {\\n  title: \"Secure Voice\";\\n}';" +
           "document.getElementById('conflictTheirsContent').textContent = " +
           "'element CP-1023 type Capability {\\n  title: \"Encrypted Voice\";\\n}';" +
           "document.getElementById('conflictResolvedContent').value = '';");
        saveScreenshot("54-cherry-pick-conflict-modal.png");
        closeModalViaDOM("mergeConflictModal");
        // Reset title
        js("document.getElementById('mergeConflictModalLabel').textContent = " +
           "'\\u26A0\\uFE0F Merge Conflict — Manual Resolution Required';");
    }

    @Test
    @Order(55)
    void captureSyncDivergedState() throws IOException {
        navigateToTab("versions");
        // Switch to Sync sub-tab
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'sync');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-sync') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        // Inject diverged state
        js("var panel = document.getElementById('syncStatePanel');" +
           "if (panel) {" +
           "  panel.innerHTML = '<div class=\"d-flex align-items-center gap-2 mb-2\">" +
           "<span class=\"badge bg-danger\">Diverged — both sides have changes</span>" +
           "<button class=\"btn btn-sm btn-outline-danger ms-2\">Resolve…</button>" +
           "</div>" +
           "<table class=\"table table-sm table-borderless mb-0\" style=\"max-width:400px;\">" +
           "<tr><td class=\"text-muted small\">Last synced</td><td class=\"small\">3/15/2026, 2:30:00 PM</td></tr>" +
           "<tr><td class=\"text-muted small\">Unpublished</td><td class=\"small\">3 commits</td></tr>" +
           "</table>';" +
           "}");
        saveScreenshot("55-sync-diverged-state.png");
    }

    @Test
    @Order(56)
    void captureSyncResolveModal() throws IOException {
        showModalViaDOM("syncDivergedModal");
        saveScreenshot("56-sync-resolve-modal.png");
        closeModalViaDOM("syncDivergedModal");
    }

    @Test
    @Order(57)
    void captureVariantDeleteConfirm() throws IOException {
        navigateToTab("versions");
        // Switch to Variants sub-tab and inject a variant with delete button
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'variants');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-variants') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        // Inject sample variant list with delete button
        js("var browser = document.getElementById('variantsBrowser');" +
           "if (browser) {" +
           "  browser.innerHTML = '<div class=\"list-group\">" +
           "<div class=\"list-group-item list-group-item-primary\">" +
           "<div class=\"d-flex justify-content-between align-items-center\">" +
           "<div><strong>draft</strong> <span class=\"badge bg-primary ms-1\">active</span> <span class=\"badge bg-secondary ms-1\">main</span></div>" +
           "<div class=\"d-flex gap-1\"><button class=\"btn btn-outline-info btn-sm py-0 px-1\" style=\"font-size:0.7rem;\">&#8596; Compare</button></div>" +
           "</div></div>" +
           "<div class=\"list-group-item\">" +
           "<div class=\"d-flex justify-content-between align-items-center\">" +
           "<div><strong>feature-voice</strong></div>" +
           "<div class=\"d-flex gap-1\">" +
           "<button class=\"btn btn-outline-primary btn-sm py-0 px-1\" style=\"font-size:0.7rem;\">&#8594; Switch</button>" +
           "<button class=\"btn btn-outline-info btn-sm py-0 px-1\" style=\"font-size:0.7rem;\">&#8596; Compare</button>" +
           "<button class=\"btn btn-outline-warning btn-sm py-0 px-1\" style=\"font-size:0.7rem;\">&#128256; Merge</button>" +
           "<button class=\"btn btn-outline-danger btn-sm py-0 px-1\" style=\"font-size:0.7rem;\">&#128465; Delete</button>" +
           "</div></div></div></div>';" +
           "}");
        saveScreenshot("57-variant-delete-confirm.png");
    }

    @Test
    @Order(58)
    void captureMergeSuccessToast() throws IOException, InterruptedException {
        navigateToTab("analyze");
        // Show a success toast for merge
        js("var toastEl = document.getElementById('operationToast');" +
           "var titleEl = document.getElementById('operationToastTitle');" +
           "var bodyEl = document.getElementById('operationToastBody');" +
           "if (toastEl && titleEl && bodyEl) {" +
           "  titleEl.textContent = '\\u2705 Merge Successful';" +
           "  bodyEl.textContent = 'Merged \"feature-voice\" into \"draft\" → a3f8c2d';" +
           "  var header = toastEl.querySelector('.toast-header');" +
           "  if (header) { header.className = 'toast-header bg-success text-white'; }" +
           "  toastEl.classList.add('show');" +
           "}");
        Thread.sleep(1000);
        saveScreenshot("58-merge-success-toast.png");
        js("var toastEl = document.getElementById('operationToast');" +
           "if (toastEl) toastEl.classList.remove('show');");
    }

    @Test
    @Order(59)
    void captureCherryPickSuccessToast() throws IOException, InterruptedException {
        navigateToTab("analyze");
        // Show a success toast for cherry-pick
        js("var toastEl = document.getElementById('operationToast');" +
           "var titleEl = document.getElementById('operationToastTitle');" +
           "var bodyEl = document.getElementById('operationToastBody');" +
           "if (toastEl && titleEl && bodyEl) {" +
           "  titleEl.textContent = '\\u2705 Cherry-Pick Successful';" +
           "  bodyEl.textContent = 'Applied onto \"review\" → b7e4f1a';" +
           "  var header = toastEl.querySelector('.toast-header');" +
           "  if (header) { header.className = 'toast-header bg-success text-white'; }" +
           "  toastEl.classList.add('show');" +
           "}");
        Thread.sleep(1000);
        saveScreenshot("59-cherry-pick-success-toast.png");
        js("var toastEl = document.getElementById('operationToast');" +
           "if (toastEl) toastEl.classList.remove('show');");
    }

    @Test
    @Order(60)
    void captureMergePreviewModal() throws IOException {
        navigateToTab("analyze");
        showModalViaDOM("mergePreviewModal");
        // Inject preview content
        js("var content = document.getElementById('mergePreviewContent');" +
           "if (content) {" +
           "  content.innerHTML = '<div class=\"alert alert-success mb-0\">" +
           "<strong>From:</strong> feature-voice <strong>→ Into:</strong> draft" +
           "<hr class=\"my-2\">Merge preview: commits to merge from \"feature-voice\" into \"draft\". No conflicts detected.</div>';" +
           "}" +
           "var btn = document.getElementById('mergePreviewProceedBtn');" +
           "if (btn) btn.classList.remove('d-none');");
        saveScreenshot("60-merge-preview-modal.png");
        closeModalViaDOM("mergePreviewModal");
    }

    @Test
    @Order(61)
    void captureMergePreviewFastForward() throws IOException {
        navigateToTab("analyze");
        showModalViaDOM("mergePreviewModal");
        // Inject fast-forward preview
        js("var content = document.getElementById('mergePreviewContent');" +
           "if (content) {" +
           "  content.innerHTML = '<div class=\"alert alert-success mb-0\">" +
           "<strong>From:</strong> feature-voice <strong>→ Into:</strong> draft" +
           "<hr class=\"my-2\">\\u2705 Fast-forward merge possible. No conflicts detected.</div>';" +
           "}" +
           "var btn = document.getElementById('mergePreviewProceedBtn');" +
           "if (btn) btn.classList.remove('d-none');");
        saveScreenshot("61-merge-preview-fast-forward.png");
        closeModalViaDOM("mergePreviewModal");
    }

    @Test
    @Order(62)
    void captureCherryPickPreviewModal() throws IOException {
        navigateToTab("analyze");
        showModalViaDOM("cherryPickPreviewModal");
        // Inject cherry-pick preview content
        js("var content = document.getElementById('cherryPickPreviewContent');" +
           "if (content) {" +
           "  content.innerHTML = '<div class=\"alert alert-success mb-0\">" +
           "<strong>Commit:</strong> a3f8c2d <strong>→ Branch:</strong> review" +
           "<hr class=\"my-2\">\\u2705 Cherry-pick looks clean. Apply commit a3f8c2d onto \"review\"?</div>';" +
           "}" +
           "var btn = document.getElementById('cherryPickPreviewProceedBtn');" +
           "if (btn) btn.classList.remove('d-none');");
        saveScreenshot("62-cherry-pick-preview-modal.png");
        closeModalViaDOM("cherryPickPreviewModal");
    }

    @Test
    @Order(63)
    void captureSyncTabUpToDate() throws IOException {
        navigateToTab("versions");
        // Switch to Sync sub-tab
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'sync');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-sync') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        js("var panel = document.getElementById('syncStatePanel');" +
           "if (panel) {" +
           "  panel.innerHTML = '<div class=\"d-flex align-items-center gap-2 mb-2\">" +
           "<span class=\"badge bg-success\">Up to date</span></div>" +
           "<table class=\"table table-sm table-borderless mb-0\" style=\"max-width:400px;\">" +
           "<tr><td class=\"text-muted small\">Last synced</td><td class=\"small\">3/15/2026, 2:30:00 PM</td></tr>" +
           "<tr><td class=\"text-muted small\">Synced commit</td><td class=\"small\"><code>a3f8c2d</code></td></tr>" +
           "</table>';" +
           "}");
        saveScreenshot("63-sync-tab-up-to-date.png");
    }

    @Test
    @Order(64)
    void captureSyncTabAhead() throws IOException {
        navigateToTab("versions");
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'sync');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-sync') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        js("var panel = document.getElementById('syncStatePanel');" +
           "if (panel) {" +
           "  panel.innerHTML = '<div class=\"d-flex align-items-center gap-2 mb-2\">" +
           "<span class=\"badge bg-info text-dark\">3 unpublished commits</span></div>" +
           "<table class=\"table table-sm table-borderless mb-0\" style=\"max-width:400px;\">" +
           "<tr><td class=\"text-muted small\">Unpublished</td><td class=\"small\">3 commits</td></tr>" +
           "</table>';" +
           "}");
        saveScreenshot("64-sync-tab-ahead.png");
    }

    @Test
    @Order(65)
    void captureSyncTabBehind() throws IOException {
        navigateToTab("versions");
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'sync');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-sync') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        js("var panel = document.getElementById('syncStatePanel');" +
           "if (panel) {" +
           "  panel.innerHTML = '<div class=\"d-flex align-items-center gap-2 mb-2\">" +
           "<span class=\"badge bg-warning text-dark\">Behind shared repository</span></div>" +
           "<table class=\"table table-sm table-borderless mb-0\" style=\"max-width:400px;\">" +
           "<tr><td class=\"text-muted small\">Last synced</td><td class=\"small\">3/14/2026, 10:00:00 AM</td></tr>" +
           "</table>';" +
           "}");
        saveScreenshot("65-sync-tab-behind.png");
    }

    @Test
    @Order(66)
    void captureVersionsTimeline() throws IOException {
        navigateToTab("versions");
        // Switch to History sub-tab
        js("document.querySelectorAll('[data-versions-tab]').forEach(function(l) {" +
           "  l.classList.toggle('active', l.getAttribute('data-versions-tab') === 'history');" +
           "});" +
           "document.querySelectorAll('.versions-sub-pane').forEach(function(p) {" +
           "  if (p.id === 'versions-history') { p.classList.remove('d-none'); }" +
           "  else { p.classList.add('d-none'); }" +
           "});");
        // Wait for the timeline to be populated
        wait(10).until(d -> {
            String html = (String) ((JavascriptExecutor) d).executeScript(
                    "var el = document.getElementById('versionsTimeline');" +
                    "return el ? el.innerHTML : '';");
            return html != null && !html.contains("Loading version history");
        });
        saveScreenshot("66-versions-timeline.png");
    }

    @Test
    @Order(67)
    void captureVersionRestoreConfirm() throws IOException {
        navigateToTab("versions");
        // Inject a simulated restore confirmation dialog
        js("var timeline = document.getElementById('versionsTimeline');" +
           "if (timeline) {" +
           "  var card = document.createElement('div');" +
           "  card.className = 'alert alert-warning mt-2';" +
           "  card.innerHTML = '<strong>Restore Version?</strong><br>" +
           "This will create a new commit restoring the DSL content from commit <code>a3f8c2d</code>.<br>" +
           "<div class=\"mt-2\"><button class=\"btn btn-sm btn-warning me-1\">\\u21A9 Restore</button>" +
           "<button class=\"btn btn-sm btn-secondary\">Cancel</button></div>';" +
           "  timeline.prepend(card);" +
           "}");
        saveScreenshot("67-version-restore-confirm.png");
    }

    @Test
    @Order(68)
    void captureDiffView() throws IOException {
        navigateToTab("dsl-editor");
        // Inject a diff view into the DSL editor area
        js("var area = document.getElementById('dslDiffOutput');" +
           "if (area) {" +
           "  area.textContent = '--- a/architecture.taxdsl\\n" +
           "+++ b/architecture.taxdsl\\n" +
           "@@ -1,5 +1,6 @@\\n" +
           " element CP-1023 type Capability {\\n" +
           "-  title: \"Secure Voice\";\\n" +
           "+  title: \"Secure Voice Service\";\\n" +
           "+  description: \"Encrypted real-time voice communication\";\\n" +
           " }\\n';" +
           "  area.style.display = 'block';" +
           "}");
        saveScreenshot("68-diff-view.png");
    }

    // ── Screenshot 69: Scored Decision Map (complements the empty-state 08) ───

    @Test
    @Order(69)
    void captureDecisionMapScored() throws IOException {
        // Ensure analysis is complete so the decision map has scored nodes to display
        navigateToTab("analyze");
        String statusText = driver.findElement(By.id("statusArea")).getText().toLowerCase();
        if (!statusText.contains("complete")) {
            forceNonInteractiveMode();
            runAnalysis();
        }
        // Switch to the decision map view — with scores, it renders the D3 treemap + decision table
        safeClick(By.id("viewDecision"));
        wait(5).until(ExpectedConditions.attributeContains(By.id("viewDecision"), "class", "btn-primary"));
        wait(10).until(ExpectedConditions.attributeToBe(By.id("taxonomyTree"), "data-view-rendered", "decision"));
        // Brief pause to let the D3 treemap rendering complete
        waitForD3Transition();
        saveScreenshot("69-decision-map-scored.png");
        safeClick(By.id("viewList"));
    }
}
