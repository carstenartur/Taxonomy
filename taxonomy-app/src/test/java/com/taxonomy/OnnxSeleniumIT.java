package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stufe 5: Selenium + Web UI — Embedding in the browser.
 * <p>
 * Proves that a <em>user in the browser</em> can use the semantic search and that the
 * embedding status badge shows availability. Real browser interaction via Selenium
 * against the containerised application with {@code LLM_PROVIDER=LOCAL_ONNX}.
 * <p>
 * The container uses {@code eclipse-temurin:21-jre} (Debian / glibc), which is the
 * production-like image. This test proves that {@code libonnxruntime.so} links correctly
 * against glibc in the Debian-based Temurin image and that the full UI works end-to-end.
 * <p>
 * Run with: {@code mvn verify -Dit.test=OnnxSeleniumIT}
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnnxSeleniumIT {

    private Network network;
    private GenericContainer<?> appContainer;
    private BrowserWebDriverContainer<?> chrome;
    private WebDriver driver;

    @BeforeAll
    void startContainers() {
        network = Network.newNetwork();

        appContainer = ContainerTestUtils.appContainer(network)
                .withEnv("LLM_PROVIDER", "LOCAL_ONNX")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "true");
        appContainer.start();

        ChromeOptions chromeOptions = new ChromeOptions();
        // Chrome 145+ HTTPS-First Mode: disable all known upgrade feature variants
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
    }

    @AfterAll
    void stopContainers() {
        if (chrome != null) chrome.stop();
        if (appContainer != null) appContainer.stop();
        if (network != null) network.close();
    }

    private WebDriverWait wait(int seconds) {
        return new WebDriverWait(driver, Duration.ofSeconds(seconds));
    }

    private void js(String script, Object... args) {
        ((JavascriptExecutor) driver).executeScript(script, args);
    }

    // ── Test 5.1 ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void appLoadsLoginPage() {
        driver.get("http://app:8080/login");
        wait(30).until(ExpectedConditions.presenceOfElementLocated(By.name("username")));
        WebElement loginForm = driver.findElement(By.name("username"));
        assertThat(loginForm.isDisplayed()).isTrue();
    }

    // ── Test 5.2 ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void loginSucceeds() {
        driver.get("http://app:8080/login");
        wait(30).until(ExpectedConditions.presenceOfElementLocated(By.name("username")));
        driver.findElement(By.name("username")).sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys("admin");
        driver.findElement(By.cssSelector("button[type='submit'], input[type='submit']")).click();

        // Wait for redirect to main page
        driver.get("http://app:8080/");
        wait(30).until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
        // Wait for the view to be fully rendered
        wait(60).until(d -> {
            String rendered = d.findElement(By.id("taxonomyTree"))
                    .getAttribute("data-view-rendered");
            return rendered != null && !rendered.isEmpty();
        });
        // Dismiss onboarding overlay if present
        List<WebElement> dismissBtns = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissBtns.isEmpty()) {
            dismissBtns.get(0).click();
            wait(5).until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
        assertThat(driver.findElement(By.id("taxonomyTree")).isDisplayed()).isTrue();
    }

    // ── Test 5.3 ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void embeddingBadgeShowsAvailable() {
        // Wait up to 180s for the embedding model to load and badge to update
        wait(180).until(d -> {
            WebElement badge = d.findElement(By.id("embeddingStatusBadge"));
            String text = badge.getText();
            return text != null && text.contains("Embeddings:")
                    && !text.contains("unavailable");
        });
        WebElement badge = driver.findElement(By.id("embeddingStatusBadge"));
        assertThat(badge.getText()).contains("Embeddings:");
    }

    // ── Test 5.4 ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void semanticSearchViaUIReturnsResults() {
        navigateToAnalyzeTab();
        openSearchPanel();
        setSearchMode("semantic");
        executeSearch("secure data exchange");
        waitForSearchResults();

        List<WebElement> results = driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"));
        assertThat(results).isNotEmpty();
    }

    // ── Test 5.5 ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void hybridSearchViaUIReturnsResults() {
        setSearchMode("hybrid");
        executeSearch("command and control");
        waitForSearchResults();

        List<WebElement> results = driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"));
        assertThat(results).isNotEmpty();
    }

    // ── Test 5.6 ─────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void graphSearchViaUIReturnsResults() {
        setSearchMode("graph");
        executeSearch("intelligence processing");
        waitForGraphResults();

        WebElement resultsArea = driver.findElement(By.id("searchResultsArea"));
        assertThat(resultsArea.isDisplayed()).isTrue();
        assertThat(resultsArea.getText()).isNotEmpty();
    }

    // ── Test 5.7 ─────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void searchResultClickNavigatesToNode() {
        // Switch back to semantic mode for a clickable result
        setSearchMode("semantic");
        executeSearch("communications");
        waitForSearchResults();

        List<WebElement> results = driver.findElements(
                By.cssSelector("#searchResultsArea .search-result-item"));
        assertThat(results).isNotEmpty();

        // Click the first result
        String code = results.get(0).getAttribute("data-code");
        results.get(0).click();

        // Verify the node is highlighted in the tree or selected
        if (code != null && !code.isEmpty()) {
            wait(5).until(d -> {
                List<WebElement> highlighted = d.findElements(
                        By.cssSelector(".search-highlight, .tax-code[data-code='" + code + "']"));
                return !highlighted.isEmpty();
            });
        }
    }

    // ── Test 5.8 ─────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    void aiStatusBadgeShowsLimited() {
        WebElement badge = driver.findElement(By.id("aiStatusBadge"));
        assertThat(badge.getText().toUpperCase()).contains("LIMITED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void navigateToAnalyzeTab() {
        js("if (window.navigateToPage) { window.navigateToPage('analyze'); }");
        wait(5).until(d -> {
            WebElement pane = d.findElement(By.id("tab-analyze"));
            return !pane.getAttribute("class").contains("d-none");
        });
    }

    private void openSearchPanel() {
        WebElement searchPanel = driver.findElement(By.id("searchPanel"));
        js("arguments[0].setAttribute('open', '');", searchPanel);
        wait(5).until(ExpectedConditions.attributeContains(searchPanel, "open", ""));
    }

    private void setSearchMode(String mode) {
        js("document.getElementById('searchModeSelect').value = '" + mode + "';");
        js("document.getElementById('searchModeSelect').dispatchEvent(new Event('change'));");
    }

    private void executeSearch(String query) {
        WebElement searchInput = driver.findElement(By.id("searchInput"));
        js("arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));",
                searchInput, query);
        js("document.getElementById('searchBtn').click();");
    }

    private void waitForSearchResults() {
        wait(30).until(d -> {
            WebElement results = d.findElement(By.id("searchResultsArea"));
            return results.isDisplayed()
                    && !results.findElements(By.cssSelector(".search-result-item")).isEmpty();
        });
    }

    private void waitForGraphResults() {
        wait(30).until(d -> {
            WebElement results = d.findElement(By.id("searchResultsArea"));
            return results.isDisplayed() && !results.getText().isEmpty();
        });
    }
}
