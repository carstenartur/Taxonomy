package com.taxonomy;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures the real successful persisted result while the authoritative
 * complete Copilot browser session continues through its later failure cases.
 */
public final class CompleteCopilotScreenshotWatcher
        implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    private static volatile Thread monitor;
    private static volatile boolean required;

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        required = Boolean.getBoolean("generateScreenshots")
                && "CompleteCopilotSessionIT".equals(
                        context.getRequiredTestClass().getSimpleName());
        if (!required || !ACTIVE.compareAndSet(false, true)) {
            return;
        }
        CAPTURED.set(false);
        FAILURE.set(null);
        monitor = Thread.ofPlatform()
                .name("complete-copilot-screenshot-watcher")
                .daemon(true)
                .start(() -> monitor(context.getRequiredTestClass()));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        if (!required) {
            return;
        }
        ACTIVE.set(false);
        Thread running = monitor;
        if (running != null) {
            running.join(Duration.ofSeconds(10));
        }
        Throwable failure = FAILURE.get();
        if (failure != null) {
            throw new AssertionError("Complete Copilot screenshot watcher failed", failure);
        }
        if (!CAPTURED.get()) {
            throw new AssertionError(
                    "The complete Copilot session never exposed its persisted successful result");
        }
    }

    private static void monitor(Class<?> testClass) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(20));
        try {
            while (ACTIVE.get() && !CAPTURED.get() && Instant.now().isBefore(deadline)) {
                WebDriver driver = findDriver(testClass);
                if (driver != null && ready(driver)) {
                    capture(driver);
                    CAPTURED.set(true);
                    return;
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            FAILURE.compareAndSet(null, throwable);
        }
    }

    private static WebDriver findDriver(Class<?> testClass) {
        Class<?> current = testClass;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !WebDriver.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return (WebDriver) field.get(null);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Browser initialization is asynchronous; retry without changing the test.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean ready(WebDriver driver) {
        try {
            return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript("""
                    const operation = document.getElementById('copilotOperation');
                    const status = operation?.dataset.operationStatus;
                    const selectedSnapshot = new URL(location.href).searchParams.get('snapshot');
                    const card = document.getElementById('requirementCopilotCard');
                    const tabs = document.querySelector(
                        '#requirementMain [role="tablist"], #requirementMain .nav-tabs');
                    const busy = document.querySelector(
                        '[aria-busy="true"], .portfolio-busy:not(.d-none), '
                            + '.page-loading:not(.d-none), .loading-overlay:not(.d-none)');
                    return document.readyState === 'complete'
                        && Boolean(card)
                        && Boolean(operation)
                        && (status === 'SUCCESS' || status === 'PARTIAL')
                        && Boolean(selectedSnapshot)
                        && Boolean(tabs)
                        && !busy
                        && window.innerWidth >= 1100;
                    """));
        } catch (RuntimeException unavailableDuringNavigation) {
            return false;
        }
    }

    private static void capture(WebDriver driver) throws Exception {
        Dimension previousSize = driver.manage().window().getSize();
        try {
            driver.manage().window().setSize(new Dimension(1900, 1200));
            ((JavascriptExecutor) driver).executeScript("""
                    const card = document.getElementById('requirementCopilotCard');
                    if (card) card.scrollIntoView({block: 'start', inline: 'nearest'});
                    """);
            Thread.sleep(500);
            Path output = documentationScreenshotPath();
            Files.createDirectories(output.getParent());
            File screenshot = ((TakesScreenshot) driver)
                    .getScreenshotAs(OutputType.FILE);
            Files.copy(
                    screenshot.toPath(),
                    output,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            driver.manage().window().setSize(previousSize);
        }
    }

    private static Path documentationScreenshotPath() {
        Path module = Path.of(System.getProperty("project.basedir", "."))
                .toAbsolutePath()
                .normalize();
        Path repository = module.getParent();
        if (repository == null) {
            repository = module;
        }
        return repository.resolve("docs/images/72-complete-copilot-run-result.png");
    }
}
