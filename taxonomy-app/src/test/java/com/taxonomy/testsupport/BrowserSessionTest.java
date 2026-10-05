package com.taxonomy.testsupport;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
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

    @Test void localModeFailsBeforeStartupWhenTheDriverIsMissing() {
        var properties = new Properties();
        properties.setProperty("taxonomy.test.browser", "local");
        assertThatThrownBy(() -> BrowserSession.open(12345, output, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webdriver.chrome.driver");
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
}
