package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Regression contract for document-import controls and result panels under the strict CSP. */
class DocumentImportCspContractTest {

    @Test
    void successfulResultsRemoveTheLegacyInlineDisplayMarker() throws Exception {
        String source = resource("/static/js/shared/taxonomy-document-import.js");

        assertThat(source)
                .contains("reviewPanel.removeAttribute('style');")
                .contains("aiResultPanel.removeAttribute('style');")
                .contains("regMapPanel.removeAttribute('style');")
                .doesNotContain("reviewPanel.style.display = '';")
                .doesNotContain("aiResultPanel.style.display = '';")
                .doesNotContain("regMapPanel.style.display = '';");
    }

    @Test
    void externalStylesheetMapsLegacyMarkersToVisibility() throws Exception {
        String css = resource("/static/css/taxonomy-ergonomics.css");

        assertThat(css)
                .contains("#docCandidateReviewPanel[style*=\"display:none\"]")
                .contains("#docAiResultPanel[style*=\"display:none\"]")
                .contains("#docRegMapResultPanel[style*=\"display:none\"]")
                .contains("#docCandidateReviewPanel:not([style*=\"display:none\"])")
                .contains("#docAiResultPanel:not([style*=\"display:none\"])")
                .contains("#docRegMapResultPanel:not([style*=\"display:none\"])");
    }

    @Test
    void missingSpinnerIsRestoredBeforeDocumentImportCapturesItsControls() throws Exception {
        String apiClient = resource("/static/js/api/taxonomy-api-client.js");
        String template = resource("/templates/index.html");

        assertThat(apiClient)
                .contains("function ensureDocumentImportSpinner()")
                .contains("spinner.id = 'docImportSpinner';")
                .contains("button.prepend(spinner);")
                .contains("ensureDocumentImportSpinner();");
        assertThat(template.indexOf("/js/api/taxonomy-api-client.js"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(template.indexOf("/js/shared/taxonomy-document-import.js"));
    }

    private static String resource(String path) throws IOException {
        assertThatCode(() -> DocumentImportCspContractTest.class.getResourceAsStream(path))
                .doesNotThrowAnyException();
        try (InputStream input = DocumentImportCspContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
