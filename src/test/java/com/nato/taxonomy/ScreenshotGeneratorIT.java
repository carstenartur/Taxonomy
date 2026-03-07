package com.nato.taxonomy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>This test is opt-in: it only runs when the system property
 * {@code generateScreenshots} is set (e.g. {@code -DgenerateScreenshots}).
 * This avoids failures in CI environments without outbound internet access
 * (Bootstrap and D3 are loaded from CDN at runtime).
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
    static void setUp() throws IOException {
        // Skip the entire test class unless explicitly opted in. This avoids
        // failures in environments without outbound internet access (Bootstrap
        // and D3 are served from CDN and must be reachable from the Chrome
        // container for the page to render correctly).
        Assumptions.assumeTrue(
                System.getProperty("generateScreenshots") != null,
                "Screenshot generation skipped (pass -DgenerateScreenshots to enable)");
        Files.createDirectories(SCREENSHOT_DIR);
    }

    /** Navigate to the home page and wait for the taxonomy tree to render. */
    @BeforeEach
    void navigateToHome() {
        WebDriver driver = chrome.getWebDriver();
        driver.get("http://taxonomy-app:8080");
        wait(driver, 30).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree .tax-node")));
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
        // expandAll sets display:'' on every .tax-children div; wait until at least one is visible
        wait(driver, 10).until(d -> {
            Object result = ((JavascriptExecutor) d).executeScript(
                    "return Array.from(document.querySelectorAll('#taxonomyTree .tax-children'))" +
                    ".some(e => window.getComputedStyle(e).display !== 'none');");
            return result instanceof Boolean && (Boolean) result;
        });
        saveScreenshot(driver, "03-taxonomy-list-view.png");
    }

    @Test
    @Order(4)
    void captureTabsView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewTabs"))).click();
        wait(driver, 10).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".nav-tabs, [role='tablist']")));
        saveScreenshot(driver, "04-taxonomy-tabs-view.png");
    }

    @Test
    @Order(5)
    void captureSunburstView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewSunburst"))).click();
        // Wait for D3 SVG to appear in #taxonomyTree, then allow animation to complete
        wait(driver, 15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree svg")));
        Thread.sleep(2000); // D3 animation settle time
        saveScreenshot(driver, "05-taxonomy-sunburst-view.png");
    }

    @Test
    @Order(6)
    void captureTreeView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewTree"))).click();
        // Wait for D3 SVG to appear in #taxonomyTree, then allow animation to complete
        wait(driver, 15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree svg")));
        Thread.sleep(2000); // D3 animation settle time
        saveScreenshot(driver, "06-taxonomy-tree-view.png");
    }

    @Test
    @Order(7)
    void captureDecisionView() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        wait(driver, 10).until(ExpectedConditions.elementToBeClickable(By.id("viewDecision"))).click();
        // Wait for D3 SVG to appear in #taxonomyTree, then allow animation to complete
        wait(driver, 15).until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#taxonomyTree svg")));
        Thread.sleep(2000); // D3 animation settle time
        saveScreenshot(driver, "07-taxonomy-decision-view.png");
    }

    @Test
    @Order(8)
    void captureMatchLegend() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        // The legend card is the closest .card ancestor of the first .legend-box element
        WebElement legendBox = wait(driver, 10).until(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".legend-box")));
        WebElement card = (WebElement) ((JavascriptExecutor) driver).executeScript(
                "return arguments[0].closest('.card');", legendBox);
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", card);
        wait(driver, 5).until(ExpectedConditions.visibilityOf(card));
        File src = card.getScreenshotAs(OutputType.FILE);
        Files.copy(src.toPath(), SCREENSHOT_DIR.resolve("08-match-legend.png"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @Order(9)
    void captureGraphExplorer() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        WebElement panel = wait(driver, 10).until(
                ExpectedConditions.presenceOfElementLocated(By.id("graphExplorerPanel")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", panel);
        wait(driver, 5).until(ExpectedConditions.visibilityOf(panel));
        saveScreenshot(driver, "09-graph-explorer.png");
    }

    @Test
    @Order(10)
    void captureProposalsPanel() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        WebElement panel = wait(driver, 10).until(
                ExpectedConditions.presenceOfElementLocated(By.id("proposalsPanel")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", panel);
        wait(driver, 5).until(ExpectedConditions.visibilityOf(panel));
        saveScreenshot(driver, "10-relation-proposals.png");
    }

    @Test
    @Order(11)
    void captureProposeModal() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        ((JavascriptExecutor) driver).executeScript(
                "if (window.bootstrap && window.bootstrap.Modal) {" +
                "  document.getElementById('proposeNodeCode').textContent = 'BP-1040';" +
                "  new window.bootstrap.Modal(" +
                "    document.getElementById('proposeRelationsModal')).show();" +
                "}"
        );
        wait(driver, 10).until(ExpectedConditions.visibilityOfElementLocated(
                By.id("proposeRelationsModal")));
        saveScreenshot(driver, "11-propose-modal.png");
        // Close modal and wait for it to be hidden
        ((JavascriptExecutor) driver).executeScript(
                "if (window.bootstrap && window.bootstrap.Modal) {" +
                "  var el = document.getElementById('proposeRelationsModal');" +
                "  var modal = window.bootstrap.Modal.getInstance(el);" +
                "  if (modal) { modal.hide(); }" +
                "}"
        );
        wait(driver, 5).until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("#proposeRelationsModal.show")));
    }

    @Test
    @Order(12)
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
    @Order(13)
    void captureAdminModal() throws Exception {
        WebDriver driver = chrome.getWebDriver();
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        wait(driver, 5).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("nav.navbar")));
        // Open admin modal via JS
        ((JavascriptExecutor) driver).executeScript(
                "if (window.bootstrap && window.bootstrap.Modal) {" +
                "  var modal = document.getElementById('adminModal') || document.getElementById('adminModeModal');" +
                "  if (modal) { new window.bootstrap.Modal(modal).show(); }" +
                "}"
        );
        wait(driver, 10).until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#adminModal.show, #adminModeModal.show")));
        saveScreenshot(driver, "13-admin-modal.png");
        // Close modal
        ((JavascriptExecutor) driver).executeScript(
                "if (window.bootstrap && window.bootstrap.Modal) {" +
                "  var modal = document.getElementById('adminModal') || document.getElementById('adminModeModal');" +
                "  if (modal) { var inst = window.bootstrap.Modal.getInstance(modal); if (inst) inst.hide(); }" +
                "}"
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
