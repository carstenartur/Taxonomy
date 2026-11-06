package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The ONNX owner must observe the login response before sending another navigation. */
@Tag("browser")
@Tag("ci-app-shard-browser")
class OnnxBrowserBootstrapIT {
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
