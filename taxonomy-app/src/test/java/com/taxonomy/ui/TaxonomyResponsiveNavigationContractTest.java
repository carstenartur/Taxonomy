package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the discoverable responsive navigation and pre-scroll task budget contract. */
class TaxonomyResponsiveNavigationContractTest {

    @Test
    void browserAuthenticationStartsOnlyAfterApplicationReadiness() throws Exception {
        String containers = repositoryFile(
                "taxonomy-app/src/test/java/com/taxonomy/ContainerTestUtils.java");
        String portfolio = repositoryFile(
                "taxonomy-app/src/test/java/com/taxonomy/PortfolioUiAcceptanceIT.java");

        assertThat(occurrences(
                containers,
                "Wait.forHttp(\"/actuator/health/readiness\")"))
                .isEqualTo(2);
        assertThat(containers)
                .doesNotContain("Wait.forHttp(\"/actuator/health\")");
        String startup = between(portfolio,
                "static void startApplicationAndBrowser() throws Exception {",
                "private static void startApplication(String context) throws Exception {");
        assertThat(startup.indexOf("startApplication(\"\");"))
                .as("application startup completes before browser authentication")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(startup.indexOf("login();"));

        String applicationStartup = between(portfolio,
                "private static void startApplication(String context) throws Exception {",
                "private static URI applicationOrigin() {");
        assertThat(applicationStartup)
                .contains("contextPath = context;")
                .contains("awaitLocalReadiness();")
                .contains("Wait.forHttp(context + \"/actuator/health/readiness\").forStatusCode(200)");
        assertThat(applicationStartup.indexOf("awaitLocalReadiness();"))
                .isLessThan(applicationStartup.indexOf("return;"));
        String localReadiness = between(portfolio,
                "private static void awaitLocalReadiness() {",
                "private static void stopApplication() {");
        assertThat(localReadiness)
                .contains("origin.resolve(contextPath + \"/actuator/health/readiness\")")
                .contains("origin.resolve(contextPath + \"/api/status/startup\")")
                .contains("assertThat(health.statusCode())")
                .contains("assertThat(startup.statusCode())")
                .contains(".path(\"initialized\").asBoolean()")
                .contains(".isTrue();");

        String login = between(portfolio,
                "private static void login() {",
                "private static void open(String path, By readyElement) {");
        assertThat(login)
                .contains("Configured test administrator was rejected after readiness")
                .contains("driver.get(browserOrigin + contextPath + \"/login\");")
                .contains("driver.get(browserOrigin + contextPath + \"/\");")
                .contains("doesNotContain(\"/change-password\")")
                .doesNotContain("ContainerTestUtils.APP_ORIGIN");
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(start).as("start marker %s", startMarker).isGreaterThanOrEqualTo(0);
        assertThat(end).as("end marker %s", endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }

    private static String repositoryFile(String relative) throws IOException {
        Path root = findRepositoryRoot();
        Path file = root.resolve(relative).normalize();
        assertThat(file).exists();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".github"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from "
                + Path.of("").toAbsolutePath());
    }
}
