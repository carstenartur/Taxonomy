package com.taxonomy;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

import java.net.URI;
import java.util.function.Supplier;

/** Failure-only evidence for the ONNX browser owner. */
final class OnnxBootstrapDiagnostics {
    private static final int TEXT_LIMIT = 1200;
    private static final int ERROR_LINE_LIMIT = 400;
    private static final int ERROR_TOTAL_LIMIT = 2400;

    private OnnxBootstrapDiagnostics() { }

    static String describe(WebDriver driver, Supplier<String> applicationLogs) {
        String applicationErrors = read(() -> applicationLogs.get().lines()
                .filter(line -> line.contains("ERROR") || line.contains("Exception")
                        || line.contains("Caused by:"))
                .limit(20)
                .map(line -> redactAndBound(line, ERROR_LINE_LIMIT))
                .collect(java.util.stream.Collectors.joining("\n")), ERROR_TOTAL_LIMIT);
        return "ONNX workbench bootstrap: path="
                + read(() -> URI.create(driver.getCurrentUrl()).getPath(), TEXT_LIMIT)
                + "; title=" + read(() -> driver.getTitle(), TEXT_LIMIT)
                + "; loginForm=" + read(() -> !driver.findElements(By.name("username")).isEmpty(), TEXT_LIMIT)
                + "; documentState=" + read(() -> ((JavascriptExecutor) driver)
                        .executeScript("return document.readyState"), TEXT_LIMIT)
                + "; visiblePage=" + read(() -> driver.findElement(By.tagName("body")).getText(), TEXT_LIMIT)
                + "; applicationErrors=" + applicationErrors;
    }

    private static String read(Supplier<?> operation, int limit) {
        try {
            return redactAndBound(String.valueOf(operation.get()), limit);
        } catch (RuntimeException unavailable) {
            return redactAndBound("[unavailable: " + unavailable.getClass().getSimpleName() + "]", limit);
        }
    }

    private static String redactAndBound(String value, int limit) {
        String redacted = value.replace(ContainerTestUtils.TEST_ADMIN_PASSWORD, "[redacted]");
        return redacted.substring(0, Math.min(redacted.length(), limit));
    }
}
