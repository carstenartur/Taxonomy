package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
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
        // Container Chrome cannot see a path on the test host; Selenium uploads it first.
        if (!(page.driver() instanceof ChromeDriver)) page.driver().setFileDetector(new LocalFileDetector());
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
        double previousDocument = ((Number) page.driver().executeScript("return performance.timeOrigin")).doubleValue();
        page.clearRequests();
        page.openAt(view.template(), "/taxonomy", "/taxonomy" + view.path(), language, "", model);
        awaitNavigation(previousDocument, "/taxonomy" + view.path(), language);

        assertThat(page.driver().findElements(By.id("applicationNavigation"))).hasSize(1);
        assertThat(page.driver().findElement(By.id("applicationNavigation")).getDomAttribute("aria-label"))
                .isEqualTo(language.equals("de") ? "Anwendungsnavigation" : "Application navigation");
        assertThat(page.driver().findElements(By.cssSelector("#applicationNavigation [data-app-route='architecture']"))).hasSize(1);
        assertThat(page.driver().findElements(By.id(view.retained()))).hasSize(1);
        assertThat(page.driver().findElement(By.cssSelector("#applicationNavigation .context-help")).getDomAttribute("href"))
                .contains("/taxonomy/?lang=" + language + "#help/" + view.help());
        assertThat(page.driver().findElement(By.cssSelector("#applicationNavigation [data-app-locale='" + language + "']"))
                .getDomAttribute("aria-current")).isEqualTo("true");
        if (width >= 1200) {
            var verticalSpread = (Number) page.driver().executeScript("""
                    const nav = document.getElementById('applicationNavigation');
                    const centers = ['.taxonomy-app-brand', '.taxonomy-app-primary', '.taxonomy-app-local',
                            '.taxonomy-app-languages', '#applicationHelp > summary'].map(selector => {
                        const rect = nav.querySelector(selector).getBoundingClientRect();
                        return rect.top + rect.height / 2;
                    });
                    return Math.max(...centers) - Math.min(...centers);
                    """);
            assertThat(verticalSpread.doubleValue()).as("The shared desktop navigation occupies one row").isLessThan(4);
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

    private static void awaitNavigation(double previousDocument, String path, String language) {
        try {
            new WebDriverWait(page.driver(), Duration.ofSeconds(10)).until(browser ->
                    Boolean.TRUE.equals(((JavascriptExecutor) browser).executeScript("""
                            return performance.timeOrigin !== arguments[0]
                                && location.pathname === arguments[1]
                                && new URL(location.href).searchParams.get('lang') === arguments[2]
                                && document.readyState === 'complete' && Boolean(window.TaxonomyAppChrome);
                            """, previousDocument, path, language)));
        } catch (TimeoutException failure) {
            Object document = page.driver().executeScript("""
                    return { url: location.href, readyState: document.readyState, timeOrigin: performance.timeOrigin,
                        chrome: Boolean(window.TaxonomyAppChrome), i18n: Boolean(window.TaxonomyI18n),
                        script: document.querySelector('script[src*="application-chrome.js"]')?.src || null,
                        resources: performance.getEntriesByType('resource')
                            .filter(entry => entry.name.includes('application-chrome.js'))
                            .map(entry => ({name: entry.name, status: entry.responseStatus, duration: entry.duration})) };
                    """);
            var requests = page.requests().stream().map(request -> request.method() + " " + request.path()).toList();
            Object logs;
            try { logs = page.driver().manage().logs().get(LogType.BROWSER).getAll(); }
            catch (RuntimeException unavailable) { logs = "Browser logs unavailable: " + unavailable.getMessage(); }
            throw new AssertionError("Shared navigation did not initialize in the requested new document: "
                    + document + "; requests=" + requests + "; browser logs=" + logs, failure);
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
