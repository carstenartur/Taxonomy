package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementReportTextTest {
    private static final String SOURCE = """
            # Ausbildungsportal

            **AF-06.** Bearbeitungsstände für *zugeordnete* Teilnehmer.

            - Bearbeitungsstand speichern
            - Gültigkeit prüfen

            [Fachvorgabe](https://example.org/standard#tests)
            Absichtlich neue Zeile.
            """;

    @Test
    void retainsRequirementContentsWithNativeWordFormatting() throws Exception {
        try (var document = new XWPFDocument()) {
            new WordDocumentWriter(document, new DecisionReportLabels("de")).requirementParagraphs(SOURCE);

            assertThat(document.getParagraphs()).extracting(p -> p.getText()).containsExactly(
                    "Ausbildungsportal", "AF-06. Bearbeitungsstände für zugeordnete Teilnehmer.",
                    "• Bearbeitungsstand speichern", "• Gültigkeit prüfen",
                    "Fachvorgabe (https://example.org/standard#tests)\nAbsichtlich neue Zeile.");
            assertThat(document.getParagraphs().get(1).getRuns()).anySatisfy(run -> {
                assertThat(run.getText(0)).isEqualTo("AF-06.");
                assertThat(run.isBold()).isTrue();
            }).anySatisfy(run -> {
                assertThat(run.getText(0)).isEqualTo("zugeordnete");
                assertThat(run.isItalic()).isTrue();
            });
        }
    }

    @Test
    void rendersHtmlWithoutLoadingAuthoredMarkupOrExternalResources() {
        String html = RequirementReportText.html(SOURCE + "\n<script>alert(1)</script>\n\n"
                + "![Remote](https://example.org/image)\n");
        assertThat(html).contains("<h1>Ausbildungsportal</h1>", "<strong>AF-06.</strong>",
                "<em>zugeordnete</em>", "<li>Gültigkeit prüfen</li>", "&lt;script&gt;",
                "Fachvorgabe (https://example.org/standard#tests)<br>",
                "Remote (https://example.org/image)")
                .doesNotContain("<script", "<img", "href=");
        assertThat(RequirementReportText.plain(SOURCE))
                .contains("Fachvorgabe (https://example.org/standard#tests)\nAbsichtlich neue Zeile.")
                .doesNotContain("**", "# Ausbildungsportal");
    }
}
