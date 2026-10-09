package com.taxonomy.reporting;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.render.decision.*;
import com.taxonomy.reporting.render.document.*;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.extension.api.report.ReportRenderContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.List;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** No application context and, by Maven boundary, no architecture implementation on this classpath. */
class ReportingIsolationTest {
    @Test
    void independentlyConstructedDecisionModelRendersWithoutLiveServices() {
        var frozen = report();
        var context = ReportRenderContext.ofPayload(frozen);
        var diagrams = new DecisionChapterDiagramRenderer();
        String html = new String(new DecisionRationaleHtmlRenderer(diagrams).render(context).content(), StandardCharsets.UTF_8);
        assertTrue(html.contains(frozen.requirement()));
        String json = new String(new DecisionRationaleJsonRenderer(new tools.jackson.databind.ObjectMapper())
                .render(context).content(), StandardCharsets.UTF_8);
        assertTrue(json.contains("analysis-sha"));
        byte[] docx = new DecisionRationaleDocxRenderer(diagrams).render(context).content();
        assertEquals('P', docx[0]);
        assertEquals('K', docx[1]);
        assertEquals("analysis-sha", frozen.metadata().analysisSnapshotFingerprintSha256());
    }

    @Test
    void originalArchitectureFormatsAlsoNeedOnlyCapturedData() {
        var model = new ArchitectureReport();
        model.setBusinessText("Captured requirement");
        var context = ReportRenderContext.of(model);
        var text = new ArchitectureReportTextRenderer();
        assertTrue(new String(new MarkdownReportRendererExtension(text).render(context).content(),
                StandardCharsets.UTF_8).contains("Captured requirement"));
        assertTrue(new String(new HtmlReportRendererExtension(text).render(context).content(),
                StandardCharsets.UTF_8).contains("Captured requirement"));
        var json = new JsonReportRendererExtension(new ObjectProvider<com.fasterxml.jackson.databind.ObjectMapper>() {
            @Override public com.fasterxml.jackson.databind.ObjectMapper getObject() {
                return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            }
        });
        assertTrue(new String(json.render(context).content(), StandardCharsets.UTF_8).contains("Captured requirement"));
        byte[] docx = new DocxReportRendererExtension(new ArchitectureReportDocxRenderer()).render(context).content();
        assertEquals('P', docx[0]);
        assertEquals('K', docx[1]);
    }

    @Test
    void architectureAndApplicationImplementationsAreAbsent() {
        for (String absent : List.of("com.taxonomy.TaxonomyApplication",
                "com.taxonomy.architecture.service.ArchitectureReportService",
                "com.taxonomy.catalog.service.TaxonomyService")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(absent));
        }
    }

    static DecisionRationaleReport report() {
        Instant generatedAt = Instant.parse("2026-08-22T14:30:00Z");
        DecisionRationaleReport.ReportMetadata metadata =
                new DecisionRationaleReport.ReportMetadata(
                        generatedAt,
                        "template-user",
                        "1.4.0",
                        "build-commit",
                        "catalogue.xlsx",
                        "2026-08",
                        "source-sha",
                        "data-sha",
                        "analysis-sha",
                        "Bundled test catalogue",
                        10,
                        2,
                        "repository",
                        "workspace",
                        "main",
                        "based-on-commit",
                        generatedAt,
                        false,
                        false,
                        "MOCK",
                        "SUCCESS",
                        "mock-model",
                        "snapshot",
                        1L,
                        2L,
                        3L,
                        4,
                        generatedAt,
                        "analysis-author",
                        "recorded-taxonomy-sha",
                        "prompt-sha",
                        true,
                        "Europe/Berlin",
                        4,
                        10,
                        3,
                        100.0);
        return new DecisionRationaleReport(
                "Template-backed decision report",
                "en",
                "Provide a secure architecture decision.",
                DecisionRationaleReport.ReportStatus.FINAL,
                metadata,
                new DecisionRationaleReport.ExecutiveSummary(
                        null,
                        List.of(),
                        "No leading leaf is needed for this template contract test.",
                        "The dynamic report body is rendered after the editable cover."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

}
