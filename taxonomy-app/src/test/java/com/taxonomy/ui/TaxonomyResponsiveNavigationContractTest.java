package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the discoverable responsive navigation and pre-scroll task budget contract. */
class TaxonomyResponsiveNavigationContractTest {

    @Test
    void narrowViewportsUseAnExplicitSectionSelectorAndTaskJump() throws Exception {
        String utils = resource("/static/js/shared/taxonomy-utils.js");
        String css = resource("/static/css/taxonomy-ergonomics.css");
        String template = resource("/templates/index.html");

        assertThat(template)
                .contains("taxonomy:page-activated")
                .contains("detail: { page: pageName }");

        assertThat(utils)
                .contains("function installResponsiveMainNavigation()")
                .contains("mobileMainNavigationSelect")
                .contains("mobileCurrentTaskBtn")
                .contains("function authorizedMainNavigationLinks()")
                .contains("window.navigateToPage(page)")
                .contains("function focusCurrentTask()")
                .contains("target.scrollIntoView({ block: 'center', inline: 'nearest' })")
                .contains("new MutationObserver(syncResponsiveMainNavigation)");

        String navigation = between(css,
                "/* Discoverable responsive primary navigation. */",
                "/* A view switch must never move the view selector.");
        assertThat(ruleBody(navigation, "\\.mobile-main-navigation"))
                .as("the compact navigation does not duplicate desktop navigation")
                .containsPattern("display:\\s*none\\s*;");
        String narrow = ruleBody(navigation, "@media\\s*\\(max-width:\\s*991\\.98px\\)");
        assertThat(ruleBody(narrow, "#mainNavTabs"))
                .containsPattern("display:\\s*none\\s*!important\\s*;");
        assertThat(ruleBody(narrow, "\\.mobile-main-navigation"))
                .as("section selection and the task jump share a row at every narrow viewport height")
                .containsPattern("display:\\s*grid\\s*;")
                .containsPattern("grid-template-columns:\\s*minmax\\(0,\\s*1fr\\)\\s+auto\\s*;");
        assertThat(ruleBody(narrow,
                "\\.mobile-main-navigation-select\\s*,\\s*\\.mobile-current-task-button"))
                .as("both responsive controls retain usable pointer targets")
                .containsPattern("min-height:\\s*44px\\s*;");

        String zoom = ruleBody(css, "@media\\s*\\(max-width:\\s*640px\\)");
        assertThat(ruleBody(zoom, "\\.navbar-brand"))
                .containsPattern("width:\\s*100%\\s*;")
                .containsPattern("white-space:\\s*normal\\s*;");
        assertThat(ruleBody(zoom, "\\.navbar-version"))
                .as("the version and separator do not consume another line of task space")
                .containsPattern("display:\\s*none\\s*;");
        assertThat(css)
                .contains(".navbar .btn-outline-light")
                .contains("color: ButtonText !important")
                .contains("background-color: ButtonFace !important")
                .contains("border-color: ButtonText !important");
    }

    @Test
    void browserVerificationMeasuresBeforeScrollAndUsesTheResponsiveControl() throws Exception {
        String fixtures = repositoryFile(".github/scripts/ui-role-fixtures.mjs");
        String roleFlow = repositoryFile(".github/scripts/ui-role-state-flow.mjs");
        String mainNavigationHelper = between(
                fixtures,
                "export async function navigateToPage(page, pageId) {",
                "export async function navigateArchitectureSubtab(page, subtab) {");
        String roleNavigation = between(
                roleFlow,
                "  const adminTab = page.locator('#adminNavTab');",
                "  await navigateToPage(page, 'analyze');");

        assertThat(mainNavigationHelper)
                .contains("const responsive = page.locator('#mobileMainNavigationSelect')")
                .contains("await responsive.selectOption(pageId)")
                .doesNotContain("scrollIntoViewIfNeeded");

        assertThat(roleNavigation)
                .contains("const responsiveNavigation = page.locator('#mobileMainNavigationSelect')")
                .contains("if (await responsiveNavigation.isVisible().catch(() => false))")
                .contains("option[value=\"admin\"]")
                .contains("ADMIN responsive navigation is unavailable after role authorization")
                .contains("ADMIN desktop navigation is unavailable after role authorization")
                .contains("responsivePages.includes('admin')");
        assertThat(roleNavigation.indexOf(
                "if (await responsiveNavigation.isVisible().catch(() => false))"))
                .isLessThan(roleNavigation.indexOf("await adminLink.scrollIntoViewIfNeeded()"));

        assertThat(roleFlow)
                .contains("await navigateToPage(page, 'analyze')")
                .contains("responsiveNavigationInsideViewport")
                .contains("taskJumpInsideViewport")
                .contains("taskSurface.progressTop <= taskSurface.viewportHeight")
                .contains("Neither the primary action nor its explicit task jump is initially visible")
                .contains("Responsive section navigation is not discoverable before any automatic scroll")
                .contains("responsive navigation remains structurally available after task interactions")
                .doesNotContain("single-row scrollable main navigation");
    }

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

    /** Scope declarations to their actual selector/media block, including nested rules. */
    private static String ruleBody(String source, String selectorPattern) {
        var rule = Pattern.compile(selectorPattern + "\\s*\\{").matcher(source);
        assertThat(rule.find()).as("CSS rule %s", selectorPattern).isTrue();
        int start = rule.end();
        int depth = 1;
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(start, index);
        }
        throw new AssertionError("Unclosed CSS rule: " + selectorPattern);
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

    private static String resource(String path) throws IOException {
        try (InputStream input = TaxonomyResponsiveNavigationContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
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
