package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complete real-browser acceptance for one AI-assisted requirement session.
 *
 * <p>The test deliberately uses the packaged application, PostgreSQL, the real
 * browser UI and the deterministic mock LLM. It does not inject result HTML or
 * fabricate terminal analysis data. The only injected failure is one transport
 * exception at the public Copilot API boundary, proving that a lost status poll
 * is rendered as reconnecting rather than as a failed server operation.</p>
 */
@Tag("ui-acceptance")
class CompleteCopilotSessionIT {

    private static final String ADMIN_PASSWORD = "Complete-Copilot-Session-2026!";
    private static final String REQUIREMENT_SENTENCE =
            "Provide traceable resilient command communication with auditable architecture decisions.";

    private static Network network;
    private static PostgreSQLContainer<?> database;
    private static GenericContainer<?> application;
    private static ContainerTestUtils.BrowserSession browserSession;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;

    @BeforeAll
    static void startPersistentApplicationAndBrowser() {
        network = Network.newNetwork();
        database = ContainerTestUtils.postgresContainer(network);
        database.start();

        application = ContainerTestUtils.postgresAppContainer(network)
                .withEnv("TAXONOMY_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false")
                .withEnv("TAXONOMY_INIT_ASYNC", "true")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "false")
                .withEnv("TAXONOMY_AI_MAX_ARCHITECTURE_NODES", "100")
                .withEnv("LLM_MOCK", "true")
                .withStartupTimeout(Duration.ofMinutes(5));
        application.start();

        browserSession = ContainerTestUtils.startBrowser(network);
        driver = browserSession.driver();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        wait = new WebDriverWait(driver, Duration.ofMinutes(5));
        login();
    }

    @AfterAll
    static void stopPersistentApplicationAndBrowser() throws Exception {
        ContainerTestUtils.closeAll(browserSession, application, database, network);
    }

    @Test
    void completeSessionSupportsCancelReconnectReloadResultsAndEquivalentExports() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String projectKey = "P-SESSION-" + suffix;
        String requirementKey = "REQ-SESSION-" + suffix;

        open("/projects?lang=en", By.id("portfolioMain"));
        createProject(projectKey);
        createRequirement(requirementKey, REQUIREMENT_SENTENCE);
        long projectId = selectedProjectId();
        long requirementId = requirementId(requirementKey);

        open("/projects/" + projectId + "/requirements/" + requirementId + "?lang=en",
                By.id("requirementMain"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("requirementCopilotCard")));
        wait.until(ExpectedConditions.elementToBeClickable(By.id("copilotRun")));

        assertCopilotControlsAreAccessibleAndInventoried();
        String cancelledOperation = cancelFirstExhaustiveRun();

        installSingleTransientStatusFailure();
        String successfulOperation = startForcedExhaustiveRun(cancelledOperation);
        wait.until(attributeEquals(By.id("copilotOperation"),
                "data-operation-status", "RECONNECTING"));
        assertThat(driver.findElement(By.id("copilotReconnectMessage")).getText())
                .containsIgnoringCase("Reconnect");
        assertThat(driver.findElement(By.id("copilotError")).isDisplayed()).isFalse();

        driver.navigate().refresh();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("requirementCopilotCard")));
        wait.until(attributeEquals(By.id("copilotOperation"),
                "data-operation-id", successfulOperation));

        wait.until(browser -> {
            String status = browser.findElement(By.id("copilotOperation"))
                    .getAttribute("data-operation-status");
            return "SUCCESS".equals(status) || "PARTIAL".equals(status);
        });
        wait.until(browser -> new java.net.URI(browser.getCurrentUrl()).getQuery() != null
                && new java.net.URI(browser.getCurrentUrl()).getQuery().contains("snapshot="));

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("snapshotDetail")));
        wait.until(browser -> browser.findElements(
                By.cssSelector("[data-decision-report-format]")).size() == 3);

        exerciseRequirementWorkspaceControls();
        assertSessionControlsAreNotObscured();
        Map<String, ExportEvidence> exports = exerciseAndReadAllDecisionReportExports(projectId);
        assertEquivalentExportSemantics(exports, requirementKey, REQUIREMENT_SENTENCE);
        assertResponsiveAndKeyboardReachable();
        assertOneConsolidatedProviderFailure();
    }

    private static String cancelFirstExhaustiveRun() {
        selectProfile("EXHAUSTIVE");
        setChecked("copilotForce", true);
        click(By.id("copilotRun"));

        wait.until(browser -> {
            WebElement surface = browser.findElement(By.id("copilotOperation"));
            String id = surface.getAttribute("data-operation-id");
            String status = surface.getAttribute("data-operation-status");
            return id != null && !id.isBlank()
                    && Set.of("PENDING", "RUNNING").contains(status);
        });
        String operationId = driver.findElement(By.id("copilotOperation"))
                .getAttribute("data-operation-id");
        assertThat(driver.findElement(By.id("copilotRun")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("copilotCancel")).isEnabled()).isTrue();

        click(By.id("copilotCancel"));
        wait.until(attributeEquals(By.id("copilotOperation"),
                "data-operation-status", "CANCELLED"));
        assertThat(driver.findElement(By.id("copilotOperationMessage")).getText())
                .containsIgnoringCase("cancel");
        return operationId;
    }

    private static String startForcedExhaustiveRun(String previousOperationId) {
        wait.until(ExpectedConditions.elementToBeClickable(By.id("copilotRun")));
        selectProfile("EXHAUSTIVE");
        setChecked("copilotForce", true);
        click(By.id("copilotRun"));
        wait.until(browser -> {
            String id = browser.findElement(By.id("copilotOperation"))
                    .getAttribute("data-operation-id");
            return id != null && !id.isBlank() && !id.equals(previousOperationId);
        });
        return driver.findElement(By.id("copilotOperation"))
                .getAttribute("data-operation-id");
    }

    private static void installSingleTransientStatusFailure() {
        javascript().executeScript("""
                window.__taxonomyOriginalCopilotGet = window.TaxonomyCopilotApi.get.bind(window.TaxonomyCopilotApi);
                window.__taxonomyTransientPollFailures = 0;
                window.TaxonomyCopilotApi.get = async function(projectId, operationId) {
                    if (window.__taxonomyTransientPollFailures++ === 0) {
                        throw new Error('Simulated transient status transport failure');
                    }
                    return window.__taxonomyOriginalCopilotGet(projectId, operationId);
                };
                """);
    }

    private static void exerciseRequirementWorkspaceControls() {
        for (String tabId : List.of(
                "text-tab", "versions-tab", "analyses-tab",
                "architecture-tab", "decisions-tab", "solutions-tab")) {
            click(By.id(tabId));
            WebElement tab = driver.findElement(By.id(tabId));
            assertThat(tab.getAttribute("aria-selected")).isEqualTo("true");
            String target = tab.getAttribute("data-bs-target");
            assertThat(driver.findElement(By.cssSelector(target)).isDisplayed()).isTrue();
        }

        click(By.id("newVersionButton"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("newVersionModal")));
        WebElement close = driver.findElement(By.cssSelector("#newVersionModal .btn-close"));
        assertThat(close.getAccessibleName()).isNotBlank();
        close.click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("newVersionModal")));

        click(By.id("analyses-tab"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("snapshotDetail")));
    }

    private static Map<String, ExportEvidence> exerciseAndReadAllDecisionReportExports(long projectId) {
        javascript().executeScript("""
                window.__taxonomyExportEvents = [];
                document.addEventListener('taxonomy:export-operation-state', function(event) {
                    window.__taxonomyExportEvents.push(event.detail);
                });
                """);

        for (String format : List.of("docx", "html", "json")) {
            click(By.cssSelector("[data-decision-report-format='" + format + "']"));
            wait.until(browser -> {
                WebElement surface = browser.findElement(By.id("requirementExportOperation"));
                return "SUCCESS".equals(surface.getAttribute("data-operation-status"))
                        && surface.getText().toLowerCase(Locale.ROOT).contains(format);
            });
            WebElement control = driver.findElement(
                    By.cssSelector("[data-decision-report-format='" + format + "']"));
            assertThat(control.getAttribute("data-session-test-outcome")).isEqualTo("export");
        }

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> raw = (Map<String, Map<String, Object>>) driver.executeAsyncScript("""
                const projectId = arguments[0];
                const done = arguments[arguments.length - 1];
                const snapshotId = new URL(window.location.href).searchParams.get('snapshot');
                function bytesToBase64(bytes) {
                    let binary = '';
                    const block = 0x8000;
                    for (let index = 0; index < bytes.length; index += block) {
                        binary += String.fromCharCode(...bytes.subarray(index, index + block));
                    }
                    return btoa(binary);
                }
                (async function() {
                    const result = {};
                    for (const format of ['docx', 'html', 'json']) {
                        const response = await window.TaxonomyPortfolioApi.downloadDecisionReport(
                            projectId, snapshotId, format, 'en');
                        const bytes = new Uint8Array(await response.arrayBuffer());
                        result[format] = {
                            status: response.status,
                            contentType: response.headers.get('Content-Type') || '',
                            disposition: response.headers.get('Content-Disposition') || '',
                            byteLength: bytes.length,
                            base64: bytesToBase64(bytes)
                        };
                    }
                    done(result);
                }()).catch(error => done({ error: { message: String(error && error.stack || error) } }));
                """, projectId);

        assertThat(raw).doesNotContainKey("error");
        Map<String, ExportEvidence> result = new LinkedHashMap<>();
        raw.forEach((format, values) -> result.put(format, new ExportEvidence(
                ((Number) values.get("status")).intValue(),
                String.valueOf(values.get("contentType")),
                String.valueOf(values.get("disposition")),
                ((Number) values.get("byteLength")).intValue(),
                Base64.getDecoder().decode(String.valueOf(values.get("base64"))))));
        return result;
    }

    private static void assertEquivalentExportSemantics(Map<String, ExportEvidence> exports,
                                                         String requirementKey,
                                                         String requirementText) {
        assertThat(exports).containsKeys("docx", "html", "json");
        exports.forEach((format, evidence) -> {
            assertThat(evidence.status()).as(format + " HTTP status").isEqualTo(200);
            assertThat(evidence.byteLength()).as(format + " bytes").isGreaterThan(0);
            assertThat(evidence.disposition()).as(format + " filename")
                    .containsIgnoringCase("filename");
        });

        ExportEvidence json = exports.get("json");
        assertThat(json.contentType()).containsIgnoringCase("application/json");
        String jsonText = new String(json.bytes(), StandardCharsets.UTF_8);
        assertThat(jsonText).startsWith("{")
                .contains(requirementText.substring(0, 24));

        ExportEvidence html = exports.get("html");
        assertThat(html.contentType()).containsIgnoringCase("text/html");
        String htmlText = new String(html.bytes(), StandardCharsets.UTF_8);
        assertThat(htmlText).containsIgnoringCase("<html")
                .contains(requirementText.substring(0, 24));

        ExportEvidence docx = exports.get("docx");
        assertThat(docx.contentType()).containsAnyOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/octet-stream");
        Map<String, byte[]> entries = unzip(docx.bytes());
        assertThat(entries).containsKeys("[Content_Types].xml", "word/document.xml");
        String documentText = normalizeXmlText(entries.get("word/document.xml"));
        assertThat(documentText).contains(requirementText.substring(0, 24));

        String marker = requirementText.substring(0, 24);
        assertThat(jsonText).contains(marker);
        assertThat(htmlText).contains(marker);
        assertThat(documentText).contains(marker);
        assertThat(requirementKey).isNotBlank();
    }

    private static void assertCopilotControlsAreAccessibleAndInventoried() {
        List<WebElement> controls = driver.findElements(By.cssSelector(
                "#requirementCopilotCard button, #requirementCopilotCard select, #requirementCopilotCard input"));
        assertThat(controls).isNotEmpty();
        for (WebElement control : controls) {
            if (!control.isDisplayed()) continue;
            assertThat(control.getAccessibleName())
                    .as("accessible name for #%s", control.getAttribute("id"))
                    .isNotBlank();
            assertThat(control.getAttribute("data-session-control"))
                    .as("control inventory for #%s", control.getAttribute("id"))
                    .isNotBlank();
            assertThat(control.getAttribute("data-session-test-outcome"))
                    .as("test outcome for #%s", control.getAttribute("id"))
                    .isIn("toggle", "operation", "cancel");
        }
    }

    private static void assertSessionControlsAreNotObscured() {
        @SuppressWarnings("unchecked")
        List<String> blocked = (List<String>) javascript().executeScript("""
                return Array.from(document.querySelectorAll('[data-session-control]'))
                    .filter(element => {
                        const rect = element.getBoundingClientRect();
                        return !element.disabled && rect.width > 0 && rect.height > 0;
                    })
                    .filter(element => {
                        const rect = element.getBoundingClientRect();
                        const x = Math.min(window.innerWidth - 1, Math.max(0, rect.left + rect.width / 2));
                        const y = Math.min(window.innerHeight - 1, Math.max(0, rect.top + rect.height / 2));
                        const hit = document.elementFromPoint(x, y);
                        return hit !== element && !element.contains(hit);
                    })
                    .map(element => element.id || element.dataset.sessionControl || element.tagName);
                """);
        assertThat(blocked).as("session controls hidden by overlays or other controls").isEmpty();
    }

    private static void assertResponsiveAndKeyboardReachable() {
        driver.manage().window().setSize(new Dimension(390, 844));
        Number overflow = (Number) javascript().executeScript(
                "return Math.max(0, document.documentElement.scrollWidth-document.documentElement.clientWidth)");
        assertThat(overflow.longValue()).isLessThanOrEqualTo(2L);

        driver.manage().window().setSize(new Dimension(1440, 1000));
        WebElement profile = driver.findElement(By.id("copilotProfile"));
        profile.click();
        profile.sendKeys(org.openqa.selenium.Keys.TAB);
        String activeId = String.valueOf(javascript().executeScript(
                "return document.activeElement && document.activeElement.id"));
        assertThat(activeId).isNotBlank().isNotEqualTo("null");
    }

    private static void assertOneConsolidatedProviderFailure() {
        javascript().executeScript("""
                window.__taxonomyWorkingCopilotStart = window.TaxonomyCopilotApi.start;
                window.TaxonomyCopilotApi.start = async function() {
                    const error = new Error('PROMPT_BUDGET_EXCEEDED: The selected AI target cannot accept this prompt.');
                    error.status = 413;
                    throw error;
                };
                """);
        setChecked("copilotForce", true);
        click(By.id("copilotRun"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("copilotError")));
        assertThat(driver.findElement(By.id("copilotError")).getText())
                .contains("PROMPT_BUDGET_EXCEEDED")
                .containsIgnoringCase("AI target");
        long visibleErrors = ((Number) javascript().executeScript("""
                return Array.from(document.querySelectorAll('[role="alert"]'))
                    .filter(element => {
                        const style = getComputedStyle(element);
                        return style.display !== 'none' && style.visibility !== 'hidden'
                            && element.getBoundingClientRect().height > 0;
                    }).length;
                """)).longValue();
        assertThat(visibleErrors).isEqualTo(1L);
        javascript().executeScript(
                "window.TaxonomyCopilotApi.start = window.__taxonomyWorkingCopilotStart;");
    }

    private static Map<String, byte[]> unzip(byte[] archive) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                zip.transferTo(output);
                entries.put(entry.getName(), output.toByteArray());
            }
        } catch (Exception exception) {
            throw new AssertionError("DOCX export is not a readable OOXML ZIP", exception);
        }
        return entries;
    }

    private static String normalizeXmlText(byte[] xml) {
        return new String(xml, StandardCharsets.UTF_8)
                .replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void login() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();
        wait.until(browser -> !browser.getCurrentUrl().contains("/login"));
        driver.get(ContainerTestUtils.APP_ORIGIN + "/");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
        dismissOnboardingWhenShown();
    }

    private static void createProject(String projectKey) {
        click(By.cssSelector("[data-bs-target='#projectModal']"));
        WebElement modal = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("projectModal")));
        fill(modal, "projectKey", projectKey);
        fill(modal, "projectTitle", "Complete Copilot session");
        fill(modal, "projectDescription",
                "Created by the authoritative complete-session JUnit acceptance test.");
        modal.findElement(By.cssSelector("button[type='submit']")).click();
        waitForModalClosed("projectModal");
        wait.until(textPresent(By.id("selectedProjectKey"), projectKey));
    }

    private static void createRequirement(String key, String text) {
        click(By.cssSelector("[data-bs-target='#requirementModal']"));
        WebElement modal = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("requirementModal")));
        fill(modal, "requirementKey", key);
        fill(modal, "requirementTitle", "Traceable resilient command communication");
        new Select(modal.findElement(By.id("requirementType"))).selectByValue("SECURITY");
        fill(modal, "requirementText", text);
        modal.findElement(By.cssSelector("button[type='submit']")).click();
        waitForModalClosed("requirementModal");
        wait.until(textPresent(By.cssSelector("#requirementsTable tbody"), key));
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

    private static void selectProfile(String value) {
        new Select(wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("copilotProfile")))).selectByValue(value);
    }

    private static void setChecked(String id, boolean checked) {
        WebElement element = driver.findElement(By.id(id));
        if (element.isSelected() != checked) element.click();
    }

    private static void open(String path, By readyElement) {
        driver.get(ContainerTestUtils.APP_ORIGIN + path);
        wait.until(ExpectedConditions.visibilityOfElementLocated(readyElement));
    }

    private static void waitForModalClosed(String modalId) {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id(modalId)));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector(".modal-backdrop.show")));
    }

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

    private static void fill(WebElement root, String id, String value) {
        WebElement element = root.findElement(By.id(id));
        element.clear();
        element.sendKeys(value);
    }

    private static ExpectedConditions.AttributeToBe attributeEquals(By locator,
                                                                     String attribute,
                                                                     String value) {
        return new ExpectedConditions.AttributeToBe(locator, attribute, value);
    }

    private static org.openqa.selenium.support.ui.ExpectedCondition<Boolean> textPresent(
            By locator, String expectedText) {
        return browser -> {
            List<WebElement> elements = browser.findElements(locator);
            return !elements.isEmpty()
                    && elements.getFirst().getText().toLowerCase(Locale.ROOT)
                    .contains(expectedText.toLowerCase(Locale.ROOT));
        };
    }

    private static JavascriptExecutor javascript() {
        return driver;
    }

    private static void dismissOnboardingWhenShown() {
        List<WebElement> dismissButtons = driver.findElements(By.id("onboardingDismiss"));
        if (dismissButtons.isEmpty() || !dismissButtons.getFirst().isDisplayed()) return;
        dismissButtons.getFirst().click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
    }

    private record ExportEvidence(int status,
                                  String contentType,
                                  String disposition,
                                  int byteLength,
                                  byte[] bytes) {
    }
}
