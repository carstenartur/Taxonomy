package com.taxonomy.portfolio.workbench;

import com.taxonomy.export.PdfDiagramRenderer;
import com.taxonomy.export.PdfDiagramRenderer.DocumentDetails;
import com.taxonomy.export.PdfDiagramRenderer.FooterLine;
import com.taxonomy.export.PdfDiagramRenderer.HeaderLine;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Supplies portfolio-specific document semantics to the framework-neutral vector PDF renderer. */
@Component
public class ArchitecturePdfRenderer {

    private final PdfDiagramRenderer renderer = new PdfDiagramRenderer();

    public byte[] render(Projection projection) {
        if (projection == null || projection.scene() == null || projection.scene().isEmpty()) {
            throw new IllegalArgumentException("Architecture projection must contain a renderable scene");
        }

        List<FooterLine> footerLines = new ArrayList<>();
        footerLines.add(new FooterLine(
                "Generated from persisted architecture snapshot; no page screenshot and no LLM re-analysis.",
                7, 17));
        if (!projection.warnings().isEmpty()) {
            footerLines.add(new FooterLine("Warnings: " + String.join(" | ", projection.warnings()), 7, 8));
        }

        return renderer.render(projection.scene(), new DocumentDetails(
                "Requirement-derived architecture snapshot " + projection.snapshotId(),
                "Taxonomy Architecture Analyzer",
                "architecture, taxonomy, requirement, snapshot, " + projection.snapshotId(),
                List.of(
                        new HeaderLine(
                                "Project " + projection.projectKey()
                                        + " · Requirement " + projection.requirementKey()
                                        + " · Snapshot " + projection.snapshotId(),
                                9, 19),
                        new HeaderLine(
                                "Provider " + safe(projection.provider())
                                        + " · Branch " + safe(projection.branchName())
                                        + " · Commit " + abbreviated(projection.commitSha()),
                                8, 33)),
                footerLines));
    }

    private static String abbreviated(String value) {
        String normalized = safe(value);
        return normalized.length() > 12 ? normalized.substring(0, 12) : normalized;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value.strip();
    }
}
