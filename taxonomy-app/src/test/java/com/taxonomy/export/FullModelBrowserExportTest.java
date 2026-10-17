package com.taxonomy.export;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the shipped browser adapter; JUnit owns every assertion and outcome. */
class FullModelBrowserExportTest {
    @Test
    void csvRetainsAcceptedZeroScoresWithoutLegacyCoverage() throws Exception {
        var csv = probe("csv").path("downloads").get(0).asText();
        assertThat(csv).contains(",zero,Explicit zero,0,").contains(",fraction,Fraction,0.125,")
                .doesNotContain(",invalid,");
    }

    @Test
    void savedJsonRetainsTheProviderOperationForAuthorizedFollowUps() throws Exception {
        var result = probe("json");
        var request = new JsonMapper().readTree(result.path("requests").get(0).path("body").asText());
        assertThat(request.path("analysisOperationId").asText()).isEqualTo("accepted-operation");
        assertThat(request.path("analysisProviderId").asText()).isEqualTo("LOCAL_ONNX");
    }

    @Test
    void mainPdfUsesTheCompleteAcceptedModelInsteadOfThePannedViewport() throws Exception {
        assertCompleteModelPdf(probe("pdf"));
    }

    @Test
    void mainPdfDoesNotDependOnTheVisibleArchitectureTab() throws Exception {
        assertCompleteModelPdf(probe("pdf-hidden-panel"));
    }

    @Test
    void staleRequirementCannotExportADifferentAnalysisAsPdf() throws Exception {
        var result = probe("pdf-stale");
        assertThat(result.path("requests").size()).isZero();
        assertThat(result.path("pdfs").size()).isZero();
        assertThat(result.path("status").asText()).contains("requirement has changed");
    }

    @Test
    void explicitViewCaptureKeepsItsDocumentedViewportContract() throws Exception {
        var result = probe("pdf-explicit");
        assertThat(result.path("requests").size()).isZero();
        var pdf = result.path("pdfs").get(0);
        assertThat(pdf.path("filename").asText()).isEqualTo("taxonomy-view.pdf");
        assertThat(pdf.path("options").path("format").toString()).isEqualTo("[800,400]");
        assertThat(pdf.path("svg").toString()).contains("translate(-1000,0) scale(2)");
    }

    private static void assertCompleteModelPdf(JsonNode result) {
        assertThat(result.path("requests").size()).isEqualTo(1);
        var request = result.path("requests").get(0);
        assertThat(request.path("url").asText()).isEqualTo("/api/diagram/current/svg");
        assertThat(request.path("method").asText()).isEqualTo("POST");
        assertThat(request.path("body").asText()).contains("CP-outside-viewport");
        var pdf = result.path("pdfs").get(0);
        assertThat(pdf.path("filename").asText()).isEqualTo("requirement-architecture.pdf");
        assertThat(pdf.path("options").path("format").toString()).isEqualTo("[1600,1100]");
        assertThat(pdf.path("svg").toString()).contains("CP-outside-viewport").doesNotContain("translate(-1000");
    }

    private static JsonNode probe(String scenario) throws Exception {
        String node = System.getProperty("taxonomy.node.executable");
        assertThat(node).as("Maven-managed Node runtime").isNotBlank();
        var process = new ProcessBuilder(node,
                Path.of("src/test/js/full-model-export-probe.cjs").toAbsolutePath().toString(),
                Path.of("src/main/resources/static/js/shared/taxonomy-export.js").toAbsolutePath().toString(),
                scenario).redirectErrorStream(true).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("browser adapter terminates").isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        return new JsonMapper().readTree(output);
    }
}
