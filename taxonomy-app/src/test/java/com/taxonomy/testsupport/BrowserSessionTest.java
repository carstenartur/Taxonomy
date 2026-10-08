package com.taxonomy.testsupport;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.manager.SeleniumManager;
import org.openqa.selenium.manager.SeleniumManagerOutput;
import org.openqa.selenium.remote.RemoteWebDriver;

class BrowserSessionTest {
    @TempDir Path output;

    @Test void preservesTheContainerDefaultAndLegacyLocalDriverSelection() {
        var properties = new Properties();
        assertThat(BrowserSession.runtime(properties)).isEqualTo(BrowserSession.Runtime.CONTAINER);
        properties.setProperty("webdriver.chrome.driver", "/local/chromedriver");
        assertThat(BrowserSession.runtime(properties)).isEqualTo(BrowserSession.Runtime.LOCAL);
    }

    @Test void explicitContainerModeOverridesAnInstalledLocalDriver() {
        var properties = new Properties();
        properties.setProperty("webdriver.chrome.driver", "/local/chromedriver");
        properties.setProperty("taxonomy.test.browser", "container");
        assertThat(BrowserSession.runtime(properties)).isEqualTo(BrowserSession.Runtime.CONTAINER);
    }

    @Test void rejectsUnknownModesInsteadOfFallingBackToDocker() {
        var properties = new Properties();
        properties.setProperty("taxonomy.test.browser", "loacl");
        assertThatThrownBy(() -> BrowserSession.open(12345, output, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taxonomy.test.browser").hasMessageContaining("loacl");
    }

    @Test void localModeProvisionsThePinnedBrowserAndDriverWithoutInstalledExecutables() throws Exception {
        var properties = managedProperties();
        Path driver = executable("managed-driver");
        Path browser = executable("managed-browser");
        var requested = new AtomicReference<List<String>>();
        var options = new AtomicReference<ChromeOptions>();
        var manager = manager(driver, browser, requested);
        try (var singleton = mockStatic(SeleniumManager.class);
             var drivers = mockConstruction(ChromeDriver.class, (created, context) -> {
                 options.set((ChromeOptions) context.arguments().get(1));
                 when(created.getCapabilities()).thenReturn(new ImmutableCapabilities(
                         "browserVersion", "149.0.7827.55", "chrome",
                         Map.of("chromedriverVersion", "149.0.7827.55 (fixture)")));
             })) {
            singleton.when(SeleniumManager::getInstance).thenReturn(manager);
            try (var session = BrowserSession.open(12345, output, properties)) {
                assertThat(session.origin()).isEqualTo("http://localhost:12345");
                assertThat(session.downloadedFiles()).isEmpty();
                assertThat(((Map<?, ?>) options.get().asMap().get("goog:chromeOptions")).get("binary"))
                        .isEqualTo(browser.toString());
                assertThat(requested.get()).containsSubsequence("--browser", "chrome",
                        "--browser-version", "149.0.7827.55", "--driver-version", "149.0.7827.55")
                        .contains("--force-browser-download", "--skip-driver-in-path", "--skip-browser-in-path")
                        .containsSubsequence("--cache-path", output.resolve("selenium-cache").toString());
            }
        }
    }

    @Test void localModeRejectsFloatingBrowserVersionsBeforeDownloading() {
        for (String version : List.of("", "stable", "149", "149.0")) {
            var properties = managedProperties();
            properties.setProperty("taxonomy.test.browser.version", version);
            assertThatThrownBy(() -> BrowserSession.open(12345, output, properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("taxonomy.test.browser.version");
        }
    }

    @Test void anExplicitBrowserSelectsItsDriverWithoutReplacingTheBrowser() throws Exception {
        var properties = managedProperties();
        Path browser = executable("explicit-browser");
        properties.setProperty("scenario.chrome.binary", browser.toString());
        var requested = new AtomicReference<List<String>>();
        var manager = manager(executable("matching-driver"), browser, requested);
        try (var singleton = mockStatic(SeleniumManager.class);
             var drivers = mockConstruction(ChromeDriver.class)) {
            singleton.when(SeleniumManager::getInstance).thenReturn(manager);
            try (var session = BrowserSession.open(12345, output, properties)) {
                assertThat(session.origin()).isEqualTo("http://localhost:12345");
                assertThat(requested.get()).containsSubsequence("--browser-path", browser.toString())
                        .doesNotContain("--browser-version", "--driver-version", "--force-browser-download");
            }
        }
    }

    @Test void explicitExecutablesRemainUsableWithoutRuntimeDownloads() throws Exception {
        var properties = managedProperties();
        properties.setProperty("webdriver.chrome.driver", executable("explicit-driver").toString());
        properties.setProperty("scenario.chrome.binary", executable("explicit-browser").toString());
        try (var singleton = mockStatic(SeleniumManager.class);
             var drivers = mockConstruction(ChromeDriver.class)) {
            singleton.when(SeleniumManager::getInstance)
                    .thenThrow(new AssertionError("Explicit executables must not invoke a downloader"));
            try (var session = BrowserSession.open(12345, output, properties)) {
                assertThat(session.origin()).isEqualTo("http://localhost:12345");
            }
        }
    }

    @Test void provisioningFailureRemainsATestFailureWithoutDockerFallback() {
        var failure = new IllegalStateException("Pinned browser download failed");
        var manager = mock(SeleniumManager.class);
        when(manager.getBinaryPaths(anyList())).thenThrow(failure);
        try (var singleton = mockStatic(SeleniumManager.class)) {
            singleton.when(SeleniumManager::getInstance).thenReturn(manager);
            assertThatThrownBy(() -> BrowserSession.open(12345, output, managedProperties()))
                    .isSameAs(failure);
        }
    }

    @Test void aManagedSessionRejectsBrowserOrDriverVersionsFromADifferentCacheEntry() throws Exception {
        for (var versions : List.of(List.of("149.0.7827.56", "149.0.7827.55"),
                List.of("149.0.7827.55", "149.0.7827.56"))) {
            var manager = manager(executable("cached-driver"), executable("cached-browser"), new AtomicReference<>());
            try (var singleton = mockStatic(SeleniumManager.class);
                 var drivers = mockConstruction(ChromeDriver.class, (created, context) ->
                         when(created.getCapabilities()).thenReturn(new ImmutableCapabilities(
                                 "browserVersion", versions.get(0), "chrome",
                                 Map.of("chromedriverVersion", versions.get(1) + " (fixture)"))))) {
                singleton.when(SeleniumManager::getInstance).thenReturn(manager);
                assertThatThrownBy(() -> BrowserSession.open(12345, output, managedProperties()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("149.0.7827.55").hasMessageContaining("149.0.7827.56");
            }
        }
    }

    @Test void offlineMavenModeReachesTheRuntimeResolver() throws Exception {
        var properties = managedProperties();
        properties.setProperty("taxonomy.test.browser.offline", "maven:true");
        var requested = new AtomicReference<List<String>>();
        var manager = manager(executable("cached-driver"), executable("cached-browser"), requested);
        try (var singleton = mockStatic(SeleniumManager.class);
             var drivers = mockConstruction(ChromeDriver.class, (created, context) ->
                     when(created.getCapabilities()).thenReturn(new ImmutableCapabilities(
                             "browserVersion", "149.0.7827.55", "chrome",
                             Map.of("chromedriverVersion", "149.0.7827.55 (fixture)"))))) {
            singleton.when(SeleniumManager::getInstance).thenReturn(manager);
            try (var session = BrowserSession.open(12345, output, properties)) {
                assertThat(session.origin()).isEqualTo("http://localhost:12345");
                assertThat(requested.get()).contains("--offline");
            }
        }
    }

    @Test void localModeRejectsANonExecutableDriverWithoutDownloadingOne() {
        var properties = new Properties();
        properties.setProperty("taxonomy.test.browser", "local");
        properties.setProperty("webdriver.chrome.driver", output.toString());
        assertThatThrownBy(() -> BrowserSession.open(12345, output, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executable").hasMessageContaining(output.toString());
    }

    @Test void releasesInfrastructureEvenWhenTheDriverCannotQuitAndClosesOnlyOnce() {
        var driver = mock(RemoteWebDriver.class);
        var failure = new IllegalStateException("lost browser connection");
        doThrow(failure).when(driver).quit();
        var stops = new AtomicInteger();
        var session = new BrowserSession(driver, stops::incrementAndGet,
                BrowserSession.Runtime.LOCAL, "http://localhost:12345", output, output);
        assertThatThrownBy(session::close).isSameAs(failure);
        session.close();
        assertThat(stops).hasValue(1);
        verify(driver, times(1)).quit();
    }

    @Test void retrievesContainerDownloadsBeforeReplacingExistingEvidence() throws Exception {
        Path report = output.resolve("report.json");
        Files.writeString(report, "previous report");
        var driver = mock(RemoteWebDriver.class);
        doAnswer(invocation -> {
            assertThat(Files.readString(report)).isEqualTo("previous report");
            Path directory = invocation.getArgument(1);
            // Selenium's binary download transport copies without REPLACE_EXISTING.
            try (var bytes = new ByteArrayInputStream("current report".getBytes(StandardCharsets.UTF_8))) {
                Files.copy(bytes, directory.resolve("report.json"));
            }
            return null;
        }).when(driver).downloadFile(eq("report.json"), any(Path.class));
        var session = new BrowserSession(driver, () -> {}, BrowserSession.Runtime.CONTAINER,
                "http://host.testcontainers.internal:12345", output, output);
        assertThat(session.download("report.json")).isEqualTo(report);
        assertThat(Files.readString(report)).isEqualTo("current report");
    }

    @Test void retainsBothShutdownFailuresForDiagnosis() {
        var driver = mock(RemoteWebDriver.class);
        var driverFailure = new IllegalStateException("driver failed");
        var infrastructureFailure = new IllegalStateException("infrastructure failed");
        doThrow(driverFailure).when(driver).quit();
        var session = new BrowserSession(driver, () -> { throw infrastructureFailure; },
                BrowserSession.Runtime.CONTAINER, "http://host.testcontainers.internal:12345", output, output);
        assertThatThrownBy(session::close).isSameAs(driverFailure)
                .hasSuppressedException(infrastructureFailure);
    }

    private Properties managedProperties() {
        var properties = new Properties();
        properties.setProperty("taxonomy.test.browser", "local");
        properties.setProperty("taxonomy.test.browser.version", "149.0.7827.55");
        properties.setProperty("taxonomy.test.browser.cache", output.resolve("selenium-cache").toString());
        return properties;
    }

    private Path executable(String name) throws Exception {
        Path executable = Files.writeString(output.resolve(name), "runtime fixture");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    private SeleniumManager manager(Path driver, Path browser, AtomicReference<List<String>> requested) {
        var result = mock(SeleniumManagerOutput.Result.class);
        when(result.getDriverPath()).thenReturn(driver.toString());
        when(result.getBrowserPath()).thenReturn(browser.toString());
        var manager = mock(SeleniumManager.class);
        when(manager.getBinaryPaths(anyList())).thenAnswer(invocation -> {
            requested.set(List.copyOf(invocation.getArgument(0)));
            return result;
        });
        return manager;
    }
}
