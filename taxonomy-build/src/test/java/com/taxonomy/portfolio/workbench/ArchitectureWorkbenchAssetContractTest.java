package com.taxonomy.portfolio.workbench;

import com.taxonomy.build.RepositoryResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.cache.NonCacheableCacheEntryValidity;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;
import org.thymeleaf.templateresource.StringTemplateResource;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureWorkbenchAssetContractTest {

    private static final String D3 = "/webjars/d3/7.9.0/dist/d3.min.js";
    private static final String DIAGRAM = "/js/shared/architecture-diagram.js";
    private static final String RENDERER = "/js/architecture-editor-renderer.js";
    private static final String DIAGRAM_CSS = "/css/shared/architecture-diagram.css";
    private static final String API = "/js/api/architecture-workbench-api.js";
    private static final String ADAPTER = "/js/architecture-workbench.js";
    private static final String SHARED_SCRIPTS = "~{fragments/application-chrome :: scripts}";
    private static final String SCRIPT_INCLUDE = "<th:block th:replace=\"" + SHARED_SCRIPTS + "\"></th:block>";

    @Test
    void pageLoadsTheApiBoundaryBeforeTheGraphAdapter() throws Exception {
        String page = resource("templates/architecture-workbench.html");
        assertThat(page).contains("Download PDF");
        assertComposedAssets(render(page, resource("templates/fragments/application-chrome.html")));
    }

    @Test
    void sharedArchitectureAssetsHaveOneOwnerAcrossAllSixSurfaces() throws Exception {
        var shared = assetPaths(resource("templates/fragments/application-chrome.html"));
        for (String asset : List.of(D3, DIAGRAM, RENDERER, DIAGRAM_CSS))
            assertThat(shared).as("shared owner of %s", asset).containsOnlyOnce(asset);
        for (String name : List.of("index", "architecture-editor", "architecture-workbench", "requirement-detail",
                "projects", "portfolio-versioning")) {
            String page = resource("templates/" + name + ".html");
            assertThat(page).as(name).containsOnlyOnce(SHARED_SCRIPTS)
                    .containsOnlyOnce("~{fragments/application-chrome :: styles}")
                    .doesNotContain(D3, DIAGRAM, RENDERER, DIAGRAM_CSS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-after-consumer", "d3-after-diagram", "duplicate-shared-script", "missing-shared-fragment"})
    void composedAssetContractRejectsBrokenDependenciesAndOwnership(String mutation) throws Exception {
        String page = resource("templates/architecture-workbench.html");
        String shared = resource("templates/fragments/application-chrome.html");
        switch (mutation) {
            case "api-after-consumer" -> page = moveScriptAfter(page, API, ADAPTER);
            case "d3-after-diagram" -> shared = moveScriptAfter(shared, D3, DIAGRAM);
            case "duplicate-shared-script" -> page = page.replace("</body>", "<script src=\"" + DIAGRAM + "\"></script></body>");
            case "missing-shared-fragment" -> {
                assertThat(page).contains(SCRIPT_INCLUDE);
                page = page.replace(SCRIPT_INCLUDE, "");
            }
            default -> throw new IllegalArgumentException(mutation);
        }
        String composed = render(page, shared);
        assertThatThrownBy(() -> assertComposedAssets(composed)).as(mutation).isInstanceOf(AssertionError.class);
    }

    private static void assertComposedAssets(String html) {
        var assets = assetPaths(html);
        for (String asset : List.of(D3, DIAGRAM, RENDERER, DIAGRAM_CSS, API, ADAPTER,
                "/js/api/taxonomy-api-client.js", "/js/api/architecture-lifecycle-api.js", "/js/shared/architecture-lifecycle.js"))
            assertThat(assets).as("composed asset %s", asset).containsOnlyOnce(asset);
        assertBefore(assets, D3, DIAGRAM);
        assertBefore(assets, DIAGRAM, RENDERER);
        assertBefore(assets, DIAGRAM, ADAPTER);
        assertBefore(assets, API, ADAPTER);
        assertBefore(assets, "/js/api/taxonomy-api-client.js", "/js/api/architecture-lifecycle-api.js");
        assertBefore(assets, "/js/api/architecture-lifecycle-api.js", "/js/shared/architecture-lifecycle.js");
    }

    private static void assertBefore(List<String> assets, String dependency, String consumer) {
        assertThat(assets.indexOf(dependency)).as("%s before %s", dependency, consumer)
                .isLessThan(assets.indexOf(consumer));
    }

    private static List<String> assetPaths(String html) {
        // Inspect actual emitted src/href attributes, never Thymeleaf source expressions.
        return Pattern.compile("<(?:script|link)\\b[^>]*?\\s(?:src|href)=\"([^\"]+)\"[^>]*>")
                .matcher(html).results().map(match -> match.group(1)).toList();
    }

    private static String moveScriptAfter(String html, String script, String anchor) {
        String moved = scriptTag(html, script);
        String after = scriptTag(html, anchor);
        String changed = html.replace(moved, "").replace(after, after + moved);
        assertThat(changed).isNotEqualTo(html);
        return changed;
    }

    private static String scriptTag(String html, String path) {
        var tag = Pattern.compile("<script\\b[^>]*\\ssrc=\"" + Pattern.quote(path) + "\"[^>]*>\\s*</script>").matcher(html);
        assertThat(tag.find()).as("script tag for %s", path).isTrue();
        return tag.group();
    }

    private static String render(String page, String shared) {
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new ITemplateResolver() {
            @Override public String getName() { return "repository-source-contract"; }
            @Override public Integer getOrder() { return 1; }
            @Override public TemplateResolution resolveTemplate(IEngineConfiguration configuration, String owner,
                    String template, Map<String, Object> attributes) {
                try {
                    String source = switch (template) {
                        case "architecture-workbench" -> page;
                        case "fragments/application-chrome" -> shared;
                        default -> resource("templates/" + template + ".html");
                    };
                    return new TemplateResolution(new StringTemplateResource(source), TemplateMode.HTML,
                            NonCacheableCacheEntryValidity.INSTANCE);
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        });
        var servlet = new MockServletContext();
        var request = new MockHttpServletRequest(servlet);
        request.setRequestURI("/projects/1/requirements/fixture/architecture");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        return engine.process("architecture-workbench", new WebContext(exchange, Locale.ENGLISH));
    }

    @Test
    void graphAdapterCannotPrintOrIssueDirectRestCalls() throws Exception {
        String adapter = resource("static/js/architecture-workbench.js");

        assertThat(adapter)
                .doesNotContain("window.print")
                .doesNotContain("fetch(")
                .contains("await window.TaxonomyI18n.ready()")
                .containsPattern("ArchitectureWorkbenchApi\\.load\\(\\s*selectedSource\\.projectId\\s*,"
                        + "\\s*selectedSource\\.snapshotId\\s*,\\s*selectedSource\\s*,\\s*controller\\.signal\\s*\\)")
                .containsPattern("catch\\s*\\(([\\w$]+)\\)\\s*\\{[^}]*showError\\(\\1\\)")
                .doesNotContain("window.location.assign");
        assertThat(resource("static/js/architecture-workbench-export.js"))
                .contains("bind('downloadArchitecturePdf', ArchitectureWorkbenchApi.pdfUrl)")
                .contains("ArchitectureWorkbenchApi.download(url, selected, signal)")
                .doesNotContain("fetch(", "window.location.assign");
        assertThat(resource("static/js/api/architecture-workbench-api.js"))
                .contains("TaxonomyApiClient.requestBlob(");
        assertThat(adapter.indexOf("await window.TaxonomyI18n.ready()"))
                .isLessThan(adapter.indexOf("ArchitectureWorkbenchApi.load("));
    }

    @Test
    void graphAdapterKeepsFailureControlsInertAndTraversesHopsLinearly() throws Exception {
        String adapter = resource("static/js/architecture-workbench.js");

        assertThat(adapter)
                .contains("let queueIndex = 0")
                .contains("while (queueIndex < queue.length)")
                .doesNotContain("queue.shift()")
                .contains("searchInput,")
                .contains("contextCheckbox,")
                .contains("fullscreenButton")
                .contains("control.disabled = true");

        // The shared controller's behavioral suite covers clearing focus and restoring
        // its camera. This asset contract requires the snapshot to use that same owner.
        assertThat(adapter)
                .contains("window.TaxonomyArchitectureDiagram.create(svg,")
                .contains("onChange: onDiagramChange")
                .contains("diagramController.getState()")
                .contains("diagramController.unfocus()")
                .contains("diagramController.focus()")
                .doesNotContain("TaxonomyArchitectureDiagram.connect(", "d3.zoom(", "new ResizeObserver(")
                .doesNotContainPattern("\\bstate\\.mode\\s*=(?!=)");
    }

    @Test
    void legacyExportControlRoutesTheAcceptedArchitectureModelToPdf() throws Exception {
        String exporter = resource("static/js/shared/taxonomy-export.js");

        assertThat(exporter)
                .doesNotContain("window.print")
                .doesNotContain("Fallback to browser print")
                .contains("Vector SVG renderer is unavailable")
                .contains("window.TaxonomyState?.currentArchView")
                .contains("diagramDownload('/api/diagram/svg'")
                .contains("parseFromString(svgText, 'image/svg+xml')")
                .doesNotContain("architecturePanel.style.display")
                .doesNotContain("#impactGraphView svg")
                .contains("requirement-architecture.pdf")
                .contains("stopImmediatePropagation")
                .contains("Object.freeze");
    }

    @Test
    void requirementDetailLinksToItsCurrentArchitectureRoute() throws Exception {
        String page = resource("templates/requirement-detail.html");

        assertThat(page)
                .contains("architectureWorkbenchLink")
                .contains("/architecture")
                .contains("Open architecture workbench");
    }

    private static String resource(String path) throws IOException {
        return RepositoryResources.applicationResource(path);
    }
}
