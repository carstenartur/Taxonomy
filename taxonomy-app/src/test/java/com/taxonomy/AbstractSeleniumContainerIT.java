package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for Selenium-based container integration tests that verify the
 * full UI renders correctly and REST endpoints work when the application is
 * backed by different databases.
 * <p>
 * Subclasses must implement {@link #createNetwork()}, {@link #createAppContainer(Network)},
 * and optionally {@link #createDbContainer(Network)} to provide the database
 * and application containers for each database backend.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractSeleniumContainerIT {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** HTTP Basic auth header value for the default admin user. */
    private static final String BASIC_AUTH = "Basic " +
            Base64.getEncoder().encodeToString("admin:admin".getBytes());

    private Network network;
    private GenericContainer<?> dbContainer;
    private GenericContainer<?> appContainer;
    private BrowserWebDriverContainer<?> chrome;
    private WebDriver driver;

    /** Create the Docker network for inter-container communication. */
    protected Network createNetwork() {
        return Network.newNetwork();
    }

    /**
     * Optionally create and return a database container. Return {@code null}
     * for HSQLDB (no external database needed). The container will be started
     * automatically.
     */
    protected GenericContainer<?> createDbContainer(Network net) {
        return null;
    }

    /**
     * Create the application container with appropriate database env vars.
     * The container should be configured but <em>not started</em>.
     */
    protected abstract GenericContainer<?> createAppContainer(Network net);

    @BeforeAll
    void startContainers() {
        network = createNetwork();

        dbContainer = createDbContainer(network);
        if (dbContainer != null) {
            dbContainer.start();
        }

        appContainer = createAppContainer(network);
        appContainer.start();

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

        // Wait for the main page to load after login
        driver.get("http://app:8080/");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        new WebDriverWait(driver, Duration.ofSeconds(60))
                .until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("#taxonomyTree .tax-node")));
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
        if (dbContainer != null) dbContainer.stop();
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
    void taxonomyEndpointReturns200() throws Exception {
        HttpResponse<String> resp = httpGet("/api/taxonomy");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(8);
    }

    @Test
    @Order(3)
    void searchEndpointWorks() throws Exception {
        HttpResponse<String> resp = httpGet("/api/search?q=BP");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThan(0);
    }

    @Test
    @Order(4)
    void aiStatusEndpointWorks() throws Exception {
        HttpResponse<String> resp = httpGet("/api/ai-status");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.has("available")).isTrue();
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
    void taxonomyTreeContainsExpectedRootNodes() {
        List<WebElement> toggleButtons = driver.findElements(By.cssSelector(".tax-toggle"));
        assertThat(toggleButtons).isNotEmpty();
    }

    @Test
    @Order(12)
    void businessRequirementTextareaIsPresent() {
        WebElement textarea = driver.findElement(By.id("businessText"));
        assertThat(textarea.isDisplayed()).isTrue();
    }

    @Test
    @Order(13)
    void analyzeButtonIsPresent() {
        WebElement btn = driver.findElement(By.id("analyzeBtn"));
        assertThat(btn.isDisplayed()).isTrue();
    }

    @Test
    @Order(14)
    void pageHasCorrectTitle() {
        assertThat(driver.getTitle()).isNotEmpty();
    }

    // ── Help-Tab tests ───────────────────────────────────────────────────────

    @Test
    @Order(15)
    void helpTabLoadsAndDisplaysToc() {
        WebElement helpTab = driver.findElement(
                By.cssSelector("#mainNavTabs .nav-link[data-page='help']"));
        helpTab.click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("helpTocList")));

        List<WebElement> tocItems = driver.findElements(
                By.cssSelector("#helpTocList .help-toc-item"));
        assertThat(tocItems).isNotEmpty();
    }

    @Test
    @Order(16)
    void helpTabLoadsDocument() {
        WebElement firstItem = driver.findElement(
                By.cssSelector("#helpTocList .help-toc-item"));
        firstItem.click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("#helpDocBody h1, #helpDocBody h2")));

        WebElement docBody = driver.findElement(By.id("helpDocBody"));
        assertThat(docBody.getText()).isNotEmpty();
    }

    // ── i18n Language Switch tests ───────────────────────────────────────────

    @Test
    @Order(17)
    void languageSwitchToGermanChangesUiLabels() {
        // Navigate to home first (in English by default)
        driver.get("http://app:8080/");
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));

        // Get English label for the Analyze tab
        WebElement analyzeTab = driver.findElement(
                By.cssSelector("#mainNavTabs .nav-link[data-page='analyze']"));
        String englishText = analyzeTab.getText().trim();

        // Switch to German via lang selector
        org.openqa.selenium.support.ui.Select langSelect =
                new org.openqa.selenium.support.ui.Select(
                        driver.findElement(By.id("langSelector")));
        langSelect.selectByValue("de");

        // Wait for page reload with German locale
        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("#taxonomyTree .tax-node")));

        // Verify German label is different from English
        WebElement analyzeTabDe = driver.findElement(
                By.cssSelector("#mainNavTabs .nav-link[data-page='analyze']"));
        String germanText = analyzeTabDe.getText().trim();
        assertThat(germanText).isNotEqualTo(englishText);
    }

    @Test
    @Order(18)
    void helpTabServesGermanDocsWhenLocaleIsDe() {
        // Should still be in German locale from previous test
        // Navigate to Help tab
        WebElement helpTab = driver.findElement(
                By.cssSelector("#mainNavTabs .nav-link[data-page='help']"));
        helpTab.click();

        // Wait for TOC to load
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("#helpTocList .help-toc-item")));

        // Click first TOC entry
        driver.findElement(By.cssSelector("#helpTocList .help-toc-item")).click();

        // Wait for document to render
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("#helpDocBody h1, #helpDocBody h2")));

        // Verify document contains German text (docs/de/ has German translations)
        String content = driver.findElement(By.id("helpDocBody")).getText();
        assertThat(content).isNotEmpty();
        // German docs should contain typical German words
        assertThat(content).containsAnyOf(
                "Benutzer", "Analyse", "Anforderung", "Taxonomie",
                "Architektur", "Anleitung", "Konfiguration", "Übersicht");
    }

    @Test
    @Order(19)
    void switchBackToEnglish() {
        // Switch back to English to not affect any further tests
        org.openqa.selenium.support.ui.Select langSelect =
                new org.openqa.selenium.support.ui.Select(
                        driver.findElement(By.id("langSelector")));
        langSelect.selectByValue("en");

        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("#taxonomyTree .tax-node")));

        WebElement analyzeTab = driver.findElement(
                By.cssSelector("#mainNavTabs .nav-link[data-page='analyze']"));
        assertThat(analyzeTab.getText().trim()).contains("Analyze");
    }
}
