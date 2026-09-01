package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
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
        driver.manage().window().setSize(new Dimension(1440, 1000));
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

        // The precise 202 + Location HTTP contract is owned by the MVC contract
        // tests. This browser acceptance only needs a successfully queued job so
        // it can verify the persisted Workbench, SVG and PDF path end to end.
        // GEMINI is a registered provider; LLM_MOCK=true intercepts its calls and
        // returns deterministic classpath scores without any external request.
        Map<String, Object> job = requestSuccessfulJson(
                "POST",
                "/api/projects/" + projectId + "/requirements/" + requirementId + "/analyses",
                mapOf(
                        "provider", "GEMINI",
                        "maxArchitectureNodes", 20,
                        "idempotencyKey", "architecture-workbench-" + suffix));
        assertThat(job.get("status")).isEqualTo("PENDING");
        String jobId = String.valueOf(job.get("id"));
        assertThat(jobId).isNotBlank().isNotEqualTo("null");

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
                By.id("architectureWorkbench"));
        wait.until(browser -> browser.findElement(By.id("architectureStatus"))
                .getText().startsWith("Loaded "));
        wait.until(browser -> !browser.findElements(By.cssSelector(".architecture-node")).isEmpty());
        wait.until(browser -> browser.findElements(By.cssSelector(".architecture-kpi")).size() >= 4);
        assertThat(driver.findElement(By.id("architectureTitle")).getText())
                .isNotBlank()
                .doesNotStartWith("archview.");
        WebElement exportFormat = driver.findElement(
        By.id("architectureExportFormat"));
    assertThat(exportFormat.isEnabled()).isTrue();
    assertThat(driver.findElement(By.id("downloadArchitectureExport")).isEnabled())
        .isTrue();
    List<String> exportFormats = exportFormat.findElements(By.tagName("option")).stream()
        .map(option -> option.getAttribute("value"))
        .toList();
    assertThat(exportFormats)
        .containsExactly("json", "svg", "pdf", "archimate", "mermaid", "structurizr")
        .doesNotContain("visio");

        fitAndWait();
        assertDiagramFitsCanvas();

        List<WebElement> nodes = driver.findElements(By.cssSelector(".architecture-node"));
        assertThat(nodes).isNotEmpty();
        String nodeCode = nodes.getFirst()
                .findElement(By.cssSelector(".architecture-node-code"))
                .getText();
        assertThat(nodeCode).isNotBlank();

        nodes.getFirst().click();
        wait.until(browser -> !browser.findElements(
                By.cssSelector("#architectureDetails dd")).isEmpty());
        assertThat(driver.findElement(By.id("architectureProvenance")).getText())
                .contains(snapshotId)
                .contains("branch");
        assertThat(driver.findElement(By.id("requirementText")).getText())
                .contains("secure resilient command information exchange");

        WebElement search = driver.findElement(By.id("architectureSearch"));
        search.sendKeys(nodeCode);
        wait.until(browser -> !browser.findElements(By.cssSelector(
                ".architecture-node.is-search-match[data-node-id='" + nodeCode + "']")).isEmpty());
        driver.findElement(By.id("clearArchitectureSearch")).click();
        wait.until(browser -> browser.findElements(
                By.cssSelector(".architecture-node.is-search-match")).isEmpty());

        int overviewNodeCount = driver.findElements(By.cssSelector(".architecture-node")).size();
        driver.findElement(By.id("architectureFocus")).click();
        wait.until(browser -> {
            int visible = browser.findElements(By.cssSelector(".architecture-node")).size();
            return visible > 0 && visible <= overviewNodeCount;
        });
        javascript().executeScript("""
                document.getElementById('architectureCanvas')
                    .dispatchEvent(new MouseEvent('click', {bubbles: true}));
                """);
        wait.until(browser -> "true".equals(browser.findElement(
                By.id("architectureOverview")).getAttribute("aria-pressed"))
                && "false".equals(browser.findElement(
                        By.id("architectureFocus")).getAttribute("aria-pressed"))
                && !browser.findElement(By.id("architectureFocus")).isEnabled()
                && browser.findElements(By.cssSelector(
                        ".architecture-node")).size() == overviewNodeCount);
        fitAndWait();
        assertDiagramFitsCanvas();

        String exportBase = "/api/projects/" + projectId
        + "/architecture-workbench/" + snapshotId + "/exports/";
    Map<String, Object> evidence = requestTextArtifact(
        exportBase + "json", "application/json");
    Map<String, Object> svg = requestTextArtifact(
        "/api/projects/" + projectId + "/architecture-workbench/"
            + snapshotId + ".svg",
        "image/svg+xml");
    Map<String, Object> pdf = requestBinaryPrefix(
        "/api/projects/" + projectId + "/architecture-workbench/"
            + snapshotId + ".pdf");
    Map<String, Object> archiMate = requestTextArtifact(
        exportBase + "archimate", "application/xml");
    Map<String, Object> mermaid = requestTextArtifact(
        exportBase + "mermaid", "text/plain");
    Map<String, Object> structurizr = requestTextArtifact(
        exportBase + "structurizr", "text/plain");

    String graphSha = String.valueOf(evidence.get("graphSha"));
    assertThat(graphSha).hasSize(64);
    for (Map<String, Object> artifact : List.of(
        evidence, svg, pdf, archiMate, mermaid, structurizr)) {
        assertThat(number(artifact.get("status"))).isEqualTo(200);
        assertThat(String.valueOf(artifact.get("snapshotId"))).isEqualTo(snapshotId);
        assertThat(String.valueOf(artifact.get("graphSha"))).isEqualTo(graphSha);
        assertThat(String.valueOf(artifact.get("contentSha"))).hasSize(64);
        assertThat(String.valueOf(artifact.get("profile"))).isNotBlank();
        assertThat(String.valueOf(artifact.get("role"))).isNotBlank();
    }

    assertThat(String.valueOf(evidence.get("contentType"))).startsWith("application/json");
    assertThat(String.valueOf(evidence.get("role"))).isEqualTo("canonical-evidence");
    assertThat(String.valueOf(evidence.get("text")))
        .contains(snapshotId)
        .contains(graphSha)
        .contains("canonical-evidence")
        .contains("experimental-model-exchange")
        .contains("lossy-text-projection");

    assertThat(String.valueOf(svg.get("contentType"))).startsWith("image/svg+xml");
    assertThat(String.valueOf(svg.get("role"))).isEqualTo("stable-human-view");
    assertThat(String.valueOf(svg.get("text")))
        .contains("<svg")
        .contains(nodeCode)
        .doesNotContain("<script")
        .doesNotContain("Download selected format");

    assertThat(String.valueOf(pdf.get("contentType"))).startsWith("application/pdf");
    assertThat(String.valueOf(pdf.get("role"))).isEqualTo("stable-human-view");
    assertThat(String.valueOf(pdf.get("prefix"))).isEqualTo("%PDF-");
    assertThat(number(pdf.get("length"))).isGreaterThan(500);

    assertThat(String.valueOf(archiMate.get("contentType"))).startsWith("application/xml");
    assertThat(String.valueOf(archiMate.get("role")))
        .isEqualTo("experimental-model-exchange");
    assertThat(String.valueOf(archiMate.get("text")))
        .contains("<model")
        .contains(nodeCode);

    assertThat(String.valueOf(mermaid.get("role"))).isEqualTo("lossy-text-projection");
    assertThat(String.valueOf(mermaid.get("text")))
        .contains("flowchart")
        .contains(nodeCode);
    assertThat(String.valueOf(structurizr.get("role")))
        .isEqualTo("lossy-text-projection");
    assertThat(String.valueOf(structurizr.get("text")))
        .contains("workspace");


        if (Boolean.getBoolean("generateScreenshots")) {
            saveDocumentationScreenshot();
        }
    }

    private static void fitAndWait() {
        javascript().executeScript("""
                window.__architectureFitReady = false;
                document.getElementById('fitArchitecture').click();
                window.setTimeout(() => { window.__architectureFitReady = true; }, 350);
                """);
        wait.until(browser -> Boolean.TRUE.equals(
                javascript().executeScript(
                        "return window.__architectureFitReady === true;")));
    }

    private static void assertDiagramFitsCanvas() {
        Object result = javascript().executeScript("""
                const shell = document.getElementById('architectureCanvasShell');
                const elements = Array.from(document.querySelectorAll(
                    '.architecture-layer-header, .architecture-node'));
                if (!shell || elements.length === 0) return false;
                const canvasBounds = shell.getBoundingClientRect();
                return elements.every(element => {
                    const bounds = element.getBoundingClientRect();
                    return bounds.left >= canvasBounds.left - 2
                        && bounds.right <= canvasBounds.right + 2
                        && bounds.top >= canvasBounds.top - 2
                        && bounds.bottom <= canvasBounds.bottom + 2;
                });
                """);
        assertThat(result).isEqualTo(Boolean.TRUE);
    }

    private static void saveDocumentationScreenshot() {
        Dimension previousSize = driver.manage().window().getSize();
        try {
            driver.manage().window().setSize(new Dimension(2200, 1300));
            javascript().executeScript("""
                    const diagram = document.getElementById('architectureCanvas');
                    diagram.dispatchEvent(new MouseEvent('click', {bubbles: true}));
                    """);
            wait.until(browser -> browser.findElements(By.cssSelector(
                    ".architecture-node.is-selected, .architecture-node.is-muted, "
                            + ".architecture-edge.is-selected, .architecture-edge.is-muted"))
                    .isEmpty());
            fitAndWait();
            assertDiagramFitsCanvas();
            java.nio.file.Path output = documentationScreenshotPath();
            java.nio.file.Files.createDirectories(output.getParent());
            java.io.File capture = ((org.openqa.selenium.TakesScreenshot) driver)
                    .getScreenshotAs(org.openqa.selenium.OutputType.FILE);
            java.nio.file.Files.copy(
                    capture.toPath(),
                    output,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not save Copilot workbench screenshot", exception);
        } finally {
            driver.manage().window().setSize(previousSize);
            fitAndWait();
        }
    }

    private static java.nio.file.Path documentationScreenshotPath() {
        java.nio.file.Path module = java.nio.file.Path.of(
                System.getProperty("project.basedir", "."))
                .toAbsolutePath()
                .normalize();
        java.nio.file.Path repository = module.getParent();
        if (repository == null) {
            repository = module;
        }
        return repository.resolve("docs/images/71-copilot-architecture-workbench.png");
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

    private static Map<String, Object> requestJson(
            String method,
            String path,
            Map<String, Object> body,
            int expectedStatus) {
        Map<String, Object> response = request(method, path, body);
        assertThat(number(response.get("status")))
                .as("HTTP %s %s response: %s", method, path, response)
                .isEqualTo(expectedStatus);
        return jsonBody(response, method, path);
    }

    private static Map<String, Object> requestSuccessfulJson(
            String method,
            String path,
            Map<String, Object> body) {
        Map<String, Object> response = request(method, path, body);
        assertThat(number(response.get("status")))
                .as("Successful HTTP %s %s response: %s", method, path, response)
                .isBetween(200L, 299L);
        return jsonBody(response, method, path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(
            String method,
            String path,
            Map<String, Object> body) {
        return (Map<String, Object>) javascript().executeAsyncScript("""
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
                        location: response.headers.get('location') || '',
                        contentType: response.headers.get('content-type') || '',
                        body: parsed,
                        text: text
                    });
                }).catch(error => done({status: 0, error: String(error)}));
                """, method, path, body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonBody(
            Map<String, Object> response,
            String method,
            String path) {
        Object responseBody = response.get("body");
        assertThat(responseBody)
                .as("JSON body for %s %s", method, path)
                .isInstanceOf(Map.class);
        return (Map<String, Object>) responseBody;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestTextArtifact(String path, String accept) {
        return (Map<String, Object>) javascript().executeAsyncScript("""
            const path = arguments[0];
            const accept = arguments[1];
            const done = arguments[arguments.length - 1];
            fetch(path, {
                headers: {Accept: accept},
                credentials: 'same-origin',
                cache: 'no-store'
            }).then(async response => done({
                status: response.status,
                contentType: response.headers.get('content-type') || '',
                snapshotId: response.headers.get('x-taxonomy-architecture-snapshot') || '',
                commitSha: response.headers.get('x-taxonomy-architecture-commit') || '',
                graphSha: response.headers.get('x-taxonomy-architecture-graph-sha256') || '',
                profile: response.headers.get('x-taxonomy-export-profile') || '',
                role: response.headers.get('x-taxonomy-export-role') || '',
                contentSha: response.headers.get('x-taxonomy-export-content-sha256') || '',
                text: await response.text()
            })).catch(error => done({status: 0, error: String(error)}));
            """, path, accept);
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
                            snapshotId: response.headers.get('x-taxonomy-architecture-snapshot') || '',
                            commitSha: response.headers.get('x-taxonomy-architecture-commit') || '',
                            graphSha: response.headers.get('x-taxonomy-architecture-graph-sha256') || '',
                            profile: response.headers.get('x-taxonomy-export-profile') || '',
                            role: response.headers.get('x-taxonomy-export-role') || '',
                            contentSha: response.headers.get('x-taxonomy-export-content-sha256') || '',
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
