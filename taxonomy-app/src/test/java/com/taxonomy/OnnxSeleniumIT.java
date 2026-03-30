package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selenium-based integration tests for the LOCAL_ONNX pipeline.
 * Validates that AI status badges, semantic search, and result navigation
 * work correctly when the application is running with local ONNX embeddings.
 * <p>
 * Opt-in: only runs when the {@code runOnnxTests} system property is set
 * <em>and</em> the embedding model directory is available.
 * Run with: {@code mvn verify -DrunOnnxTests -Dit.test=OnnxSeleniumIT}
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "runOnnxTests", matches = ".*")
class OnnxSeleniumIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** HTTP Basic auth header value for the default admin user. */
    private static final String BASIC_AUTH = "Basic " +
            Base64.getEncoder().encodeToString("admin:admin".getBytes());

    private Network network;
    private GenericContainer<?> appContainer;
    private BrowserWebDriverContainer<?> chrome;
    private WebDriver driver;

    @BeforeAll
    void startContainers() {
        network = Network.newNetwork();

        appContainer = new GenericContainer<>(ContainerTestUtils.sharedImage())
                .withNetwork(network)
                .withNetworkAliases("app")
                .withExposedPorts(8080)
                .withEnv("LLM_PROVIDER", "LOCAL_ONNX")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "true")
                .withStartupTimeout(Duration.ofSeconds(180))
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                        .forHttp("/actuator/health")
                        .forStatusCode(200)
                        .forPort(8080));

        // If a local model directory is available, bind-mount it into the container
        String modelDir = System.getenv("TAXONOMY_EMBEDDING_MODEL_DIR");
        if (modelDir != null && !modelDir.isBlank() && Files.isDirectory(Path.of(modelDir))) {
            appContainer.withFileSystemBind(modelDir, "/models",
                    org.testcontainers.containers.BindMode.READ_ONLY);
            appContainer.withEnv("TAXONOMY_EMBEDDING_MODEL_DIR", "/models");
        }

        appContainer.start();

        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments(
                "--disable-features=HttpsUpgrades,HttpsFirstMode,HttpsFirstModeV2,"
                        + "HttpsFirstBalancedMode,HttpsFirstModeForTypedNavigations,"
                        + "HttpsFirstModeInterstitial",
                "--unsafely-treat-insecure-origin-as-secure=http://app:8080",
                "--ignore-certificate-errors",
                "--allow-running-insecure-content");
        chromeOptions.setAcceptInsecureCerts(true);
        chrome = new BrowserWebDriverContainer<>()
                .withNetwork(network)
                .withCapabilities(chromeOptions);
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

        // Wait for the main page to load after login
        driver.get("http://app:8080/");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        new WebDriverWait(driver, Duration.ofSeconds(60))
                .until(d -> {
                    String rendered = d.findElement(By.id("taxonomyTree"))
                            .getAttribute("data-view-rendered");
                    return rendered != null && !rendered.isEmpty();
                });

        // Dismiss onboarding overlay if present
        List<WebElement> dismissBtns = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissBtns.isEmpty()) {
            dismissBtns.get(0).click();
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
    }

    @AfterAll
    void stopContainers() {
        if (chrome != null) chrome.stop();
        if (appContainer != null) appContainer.stop();
        if (network != null) network.close();
    }

    private String baseUrl() {
        return "http://" + appContainer.getHost() + ":" + appContainer.getMappedPort(8080);
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── REST API tests ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void containerStartsSuccessfully() {
        assertThat(appContainer.isRunning()).isTrue();
    }

    @Test
    @Order(2)
    void embeddingStatusReportsAvailable() throws Exception {
        HttpResponse<String> resp = httpGet("/api/embedding/status");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.has("enabled")).isTrue();
        assertThat(body.get("enabled").booleanValue()).isTrue();
    }

    @Test
    @Order(3)
    void aiStatusEndpointReturnsLimited() throws Exception {
        HttpResponse<String> resp = httpGet("/api/ai-status");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.get("level").textValue()).isEqualTo("LIMITED");
        assertThat(body.get("available").booleanValue()).isTrue();
        assertThat(body.get("limited").booleanValue()).isTrue();
    }

    // ── Selenium UI tests ────────────────────────────────────────────────────

    @Test
    @Order(10)
    void homePageLoadsWithTaxonomyTree() {
        WebElement tree = driver.findElement(By.id("taxonomyTree"));
        assertThat(tree.isDisplayed()).isTrue();
        List<WebElement> nodes = tree.findElements(By.cssSelector(".tax-node"));
        assertThat(nodes).isNotEmpty();
    }

    @Test
    @Order(11)
    void aiStatusBadgeShowsLimited() {
        // Wait for the AI status badge to be populated (async fetch)
        WebElement badge = new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(d -> {
                    WebElement b = d.findElement(By.id("aiStatusBadge"));
                    String text = b.getText();
                    return (text != null && !text.isEmpty() && !text.contains("Unknown")) ? b : null;
                });
        String badgeText = badge.getText();
        assertThat(badgeText).containsIgnoringCase("LIMITED");
    }

    @Test
    @Order(20)
    void semanticSearchViaUIReturnsResults() {
        navigateToAnalyzeTab();
        waitForEmbeddingsReady();

        // Open search panel
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        if (searchPanel.getAttribute("open") == null) {
            searchPanel.findElement(By.tagName("summary")).click();
        }

        // Set search mode to semantic
        ((JavascriptExecutor) driver).executeScript(
                "document.getElementById('searchModeSelect').value = 'semantic';");

        // Enter search query and trigger search
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = ''; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = 'communication and collaboration';"
                        + "arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        driver.findElement(By.id("searchBtn")).click();

        // Wait for search results
        waitForSearchResults();

        // Verify results are present
        List<WebElement> items = driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"));
        assertThat(items).isNotEmpty();
    }

    @Test
    @Order(21)
    void searchResultClickNavigatesToNode() {
        // Results should still be visible from the previous test
        // If not, re-run the search
        List<WebElement> items = driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"));
        if (items.isEmpty()) {
            navigateToAnalyzeTab();
            waitForEmbeddingsReady();
            WebElement searchPanel = driver.findElement(By.id("searchPanel"));
            if (searchPanel.getAttribute("open") == null) {
                searchPanel.findElement(By.tagName("summary")).click();
            }
            ((JavascriptExecutor) driver).executeScript(
                    "document.getElementById('searchModeSelect').value = 'semantic';");
            WebElement searchInput = driver.findElement(By.id("searchInput"));
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].value = 'communication and collaboration';"
                            + "arguments[0].dispatchEvent(new Event('input'));",
                    searchInput);
            driver.findElement(By.id("searchBtn")).click();
            waitForSearchResults();
            items = driver.findElements(
                    By.cssSelector("#searchResultsArea .search-result-item"));
        }

        assertThat(items).isNotEmpty();

        // Get the code of the first result and click it
        String code = items.get(0).getAttribute("data-code");
        assertThat(code).isNotNull().isNotEmpty();
        items.get(0).click();

        // Verify that the tree has navigated (the node should be highlighted)
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> {
                    List<WebElement> highlighted = d.findElements(
                            By.cssSelector(".tax-node.highlight, .tax-node.selected, .tax-node-active"));
                    if (!highlighted.isEmpty()) return true;
                    // Also check if the tree scrolled to show the node
                    WebElement tree = d.findElement(By.id("taxonomyTree"));
                    return tree.getText().contains(code);
                });
    }

    @Test
    @Order(22)
    void fullTextSearchWorks() {
        navigateToAnalyzeTab();
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        if (searchPanel.getAttribute("open") == null) {
            searchPanel.findElement(By.tagName("summary")).click();
        }

        // Set search mode to fulltext
        ((JavascriptExecutor) driver).executeScript(
                "document.getElementById('searchModeSelect').value = 'fulltext';");

        WebElement searchInput = driver.findElement(By.id("searchInput"));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = 'BP'; arguments[0].dispatchEvent(new Event('input'));",
                searchInput);
        driver.findElement(By.id("searchBtn")).click();

        waitForSearchResults();

        List<WebElement> items = driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"));
        assertThat(items).isNotEmpty();
    }

    // ── Helper methods ───────────────────────────────────────────────────────

    private void navigateToAnalyzeTab() {
        WebElement analyzeTab = driver.findElement(
                By.cssSelector("#mainNavTabs .nav-link[data-page='analyze']"));
        if (!analyzeTab.getAttribute("class").contains("active")) {
            analyzeTab.click();
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.presenceOfElementLocated(By.id("searchPanel")));
        }
    }

    /**
     * Waits for embeddings to be ready by monitoring the nav-bar embedding badge.
     * The badge text changes from "Embeddings: unavailable" to
     * "Embeddings: N nodes" once the embedding index is built.
     */
    private void waitForEmbeddingsReady() {
        new WebDriverWait(driver, Duration.ofSeconds(180))
                .until(d -> {
                    try {
                        WebElement badge = d.findElement(By.id("embeddingStatusBadge"));
                        String text = badge.getText();
                        return text != null && text.contains("Embeddings:")
                                && !text.contains("unavailable");
                    } catch (org.openqa.selenium.NoSuchElementException e) {
                        return false;
                    }
                });
    }

    /**
     * Waits for search results to appear in the search results area.
     */
    private void waitForSearchResults() {
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(d -> {
                    WebElement area = d.findElement(By.id("searchResultsArea"));
                    if (!area.isDisplayed()) return false;
                    List<WebElement> items = area.findElements(
                            By.cssSelector(".search-result-item"));
                    return !items.isEmpty();
                });
    }
}
