package com.taxonomy.reporting.render.document;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.reporting.api.document.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureWordEvidenceTest {
    private static final String REASON = "Original evidence. ".repeat(120) + "COMPLETE-REASON-END";

    @Test void equalEvidenceIsPrintedOnceWithEverySourceLabelInBothLanguages() throws Exception {
        for (String language : List.of("en", "de")) {
            String text = render(language, metadata(), Map.of("presenceReason", REASON, "decisionComment", REASON), 1);
            assertThat(occurrences(text, REASON)).isEqualTo(1);
            for (String label : language.equals("de")
                    ? List.of("Aufgenommen weil", "Herleitungsbegründung", "Begründung der Aufnahme",
                            "Gespeicherte Auswahlbegründung", "Gespeicherter Prüfkommentar")
                    : List.of("Included because", "Derivation reason", "Presence reason",
                            "Recorded selection reason", "Recorded review comment")) {
                assertThat(text).contains(label);
            }
        }
    }

    @Test void differentTextAndWhitespaceAreNotCollapsed() throws Exception {
        String selection = "Original evidence.  ".repeat(120) + "SELECTION-END";
        String review = REASON + " Additional review context.";
        for (String language : List.of("en", "de")) {
            assertThat(render(language, metadata(), Map.of("presenceReason", selection, "decisionComment", review), 1))
                    .contains(REASON, selection, review);
        }
    }

    @Test void supplementalEvidenceSurvivesWithoutGraphMetadata() throws Exception {
        assertThat(render("en", null, Map.of("presenceReason", REASON, "decisionComment", "Independent review"), 1))
                .contains(REASON, "Independent review", "Recorded selection reason", "Recorded review comment");
    }

    @Test void equalReasonsOnDifferentRelationshipsRemainSeparatelyVisible() throws Exception {
        assertThat(occurrences(render("en", metadata(), Map.of("presenceReason", REASON), 2), REASON)).isEqualTo(2);
    }

    private static DiagramRelationMetadata metadata() {
        return new DiagramRelationMetadata(null, RelationOrigin.LLM_SUPPORTED, .7,
                REASON, REASON, REASON, null, List.of());
    }

    private static int occurrences(String text, String value) {
        return text.split(Pattern.quote(value), -1).length - 1;
    }

    private static String render(String language, DiagramRelationMetadata metadata,
            Map<String, String> fields, int relationships) throws Exception {
        var edges = new java.util.ArrayList<DiagramEdge>();
        var reasons = new java.util.LinkedHashMap<String, Map<String, String>>();
        for (int i = 0; i < relationships; i++) {
            String id = "edge-" + i;
            edges.add(new DiagramEdge(id, "source", "target", "SUPPORTS", .7, null, metadata));
            reasons.put(id, fields);
        }
        var graph = new DiagramModel("Authored fixture", List.of(
                new DiagramNode("source", "Source", "Capability", .8, false, 0),
                new DiagramNode("target", "Target", "Process", .6, false, 1)), edges, new DiagramLayout("LR", false));
        String digest = ArchitectureReportDocument.graphSha256(graph);
        var evidence = new ArchitectureReportDocument.SnapshotEvidence(1L, 2L, 3L, 1,
                "saved-snapshot", "repository", "workspace", "branch", "commit", "provider", "model",
                "taxonomy-digest", digest);
        var report = ArchitectureReportDocuments.from("Report", language, "Original requirement", "Scope",
                "Recommendation", List.of(), graph, new LayeredDiagramLayoutService().layout(graph),
                new DecisionTreeOverview(List.of(), List.of()), evidence).withRelationReasons(reasons);
        try (var document = new XWPFDocument(new ByteArrayInputStream(new ArchitectureReportDocxRenderer().render(report)));
             var extractor = new XWPFWordExtractor(document)) {
            assertThat(ArchitectureReportDocument.graphSha256(graph)).isEqualTo(digest);
            assertThat(document.getProperties().getCustomProperties().getProperty("taxonomy.graph.sha256").getLpwstr())
                    .isEqualTo(digest);
            return extractor.getText();
        }
    }
}
