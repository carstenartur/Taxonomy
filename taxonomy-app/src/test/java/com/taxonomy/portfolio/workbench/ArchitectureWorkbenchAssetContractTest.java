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
                .contains("id=\"architectureExportFormat\"")
                .contains("aria-describedby=\"architectureExportHint\"\n                disabled")
                .contains("id=\"downloadArchitectureExport\"")
                .contains("type=\"button\"\n                disabled")
                .contains("Evidence JSON")
                .contains("ArchiMate Exchange XML (experimental)")
                .contains("Every format is generated from the exact persisted snapshot");
        assertThat(page.indexOf("/js/api/architecture-workbench-api.js"))
                .isLessThan(page.indexOf("/js/architecture-workbench.js"));
    }

    @Test
    void graphAdapterCannotPrintOrIssueDirectRestCalls() throws Exception {
        String adapter = resource("static/js/architecture-workbench.js");

        assertThat(adapter)
                .doesNotContain("window.print")
                .doesNotContain("fetch(")
                .doesNotContain("downloadArchitectureSvg")
                .doesNotContain("downloadArchitecturePdf")
                .contains("Promise.resolve()")
                .contains("return ArchitectureWorkbenchApi.load(projectId, snapshotId)")
                .contains(".catch(showError)")
                .contains("ArchitectureWorkbenchApi.exportUrl(projectId, snapshotId, formatId)")
                .contains("from persisted snapshot");
    }

    @Test
    void graphAdapterKeepsFailureControlsInertAndTraversesHopsLinearly() throws Exception {
        String adapter = resource("static/js/architecture-workbench.js");

        assertThat(adapter)
                .contains("let queueIndex = 0")
                .contains("while (queueIndex < queue.length)")
                .doesNotContain("queue.shift()")
                .contains("const leavingFocusMode = !nodeId && state.mode === 'focus'")
                .contains("state.mode = 'overview'")
                .contains("searchInput,")
                .contains("contextCheckbox,")
                .contains("fullscreenButton,")
                .contains("exportFormatSelect,")
                .contains("exportButton")
                .contains("control.disabled = true")
                .contains("control.disabled = false");
    }

    @Test
    void apiBoundaryAllowsOnlyTheDeclaredSnapshotExportFormats() throws Exception {
        String api = resource("static/js/api/architecture-workbench-api.js");

        assertThat(api)
                .contains("'json', 'svg', 'pdf', 'archimate', 'mermaid', 'structurizr'")
                .contains("EXPORT_FORMATS.has(normalized)")
                .contains("'/exports/' + encodeURIComponent(exportFormat(formatId))")
                .doesNotContain("'visio'");
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
