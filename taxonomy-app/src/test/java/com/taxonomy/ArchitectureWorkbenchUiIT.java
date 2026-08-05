package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser acceptance for the server-authoritative architecture workbench.
 *
 * <p>The test creates its fixture through the authenticated browser API, waits
 * for a real persisted analysis snapshot and then proves that the UI, SVG and
 * PDF endpoints expose the architecture model rather than the surrounding page.</p>
 */
@Tag("ui-acceptance")
class ArchitectureWorkbenchUiIT {

    private static final String ADMIN_PASSWORD = "Architecture-Workbench-Ui-2026!";

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
        wait = new WebDriverWait(driver, Duration.ofSeconds(120));
        login();
    }

    @AfterAll
    static void stopApplicationAndBrowser() throws Exception {
        ContainerTestUtils.closeAll(browserSession, application, network);
    }

    @Test
    void rendersPersistedArchitectureAndExportsModelArtifacts() {
        open("/projects?lang=en", By.id("portfolioMain"));

        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> project = requestJson(
                "POST",
                "/api/projects",
                mapOf(
                        "projectKey", "P-ARCH-" + suffix,
                        "title", "Architecture workbench acceptance",
                        "description", "Verifies a persisted architecture instead of a page screenshot.",
                        "status", "ACTIVE"),
                201);
        long projectId = number(project.get("id"));

        Map<String, Object> requirement = requestJson(
                "POST",
                "/api/projects/" + projectId + "/requirements",
                mapOf(
                        "requirementKey", "REQ-ARCH-001",
                        "title", "Secure command exchange",
                        "text", "Provide secure resilient command information exchange with auditable decisions.",
                        "status", "APPROVED",
                        "priority", 90,
                        "criticality", "MISSION_CRITICAL",
                        "requirementType", "SECURITY",
                        "reviewStatus", "CONFIRMED",
                        "changeReason", "Browser acceptance fixture"),
                201);
        long requirementId = number(requirement.get("id"));

        Map<String, Object> job = requestJson(
                "POST",
                "/api/projects/" + projectId + "/requirements/" + requirementId + "/analyses",
                mapOf(
                        "maxArchitectureNodes", 20,
                        "idempotencyKey", "architecture-workbench-" + suffix),
                202);
        String jobId = String.valueOf(job.get("id"));

        Map<String, Object> completed = new WebDriverWait(driver, Duration.ofMinutes(4))
                .until(browser -> {
                    Map<String, Object> current = requestJson(
                            "GET",
                            "/api/projects/" + projectId + "/analysis-jobs/" + jobId,
                            null,
                            200);
                    String status = String.valueOf(current.get("status"));
                    return switch (status) {
                        case "SUCCESS", "PARTIAL" -> current;
                        case "FAILED", "CANCELLED" -> throw new AssertionError(
                                "Architecture analysis ended with " + status + ": "
                                        + current.get("errorSummary"));
                        default -> null;
                    };
                });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) completed.get("items");
        assertThat(items).isNotEmpty();
        String snapshotId = String.valueOf(items.getFirst().get("snapshotId"));
        assertThat(snapshotId).isNotBlank().isNotEqualTo("null");

        open("/projects/" + projectId + "/requirements/" + requirementId + "/architecture",
                By.id("architectureCanvas"));
        wait.until(browser -> browser.findElement(By.id("architectureStatus"))
                .getText().startsWith("Loaded "));
        wait.until(browser -> !browser.findElements(By.cssSelector(".architecture-node")).isEmpty());

        List<WebElement> nodes = driver.findElements(By.cssSelector(".architecture-node"));
        assertThat(nodes).isNotEmpty();
        String nodeCode = nodes.getFirst().findElement(By.cssSelector("text")).getText();
        assertThat(nodeCode).isNotBlank();

        nodes.getFirst().click();
        wait.until(browser -> !browser.findElements(
                By.cssSelector("#architectureDetails dd")).isEmpty());
        assertThat(driver.findElement(By.id("architectureProvenance")).getText())
                .contains(snapshotId)
                .contains("branch");
        assertThat(driver.findElement(By.id("requirementText")).getText())
                .contains("secure resilient command information exchange");

        Map<String, Object> svg = requestText(
                "/api/projects/" + projectId + "/architecture-workbench/" + snapshotId + ".svg");
        assertThat(number(svg.get("status"))).isEqualTo(200);
        assertThat(String.valueOf(svg.get("contentType"))).startsWith("image/svg+xml");
        assertThat(String.valueOf(svg.get("text")))
                .contains("<svg")
                .contains(nodeCode)
                .doesNotContain("<script")
                .doesNotContain("Download PDF");

        Map<String, Object> pdf = requestBinaryPrefix(
                "/api/projects/" + projectId + "/architecture-workbench/" + snapshotId + ".pdf");
        assertThat(number(pdf.get("status"))).isEqualTo(200);
        assertThat(String.valueOf(pdf.get("contentType"))).startsWith("application/pdf");
        assertThat(String.valueOf(pdf.get("prefix"))).isEqualTo("%PDF-");
        assertThat(number(pdf.get("length"))).isGreaterThan(500);
    }

    private static void login() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
        List<WebElement> dismiss = driver.findElements(By.id("onboardingDismiss"));
        if (!dismiss.isEmpty() && dismiss.getFirst().isDisplayed()) {
            dismiss.getFirst().click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
    }

    private static void open(String path, By readyElement) {
        driver.get(ContainerTestUtils.APP_ORIGIN + path);
        wait.until(ExpectedConditions.visibilityOfElementLocated(readyElement));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestJson(
            String method,
            String path,
            Map<String, Object> body,
            int expectedStatus) {
        Map<String, Object> response = (Map<String, Object>) javascript().executeAsyncScript("""
                const method = arguments[0];
                const path = arguments[1];
                const body = arguments[2];
                const done = arguments[arguments.length - 1];
                const headers = {Accept: 'application/json'};
                const token = document.querySelector('meta[name="_csrf"]')?.content;
                const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content
                    || 'X-CSRF-TOKEN';
                if (body !== null) headers['Content-Type'] = 'application/json';
                if (token) headers[csrfHeader] = token;
                fetch(path, {
                    method: method,
                    headers: headers,
                    credentials: 'same-origin',
                    body: body === null ? undefined : JSON.stringify(body)
                }).then(async response => {
                    const text = await response.text();
                    let parsed = null;
                    try { parsed = text ? JSON.parse(text) : null; } catch (ignored) { }
                    done({
                        status: response.status,
                        contentType: response.headers.get('content-type') || '',
                        body: parsed,
                        text: text
                    });
                }).catch(error => done({status: 0, error: String(error)}));
                """, method, path, body);
        assertThat(number(response.get("status")))
                .as("HTTP %s %s response: %s", method, path, response)
                .isEqualTo(expectedStatus);
        Object responseBody = response.get("body");
        assertThat(responseBody)
                .as("JSON body for %s %s", method, path)
                .isInstanceOf(Map.class);
        return (Map<String, Object>) responseBody;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestText(String path) {
        return (Map<String, Object>) javascript().executeAsyncScript("""
                const path = arguments[0];
                const done = arguments[arguments.length - 1];
                fetch(path, {headers: {Accept: 'image/svg+xml'}, credentials: 'same-origin'})
                    .then(async response => done({
                        status: response.status,
                        contentType: response.headers.get('content-type') || '',
                        text: await response.text()
                    }))
                    .catch(error => done({status: 0, error: String(error)}));
                """, path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestBinaryPrefix(String path) {
        return (Map<String, Object>) javascript().executeAsyncScript("""
                const path = arguments[0];
                const done = arguments[arguments.length - 1];
                fetch(path, {headers: {Accept: 'application/pdf'}, credentials: 'same-origin'})
                    .then(async response => {
                        const bytes = new Uint8Array(await response.arrayBuffer());
                        const prefix = String.fromCharCode(...bytes.slice(0, 5));
                        done({
                            status: response.status,
                            contentType: response.headers.get('content-type') || '',
                            prefix: prefix,
                            length: bytes.length
                        });
                    })
                    .catch(error => done({status: 0, error: String(error)}));
                """, path);
    }

    private static JavascriptExecutor javascript() {
        return driver;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
