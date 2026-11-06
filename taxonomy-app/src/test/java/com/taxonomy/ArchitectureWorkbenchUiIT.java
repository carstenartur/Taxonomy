package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
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
@Tag("docker-required")
@Tag("ci-app-shard-browser")
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
        fitAndWait();
        assertDiagramFitsCanvas();
        assertViewportLayoutAndExportControls();

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


        if (Boolean.getBoolean("generateScreenshots")) {
            saveDocumentationScreenshot();
            saveEditorDocumentationScreenshots();
        }
    }

    private static void assertViewportLayoutAndExportControls() {
        Dimension previousSize = driver.manage().window().getSize();
        try {
            for (Dimension size : List.of(new Dimension(1366, 768), new Dimension(1024, 768))) {
                driver.manage().window().setSize(size);
                try {
                    wait.until(browser -> Boolean.TRUE.equals(javascript().executeScript("""
                            const canvas = document.getElementById('architectureCanvasShell').getBoundingClientRect();
                            const details = document.querySelector('.workbench-detail-panel').getBoundingClientRect();
                            return canvas.height > 0 && canvas.bottom <= innerHeight + 1
                                && details.bottom <= innerHeight + 1
                                && document.documentElement.scrollWidth <= innerWidth + 1;
                            """)));
                    fitAndWait();
                    assertDiagramFitsCanvas();
                } catch (RuntimeException | AssertionError failure) {
                    // Capture the failing viewport before finally restores the original window.
                    saveViewportFailure(size, failure);
                    throw failure;
                }
            }
            WebElement exports = driver.findElement(By.cssSelector("#architectureExportOptions > summary"));
            exports.sendKeys(Keys.ENTER);
            for (String id : List.of("downloadArchitectureSvg", "downloadArchitecturePdf",
                    "downloadArchitectureWord", "downloadDecisionWord",
                    "downloadArchitectureArchiMate", "downloadArchitectureVisio")) {
                assertThat(driver.findElement(By.id(id)).isDisplayed()).as("Export control %s", id).isTrue();
            }
            exports.sendKeys(Keys.ESCAPE);
            assertThat(driver.findElement(By.id("architectureExportOptions")).getDomAttribute("open")).isNull();
            assertThat(driver.switchTo().activeElement()).isEqualTo(exports);
        } finally {
            driver.manage().window().setSize(previousSize);
            fitAndWait();
        }
    }

    private static void saveViewportFailure(Dimension requestedSize, Throwable failure) {
        try {
            var directory = java.nio.file.Path.of("target", "architecture-workbench-native",
                    requestedSize.width + "x" + requestedSize.height);
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.write(directory.resolve("viewport.png"),
                    driver.getScreenshotAs(org.openqa.selenium.OutputType.BYTES));
            String state = String.valueOf(javascript().executeScript("""
                    const bounds = selector => {
                        const element = document.querySelector(selector);
                        if (!element) return null;
                        const style = getComputedStyle(element);
                        return {rect:element.getBoundingClientRect().toJSON(),display:style.display,
                            minHeight:style.minHeight,height:style.height,gridRows:style.gridTemplateRows,
                            overflow:style.overflow,snapshotHeight:style.getPropertyValue('--architecture-snapshot-height')};
                    };
                    return JSON.stringify({url:location.href,requestedWindow:{width:arguments[0],height:arguments[1]},
                        viewport:{innerWidth,innerHeight,outerWidth,outerHeight,scrollX,scrollY,
                            documentWidth:document.documentElement.scrollWidth},
                        canvas:bounds('#architectureCanvasShell'),details:bounds('.workbench-detail-panel'),
                        grid:bounds('.workbench-grid'),workbench:bounds('#architectureWorkbench'),
                        controls:bounds('.architecture-controls'),userAgent:navigator.userAgent},null,2);
                    """, requestedSize.width, requestedSize.height));
            java.nio.file.Files.writeString(directory.resolve("layout.json"), state);
        } catch (Exception evidenceFailure) {
            failure.addSuppressed(evidenceFailure);
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
            saveDocumentationCapture("71-copilot-architecture-workbench.png", null);
            String snapshotUrl = driver.getCurrentUrl();
            for (String language : List.of("de", "en")) {
                openDocumentationLanguage(snapshotUrl, language, By.id("architectureWorkbench"));
                wait.until(browser -> !browser.findElements(By.cssSelector(".architecture-node")).isEmpty());
                fitAndWait();
                assertDiagramFitsCanvas();
                saveDocumentationCapture("architecture-workbench-current-" + language + ".png", null);
            }
        } finally {
            driver.manage().window().setSize(previousSize);
            fitAndWait();
        }
    }

    private static void saveEditorDocumentationScreenshots() {
        open("/architecture/editor?lang=en", By.id("architectureEditor"));
        waitForEditor();
        List<WebElement> createWorkspace = driver.findElements(By.id("editorWorkspace"));
        if (!createWorkspace.isEmpty() && createWorkspace.getFirst().isDisplayed()) {
            createWorkspace.getFirst().click();
            wait.until(ExpectedConditions.elementToBeClickable(By.id("editorNew")));
        }
        createDocumentationElement("Clinical identity service");
        createDocumentationElement("Hospital communication platform");
        String editorUrl = driver.getCurrentUrl();
        for (String language : List.of("de", "en")) {
            openDocumentationLanguage(editorUrl, language, By.id("architectureEditor"));
            waitForEditor();
            assertThat(driver.findElement(By.id("editorTitle")).getDomProperty("value"))
                    .isEqualTo("Hospital communication platform");
            assertThat(driver.findElement(By.id("editorNew")).isEnabled()).isTrue();
            driver.findElement(By.id("editorFocus")).click();
            wait.until(browser -> !browser.findElements(By.cssSelector("#editorGraph .editor-node")).isEmpty());
            saveDocumentationCapture("architecture-editor-current-" + language + ".png",
                    driver.findElement(By.id("architectureEditor")));

            WebElement useAi = driver.findElement(By.id("editorLensUseAi"));
            if (useAi.isSelected()) useAi.click();
            WebElement enableLens = wait.until(ExpectedConditions.elementToBeClickable(By.id("editorLensEnabled")));
            if (!enableLens.isSelected()) enableLens.click();
            assertThat(enableLens.isSelected()).isTrue();
            assertThat(useAi.isSelected()).isFalse();
            saveDocumentationCapture("architecture-editor-focus-" + language + ".png",
                    driver.findElement(By.cssSelector(".editor-lens-panel")));
        }
    }

    private static void createDocumentationElement(String title) {
        wait.until(ExpectedConditions.elementToBeClickable(By.id("editorNew"))).click();
        new Select(driver.findElement(By.id("editorType"))).selectByValue("System");
        driver.findElement(By.id("editorTitle")).sendKeys(title);
        WebElement rationale = driver.findElement(By.id("editorRationale"));
        rationale.clear();
        rationale.sendKeys("Document the hospital communication example");
        driver.findElement(By.id("editorPreviewElement")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#editorPreviewDialog[open]")));
        wait.until(ExpectedConditions.elementToBeClickable(By.id("editorAccept"))).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("editorPreviewDialog")));
        waitForEditor();
        assertThat(driver.findElement(By.id("editorTitle")).getDomProperty("value")).isEqualTo(title);
    }

    private static void waitForEditor() {
        wait.until(browser -> "false".equals(browser.findElement(
                By.id("architectureEditor")).getDomAttribute("aria-busy")));
    }

    private static void openDocumentationLanguage(String sourceUrl, String language, By readyElement) {
        String translatedUrl = String.valueOf(javascript().executeScript("""
                const url = new URL(arguments[0]);
                url.searchParams.set('lang', arguments[1]);
                return url.href;
                """, sourceUrl, language));
        driver.get(translatedUrl);
        wait.until(ExpectedConditions.visibilityOfElementLocated(readyElement));
        assertThat(javascript().executeScript("return document.documentElement.lang")).isEqualTo(language);
    }

    private static void saveDocumentationCapture(String filename, WebElement element) {
        try {
            java.nio.file.Path output = documentationScreenshotDirectory().resolve(filename);
            java.nio.file.Files.createDirectories(output.getParent());
            byte[] bytes = element == null ? driver.getScreenshotAs(org.openqa.selenium.OutputType.BYTES)
                    : element.getScreenshotAs(org.openqa.selenium.OutputType.BYTES);
            java.nio.file.Files.write(output, bytes);
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not save architecture screenshot " + filename, exception);
        }
    }

    private static java.nio.file.Path documentationScreenshotDirectory() {
        String configured = System.getenv("TAXONOMY_DOCS_IMAGE_DIR");
        if (configured == null || configured.isBlank()) configured = System.getProperty("taxonomy.docs.image.dir");
        if (configured != null && !configured.isBlank()) {
            return java.nio.file.Path.of(configured);
        }
        java.nio.file.Path module = java.nio.file.Path.of(
                System.getProperty("project.basedir", "."))
                .toAbsolutePath()
                .normalize();
        java.nio.file.Path repository = module.getParent();
        if (repository == null) {
            repository = module;
        }
        return repository.resolve("docs/images");
    }

    private static void login() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("applicationWorkspaces")));
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
