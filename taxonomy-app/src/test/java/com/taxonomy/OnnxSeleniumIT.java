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
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Selenium-based end-to-end verification for the LOCAL_ONNX pipeline. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "runOnnxTests", matches = ".*")
class OnnxSeleniumIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration SEMANTIC_INDEX_TIMEOUT = Duration.ofMinutes(10);
    private static final String SEMANTIC_QUERY = "communication and collaboration";
    private static final String BASIC_AUTH = "Basic "
            + Base64.getEncoder().encodeToString(
                    ("admin:" + ContainerTestUtils.TEST_ADMIN_PASSWORD)
                            .getBytes(StandardCharsets.UTF_8));

    private Network network;
    private GenericContainer<?> appContainer;
    private ContainerTestUtils.BrowserSession browserSession;
    private WebDriver driver;

    @BeforeAll
    void startContainers() throws Exception {
        network = Network.newNetwork();

        appContainer = ContainerTestUtils.appContainer(network)
                .withEnv("LLM_PROVIDER", "LOCAL_ONNX")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "true");

        String modelDirectory = System.getenv("TAXONOMY_EMBEDDING_MODEL_DIR");
        if (modelDirectory != null
                && !modelDirectory.isBlank()
                && Files.isDirectory(Path.of(modelDirectory))) {
            appContainer.withFileSystemBind(
                    modelDirectory,
                    "/models",
                    org.testcontainers.containers.BindMode.READ_ONLY);
            appContainer.withEnv("TAXONOMY_EMBEDDING_MODEL_DIR", "/models");
        }

        appContainer.start();
        waitForSemanticBackendReady();

        browserSession = ContainerTestUtils.startBrowser(network);
        driver = browserSession.driver();
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900));

        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.name("username")));
        driver.findElement(By.name("username")).sendKeys("admin");
        driver.findElement(By.name("password"))
                .sendKeys(ContainerTestUtils.TEST_ADMIN_PASSWORD);
        driver.findElement(By.cssSelector(
                "button[type='submit'], input[type='submit']")).click();

        driver.get(ContainerTestUtils.APP_ORIGIN + "/");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        new WebDriverWait(driver, Duration.ofSeconds(120))
                .until(currentDriver -> {
                    String rendered = currentDriver.findElement(By.id("taxonomyTree"))
                            .getAttribute("data-view-rendered");
                    return rendered != null && !rendered.isEmpty();
                });

        List<WebElement> dismissButtons = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissButtons.isEmpty()) {
            dismissButtons.getFirst().click();
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.invisibilityOfElementLocated(
                            By.id("onboardingOverlay")));
        }
    }

    @AfterAll
    void stopContainers() throws Exception {
        ContainerTestUtils.closeAll(browserSession, appContainer, network);
    }

    private String baseUrl() {
        return "http://" + appContainer.getHost() + ":"
                + appContainer.getMappedPort(8080);
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void containerStartsSuccessfully() {
        assertThat(appContainer.isRunning()).isTrue();
    }

    @Test
    @Order(2)
    void embeddingStatusReportsAvailable() throws Exception {
        HttpResponse<String> response = httpGet("/api/embedding/status");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.has("enabled")).isTrue();
        assertThat(body.get("enabled").booleanValue()).isTrue();
        assertThat(body.get("available").booleanValue()).isTrue();
    }

    @Test
    @Order(3)
    void aiStatusEndpointReturnsLimited() throws Exception {
        HttpResponse<String> response = httpGet("/api/ai-status");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("level").textValue()).isEqualTo("LIMITED");
        assertThat(body.get("available").booleanValue()).isTrue();
        assertThat(body.get("limited").booleanValue()).isTrue();
    }

    @Test
    @Order(4)
    void semanticSearchEndpointReturnsResults() throws Exception {
        HttpResponse<String> response = semanticSearch();
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThan(0);
    }

    @Test
    @Order(10)
    void homePageLoadsWithTaxonomyTree() {
        WebElement tree = driver.findElement(By.id("taxonomyTree"));
        assertThat(tree.isDisplayed()).isTrue();
        assertThat(tree.findElements(By.cssSelector(".tax-node"))).isNotEmpty();
    }

    @Test
    @Order(11)
    void aiStatusBadgeShowsLimited() {
        WebElement badge = new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(currentDriver -> {
                    WebElement candidate = currentDriver.findElement(By.id("aiStatusBadge"));
                    String text = candidate.getText();
                    return text != null
                            && !text.isEmpty()
                            && !text.contains("Unknown")
                            ? candidate
                            : null;
                });
        assertThat(badge.getText()).containsIgnoringCase("LIMITED");
    }

    @Test
    @Order(20)
    void semanticSearchViaUiReturnsResults() {
        navigateToAnalyzeTab();
        waitForEmbeddingsReady();
        executeUiSearch("semantic", SEMANTIC_QUERY);

        waitForSearchResults();
        assertThat(driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"))).isNotEmpty();
    }

    @Test
    @Order(21)
    void searchResultClickRevealsAndHighlightsExactNode() {
        List<WebElement> items = driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"));
        if (items.isEmpty()) {
            navigateToAnalyzeTab();
            waitForEmbeddingsReady();
            executeUiSearch("semantic", SEMANTIC_QUERY);
            waitForSearchResults();
            items = driver.findElements(
                    By.cssSelector("#searchResultsArea .search-result-item"));
        }

        assertThat(items).isNotEmpty();
        String code = items.getFirst().getAttribute("data-code");
        assertThat(code).isNotNull().isNotEmpty();
        items.getFirst().click();

        WebElement highlightedHeader = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(currentDriver -> currentDriver.findElements(
                                By.cssSelector(".tax-node-header.search-highlight"))
                        .stream()
                        .filter(WebElement::isDisplayed)
                        .filter(header -> code.equals(header.findElement(By.xpath(".."))
                                .getAttribute("data-code")))
                        .findFirst()
                        .orElse(null));

        assertThat(highlightedHeader).isDisplayed();
        assertThat(highlightedHeader.findElement(By.xpath(".."))
                .getAttribute("data-code")).isEqualTo(code);
    }

    @Test
    @Order(22)
    void fullTextSearchWorks() {
        navigateToAnalyzeTab();
        executeUiSearch("fulltext", "Business Processes");

        waitForSearchResults();
        assertThat(driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"))).isNotEmpty();
    }

    private HttpResponse<String> semanticSearch() throws Exception {
        return httpGet("/api/search/semantic?q="
                + URLEncoder.encode(SEMANTIC_QUERY, StandardCharsets.UTF_8)
                + "&maxResults=20");
    }

    private void waitForSemanticBackendReady() throws Exception {
        long deadline = System.nanoTime() + SEMANTIC_INDEX_TIMEOUT.toNanos();
        int lastStatus = -1;
        String lastBody = "";

        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = semanticSearch();
            lastStatus = response.statusCode();
            lastBody = response.body();
            if (lastStatus == 200) {
                JsonNode body = MAPPER.readTree(lastBody);
                if (body.isArray() && body.size() > 0) {
                    return;
                }
            }
            Thread.sleep(1_000);
        }

        throw new AssertionError(
                "LOCAL_ONNX semantic index did not become usable within "
                        + SEMANTIC_INDEX_TIMEOUT
                        + "; last status=" + lastStatus
                        + ", last body=" + lastBody);
    }

    private void navigateToAnalyzeTab() {
        WebElement analyzeTab = driver.findElement(
                By.cssSelector("#mainNavTabs .nav-link[data-page='analyze']"));
        if (!analyzeTab.getAttribute("class").contains("active")) {
            analyzeTab.click();
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.presenceOfElementLocated(By.id("searchPanel")));
        }
    }

    private void waitForEmbeddingsReady() {
        ((JavascriptExecutor) driver).executeScript(
                "window.TaxonomySearch.checkEmbeddingStatus();");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(currentDriver -> currentDriver.findElement(
                        By.cssSelector("#searchModeSelect option[value='semantic']"))
                        .isEnabled());
    }

    private void executeUiSearch(String mode, String query) {
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        if (searchPanel.getAttribute("open") == null) {
            searchPanel.findElement(By.tagName("summary")).click();
        }

        ((JavascriptExecutor) driver).executeScript(
                "document.getElementById('searchModeSelect').value = arguments[0];",
                mode);
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = arguments[1];"
                        + "arguments[0].dispatchEvent(new Event('input'));",
                searchInput,
                query);
        driver.findElement(By.id("searchBtn")).click();
    }

    private void waitForSearchResults() {
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(currentDriver -> {
                    WebElement area = currentDriver.findElement(By.id("searchResultsArea"));
                    return area.isDisplayed()
                            && !area.findElements(By.cssSelector(".search-result-item"))
                            .isEmpty();
                });
    }
}
