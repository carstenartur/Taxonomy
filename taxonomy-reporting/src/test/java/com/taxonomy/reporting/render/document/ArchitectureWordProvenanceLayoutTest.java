package com.taxonomy.reporting.render.document;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.diagram.*;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.export.DiagramPresentation;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the native scenario's complete provenance and geometry for the twelve-page gate.
 * Its two BR-1228 display labels are neutralized; the canonical graph hash is recomputed.
 */
class ArchitectureWordProvenanceLayoutTest {
    @Test
    void identicalReasonsShareTheirFullValueWhileRetainingEveryFieldLabel() throws Exception {
        String reason = "The complete identical reason, including original quote and condition.";
        var metadata = new DiagramRelationMetadata(91L, RelationOrigin.LLM_SUPPORTED, null,
                reason, reason, reason, null, List.of());
        try (var document = word(singleRelation(metadata))) {
            var paragraphs = document.getParagraphs().stream().filter(p -> p.getText().contains(reason)).toList();
            assertThat(paragraphs).hasSize(1);
            assertThat(paragraphs.getFirst().getText())
                    .contains("Included because", "Derivation reason", "Presence reason", reason);
            assertThat(paragraphs.getFirst().getRuns()).allSatisfy(run -> assertThat(run.getFontSizeAsDouble()).isEqualTo(10d));
            assertThat(paragraphs.getFirst().getSpacingAfter()).isEqualTo(20);
            assertThat(new XWPFWordExtractor(document).getText()).doesNotContain("Confidence: 0");
        }
    }

    @Test
    void differentReasonValuesAndExplicitZeroConfidenceRemainSeparateAndComplete() throws Exception {
        var metadata = new DiagramRelationMetadata(92L, RelationOrigin.LLM_SUPPORTED, 0d,
                "Reason", "Reason ", "A distinct presence reason", null, List.of());
        try (var document = word(singleRelation(metadata))) {
            assertThat(document.getParagraphs()).extracting(p -> p.getText()).contains(
                    "Included because: Reason", "Derivation reason: Reason ", "Presence reason: A distinct presence reason",
                    "Confidence: 0.0");
        }
    }

    @Test
    void scenarioRetainsEveryNodeRelationshipAndEvidenceValueWithoutTriplicatedReasons() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        NativeFixture fixture;
        try (var input = getClass().getResourceAsStream("/reports/architecture-provenance-layout.json")) {
            fixture = mapper.readValue(input, NativeFixture.class);
        }
        var report = fixture.document();
        assertThat(report.diagram().nodes()).hasSize(30);
        assertThat(report.diagram().edges()).hasSize(7);
        byte[] bytes = new ArchitectureReportDocxRenderer().render(report);
        String review = System.getProperty("qa.report.nativeReviewDir");
        if (review != null) Files.write(Files.createDirectories(Path.of(review)).resolve("report.docx"), bytes);
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes));
                var extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            assertThat(text).contains(report.requirement(), report.scope(), report.recommendation(), report.evidence().graphSha256());
            assertThat(report.diagram().edges()).allSatisfy(edge -> assertThat(edge.metadata().confidence()).isNull());
            assertThat(text).doesNotContain("Confidence:");
            report.unresolvedGaps().forEach(value -> assertThat(text).contains(value));
            report.diagram().nodes().forEach(node -> assertThat(text).contains(node.id(), node.label()));
            for (var edge : report.diagram().edges()) {
                assertThat(text).contains(edge.id(), edge.sourceId(), edge.targetId(), edge.relationType());
                var metadata = edge.metadata();
                for (String line : DiagramPresentation.relationshipDetails(metadata)) {
                    if (line.startsWith("Included because: ") || line.startsWith("Derivation reason: ") || line.startsWith("Presence reason: ")) {
                        assertThat(text).contains(line.substring(line.indexOf(": ") + 2));
                    } else {
                        assertThat(text).contains(line);
                    }
                }
                assertThat(document.getParagraphs().stream().filter(p -> p.getText().contains(metadata.includedBecause())))
                        .hasSize(1);
            }
        }
    }

    private static XWPFDocument word(ArchitectureReportDocument report) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(new ArchitectureReportDocxRenderer().render(report)));
    }

    private static ArchitectureReportDocument singleRelation(DiagramRelationMetadata metadata) {
        var graph = new DiagramModel("Report", List.of(new DiagramNode("A", "Source", "Capability", 1, true, 0),
                new DiagramNode("B", "Target", "Process", 0, false, 1)),
                List.of(new DiagramEdge("edge", "A", "B", "SUPPORTS", .125, "impact", metadata)), new DiagramLayout("LR", true));
        var evidence = new ArchitectureReportDocument.SnapshotEvidence(1L, 2L, 3L, 1, "snapshot", "repo", "ws", "main", "commit",
                "MOCK", "model", "taxonomy-sha", ArchitectureReportDocument.graphSha256(graph));
        return ArchitectureReportDocuments.from("Report", "en", "Requirement", "Scope", "Recommendation", List.of(), graph,
                new LayeredDiagramLayoutService().layout(graph), DecisionTreeOverview.from(List.of()), evidence);
    }

    private record NativeFixture(String title, String languageTag, String requirement, String scope, String recommendation,
            List<String> unresolvedGaps, DiagramModel diagram, DiagramScene scene, ArchitectureReportDocument.SnapshotEvidence evidence) {
        ArchitectureReportDocument document() {
            return ArchitectureReportDocuments.from(title, languageTag, requirement, scope, recommendation, unresolvedGaps,
                    diagram, scene, DecisionTreeOverview.from(List.of()), evidence);
        }
    }
}
