package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.By;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The actual template pages use the same shell without dropping document actions. */
@Tag("browser")
@Tag("ci-app-shard-browser")
class DocumentTemplateNavigationBrowserIT {
    private static PortfolioClientTestPage page;
    private static Path templateFile;
    private static final String FROM = "a".repeat(40), TO = "b".repeat(40);
    record View(String template, String path, String retained, String help) { }

    static Stream<Object[]> views() {
        return Stream.of(
                new View("document-templates", "/admin/document-templates", "documentTemplateBack", "DOCUMENT_TEMPLATES"),
                new View("document-template-detail", "/admin/document-templates/sample", "documentTemplateDetailBack", "DOCUMENT_TEMPLATES/template-versions"),
                new View("document-template-local-edit", "/admin/document-templates/sample/edit-local", "documentTemplateLocalBack", "DOCUMENT_TEMPLATES/template-editing"),
                new View("document-template-restore", "/admin/document-templates/sample/restore", "documentTemplateRestoreBack", "DOCUMENT_TEMPLATES/template-versions"),
                new View("document-template-part-comparison", "/admin/document-templates/sample/compare-part", "partComparisonBack", "DOCUMENT_TEMPLATES/template-versions"),
                new View("webdav-credentials", "/admin/webdav-credentials", "webDavCredentialsBack", "DOCUMENT_TEMPLATES/template-access"))
                .flatMap(view -> Stream.of(new Object[]{view, "en", 1440}, new Object[]{view, "de", 1024},
                        new Object[]{view, "de", 390}));
    }

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        templateFile = Files.writeString(downloads.resolve("selected-template.dotx"), "fixture");
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    @ParameterizedTest
    @MethodSource("views")
    void templateWorkflowsShareNavigationAndKeepTheirLocalActions(View view, String language, int width) {
        page.clearRoutes();
        var cdp = (HasCdp) new Augmenter().augment(page.driver());
        cdp.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
                "width", width, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
        var template = Map.of("templateId", "sample", "displayName", "Sample template", "headCommit", TO,
                "fileName", "sample.dotx", "updatedAt", "2026-11-05", "updatedBy", "alice",
                "partCount", 1, "uncompressedSize", 128);
        var model = Map.<String, Object>ofEntries(Map.entry("template", template), Map.entry("templateId", "sample"),
                Map.entry("directWordEnabled", false), Map.entry("templatePreviewAvailable", false),
                Map.entry("maxArchiveBytes", 100000), Map.entry("history", List.of()), Map.entry("credentials", List.of()),
                Map.entry("restoreTarget", Map.of("displayName", "Sample", "updatedBy", "alice", "updatedAt", "2026-11-05")),
                Map.entry("restoreRevision", FROM), Map.entry("restoreExpectedHead", TO), Map.entry("restoreConflict", false),
                Map.entry("fromRevision", FROM), Map.entry("toRevision", TO), Map.entry("partPath", "word/document.xml"),
                Map.entry("partChange", "MODIFIED"), Map.entry("comparisonMode", "BINARY"));
        page.route("GET", "/taxonomy/api/admin/document-templates", request -> PortfolioClientTestPage.json(200, List.of()));
        page.openAt(view.template(), "/taxonomy", "/taxonomy" + view.path(), language, "", model);
        assertThat(page.driver().executeScript("return Boolean(window.TaxonomyAppChrome)")).isEqualTo(true);

        assertThat(page.driver().findElements(By.id("applicationNavigation"))).hasSize(1);
        assertThat(page.driver().findElements(By.cssSelector("#applicationNavigation [data-app-route='architecture']"))).hasSize(1);
        assertThat(page.driver().findElements(By.id(view.retained()))).hasSize(1);
        assertThat(page.driver().findElement(By.cssSelector("#applicationNavigation .context-help")).getDomAttribute("href"))
                .contains("/taxonomy/?lang=" + language + "#help/" + view.help());
        assertThat(page.driver().findElement(By.cssSelector("#applicationNavigation [data-app-locale='" + language + "']"))
                .getDomAttribute("aria-current")).isEqualTo("true");
        if (width >= 992) {
            var centers = (List<?>) page.driver().executeScript("""
                    const nav = document.getElementById('applicationNavigation');
                    return ['.taxonomy-app-brand', '.taxonomy-app-primary', '.taxonomy-app-local',
                            '.taxonomy-app-languages', '.context-help'].map(selector => {
                        const rect = nav.querySelector(selector).getBoundingClientRect();
                        return Math.round(rect.top + rect.height / 2);
                    });
                    """);
            assertThat(centers).as("The shared desktop navigation occupies one row").allMatch(centers.getFirst()::equals);
        }
        if (view.template().equals("document-template-restore") || view.template().equals("document-template-part-comparison")) {
            assertThat(page.driver().findElement(By.id(view.retained())).getDomAttribute("href"))
                    .contains("from=" + FROM, "to=" + TO);
        }
        if (view.template().equals("document-template-local-edit")) {
            page.driver().findElement(By.id("localTemplateFile")).sendKeys(templateFile.toAbsolutePath().toString());
            assertThat(departureBlocked()).isTrue();
            page.driver().executeScript("document.getElementById('localTemplateFile').value=''");
            assertThat(departureBlocked()).isFalse();
        }
    }

    private static boolean departureBlocked() {
        return Boolean.TRUE.equals(page.driver().executeScript("""
                const event = new Event('beforeunload', {cancelable: true});
                window.dispatchEvent(event);
                return event.defaultPrevented;
                """));
    }
}
