package com.taxonomy;

import com.taxonomy.acceptance.CivilianExportQa;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Network;
import org.testcontainers.selenium.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real browser journey. No intercepted application requests, injected HTML or result data. */
final class CivilianBrowserWalkthrough implements AutoCloseable {
    private final RemoteWebDriver driver;
    private final WebDriverWait wait;
    private final String origin;
    private final Path output;
    private final Path screenshots;
    private final Path downloads;
    private final Network network;
    private final BrowserWebDriverContainer containerBrowser;
    private final List<Map<String, Object>> controls = new ArrayList<>();
    private boolean completed;

    CivilianBrowserWalkthrough(int port, Path output) throws Exception {
        assertThat(Boolean.getBoolean("generateScreenshots")).isTrue();
        this.output = output;
        downloads = output.resolve("browser-downloads").toAbsolutePath();
        Files.createDirectories(downloads);
        screenshots = Path.of(System.getProperty("project.basedir", ".")).toAbsolutePath()
                .normalize().getParent().resolve("docs/images");
        Files.createDirectories(screenshots);
        if (System.getProperty("webdriver.chrome.driver") != null) {
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage");
            String binary = System.getProperty("civilian.chrome.binary");
            if (binary != null) options.setBinary(binary);
            options.setExperimentalOption("prefs", Map.of("download.default_directory", downloads.toString(),
                    "download.prompt_for_download", false, "plugins.always_open_pdf_externally", true));
            driver = new ChromeDriver(options);
            origin = "http://localhost:" + port;
            network = null;
            containerBrowser = null;
        } else {
            Testcontainers.exposeHostPorts(port);
            network = Network.newNetwork();
            String image = System.getProperty("selenium.container.image",
                    "selenium/standalone-chrome:" + new BuildInfo().getReleaseLabel());
            containerBrowser = new BrowserWebDriverContainer(DockerImageName.parse(image)).withNetwork(network)
                    .withEnv("SE_NODE_ENABLE_MANAGED_DOWNLOADS", "true");
            try {
                containerBrowser.start();
                var options = new ChromeOptions();
                options.setEnableDownloads(true);
                options.setExperimentalOption("prefs", Map.of("plugins.always_open_pdf_externally", true));
                driver = new RemoteWebDriver(containerBrowser.getSeleniumAddress(), options);
            } catch (RuntimeException failure) {
                containerBrowser.close();
                network.close();
                throw failure;
            }
            origin = "http://host.testcontainers.internal:" + port;
        }
        driver.manage().window().setSize(new Dimension(1600, 1100));
        wait = new WebDriverWait(driver, Duration.ofSeconds(45));
    }

    CivilianArchitectureAcceptanceTest.Run start(JsonNode fixture) throws Exception {
        driver.get(origin + "/login");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("username"))).sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(CivilianArchitectureAcceptanceTest.PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();
        wait.until(browser -> !browser.getCurrentUrl().contains("/login"));
        driver.get(origin + "/projects?lang=en");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("portfolioMain")));
        String projectKey = "CIV-FLOOD-" + UUID.randomUUID().toString().substring(0, 8);
        click(By.cssSelector("[data-bs-target='#projectModal']"));
        var modal = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("projectModal")));
        fill(modal, "projectKey", projectKey);
        fill(modal, "projectTitle", "Civilian flood information");
        fill(modal, "projectDescription", "GOV.UK service requirements and Environment Agency API contract; deterministic LLM responses.");
        modal.findElement(By.cssSelector("button[type='submit']")).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("projectModal")));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("selectedProjectKey"), projectKey));
        click(By.cssSelector("[data-bs-target='#requirementModal']"));
        modal = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("requirementModal")));
        fill(modal, "requirementKey", fixture.at("/requirement/key").asText());
        fill(modal, "requirementTitle", fixture.at("/requirement/title").asText());
        new Select(modal.findElement(By.id("requirementType"))).selectByValue("FUNCTIONAL");
        fill(modal, "requirementText", fixture.at("/requirement/text").asText());
        modal.findElement(By.cssSelector("button[type='submit']")).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("requirementModal")));
        var row = wait.until(browser -> browser.findElements(By.cssSelector("#requirementsTable tbody tr")).stream()
                .filter(element -> element.getText().contains("CIV-FLOOD-001")).findFirst().orElse(null));
        long requirementId = Long.parseLong(row.findElement(By.cssSelector(".requirement-snapshots"))
                .getAttribute("data-requirement-id"));
        long projectId = Long.parseLong(String.valueOf(driver.executeScript(
                "return window.localStorage.getItem('taxonomy.portfolio.projectId')")));
        driver.get(origin + "/projects/" + projectId + "/requirements/" + requirementId + "?lang=en");
        wait.until(ExpectedConditions.elementToBeClickable(By.id("copilotRun")));
        new Select(driver.findElement(By.id("copilotProfile"))).selectByValue("EXHAUSTIVE");
        inventory("requirement");
        screenshot("73-civilian-requirement.png");
        click(By.id("copilotRun"));
        wait.until(browser -> {
            String id = browser.findElement(By.id("copilotOperation")).getAttribute("data-operation-id");
            return id != null && !id.isBlank();
        });
        String operation = driver.findElement(By.id("copilotOperation")).getAttribute("data-operation-id");
        // Reload while real server work is in progress; operation identity must survive the browser.
        driver.navigate().refresh();
        wait.until(browser -> browser.getCurrentUrl().contains("snapshot=")
                || browser.findElements(By.id("copilotOperation")).stream()
                .anyMatch(element -> operation.equals(element.getAttribute("data-operation-id"))));
        return new CivilianArchitectureAcceptanceTest.Run(projectId, requirementId, operation);
    }

    void inspect(long projectId, long requirementId, String snapshotId, JsonNode projection,
                 Map<String, byte[]> artifacts) throws Exception {
        wait.until(browser -> browser.getCurrentUrl().contains("snapshot=" + snapshotId));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("snapshotResultOverview")));
        inventory("result");
        screenshot("74-civilian-result.png");
        driver.navigate().refresh();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("snapshotResultOverview")));
        assertThat(driver.getCurrentUrl()).contains("snapshot=" + snapshotId);
        driver.get(origin + "/projects/" + projectId + "/requirements/" + requirementId + "/architecture?lang=en");
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("architectureStatus"), "Loaded "));
        wait.until(browser -> browser.findElements(By.cssSelector(".architecture-node")).size() > 0);
        assertThat(driver.findElement(By.id("architectureProvenance")).getText()).contains(snapshotId);
        click(By.id("fitArchitecture"));
        awaitFit();
        inventory("architecture");
        screenshot("75-civilian-architecture.png");

        int total = driver.findElements(By.cssSelector(".architecture-node")).size();
        var search = driver.findElement(By.id("architectureSearch"));
        search.sendKeys("CI-1052");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".architecture-node.is-search-match[data-node-id='CI-1052']")));
        click(By.id("clearArchitectureSearch"));
        wait.until(browser -> browser.findElements(By.cssSelector(".architecture-node.is-search-match")).isEmpty());
        click(By.cssSelector(".architecture-node[data-node-id='CI-1052']"));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("architectureDetails"), "CI-1052"));
        click(By.id("architectureFocus"));
        wait.until(ExpectedConditions.attributeToBe(By.id("architectureFocus"), "aria-pressed", "true"));
        Set<String> neighbors = new TreeSet<>(Set.of("CI-1052"));
        for (JsonNode edge : projection.at("/scene/edges")) {
            if (edge.path("sourceId").asText().equals("CI-1052")) neighbors.add(edge.path("targetId").asText());
            if (edge.path("targetId").asText().equals("CI-1052")) neighbors.add(edge.path("sourceId").asText());
        }
        assertThat(neighbors.size()).isLessThan(total);
        wait.until(browser -> visibleNodeIds().equals(neighbors));
        click(By.id("fitArchitecture"));
        awaitFit();
        screenshot("76-civilian-focus.png");
        click(By.id("architectureOverview"));
        wait.until(browser -> browser.findElements(By.cssSelector(".architecture-node")).size() == total);
        click(By.id("showArchitectureContext"));
        assertThat(driver.findElement(By.id("showArchitectureContext")).isSelected()).isFalse();
        Set<String> anchors = new TreeSet<>();
        projection.at("/scene/nodes").forEach(node -> {
            if (node.path("anchor").asBoolean()) anchors.add(node.path("id").asText());
        });
        wait.until(browser -> visibleNodeIds().equals(anchors));
        click(By.id("showArchitectureContext"));
        assertThat(driver.findElement(By.id("showArchitectureContext")).isSelected()).isTrue();
        wait.until(browser -> visibleNodeIds().size() == total);
        click(By.id("fitArchitecture"));
        awaitFit();
        double before = zoom();
        click(By.id("zoomArchitectureIn"));
        wait.until(browser -> zoom() > before);
        click(By.id("zoomArchitectureOut"));
        wait.until(browser -> zoom() <= before + .01);
        click(By.id("fitArchitecture"));
        click(By.id("fullscreenArchitecture"));
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript("return !!document.fullscreenElement")));
        click(By.id("fullscreenArchitecture"));
        wait.until(browser -> Boolean.FALSE.equals(driver.executeScript("return !!document.fullscreenElement")));
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("downloadArchitectureSvg", "architecture.svg");
        buttons.put("downloadArchitecturePdf", "architecture.pdf");
        buttons.put("downloadArchitectureArchiMate", "architecture.archimate.zip");
        buttons.put("downloadArchitectureVisio", "architecture.visio.zip");
        var downloaded = new LinkedHashMap<>(artifacts);
        var downloadHashes = new LinkedHashMap<String, String>();
        for (var button : buttons.entrySet()) {
            Set<String> beforeDownload = downloadedFiles();
            click(By.id(button.getKey()));
            String name = wait.until(browser -> downloadedFiles().stream()
                    .filter(file -> !beforeDownload.contains(file)).findFirst().orElse(null));
            if (containerBrowser != null) driver.downloadFile(name, downloads);
            byte[] bytes = Files.readAllBytes(downloads.resolve(name));
            assertThat(bytes).as("Browser download %s", name).isNotEmpty();
            downloaded.put(button.getValue(), bytes);
            downloadHashes.put(button.getKey(), CivilianExportQa.sha256(bytes));
        }
        CivilianExportQa.verify(projection, downloaded, output, false);
        driver.manage().window().setSize(new Dimension(390, 844));
        click(By.id("fitArchitecture"));
        awaitFit();
        assertThat(Boolean.TRUE.equals(driver.executeScript(
                "return document.documentElement.scrollWidth <= window.innerWidth + 2"))).as("No horizontal page overflow").isTrue();
        screenshot("77-civilian-mobile.png");
        Files.writeString(output.resolve("controls.json"), new ObjectMapper().writeValueAsString(controls));
        Files.writeString(output.resolve("browser.json"), new ObjectMapper().writeValueAsString(Map.of(
                "browser", driver.getCapabilities().getBrowserName(), "version", driver.getCapabilities().getBrowserVersion(),
                "downloadSha256", downloadHashes, "contextNodeCount", total - anchors.size(),
                "snapshotId", snapshotId, "screenshots", List.of("73-civilian-requirement.png", "74-civilian-result.png",
                "75-civilian-architecture.png", "76-civilian-focus.png", "77-civilian-mobile.png"))));
        completed = true;
    }

    private double zoom() { return ((Number) driver.executeScript("return document.getElementById('architectureCanvas').__zoom.k")).doubleValue(); }
    private Set<String> visibleNodeIds() {
        Set<String> ids = new TreeSet<>();
        driver.findElements(By.cssSelector(".architecture-node"))
                .forEach(node -> ids.add(node.getAttribute("data-node-id")));
        return ids;
    }
    private Set<String> downloadedFiles() {
        try {
            Collection<String> names;
            if (containerBrowser != null) names = driver.getDownloadableFiles();
            else try (var files = Files.list(downloads)) {
                names = files.map(path -> path.getFileName().toString()).toList();
            }
            var completed = new TreeSet<String>();
            names.stream().filter(name -> name.endsWith(".svg") || name.endsWith(".pdf") || name.endsWith(".zip"))
                    .forEach(completed::add);
            return completed;
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private void awaitFit() {
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript("""
                const canvas = document.getElementById('architectureCanvas');
                if (canvas.__transition) return false;
                const bounds = canvas.getBoundingClientRect();
                return [...canvas.querySelectorAll('.architecture-node')].every(node => {
                    const box = node.getBoundingClientRect();
                    return box.left >= bounds.left - 2 && box.right <= bounds.right + 2
                        && box.top >= bounds.top - 2 && box.bottom <= bounds.bottom + 2;
                });
                """)));
    }
    @SuppressWarnings("unchecked")
    private void inventory(String page) {
        var entries = (List<Map<String, Object>>) driver.executeScript("""
                return [...document.querySelectorAll('button, input, select, a.btn')].map(e => ({
                    id:e.id || null, tag:e.tagName, label:e.getAttribute('aria-label') || e.title || e.innerText || e.name || '',
                    visible:!!(e.offsetWidth || e.offsetHeight), disabled:!!e.disabled
                }));
                """);
        entries.forEach(entry -> { var row = new LinkedHashMap<>(entry); row.put("page", page); controls.add(row); });
    }
    private void screenshot(String name) throws Exception {
        // D3 fit/zoom transitions must finish before documenting the actual viewport.
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript("""
                const canvas = document.getElementById('architectureCanvas');
                return !canvas || !canvas.__transition;
                """)));
        // Never alter the page or hide inconvenient state.
        driver.executeAsyncScript("const done=arguments[arguments.length-1]; requestAnimationFrame(()=>requestAnimationFrame(done));");
        byte[] bytes = driver.getScreenshotAs(OutputType.BYTES);
        Files.write(screenshots.resolve(name), bytes);
        Files.write(output.resolve(name), bytes);
    }
    private void click(By selector) {
        WebElement element = wait.until(ExpectedConditions.elementToBeClickable(selector));
        driver.executeScript("arguments[0].scrollIntoView({block:'center'})", element);
        element.click();
    }
    private static void fill(WebElement root, String id, String text) {
        var field = root.findElement(By.id(id)); field.clear(); field.sendKeys(text);
    }
    @Override public void close() throws Exception {
        if (!completed) {
            // Preserve actual failure state for CI diagnosis without turning it into documentation.
            try {
                Files.write(output.resolve("browser-failure.png"), driver.getScreenshotAs(OutputType.BYTES));
                Files.writeString(output.resolve("browser-failure.html"), driver.getPageSource());
            } catch (Exception evidenceFailure) {
                System.err.println("Unable to capture civilian browser failure evidence: " + evidenceFailure.getClass().getSimpleName());
            }
        }
        try { driver.quit(); }
        finally {
            try { if (containerBrowser != null) containerBrowser.close(); }
            finally { if (network != null) network.close(); }
        }
    }
}
