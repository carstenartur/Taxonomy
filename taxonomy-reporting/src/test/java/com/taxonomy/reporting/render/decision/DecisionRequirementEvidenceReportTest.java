package com.taxonomy.reporting.render.decision;

import com.taxonomy.dto.RequirementNodeAssessment;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static com.taxonomy.dto.RequirementNodeAssessment.*;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionRequirementEvidenceReportTest {
    private static final String QUOTE = "  Retain <archive> copies.  ";
    private static final String SOURCE = QUOTE + "\nReview recovery targets.";
    private static final String QUESTION = "What recovery target applies?";

    @Test
    void jsonKeepsTheUneditedSourceAndEveryClaim() {
        var mapper = JsonMapper.builder().build();
        var bytes = new DecisionRationaleJsonRenderer(mapper)
                .render(ReportRenderContext.ofPayload(assessedReport())).bytes();
        var report = mapper.readTree(bytes);
        assertThat(report.path("nodeAssessments").path("BP").size()).isEqualTo(2);
        assertThat(report.path("requirement").asString()).isEqualTo(SOURCE);
        var claims = report.path("nodeAssessments").path("BP");
        assertThat(claims.get(0).path("requirementQuote").asString()).isEqualTo(QUOTE);
        assertThat(claims.get(0).path("contributionType").asString()).isEqualTo("SUPPORTS");
        assertThat(claims.get(1).path("question").asString()).isEqualTo(QUESTION);
    }

    @Test
    void htmlDisplaysSupportedIndirectAndUnresolvedDirectClaimsWithTheirOwnColors() {
        var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer())
                .render(assessedReport());
        assertThat(html).contains("Requirement contributions and evidence", "AI interpretations", "  Retain &lt;archive&gt; copies.  ",
                "Supports", "Direct subject", "Existing environment", "If archive copies are required",
                "Archive copies support recovery.", QUESTION, "#166534", "#92400E");
        assertThat(html).doesNotContain("Retain <archive> copies.");
    }

    @Test
    void docxIncludesSeparateStatusLabelsAndOriginalQuote() throws Exception {
        var renderer = new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer());
        try (var document = new XWPFDocument(new ByteArrayInputStream(renderer.render(assessedReport())));
                var extractor = new XWPFWordExtractor(document)) {
            assertThat(extractor.getText()).contains("Requirement contributions and evidence", QUOTE, "Supported", "Clarification needed",
                    "If archive copies are required", QUESTION);
        }
    }

    @Test
    void legacyReportsDisplayNeutralAbsence() {
        var html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(baseReport());
        assertThat(html).contains("Requirement contributions and evidence", "Not assessed");
    }

    private DecisionRationaleReport assessedReport() {
        var supported = new RequirementNodeAssessment(QUOTE, List.of(SubjectKind.INFORMATION),
                List.of(RequirementKind.FUNCTIONAL), ContributionType.SUPPORTS, EvidenceStatus.SUPPORTED,
                EvidenceBasis.EXPLICIT, Necessity.OPTIONAL, Scope.EXISTING_ENVIRONMENT,
                "If archive copies are required", "Archive copies support recovery.", "");
        var unresolved = new RequirementNodeAssessment("Review recovery targets.", List.of(SubjectKind.PROCESS),
                List.of(RequirementKind.QUALITY), ContributionType.DIRECT_SUBJECT, EvidenceStatus.UNRESOLVED,
                EvidenceBasis.UNSPECIFIED, Necessity.UNDETERMINED, Scope.UNSPECIFIED, "",
                "The target is unspecified.", QUESTION);
        return baseReport().withNodeAssessments(Map.of("BP", List.of(supported, unresolved)));
    }

    private DecisionRationaleReport baseReport() {
        var fixture = DecisionRationaleTemplateRendererTest.report();
        return new DecisionRationaleReport(fixture.title(), fixture.languageTag(), SOURCE, fixture.status(),
                fixture.metadata(), fixture.executiveSummary(), fixture.chapters(), fixture.leadingLeaves(),
                fixture.warnings(), fixture.productCoverageGaps(), fixture.discrepancies(), fixture.viewContext());
    }
}
