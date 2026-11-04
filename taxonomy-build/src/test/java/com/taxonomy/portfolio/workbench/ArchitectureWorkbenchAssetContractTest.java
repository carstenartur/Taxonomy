package com.taxonomy.portfolio.workbench;

import com.taxonomy.build.RepositoryResources;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureWorkbenchAssetContractTest {

    @Test
    void pageLoadsTheApiBoundaryBeforeTheGraphAdapter() throws Exception {
        String page = resource("templates/architecture-workbench.html");

        assertThat(page)
                .contains("/js/api/architecture-workbench-api.js")
                .contains("/js/shared/architecture-diagram.js")
                .contains("/js/architecture-workbench.js")
                .contains("/webjars/d3/7.9.0/dist/d3.min.js")
                .contains("Download PDF");
        assertThat(page.indexOf("/js/api/architecture-workbench-api.js"))
                .isLessThan(page.indexOf("/js/architecture-workbench.js"));
        assertThat(page.indexOf("/webjars/d3/7.9.0/dist/d3.min.js"))
                .isLessThan(page.indexOf("/js/shared/architecture-diagram.js"));
        assertThat(page.indexOf("/js/shared/architecture-diagram.js"))
                .isLessThan(page.indexOf("/js/architecture-workbench.js"));
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
                .contains("ArchitectureWorkbenchApi.pdfUrl");
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

    private static String resource(String path) throws Exception {
        return RepositoryResources.applicationResource(path);
    }
}
