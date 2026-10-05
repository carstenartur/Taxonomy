package com.taxonomy.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.openqa.selenium.BuildInfo;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.testcontainers.Testcontainers;
import org.testcontainers.selenium.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;

/** Same browser contract against a host Spring Boot/HTTP server, locally or through Docker. */
public final class BrowserSession implements AutoCloseable {
    /** Explicit non-secret browser configuration to retain across real application JVMs. */
    public static final List<String> JVM_PROPERTIES = List.of(
            "taxonomy.test.browser", "webdriver.chrome.driver", "scenario.chrome.binary",
            "selenium.container.image", "scenario.screenshot.directory");

    enum Runtime { LOCAL, CONTAINER }

    private final RemoteWebDriver driver;
    private final Runnable stopInfrastructure;
    private final Runtime runtime;
    private final String origin;
    private final Path downloads;
    private final Path localDownloads;
    private boolean closed;

    BrowserSession(RemoteWebDriver driver, Runnable stopInfrastructure, Runtime runtime,
                   String origin, Path downloads, Path localDownloads) {
        this.driver = driver;
        this.stopInfrastructure = stopInfrastructure;
        this.runtime = runtime;
        this.origin = origin;
        this.downloads = downloads;
        this.localDownloads = localDownloads;
    }

    static Runtime runtime(Properties properties) {
        String explicit = properties.getProperty("taxonomy.test.browser", "").trim();
        return switch (explicit) {
            case "local" -> Runtime.LOCAL;
            case "container" -> Runtime.CONTAINER;
            case "" -> properties.getProperty("webdriver.chrome.driver", "").isBlank()
                    ? Runtime.CONTAINER : Runtime.LOCAL;
            default -> throw new IllegalArgumentException(
                    "Unknown taxonomy.test.browser '" + explicit + "'; use local or container");
        };
    }

    public static BrowserSession open(int port, Path downloads) throws IOException {
        return open(port, downloads, System.getProperties());
    }

    static BrowserSession open(int port, Path downloads, Properties properties) throws IOException {
        Runtime runtime = runtime(properties);
        var options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage");
        Path directory = downloads.toAbsolutePath().normalize();
        if (runtime == Runtime.LOCAL) {
            Path executable = executable(properties, "webdriver.chrome.driver");
            if (!properties.getProperty("scenario.chrome.binary", "").isBlank()) {
                options.setBinary(executable(properties, "scenario.chrome.binary").toString());
            }
            Files.createDirectories(directory);
            // Match managed container downloads: old evidence is not a new session's download.
            Path localDownloads = Files.createTempDirectory(directory, "session-");
            options.setExperimentalOption("prefs", Map.of(
                    "download.default_directory", localDownloads.toString(),
                    "download.prompt_for_download", false,
                    "plugins.always_open_pdf_externally", true));
            var service = new ChromeDriverService.Builder()
                    .usingDriverExecutable(executable.toFile()).usingAnyFreePort().build();
            try {
                var driver = new ChromeDriver(service, options);
                try {
                    // Headless Shell does not apply Chrome's profile download preferences.
                    // Set the same real download destination through the browser protocol.
                    driver.executeCdpCommand("Browser.setDownloadBehavior", Map.of(
                            "behavior", "allow", "downloadPath", localDownloads.toString()));
                    return new BrowserSession(driver, service::stop,
                            runtime, "http://localhost:" + port, directory, localDownloads);
                } catch (RuntimeException | Error failure) {
                    stopAfterFailure(driver::quit, failure);
                    throw failure;
                }
            } catch (RuntimeException | Error failure) {
                stopAfterFailure(service::stop, failure);
                throw failure;
            }
        }
        Files.createDirectories(directory);
        Testcontainers.exposeHostPorts(port);
        String origin = "http://host.testcontainers.internal:" + port;
        String version = new BuildInfo().getReleaseLabel();
        String image = properties.getProperty("selenium.container.image", "selenium/standalone-chrome:" + version);
        String tag = DockerImageName.parse(image).getVersionPart();
        if (!tag.equals(version) && !tag.startsWith(version + "-")) {
            throw new IllegalArgumentException("Selenium client " + version + " does not match browser image " + image);
        }
        var container = new BrowserWebDriverContainer(DockerImageName.parse(image))
                .withEnv("SE_NODE_ENABLE_MANAGED_DOWNLOADS", "true");
        options.setEnableDownloads(true);
        // Chrome must allow downloads from this isolated HTTP host bridge.
        options.addArguments("--unsafely-treat-insecure-origin-as-secure=" + origin);
        options.setExperimentalOption("prefs", Map.of("plugins.always_open_pdf_externally", true));
        try {
            container.start();
            return new BrowserSession(new RemoteWebDriver(container.getSeleniumAddress(), options),
                    container::close, runtime, origin, directory, directory);
        } catch (RuntimeException | Error failure) {
            stopAfterFailure(container::close, failure);
            throw failure;
        }
    }

    private static Path executable(Properties properties, String property) {
        String value = properties.getProperty(property, "");
        if (value.isBlank()) throw new IllegalArgumentException(
                "Local browser requires -D" + property + "=/absolute/path (see docs/testing/docker-free-tests.md)");
        Path path = Path.of(value);
        if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException(property + " must name an absolute executable file: " + value);
        }
        return path;
    }

    public RemoteWebDriver driver() { return driver; }
    public String origin() { return origin; }

    public Set<String> downloadedFiles() {
        if (runtime == Runtime.CONTAINER) return new TreeSet<>(driver.getDownloadableFiles());
        try (var files = Files.list(localDownloads)) {
            return new TreeSet<>(files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.endsWith(".crdownload") && !name.endsWith(".tmp")).toList());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public Path download(String name) throws IOException {
        Path destination = downloads.resolve(name).normalize();
        if (!downloads.equals(destination.getParent())) throw new IllegalArgumentException("Expected a download filename: " + name);
        Path source = localDownloads.resolve(name);
        if (runtime == Runtime.CONTAINER) {
            Path transfer = Files.createTempDirectory(downloads, "transfer-");
            driver.downloadFile(name, transfer);
            source = transfer.resolve(name);
        }
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }

    private static void stopAfterFailure(Runnable stop, Throwable failure) {
        try { stop.run(); }
        catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { driver.quit(); }
        catch (RuntimeException | Error failure) {
            stopAfterFailure(stopInfrastructure, failure);
            throw failure;
        }
        stopInfrastructure.run();
    }
}
