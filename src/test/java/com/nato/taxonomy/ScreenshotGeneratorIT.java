package com.nato.taxonomy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Generates browser screenshots of the Taxonomy web UI for use in the user
 * guide documentation. Screenshots are saved to {@code docs/images/}.
 *
 * <p>Requires Docker and the application JAR at
 * {@code target/taxonomy-1.0.0-SNAPSHOT.jar} to be present (i.e. run after
 * {@code mvn package -DskipTests}).
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScreenshotGeneratorIT {

    private static final Path SCREENSHOT_DIR = Path.of("docs/images");

    static Network network = Network.newNetwork();

    @Container
    static GenericContainer<?> app = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("app.jar", Path.of("target/taxonomy-1.0.0-SNAPSHOT.jar"))
                    .withDockerfileFromBuilder(builder -> builder
                            .from("eclipse-temurin:17-jre-alpine")
                            .workDir("/app")
                            .copy("app.jar", "app.jar")
                            .expose(8080)
                            .entryPoint("java", "-jar", "app.jar")
                            .build()))
            .withExposedPorts(8080)
            .withStartupTimeout(Duration.ofSeconds(120))
            .withNetwork(network)
            .withNetworkAliases("taxonomy-app")
            .waitingFor(Wait.forHttp("/api/diagnostics")
                    .forStatusCode(200)
                    .forPort(8080));

    @Container
    static BrowserWebDriverContainer<?> chrome = new BrowserWebDriverContainer<>()
            .withCapabilities(new ChromeOptions()
                    .addArguments("--window-size=1400,900")
                    .addArguments("--force-device-scale-factor=1")
                    .addArguments("--disable-gpu")
                    .addArguments("--no-sandbox"))
            .withNetwork(network);

    @BeforeAll
    static void createOutputDir() throws IOException {
        Files.createDirectories(SCREENSHOT_DIR);
    }

    private String appUrl() {
        return "http://taxonomy-app:8080";
    }

    private void saveScreenshot(WebDriver driver, String filename) throws IOException {
        File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), SCREENSHOT_DIR.resolve(filename),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private WebDriverWait wait(WebDriver driver, int timeoutSeconds) {
        return new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
    }

    @Test
    @Order(1)
    void captureFullLayout() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        driver.get(appUrl());

        // Wait for taxonomy tree to load then let rendering settle
        wait(driver, 30).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree .tax-node, #taxonomyTree ul")));
        wait(driver, 5).until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#taxonomyTree")));

        saveScreenshot(driver, "01-full-layout.png");
    }

    @Test
    @Order(2)
    void captureAnalysisPanel() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        WebElement panel = wait(driver, 10).until(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".col-lg-5")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", panel);
        saveScreenshot(driver, "02-analysis-panel.png");
    }

    @Test
    @Order(3)
    void captureExpandedListView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("expandAll"))).click();
        // Wait for expand animation to settle
        wait(driver, 10).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree li.expanded, #taxonomyTree li[aria-expanded='true']")));
        saveScreenshot(driver, "03-taxonomy-list-view.png");
    }

    @Test
    @Order(4)
    void captureTabsView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewTabs"))).click();
        wait(driver, 10).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTabs, .nav-tabs, [role='tablist']")));
        saveScreenshot(driver, "04-taxonomy-tabs-view.png");
    }

    @Test
    @Order(5)
    void captureSunburstView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewSunburst"))).click();
        // Wait for D3 SVG to appear, then allow animation to complete
        wait(driver, 15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#sunburstContainer svg, #taxonomyTree svg")));
        Thread.sleep(2000); // D3 animation settle time
        saveScreenshot(driver, "05-taxonomy-sunburst-view.png");
    }

    @Test
    @Order(6)
    void captureTreeView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewTree"))).click();
        // Wait for D3 SVG to appear, then allow animation to complete
        wait(driver, 15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#treeContainer svg, #taxonomyTree svg")));
        Thread.sleep(2000); // D3 animation settle time
        saveScreenshot(driver, "06-taxonomy-tree-view.png");
    }

    @Test
    @Order(7)
    void captureDecisionView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewDecision"))).click();
        // Wait for D3 SVG to appear, then allow animation to complete
        wait(driver, 15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#decisionContainer svg, #taxonomyTree svg")));
        Thread.sleep(2000); // D3 animation settle time
        saveScreenshot(driver, "07-taxonomy-decision-view.png");
    }

    @Test
    @Order(8)
    void captureGraphExplorer() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        WebElement panel = wait(driver, 10).until(
                ExpectedConditions.presenceOfElementLocated(By.id("graphExplorerPanel")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", panel);
        wait(driver, 5).until(ExpectedConditions.visibilityOf(panel));
        saveScreenshot(driver, "09-graph-explorer.png");
    }

    @Test
    @Order(9)
    void captureProposalsPanel() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        WebElement panel = wait(driver, 10).until(
                ExpectedConditions.presenceOfElementLocated(By.id("proposalsPanel")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", panel);
        wait(driver, 5).until(ExpectedConditions.visibilityOf(panel));
        saveScreenshot(driver, "10-relation-proposals.png");
    }

    @Test
    @Order(10)
    void captureProposeModal() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        ((JavascriptExecutor) driver).executeScript(
                "document.getElementById('proposeNodeCode').textContent = 'BP-1040';" +
                "new bootstrap.Modal(document.getElementById('proposeRelationsModal')).show();"
        );
        wait(driver, 10).until(ExpectedConditions.visibilityOfElementLocated(
                By.id("proposeRelationsModal")));
        saveScreenshot(driver, "11-propose-modal.png");
        // Close modal and wait for it to be hidden
        ((JavascriptExecutor) driver).executeScript(
                "bootstrap.Modal.getInstance(document.getElementById('proposeRelationsModal')).hide();"
        );
        wait(driver, 5).until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("#proposeRelationsModal.show")));
    }

    @Test
    @Order(11)
    void captureNavbar() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        WebElement navbar = wait(driver, 10).until(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector("nav.navbar")));
        File src = navbar.getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), SCREENSHOT_DIR.resolve("12-navbar.png"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @Order(12)
    void captureMatchLegend() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        // Switch back to list view first
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewList"))).click();
        // Find the match legend element
        WebElement legend = wait(driver, 10).until(
                ExpectedConditions.visibilityOfElementLocated(
                        By.cssSelector("#matchLegend, .match-legend, [class*='legend']")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", legend);
        wait(driver, 5).until(ExpectedConditions.visibilityOf(legend));
        File src = legend.getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), SCREENSHOT_DIR.resolve("08-match-legend.png"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @Order(13)
    void captureAdminModal() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        wait(driver, 5).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("nav.navbar")));
        // Open admin modal via JS
        ((JavascriptExecutor) driver).executeScript(
                "var modal = document.getElementById('adminModal') || document.getElementById('adminModeModal');" +
                "if (modal) { new bootstrap.Modal(modal).show(); }"
        );
        wait(driver, 10).until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#adminModal.show, #adminModeModal.show")));
        saveScreenshot(driver, "13-admin-modal.png");
        // Close modal
        ((JavascriptExecutor) driver).executeScript(
                "var modal = document.getElementById('adminModal') || document.getElementById('adminModeModal');" +
                "if (modal) { var inst = bootstrap.Modal.getInstance(modal); if (inst) inst.hide(); }"
        );
        wait(driver, 5).until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("#adminModal.show, #adminModeModal.show")));
    }

    @Test
    @Order(14)
    void captureAnalysisPanelWithText() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        WebElement textarea = wait(driver, 10).until(
                ExpectedConditions.elementToBeClickable(
                        By.cssSelector("#businessText, textarea[name='businessText'], #requirementText")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", textarea);
        wait(driver, 5).until(ExpectedConditions.visibilityOf(textarea));
        textarea.clear();
        textarea.sendKeys("Provide secure voice communications between HQ and deployed joint forces");
        wait(driver, 5).until(ExpectedConditions.attributeContains(textarea, "value",
                "Provide secure voice communications"));
        saveScreenshot(driver, "14-analysis-with-text.png");
    }
}
