package com.taxonomy.portfolio.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureWorkbenchAssetContractTest {

    @Test
    void pageLoadsTheApiBoundaryBeforeTheGraphAdapter() throws Exception {
        String page = resource("templates/architecture-workbench.html");

        assertThat(page)
                .contains("/js/api/architecture-workbench-api.js")
                .contains("/js/architecture-workbench.js")
                .contains("/webjars/d3/7.9.0/dist/d3.min.js")
                .contains("Download PDF");
        assertThat(page.indexOf("/js/api/architecture-workbench-api.js"))
                .isLessThan(page.indexOf("/js/architecture-workbench.js"));
    }

    @Test
    void graphAdapterCannotPrintOrIssueDirectRestCalls() throws Exception {
        String adapter = resource("static/js/architecture-workbench.js");

        assertThat(adapter)
                .doesNotContain("window.print")
                .doesNotContain("fetch(")
                .contains("ArchitectureWorkbenchApi.load")
                .contains("ArchitectureWorkbenchApi.pdfUrl");
    }

    @Test
    void legacyExportControlRoutesOnlyTheActiveArchitectureResultToItsGraph() throws Exception {
        String exporter = resource("static/js/shared/taxonomy-export.js");

        assertThat(exporter)
                .doesNotContain("window.print")
                .doesNotContain("Fallback to browser print")
                .contains("Vector SVG renderer is unavailable")
                .contains("state.currentArchView")
                .contains("architectureViewPanel")
                .contains("architecturePanel.style.display !== 'none'")
                .contains("#impactGraphView svg")
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
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
