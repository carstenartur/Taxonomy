package com.taxonomy.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.support.ui.WebDriverWait;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the same resource/download contract under either Maven-selected runtime. */
@Tag("browser")
class BrowserSessionIT {
    @TempDir Path downloads;

    @Test void browserRendersDownloadsCapturesEvidenceAndReleasesItsSession() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", exchange -> {
            boolean download = exchange.getRequestURI().getPath().equals("/report");
            byte[] body = (download ? "{\"text\":\"Grüße — preserved\"}" : """
                    <!doctype html><meta charset="utf-8"><title>Browser runtime contract</title>
                    <h1>Grüße — preserved</h1><a href="/report">Download report</a>
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", download ? "application/json" : "text/html; charset=utf-8");
            if (download) exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=report.json");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            var session = BrowserSession.open(server.getAddress().getPort(), downloads);
            var driver = session.driver();
            try (session) {
                driver.get(session.origin());
                assertThat(driver.findElement(By.tagName("h1")).getText()).isEqualTo("Grüße — preserved");
                driver.findElement(By.linkText("Download report")).click();
                new WebDriverWait(driver, Duration.ofSeconds(20))
                        .until(ignored -> session.downloadedFiles().contains("report.json"));
                assertThat(Files.readString(session.download("report.json")))
                        .isEqualTo("{\"text\":\"Grüße — preserved\"}");
                byte[] screenshot = driver.getScreenshotAs(OutputType.BYTES);
                assertThat(screenshot).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
                Path evidence = Path.of("target/browser-runtime");
                Files.createDirectories(evidence);
                Files.write(evidence.resolve("browser.png"), screenshot);
            }
            assertThat(driver.getSessionId()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
