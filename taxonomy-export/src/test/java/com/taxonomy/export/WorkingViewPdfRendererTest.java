package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.*;
import com.taxonomy.model.SeedType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class WorkingViewPdfRendererTest {
    @Test void originalNodeReasonsUseTheCompleteReadablePaginatedInventory() throws Exception {
        String reason = java.util.stream.IntStream.range(0, 120)
                .mapToObj(index -> "Original evidence for this selected node remains visible in the exported document. PARAGRAPH-" + index + "-END.")
                .collect(java.util.stream.Collectors.joining("\n")) + " ORIGINAL-NODE-REASON-END";
        byte[] bytes = new ArchitecturePdfRenderer().renderWorkingView(scene(2), Map.of(
                "NODE-0", reason, "EXCLUDED-NODE", "EXCLUDED-REASON-MUST-NOT-LEAK"), Map.of(
                "EDGE-END", Map.of(
                        "presenceReason", "The original mapping explains this selection. ".repeat(70) + "SUPPLEMENTAL-SELECTION-END",
                        "decisionComment", "The original reviewer recorded this assessment. ".repeat(70) + "SUPPLEMENTAL-REVIEW-END"),
                "EXCLUDED-EDGE", Map.of("presenceReason", "EXCLUDED-RELATION-REASON-MUST-NOT-LEAK")));
        try (var pdf = Loader.loadPDF(bytes)) {
            var positions = new ArrayList<org.apache.pdfbox.text.TextPosition>();
            var stripper = new PDFTextStripper() {
                @Override protected void writeString(String value, List<org.apache.pdfbox.text.TextPosition> textPositions)
                        throws java.io.IOException {
                    positions.addAll(textPositions);
                    super.writeString(value, textPositions);
                }
            };
            stripper.setStartPage(2); // The compact two-node diagram occupies the first page.
            String text = stripper.getText(pdf);
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
            assertThat(text).contains("NODE-0", "NODE-1", "80%", "Explanation:", "ORIGINAL-NODE-REASON-END", "QUESTION-END")
                    .contains("Recorded selection reason", "Recorded review comment", "SUPPLEMENTAL-SELECTION-END", "SUPPLEMENTAL-REVIEW-END")
                    .doesNotContain("EXCLUDED-REASON-MUST-NOT-LEAK", "EXCLUDED-RELATION-REASON-MUST-NOT-LEAK");
            for (int index = 0; index < 120; index++) assertThat(text).contains("PARAGRAPH-" + index + "-END");
            assertThat(positions).isNotEmpty().allSatisfy(position -> {
                assertThat(position.getFontSizeInPt()).isGreaterThanOrEqualTo(9);
                assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(32);
                assertThat(position.getXDirAdj() + position.getWidthDirAdj())
                        .isLessThanOrEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth() - 31.9f);
                assertThat(position.getYDirAdj()).isBetween(32f,
                        org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getHeight() - 32);
            });
        }
    }

    @Test void workingViewDoesNotClaimGitOrPersistedSnapshotAuthority() throws Exception {
        var scene = scene(2);
        try (var pdf = Loader.loadPDF(renderWorkingView(scene))) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Client-provided working view").doesNotContain("Git-authoritative", "Generated from persisted architecture snapshot");
            assertThat(pdf.getDocumentInformation().getSubject()).contains("Client-provided working view");
        }
    }

    @Test void fiveHundredWideNodesRetainFullLabelsAndRelationshipEvidenceWithinPdfLimits() throws Exception {
        var scene = scene(500);
        assertThat(scene.width()).isGreaterThan(14_400);
        try (var pdf = Loader.loadPDF(renderWorkingView(scene))) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2);
            for (var page : pdf.getPages()) {
                assertThat(page.getMediaBox().getWidth()).isLessThanOrEqualTo(14_400);
                assertThat(page.getMediaBox().getHeight()).isLessThanOrEqualTo(14_400);
            }
            for (int index = 0; index < 500; index++) assertThat(text).contains(String.format("LABEL-%04d-END", index));
            assertThat(text).contains("Elements: 500", "Relationships: 1", "Cross-page relationships: 1", "D1 -> D2",
                    "Relation ID: 9223372036854775807", "LLM_SUPPORTED", "Confidence: 0.125", "Seed type: FRAMEWORK_SEED",
                    "INCLUSION-END", "DERIVATION-END", "PRESENCE-END", "CONTRIBUTION-END", "ORIGINAL-QUOTE-END",
                    "CONTRIBUTION-CONDITION-END", "VERIFIED", "ALTERNATIVE", "DECISION-CONTRIBUTION-END", "DECISION-QUOTE-END",
                    "DECISION-CONDITION-END", "ALTERNATIVE-GROUP-END", "RATIONALE-END", "QUESTION-END");
        }
    }

    private static byte[] renderWorkingView(DiagramScene scene) throws Exception {
        return new ArchitecturePdfRenderer().renderWorkingView(scene);
    }

    static DiagramScene scene(int count) {
        var nodes = new ArrayList<DiagramNode>();
        for (int index = 0; index < count; index++) nodes.add(new DiagramNode("NODE-" + index,
                "W".repeat(100) + String.format(" LABEL-%04d-END", index), "Capabilities", .8, index == 0, 1));
        var source = new RelationSearchModel.Node("NODE-0", "CP", "Source", "Source description", false);
        var target = new RelationSearchModel.Node("NODE-" + (count - 1), "CP", "Target", "Target description", false);
        var contribution = new RelationSearchModel.Contribution(source, "CONTRIBUTION-END", "ORIGINAL-QUOTE-END", "CONTRIBUTION-CONDITION-END");
        var decision = new RelationSearchModel.Decision(target.id(), RelationSearchModel.Outcome.VERIFIED,
                "DECISION-CONTRIBUTION-END", "DECISION-QUOTE-END", RelationSearchModel.Necessity.ALTERNATIVE,
                "DECISION-CONDITION-END", "ALTERNATIVE-GROUP-END", "RATIONALE-END", "QUESTION-END");
        var metadata = new DiagramRelationMetadata(Long.MAX_VALUE, RelationOrigin.LLM_SUPPORTED, .125,
                "INCLUSION-END", "DERIVATION-END", "PRESENCE-END", SeedType.FRAMEWORK_SEED,
                List.of(new RelationSearchModel.Edge(contribution, target, "SUPPORTS", RelationSearchModel.Direction.OUTGOING, decision)));
        return new LayeredDiagramLayoutService().layout(new DiagramModel("Working diagram", nodes,
                List.of(new DiagramEdge("EDGE-END", source.id(), target.id(), "SUPPORTS", .8, "impact", metadata)), new DiagramLayout("LR", true)));
    }
}
