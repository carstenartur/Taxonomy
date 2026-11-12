package com.taxonomy.reporting.render.decision;

import com.taxonomy.reporting.templates.RepeatableWordLayout;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import java.util.List;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepeatableWordLayoutTest {
    private static final String NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String REPORT = "<w:document xmlns:w=\"" + NS + "\"><w:body>"
            + "<w:p><w:bookmarkStart w:name=\"decision_chapters\"/></w:p><w:p><w:r><w:t>Mandatory evidence</w:t></w:r></w:p>"
            + "<w:p><w:bookmarkStart w:name=\"decision_evidence\"/></w:p><w:sectPr/></w:body></w:document>";

    @Test
    void materializesNamedFieldsFromRealReportRowsAndPreservesStaticFormattingAndEvidence() {
        String layout = repeat("chapters", "code") + repeat("appendix", "code");
        var source = document(layout);
        var templates = new RepeatableWordLayout();
        String cleaned = new String(templates.extract(source), StandardCharsets.UTF_8);
        assertThat(cleaned).doesNotContain("taxonomy.repeat.", "taxonomy.field.");
        var report = populatedReport();
        String result = new String(templates.materialize(REPORT.getBytes(StandardCharsets.UTF_8), report),
                StandardCharsets.UTF_8);
        assertThat(result).contains("Mandatory evidence", "Custom prefix", "w:b", "w:i")
                .doesNotContain("taxonomy.repeat.", "taxonomy.field.", "Sample field");
        for (var chapter : report.chapters()) assertThat(result).contains(chapter.parentCode());
        for (var leaf : report.leadingLeaves()) assertThat(result).contains(leaf.code());
    }

    @Test
    void rejectsMisplacedUnknownBoundOrDrawingBearingControls() {
        var valid = repeat("chapters", "code");
        for (String malformed : new String[]{
                valid.replace("chapters.v1", "chapters.v2"),
                valid.replace("field.code", "field.unknown"),
                valid.replace("<w:sdtPr>", "<w:sdtPr><w:dataBinding/>"),
                valid.replace("Custom prefix", "Custom prefix</w:t><w:drawing/><w:t>")}) {
            assertThatThrownBy(() -> new RepeatableWordLayout().extract(document(malformed)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void splittingAPlaceholderAcrossRunsPreservesSurroundingTextAndItsRunStyling() {
        assertThat(RepeatableWordLayout.replaceRuns(java.util.List.of("Before ", "{{taxonomy.", "report.title}}", " After"),
                Map.of("{{taxonomy.report.title}}", "Report"))).containsExactly("Before ", "Report", "", " After");
    }

    static DecisionRationaleReport populatedReport() {
        var base = DecisionRationaleTemplateRendererTest.report();
        var chapters = List.of(
                new DecisionRationaleReport.DecisionChapter(1, "BP", "First", "", 95d, 0, true, "First decision", "", List.of(), List.of()),
                new DecisionRationaleReport.DecisionChapter(2, "CR", "Second", "", 80d, 0, true, "Second decision", "", List.of(), List.of()));
        var leaves = List.of(new DecisionRationaleReport.LeafCandidate("CR-10", "Leaf", 73, "CR", 1, "CR/CR-10",
                "Frozen rationale", DecisionRationaleReport.ReasonSource.AI_SCORING));
        return new DecisionRationaleReport(base.title(), base.languageTag(), base.requirement(), base.status(),
                base.metadata(), base.executiveSummary(), chapters, leaves, List.of(), List.of(), List.of(), null);
    }

    private static byte[] document(String content) {
        return ("<w:document xmlns:w=\"" + NS + "\"><w:body>" + content + "</w:body></w:document>")
                .getBytes(StandardCharsets.UTF_8);
    }

    static String repeat(String section, String field) {
        return "<w:sdt><w:sdtPr><w:tag w:val=\"taxonomy.repeat." + section + ".v1\"/></w:sdtPr><w:sdtContent>"
                + "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Custom prefix</w:t></w:r><w:sdt><w:sdtPr>"
                + "<w:tag w:val=\"taxonomy.field." + field + "\"/></w:sdtPr><w:sdtContent><w:r>"
                + "<w:rPr><w:i/></w:rPr><w:t>Sample field</w:t></w:r></w:sdtContent></w:sdt></w:p>"
                + "</w:sdtContent></w:sdt>";
    }
}
