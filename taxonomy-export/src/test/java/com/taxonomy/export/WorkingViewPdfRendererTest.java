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
