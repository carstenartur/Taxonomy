package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** The ONNX owner must observe the login response before sending another navigation. */
@Tag("browser")
@Tag("ci-app-shard-browser")
class OnnxBrowserBootstrapIT {
    enum FailedRead { PATH, TITLE, LOGIN_FORM, DOCUMENT_STATE, BODY_MISSING, BODY_STALE, APPLICATION_LOGS }

    @ParameterizedTest
    @EnumSource(FailedRead.class)
    void diagnosticReadFailurePreservesTheTreeTimeoutAndOtherEvidence(FailedRead failed) {
        WebDriver driver = diagnosticBrowser("Browser reached error page");
        var missingTree = new NoSuchElementException("Tree not rendered");
        when(driver.findElement(By.id("taxonomyTree"))).thenThrow(missingTree);
        RuntimeException unavailable = switch (failed) {
            case BODY_MISSING -> new NoSuchElementException("must not disclose admin");
            case BODY_STALE -> new StaleElementReferenceException("must not disclose admin");
            default -> new IllegalStateException("must not disclose admin");
        };
        String field = switch (failed) {
            case PATH -> { when(driver.getCurrentUrl()).thenThrow(unavailable); yield "path"; }
            case TITLE -> { when(driver.getTitle()).thenThrow(unavailable); yield "title"; }
            case LOGIN_FORM -> {
                when(driver.findElements(By.name("username"))).thenThrow(unavailable); yield "loginForm";
            }
            case DOCUMENT_STATE -> {
                when(((JavascriptExecutor) driver).executeScript("return document.readyState"))
                        .thenThrow(unavailable); yield "documentState";
            }
            case BODY_MISSING -> {
                when(driver.findElement(By.tagName("body"))).thenThrow(unavailable); yield "visiblePage";
            }
            case BODY_STALE -> {
                when(driver.findElement(By.tagName("body")).getText()).thenThrow(unavailable); yield "visiblePage";
            }
            case APPLICATION_LOGS -> "applicationErrors";
        };
        Supplier<String> logs = () -> {
            if (failed == FailedRead.APPLICATION_LOGS) throw unavailable;
            return "INFO started\nERROR model load failed";
        };

        var treeCondition = ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree"));
        TimeoutException original = assertThrows(TimeoutException.class, () ->
                new WebDriverWait(driver, Duration.ZERO).until(treeCondition));
        TimeoutException timeout = assertThrows(TimeoutException.class, () ->
                new WebDriverWait(driver, Duration.ZERO)
                        .withMessage(() -> OnnxBootstrapDiagnostics.describe(driver, logs))
                        .until(treeCondition));

        assertSame(original.getCause(), timeout.getCause());
        assertThat(timeout.getMessage()).contains(field + "=[unavailable: "
                + unavailable.getClass().getSimpleName() + "]").doesNotContain("must not disclose");
        Map.of("path", "/workbench", "title", "Workbench", "loginForm", "false",
                "documentState", "complete", "visiblePage", "Browser reached error page",
                "applicationErrors", "ERROR model load failed").forEach((name, value) -> {
                    if (!name.equals(field)) assertThat(timeout.getMessage()).contains(name + "=" + value);
                });
    }

    @Test
    void visiblePageRedactsTheCompletePasswordBeforeItsTruncationBoundary() {
        String prefix = "x".repeat(1198);
        String message = OnnxBootstrapDiagnostics.describe(
                diagnosticBrowser(prefix + ContainerTestUtils.TEST_ADMIN_PASSWORD + "tail"), () -> "");

        assertThat(diagnosticValue(message, "visiblePage")).isEqualTo(prefix + "[r");
    }

    @Test
    void expandedRedactionStillFitsTheVisiblePageLimit() {
        String message = OnnxBootstrapDiagnostics.describe(
                diagnosticBrowser(ContainerTestUtils.TEST_ADMIN_PASSWORD.repeat(400)), () -> "");

        assertThat(diagnosticValue(message, "visiblePage"))
                .hasSize(1200).doesNotContain(ContainerTestUtils.TEST_ADMIN_PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "title", "documentState"})
    void otherBrowserTextIsAlsoRedactedAndBounded(String field) {
        WebDriver driver = diagnosticBrowser("Error page");
        String text = ContainerTestUtils.TEST_ADMIN_PASSWORD.repeat(500);
        switch (field) {
            case "path" -> when(driver.getCurrentUrl()).thenReturn("http://taxonomy.test/" + text);
            case "title" -> when(driver.getTitle()).thenReturn(text);
            case "documentState" -> when(((JavascriptExecutor) driver)
                    .executeScript("return document.readyState")).thenReturn(text);
            default -> throw new AssertionError(field);
        }

        String message = OnnxBootstrapDiagnostics.describe(driver, () -> "");

        assertThat(diagnosticValue(message, field))
                .hasSize(1200).doesNotContain(ContainerTestUtils.TEST_ADMIN_PASSWORD);
    }

    @Test
    void applicationErrorsHaveStrictRedactedLineAndTotalCharacterLimits() {
        String logs = ("ERROR " + ContainerTestUtils.TEST_ADMIN_PASSWORD.repeat(1000) + "\n").repeat(25);
        String message = OnnxBootstrapDiagnostics.describe(diagnosticBrowser("Error page"), () -> logs);
        String errors = diagnosticValue(message, "applicationErrors");

        assertThat(errors.length()).isLessThanOrEqualTo(2400);
        assertThat(errors).doesNotContain(ContainerTestUtils.TEST_ADMIN_PASSWORD);
        assertThat(errors.lines().toList()).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(400));
    }

    @Test
    void applicationErrorRedactionPrecedesItsLineBoundary() {
        String prefix = "ERROR " + "x".repeat(392);
        String message = OnnxBootstrapDiagnostics.describe(diagnosticBrowser("Error page"),
                () -> prefix + ContainerTestUtils.TEST_ADMIN_PASSWORD + "tail");

        assertThat(diagnosticValue(message, "applicationErrors")).isEqualTo(prefix + "[r");
    }

    @Test
    void applicationErrorsStillIncludeAtMostTwentyRelevantLines() {
        String logs = "INFO irrelevant\n" + "ERROR bounded\n".repeat(25);
        String message = OnnxBootstrapDiagnostics.describe(diagnosticBrowser("Error page"), () -> logs);

        assertThat(diagnosticValue(message, "applicationErrors").lines().toList())
                .hasSize(20).allMatch(line -> line.equals("ERROR bounded"));
    }

    private static WebDriver diagnosticBrowser(String bodyText) {
        WebDriver driver = mock(WebDriver.class, withSettings().extraInterfaces(JavascriptExecutor.class));
        WebElement body = mock(WebElement.class);
        when(driver.getCurrentUrl()).thenReturn("http://taxonomy.test/workbench");
        when(driver.getTitle()).thenReturn("Workbench");
        when(driver.findElements(By.name("username"))).thenReturn(List.of());
        when(((JavascriptExecutor) driver).executeScript("return document.readyState")).thenReturn("complete");
        when(driver.findElement(By.tagName("body"))).thenReturn(body);
        when(body.getText()).thenReturn(bodyText);
        return driver;
    }

    private static String diagnosticValue(String message, String name) {
        int start = message.indexOf(name + "=") + name.length() + 1;
        int end = message.indexOf("; ", start);
        return message.substring(start, end == -1 ? message.length() : end);
    }

    @Test
    void loginSubmissionReachesTheProtectedWorkbenchBeforeAnotherNavigation(@TempDir Path downloads)
            throws Exception {
        try (var page = new PortfolioClientTestPage(downloads)) {
            var submissions = new AtomicInteger();
            page.route("POST", "/login", request -> {
                submissions.incrementAndGet();
                return new PortfolioClientTestPage.Response(302, "text/html", "")
                        .withHeader("Location", "/")
                        .withHeader("Set-Cookie", "login-fixture=authenticated; Path=/; HttpOnly; SameSite=Lax");
            });
            page.route("GET", "/", request -> {
                if (!"login-fixture=authenticated".equals(request.header("Cookie"))) {
                    return new PortfolioClientTestPage.Response(302, "text/html", "")
                            .withHeader("Location", "/login");
                }
                return new PortfolioClientTestPage.Response(200, "text/html",
                        "<!doctype html><title>Protected workbench</title><div id=\"taxonomyTree\"></div>");
            });
            // Hold the real form's submission in the browser task queue. An immediate
            // driver.get('/') cancels that task and lands on the anonymous login page.
            page.open("login", "", "/login", "en", """
                    document.addEventListener('submit', function (event) {
                        event.preventDefault();
                        var form = event.target;
                        window.setTimeout(function () {
                            HTMLFormElement.prototype.submit.call(form);
                        }, 250);
                    }, { once: true });
                    """);

            OnnxSeleniumIT.loginAndWaitForWorkbench(page.driver(), page.origin(),
                    () -> "Requests received: " + page.requests().stream()
                            .map(request -> request.method() + " " + request.path()).toList());

            assertThat(submissions.get()).isEqualTo(1);
            assertThat(page.driver().getTitle()).isEqualTo("Protected workbench");
            assertThat(page.requests())
                    .filteredOn(request -> request.method().equals("GET") && request.path().equals("/"))
                    .hasSize(1);
        }
    }
}
