package com.taxonomy;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maven/Failsafe-owned browser acceptance for the project portfolio.
 *
 * <p>Domain, persistence, REST, Git and report-format semantics belong to the
 * ordinary JUnit service and controller tests. This class deliberately verifies
 * only behaviour that requires a real browser: forms, navigation, asynchronous
 * job visibility, reload recovery, dialogs, responsive layout and downloads.</p>
 */
@Tag("ui-acceptance")
class PortfolioUiAcceptanceIT {

    private static final String ADMIN_PASSWORD = "Portfolio-Ui-Acceptance-2026!";

    private static Network network;
    private static GenericContainer<?> application;
    private static ContainerTestUtils.BrowserSession browserSession;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;

    @BeforeAll
    static void startApplicationAndBrowser() {
        network = Network.newNetwork();
        application = ContainerTestUtils.appContainer(network)
                .withEnv("TAXONOMY_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false")
                .withEnv("TAXONOMY_INIT_ASYNC", "true")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "false")
                .withEnv("LLM_MOCK", "true");
        application.start();

        browserSession = ContainerTestUtils.startBrowser(network);
        driver = browserSession.driver();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        driver.setFileDetector(new LocalFileDetector());
        wait = new WebDriverWait(driver, Duration.ofSeconds(120));
        login();
    }

    @AfterAll
    static void stopApplicationAndBrowser() throws Exception {
        ContainerTestUtils.closeAll(browserSession, application, network);
    }

    @Test
    void portfolioWorkflowsAreOperableThroughTheRealBrowser() throws Exception {
        String suffix = uniqueSuffix();
        String projectKey = "P-JUNIT-" + suffix;
        String solutionKey = "SOL-JUNIT-" + suffix;
        String productKey = "PRD-JUNIT-" + suffix;

        open("/projects?lang=en", By.id("portfolioMain"));
        assertNativeProjectNavigation();
        createProject(projectKey);
        createRequirement("REQ-001", "Secure communication",
                "Provide traceable secure communication and auditable architecture decisions.");
        createRequirement("REQ-002", "Resilient exchange",
                "Provide resilient secure data exchange with auditable architecture decisions.");
        createRequirement("REQ-003", "Controlled operation",
                "Provide controlled operation and secure communication with human review.");
        createSolution(solutionKey);
        createProduct(productKey);

        verifyNonBlockingAnalysisAndReloadRecovery();

        long projectId = selectedProjectId();
        long requirementId = requirementId("REQ-001");
        verifyRequirementAndMatrixWorkspaces(projectId, requirementId);
        verifyGuidedImport(projectId, requirementId, suffix);
        verifyVersioningAndReports(projectId);
        verifyGuidedConflictDecision(suffix);
        verifyTaxonomyPickerAndProductComparison();
        verifyResponsiveLayout();
    }

    private static void login() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();

        wait.until(currentDriver -> {
            String currentUrl = currentDriver.getCurrentUrl();
            if (currentUrl.contains("/login?error")) {
                throw new AssertionError(
                        "Configured test administrator was rejected after readiness: "
                                + currentUrl);
            }
            return !currentUrl.endsWith("/login");
        });
        assertThat(driver.getCurrentUrl())
                .as("post-login URL (title: %s)", driver.getTitle())
                .doesNotContain("/login")
                .doesNotContain("/change-password");

        // Authentication may return to a saved request. The navigation contract
        // belongs to the application workbench, so open it explicitly.
        driver.get(ContainerTestUtils.APP_ORIGIN + "/");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
        dismissOnboardingWhenShown();
    }

    private static void open(String path, By readyElement) {
        driver.get(ContainerTestUtils.APP_ORIGIN + path);
        wait.until(ExpectedConditions.visibilityOfElementLocated(readyElement));
    }

    private static void assertNativeProjectNavigation() {
        WebElement projectList = driver.findElement(By.id("projectList"));
        assertThat(projectList.getAttribute("role")).isNull();
        assertThat(projectList.getAttribute("aria-label")).isEqualTo("Projects");
    }

    private static void createProject(String projectKey) {
        submitModal("projectModal", modal -> {
            fill(modal, "projectKey", projectKey);
            fill(modal, "projectTitle", "JUnit portfolio acceptance");
            fill(modal, "projectDescription",
                    "Created by the Maven/Failsafe Selenium acceptance test.");
        });
        wait.until(textPresent(By.id("selectedProjectKey"), projectKey));
    }

    private static void createRequirement(String key, String title, String text) {
        click(By.cssSelector("[data-bs-target='#requirementModal']"));
        WebElement modal = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("requirementModal")));
        fill(modal, "requirementKey", key);
        fill(modal, "requirementTitle", title);
        new Select(modal.findElement(By.id("requirementType"))).selectByValue("SECURITY");
        fill(modal, "requirementText", text);
        modal.findElement(By.cssSelector("button[type='submit']")).click();
        waitForModalClosed("requirementModal");
        waitUntilPortfolioIdle();
        wait.until(textPresent(By.cssSelector("#requirementsTable tbody"), key));
    }

    private static void createSolution(String solutionKey) {
        click(By.id("solutions-tab"));
        click(By.cssSelector("[data-bs-target='#solutionModal']"));
        WebElement modal = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("solutionModal")));
        fill(modal, "solutionKey", solutionKey);
        fill(modal, "solutionTitle", "Secure communication service");
        new Select(modal.findElement(By.id("solutionType"))).selectByValue("SERVICE");
        new Select(modal.findElement(By.id("solutionOperatingModel")))
                .selectByValue("PRIVATE_CLOUD");
        modal.findElement(By.cssSelector("button[type='submit']")).click();
        waitForModalClosed("solutionModal");
        waitUntilPortfolioIdle();
        wait.until(textPresent(By.id("solutionsList"), "Secure communication service"));
    }

    private static void createProduct(String productKey) {
        click(By.id("products-tab"));
        click(By.cssSelector("[data-bs-target='#productModal']"));
        WebElement modal = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("productModal")));
        fill(modal, "productKey", productKey);
        fill(modal, "productManufacturer", "JUnit Vendor");
        fill(modal, "productName", "JUnit Product");
        fill(modal, "productVersion", "1.0");
        new Select(modal.findElement(By.id("productOperatingModel")))
                .selectByValue("PRIVATE_CLOUD");
        fill(modal, "productSource", "JUnit browser acceptance catalogue source");
        modal.findElement(By.cssSelector("button[type='submit']")).click();
        waitForModalClosed("productModal");
        waitUntilPortfolioIdle();
        wait.until(textPresent(By.id("productsList"), "JUnit Product"));
    }

    private static void verifyNonBlockingAnalysisAndReloadRecovery() {
        click(By.id("requirements-tab"));
        click(By.id("analyzeAllBtn"));

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("portfolioJobCenter")));
        wait.until(browser -> !browser.findElements(
                By.cssSelector("#portfolioJobList .portfolio-job")).isEmpty());
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("portfolioBusy")));

        click(By.id("products-tab"));
        assertThat(wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("productsPane"))).isDisplayed()).isTrue();

        WebDriverWait analysisWait = new WebDriverWait(driver, Duration.ofMinutes(4));
        analysisWait.until(browser -> {
            String text = browser.findElement(By.id("portfolioJobList")).getText();
            return text.contains("Successful") || text.contains("Partial");
        });

        driver.navigate().refresh();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("portfolioJobCenter")));
        wait.until(browser -> !browser.findElements(
                By.cssSelector("#portfolioJobList .portfolio-job")).isEmpty());
        assertThat(driver.findElements(
                By.cssSelector("#portfolioJobList .portfolio-job"))).isNotEmpty();
    }

    private static void verifyRequirementAndMatrixWorkspaces(long projectId,
                                                              long requirementId) {
        open("/projects/" + projectId + "/requirements/" + requirementId + "?lang=en",
                By.id("requirementMain"));
        wait.until(textPresent(By.id("currentText"), "secure communication"));
        assertThat(driver.findElement(By.id("currentText")).getText())
                .contains("secure communication");
        assertThat(driver.findElements(By.cssSelector("#versionList [data-version-id]")))
                .isNotEmpty();
        assertThat(driver.findElements(By.cssSelector("#snapshotList [data-snapshot-id]")))
                .isNotEmpty();

        click(By.id("analyses-tab"));
        click(By.cssSelector("#snapshotList [data-snapshot-id]"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("snapshotDetail")));
        click(By.id("architecture-tab"));
        wait.until(browser -> !browser.findElements(
                By.cssSelector("#mappingTable tbody tr")).isEmpty());
        assertThat(driver.findElements(By.cssSelector("#mappingTable tbody tr")))
                .isNotEmpty();

        open("/projects/" + projectId + "/matrices?lang=en", By.id("matrixMain"));
        wait.until(browser -> !browser.findElements(
                By.cssSelector("#taxonomyMatrix .matrix-drilldown")).isEmpty());
        int before = driver.findElements(
                By.cssSelector("#taxonomyMatrix .matrix-drilldown")).size();
        fill("matrixSearch", "REQ-001");
        int after = driver.findElements(
                By.cssSelector("#taxonomyMatrix .matrix-drilldown")).size();
        assertThat(after).isBetween(1, before);
        click(By.cssSelector("#taxonomyMatrix .matrix-drilldown"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("cellDetail")));
        assertThat(driver.findElements(By.cssSelector("#cellDetailBody dt")).size())
                .isGreaterThanOrEqualTo(3);
    }

    private static void verifyGuidedImport(long projectId,
                                           long requirementId,
                                           String suffix) throws Exception {
        Path pdf = createRequirementPdf(suffix);
        try {
            open("/projects/" + projectId + "/import?lang=en", By.id("importMain"));
            driver.findElement(By.id("documentFile")).sendKeys(pdf.toAbsolutePath().toString());
            fill("documentTitle", "JUnit portfolio import source");
            click(By.cssSelector("#uploadForm button[type='submit']"));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("reviewStep")));

            while (driver.findElements(By.cssSelector(".candidate-card")).size() < 3) {
                click(By.id("addManualCandidate"));
            }

            WebElement first = candidate(0);
            new Select(first.findElement(By.cssSelector(".candidate-decision")))
                    .selectByValue("VERSION");
            first = candidate(0);
            new Select(first.findElement(By.cssSelector(".candidate-target-requirement")))
                    .selectByValue(Long.toString(requirementId));
            fill(first, ".candidate-text",
                    "The platform shall encrypt communication and retain source provenance.");

            WebElement second = candidate(1);
            new Select(second.findElement(By.cssSelector(".candidate-decision")))
                    .selectByValue("NEW");
            fill(second, ".candidate-key", "REQ-IMP-A-" + suffix);
            fill(second, ".candidate-title", "Imported audit history requirement");
            fill(second, ".candidate-text",
                    "The platform shall retain an auditable history of architecture decisions.");

            WebElement third = candidate(2);
            new Select(third.findElement(By.cssSelector(".candidate-decision")))
                    .selectByValue("NEW");
            fill(third, ".candidate-key", "REQ-IMP-B-" + suffix);
            fill(third, ".candidate-title", "Imported offline operation requirement");
            fill(third, ".candidate-text",
                    "The platform shall continue operating without external connectivity.");

            click(By.id("reviewSummaryButton"));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("summaryStep")));
            assertThat(driver.findElements(By.cssSelector("#importSummary tbody tr")).size())
                    .isGreaterThanOrEqualTo(3);
            click(By.id("confirmImport"));
            wait.until(textPresent(By.id("importInfo"), "imported successfully"));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("portfolioMain")));
            wait.until(textPresent(By.cssSelector("#requirementsTable tbody"),
                    "REQ-IMP-A-" + suffix));
            wait.until(textPresent(By.cssSelector("#requirementsTable tbody"),
                    "REQ-IMP-B-" + suffix));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    private static void verifyVersioningAndReports(long projectId) {
        open("/projects/" + projectId + "/versioning?lang=en", By.id("versioningMain"));
        wait.until(browser -> {
            String dsl = browser.findElement(By.id("dslPreview"))
                    .getDomProperty("textContent");
            return dsl != null && dsl.toLowerCase(Locale.ROOT).contains("portfolio");
        });
        assertThat(driver.findElement(By.id("dslPreview")).getDomProperty("textContent"))
                .contains("portfolio");
        assertThat(driver.findElements(By.cssSelector("#portfolioCounts .card"))).hasSize(4);

        String headBefore = driver.findElement(By.id("headCommit")).getText();
        fill("commitMessage", "JUnit reviewed portfolio " + System.nanoTime());
        click(By.cssSelector("#commitForm button[type='submit']"));
        wait.until(browser -> {
            String current = browser.findElement(By.id("headCommit")).getText();
            return !current.isBlank() && !current.equals(headBefore);
        });
        click(By.id("previewMaterialize"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#materializePreview dl")));
        assertThat(driver.findElements(By.cssSelector("#materializePreview code")))
                .isNotEmpty();

        open("/projects/" + projectId + "/reports?lang=en", By.id("reportsMain"));
        click(By.id("previewReport"));
        WebElement frame = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("reportPreviewFrame")));
        driver.switchTo().frame(frame);
        wait.until(textPresent(By.tagName("body"), "Project portfolio report"));
        driver.switchTo().defaultContent();
        assertThat(driver.findElement(By.id("previewBaseline")).getText()).isNotBlank();
        assertThat(driver.findElements(By.cssSelector("[href*='/reports/'], button[data-format]")))
                .isNotEmpty();
    }

    private static void verifyGuidedConflictDecision(String suffix) {
        open("/projects?lang=en", By.id("portfolioMain"));
        String cloudKey = "REQ-CLOUD-" + suffix;
        String hostingKey = "REQ-HOST-" + suffix;
        createRequirement(cloudKey, "Mandatory public cloud",
                "The solution must use public cloud hosting for all runtime services.");
        createRequirement(hostingKey, "External hosting prohibited",
                "The solution must not use external hosting and must remain on premises.");

        click(By.id("detectConflictsBtn"));
        waitUntilPortfolioIdle();
        click(By.id("conflicts-tab"));
        WebElement card = wait.until(browser -> browser.findElements(
                        By.cssSelector(".portfolio-conflict-card")).stream()
                .filter(WebElement::isDisplayed)
                .filter(element -> element.getText().contains(cloudKey)
                        && element.getText().contains(hostingKey))
                .findFirst().orElse(null));
        click(card.findElement(By.cssSelector(
                ".conflict-review[data-status='CONFIRMED']")));
        WebElement dialog = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("guidedConflictDialog")));
        assertThat(dialog.findElement(By.id("guidedConflictRequirementA")).getText())
                .isNotBlank();
        assertThat(dialog.findElement(By.id("guidedConflictRequirementB")).getText())
                .isNotBlank();
        assertThat(dialog.findElement(By.id("guidedConflictEvidence")).getText())
                .isNotBlank();
        new Select(dialog.findElement(By.id("guidedConflictDecision")))
                .selectByValue("RESOLVED");
        fill(dialog, "guidedConflictResolution",
                "Use a private on-premises cloud; public-cloud wording is superseded.");
        dialog.findElement(By.cssSelector("button[type='submit']")).click();
        waitForModalClosed("guidedConflictDialog");
    }

    private static void verifyTaxonomyPickerAndProductComparison() {
        click(By.id("solutions-tab"));
        WebElement solution = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".portfolio-solution-card")));
        WebElement details = solution.findElement(By.cssSelector("details"));
        if (details.getAttribute("open") == null) {
            click(details.findElement(By.cssSelector("summary")));
        }
        WebElement input = solution.findElement(By.cssSelector(".solution-node-code"));
        assertThat(input.getAttribute("list")).isEqualTo("taxonomyNodeOptions");
        input.clear();
        input.sendKeys("secure");
        wait.until(browser -> !browser.findElements(
                By.cssSelector("#taxonomyNodeOptions option")).isEmpty());
        String suggestion = driver.findElement(
                By.cssSelector("#taxonomyNodeOptions option")).getAttribute("value");
        input.clear();
        input.sendKeys(suggestion, Keys.TAB);
        wait.until(browser -> {
            String title = input.getAttribute("title");
            return title != null && title.contains(suggestion);
        });
        assertThat(solution.findElements(By.cssSelector(".product-comparison")))
                .isNotEmpty();
    }

    private static void verifyResponsiveLayout() {
        driver.manage().window().setSize(new Dimension(390, 844));
        assertThat(horizontalOverflow()).isLessThanOrEqualTo(2L);

        driver.manage().window().setSize(new Dimension(1440, 1000));
        javascript().executeScript("document.documentElement.style.fontSize='200%'");
        assertThat(horizontalOverflow()).isLessThanOrEqualTo(2L);
        javascript().executeScript("document.documentElement.style.fontSize=''");
    }

    private static void submitModal(String modalId, Consumer<WebElement> formValues) {
        click(By.cssSelector("[data-bs-target='#" + modalId + "']"));
        WebElement modal = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id(modalId)));
        formValues.accept(modal);
        modal.findElement(By.cssSelector("button[type='submit']")).click();
        waitForModalClosed(modalId);
        waitUntilPortfolioIdle();
    }

    private static void waitForModalClosed(String modalId) {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id(modalId)));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector(".modal-backdrop.show")));
        wait.until(browser -> {
            String classes = browser.findElement(By.tagName("body")).getAttribute("class");
            return classes == null || !classes.contains("modal-open");
        });
    }

    private static void waitUntilPortfolioIdle() {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("portfolioBusy")));
    }

    /**
     * Performs a real native browser click after scrolling to a stable viewport
     * position. Bootstrap may report a trigger as clickable while the fading
     * backdrop still intercepts the pointer; retry only that transient browser
     * condition instead of bypassing it with JavaScript.
     */
    private static void click(By locator) {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector(".modal-backdrop.show")));
        wait.until(browser -> {
            try {
                WebElement element = browser.findElement(locator);
                if (!element.isDisplayed() || !element.isEnabled()) return false;
                javascript().executeScript(
                        "arguments[0].scrollIntoView({block:'center',inline:'nearest'});",
                        element);
                element.click();
                return true;
            } catch (ElementClickInterceptedException | StaleElementReferenceException error) {
                return false;
            }
        });
    }

    private static void click(WebElement element) {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector(".modal-backdrop.show")));
        wait.until(browser -> {
            try {
                if (!element.isDisplayed() || !element.isEnabled()) return false;
                javascript().executeScript(
                        "arguments[0].scrollIntoView({block:'center',inline:'nearest'});",
                        element);
                element.click();
                return true;
            } catch (ElementClickInterceptedException error) {
                return false;
            }
        });
    }

    private static void fill(String id, String value) {
        WebElement element = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id(id)));
        element.clear();
        element.sendKeys(value);
    }

    private static void fill(WebElement root, String selector, String value) {
        By locator = selector.startsWith(".") || selector.startsWith("#")
                ? By.cssSelector(selector) : By.id(selector);
        WebElement element = root.findElement(locator);
        element.clear();
        element.sendKeys(value);
    }

    private static WebElement candidate(int index) {
        return wait.until(browser -> {
            List<WebElement> candidates = browser.findElements(By.cssSelector(".candidate-card"));
            return candidates.size() > index ? candidates.get(index) : null;
        });
    }

    private static long selectedProjectId() {
        Object value = javascript().executeScript(
                "return window.localStorage.getItem('taxonomy.portfolio.projectId')");
        return Long.parseLong(String.valueOf(value));
    }

    private static long requirementId(String requirementKey) {
        WebElement row = wait.until(browser -> browser.findElements(
                        By.cssSelector("#requirementsTable tbody tr")).stream()
                .filter(element -> element.getText().contains(requirementKey))
                .findFirst().orElse(null));
        return Long.parseLong(row.findElement(
                By.cssSelector(".requirement-snapshots")).getAttribute("data-requirement-id"));
    }

    private static Path createRequirementPdf(String suffix) throws Exception {
        Path file = Files.createTempFile("taxonomy-portfolio-" + suffix, ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(50, 750);
                content.showText("The platform shall encrypt all external communication.");
                content.newLineAtOffset(0, -20);
                content.showText("The platform shall retain auditable architecture decisions.");
                content.newLineAtOffset(0, -20);
                content.showText("The platform shall continue operating without external connectivity.");
                content.endText();
            }
            document.save(file.toFile());
        }
        return file;
    }

    private static ExpectedCondition<Boolean> textPresent(By locator, String expectedText) {
        return browser -> {
            List<WebElement> elements = browser.findElements(locator);
            return !elements.isEmpty()
                    && elements.getFirst().getText().toLowerCase(Locale.ROOT)
                    .contains(expectedText.toLowerCase(Locale.ROOT));
        };
    }

    private static long horizontalOverflow() {
        Number value = (Number) javascript().executeScript("return Math.max(0, "
                + "document.documentElement.scrollWidth-document.documentElement.clientWidth)");
        return value.longValue();
    }

    private static JavascriptExecutor javascript() {
        return driver;
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static void dismissOnboardingWhenShown() {
        List<WebElement> dismissButtons = driver.findElements(By.id("onboardingDismiss"));
        if (dismissButtons.isEmpty() || !dismissButtons.getFirst().isDisplayed()) {
            return;
        }
        dismissButtons.getFirst().click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
    }
}
