package com.taxonomy.architecture.decision;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Creates a deterministic, non-sensitive sample DOCX for validating the active template. */
@Service
public final class DecisionRationaleTemplatePreviewService {

    private final DecisionRationaleTemplateRenderer templateRenderer;
    private final DecisionRationaleDocxRenderer docxRenderer;

    public DecisionRationaleTemplatePreviewService(
            DecisionRationaleTemplateRenderer templateRenderer,
            DecisionRationaleDocxRenderer docxRenderer) {
        this.templateRenderer = templateRenderer;
        this.docxRenderer = docxRenderer;
    }

    public byte[] renderPreview() {
        Instant generatedAt = Instant.parse("2026-01-01T12:00:00Z");
        DecisionRationaleReport.ReportMetadata metadata =
                new DecisionRationaleReport.ReportMetadata(
                        generatedAt,
                        "template-test",
                        "preview",
                        "preview-build",
                        "preview-catalogue",
                        "preview-data",
                        "preview-resource-sha",
                        "preview-data-sha",
                        "preview-analysis-sha",
                        "Bundled non-sensitive preview data",
                        3,
                        1,
                        "preview-repository",
                        "preview-workspace",
                        "main",
                        "preview-commit",
                        generatedAt,
                        false,
                        false,
                        "PREVIEW",
                        "SUCCESS",
                        "preview-model",
                        "preview-snapshot",
                        null,
                        null,
                        null,
                        null,
                        generatedAt,
                        "template-test",
                        "preview-taxonomy-sha",
                        "preview-prompt-sha",
                        true,
                        "Europe/Berlin",
                        0,
                        3,
                        2,
                        100.0);
        DecisionRationaleReport report = new DecisionRationaleReport(
                "Taxonomy template test report",
                "en",
                "Verify branding, page setup, headers, footers and generated report content.",
                DecisionRationaleReport.ReportStatus.FINAL,
                metadata,
                new DecisionRationaleReport.ExecutiveSummary(
                        null,
                        List.of(),
                        "The active Word template was opened and materialized successfully.",
                        "This preview uses synthetic, non-sensitive data."),
                List.of(),
                List.of(),
                List.of("Preview only — no architecture decision was evaluated."),
                List.of(),
                null);
        return templateRenderer.render(docxRenderer, report);
    }
}
