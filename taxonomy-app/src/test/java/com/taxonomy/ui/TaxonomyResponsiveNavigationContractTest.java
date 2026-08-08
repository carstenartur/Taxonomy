package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the discoverable responsive navigation and pre-scroll task budget contract. */
class TaxonomyResponsiveNavigationContractTest {

    @Test
    void narrowViewportsUseAnExplicitSectionSelectorAndTaskJump() throws Exception {
        String utils = resource("/static/js/shared/taxonomy-utils.js");
        String css = resource("/static/css/taxonomy-ergonomics.css");

        assertThat(utils)
                .contains("function installResponsiveMainNavigation()")
                .contains("mobileMainNavigationSelect")
                .contains("mobileCurrentTaskBtn")
                .contains("function authorizedMainNavigationLinks()")
                .contains("window.navigateToPage(page)")
                .contains("function focusCurrentTask()")
                .contains("target.scrollIntoView({ block: 'center', inline: 'nearest' })")
                .contains("new MutationObserver(syncResponsiveMainNavigation)");

        assertThat(css)
                .contains("/* Discoverable responsive primary navigation. */")
                .containsPattern("(?s)#mainNavTabs\\s*\\{[^}]*display:\\s*none\\s*!important;")
                .containsPattern("(?s)\\.mobile-main-navigation\\s*\\{[^}]*display:\\s*grid;")
                .contains("min-height: 44px")
                .contains("grid-template-columns: 1fr");
    }

    @Test
    void browserVerificationMeasuresBeforeScrollAndUsesTheResponsiveControl() throws Exception {
        String fixtures = repositoryFile(".github/scripts/ui-role-fixtures.mjs");
        String roleFlow = repositoryFile(".github/scripts/ui-role-state-flow.mjs");
        String mainNavigationHelper = between(
                fixtures,
                "export async function navigateToPage(page, pageId) {",
                "export async function navigateArchitectureSubtab(page, subtab) {");

        assertThat(mainNavigationHelper)
                .contains("const responsive = page.locator('#mobileMainNavigationSelect')")
                .contains("await responsive.selectOption(pageId)")
                .doesNotContain("scrollIntoViewIfNeeded");

        assertThat(roleFlow)
                .contains("await navigateToPage(page, 'analyze')")
                .contains("responsiveNavigationInsideViewport")
                .contains("taskJumpInsideViewport")
                .contains("taskSurface.progressTop <= taskSurface.viewportHeight")
                .contains("Neither the primary action nor its explicit task jump is initially visible")
                .contains("discoverable responsive main navigation and current-task jump")
                .doesNotContain("single-row scrollable main navigation");
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
