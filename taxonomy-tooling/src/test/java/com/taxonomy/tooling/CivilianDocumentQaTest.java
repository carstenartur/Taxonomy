package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CivilianDocumentQaTest {
    @Test
    void joinsContinuedParagraphsWithoutRunningFurnitureAndKeepsUnicode() throws Exception {
        String bbox = xml(page("Öffentliche Warnungen") + page("für Bürger"));
        assertThat(CivilianDocumentQa.checkText("decision.docx", bbox, "unused",
                List.of("Öffentliche Warnungen für Bürger"), true))
                .containsEntry("pages", 2).containsEntry("contentAssertions", 1);
        assertThatThrownBy(() -> CivilianDocumentQa.checkText("decision.docx", bbox, "",
                List.of("Öffentliche Warnungen für Bürger und Abonnenten"), true))
                .hasMessageContaining("missing text");
    }

    @Test
    void rejectsHeaderOnlyPagesAndPaginationRegression() {
        assertThatThrownBy(() -> CivilianDocumentQa.checkText("decision.docx", xml(page("")), "",
                List.of("Running header"), true)).hasMessageContaining("empty page bodies [1]");
        assertThatThrownBy(() -> CivilianDocumentQa.checkText("decision.docx", xml(page("Text").repeat(66)), "",
                List.of("Text"), true)).hasMessageContaining("grew to 66 pages");
    }

    @Test
    void rejectsRationaleLabelsSeparatedFromTheirExplanation() {
        assertThatThrownBy(() -> CivilianDocumentQa.checkText("decision.docx",
                xml(page("Other text Comparative rationale") + page("Explanation")), "",
                List.of("Explanation"), true)).hasMessageContaining("orphan rationale heading on page 1");
    }

    @Test
    void checksDrawLabelsInReadingOrderAndRejectsForeignDoctype() throws Exception {
        assertThat(CivilianDocumentQa.checkText("architecture.vsdx", xml(page("mixed label order")),
                "Public warning service", List.of("Public warning service"), false))
                .containsEntry("pages", 1);
        assertThatThrownBy(() -> CivilianDocumentQa.checkText("decision.docx",
                "<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///does-not-exist'>]>" + xml(page("Text")),
                "", List.of("Text"), true)).isInstanceOf(java.io.IOException.class);
    }

    private static String xml(String pages) {
        return "<html xmlns='http://www.w3.org/1999/xhtml'><body><doc>" + pages + "</doc></body></html>";
    }

    private static String page(String body) {
        return "<page width='595' height='842'><word yMin='24' yMax='35'>Running header</word>"
                + "<word yMin='51' yMax='62'>" + body + "</word>"
                + "<word yMin='796' yMax='808'>Page footer</word></page>";
    }
}
