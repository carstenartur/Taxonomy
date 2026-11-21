package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitecturePdfRendererTest {

    private final LayeredDiagramLayoutService layout = new LayeredDiagramLayoutService();
    private final ArchitecturePdfRenderer renderer = new ArchitecturePdfRenderer();

    @Test
    void wideScenarioSceneKeepsReadableLabelsAndHeaderTextInsideTheNode() throws Exception {
        var node = new com.taxonomy.diagram.DiagramSceneNode("CO-1048", "Short Messaging Access Services",
                "Communications Services", 1, true, 8, 4, true, null, false,
                2200, 40, 240, 84);
        var wide = new DiagramScene("Flood information", 2516, 650, "LR", List.of(node), List.of());
        try (var document = org.apache.pdfbox.Loader.loadPDF(renderer.render(wide, "FLOOD-001"))) {
            var labels = new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();
            var nodeText = new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();
            var text = new org.apache.pdfbox.text.PDFTextStripper() {
                @Override protected void writeString(String value, List<org.apache.pdfbox.text.TextPosition> positions)
                        throws java.io.IOException {
                    if (value.contains("Short Messaging")) labels.addAll(positions);
                    if (value.contains("Short Messaging") || value.contains("CO-1048")
                            || value.contains("Communications Services") || value.contains("100%")) {
                        nodeText.addAll(positions);
                    }
                    super.writeString(value, positions);
                }
            };
            text.setEndPage(1); // Geometry belongs to the diagram, not the separate inventory.
            assertThat(text.getText(document)).contains("Short Messaging Access Services", "CO-1048", "100%", "Communications Services");
            assertThat(labels).isNotEmpty().allSatisfy(position -> assertThat(position.getFontSizeInPt()).isGreaterThanOrEqualTo(8));
            assertThat(nodeText).isNotEmpty().allSatisfy(position -> {
                assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(84);
                assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThanOrEqualTo(324);
            });
            assertThat(document.getPage(0).getMediaBox().getWidth())
                    .isLessThanOrEqualTo(org.apache.pdfbox.pdmodel.common.PDRectangle.A3.getHeight());
        }
    }

    @Test
    void snapshotInventoryPreservesParagraphsAndUnicodeEvidence() throws Exception {
        var metadata = new ArchitecturePdfRenderer.SnapshotMetadata(
                "P-001", "REQ-001", "snapshot-1", "MODEL", "draft", "abcdef1234567890", List.of());
        byte[] bytes = renderer.render(scene(), metadata, Map.of("CP-1",
                "Erste Begründung: Einsatzkräfte → Führungsstelle.\r\n\r\nZweite Begründung: Größe ≤ 10. verification offline."),
                Map.of(), java.util.Locale.GERMAN);

        try (var pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertThat(text).contains("Begründung: Erste Begründung: Einsatzkräfte → Führungsstelle.",
                    "Zweite Begründung: Größe ≤ 10. verification offline.")
                    .doesNotContain("??", "Einsatzkraefte ? Fuehrungsstelle");
            assertThat(text.lines().filter(line -> line.contains("Zweite Begründung")))
                    .allMatch(line -> !line.contains("Erste Begründung"));
        }
    }

    @Test
    void retainedSnapshotDistinguishesUnavailableMetricsFromRecordedZeroes() throws Exception {
        var view = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {"includedElements":[
                  {"nodeCode":"source-evidence","title":"Evidence-only source","taxonomySheet":"CI","origin":"REQUIREMENT_EVIDENCE"},
                  {"nodeCode":"accepted-endpoint","title":"Unscored endpoint","taxonomySheet":"BP","origin":"RELATION_EVIDENCE",
                   "relevance":0,"taxonomyDepth":0},
                  {"nodeCode":"scored-root","title":"Scored root","taxonomySheet":"CP","origin":"DIRECT_SCORED",
                   "relevance":0,"taxonomyDepth":0,"hierarchyPath":"scored-root",
                   "scoreDetail":{"nodeCode":"scored-root","kind":"HIERARCHICAL_RELEVANCE","rawScore":0,"effectiveRelevance":0}},
                  {"nodeCode":"typed-child","title":"Typed child","taxonomySheet":"CI","origin":"RELATION_EVIDENCE",
                   "hierarchyPath":"CI > typed-child", "scoreDetail":{"nodeCode":"typed-child","kind":"HIERARCHICAL_RELEVANCE","rawScore":60,"effectiveRelevance":60}},
                  {"nodeCode":"legacy-scored","title":"Legacy retained values","taxonomySheet":"UA","relevance":0.35,"taxonomyDepth":2}],
                 "includedRelationships":[{"sourceCode":"source-evidence","targetCode":"accepted-endpoint","relationType":"SUPPORTS"}]}
                """, com.taxonomy.dto.RequirementArchitectureView.class);
        var graph = PersistedDiagramProjection.project(new DiagramProjectionService(), view, "Saved snapshot");
        var metadata = new ArchitecturePdfRenderer.SnapshotMetadata(
                "P-001", "REQ-001", "snapshot-1", "MODEL", "draft", "abcdef1234567890", List.of());
        var retainedScene = layout.layout(graph);

        try (var pdf = org.apache.pdfbox.Loader.loadPDF(renderer.render(retainedScene, metadata))) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertThat(text)
                    .containsPattern("source-evidence \\| D\\d+ \\| Depth unknown \\| Score unrecorded")
                    .containsPattern("accepted-endpoint \\| D\\d+ \\| Depth unknown \\| Score unrecorded")
                    .containsPattern("scored-root \\| D\\d+ \\| Depth 0 \\| 0%")
                    .containsPattern("typed-child \\| D\\d+ \\| Depth 1 \\| 60%")
                    .containsPattern("legacy-scored \\| D\\d+ \\| Depth 2 \\| 35%");
        }
        assertThat(new SvgDiagramRenderer().render(retainedScene))
                .contains("source-evidence | Score unrecorded | Depth unknown", "scored-root | 0% | Depth 0");
    }

    @Test
    void retainedRelevancePresenceSurvivesSnapshotJsonRoundTrip() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var view = mapper.readValue("""
                {"includedElements":[
                  {"nodeCode":"missing-score","origin":"DIRECT_SCORED"},
                  {"nodeCode":"stored-zero","origin":"PROPAGATED","relevance":0},
                  {"nodeCode":"retained-typed","origin":"RELATION_EVIDENCE",
                   "scoreDetail":{"nodeCode":"retained-typed","kind":"HIERARCHICAL_RELEVANCE","rawScore":60,"effectiveRelevance":60}}]}
                """, com.taxonomy.dto.RequirementArchitectureView.class);

        byte[] saved = mapper.writeValueAsBytes(view);
        var elements = mapper.readTree(saved).path("includedElements");
        assertThat(elements.get(0).has("relevance")).isFalse();
        assertThat(elements.get(1).path("relevance").isNumber()).isTrue();
        assertThat(elements.get(2).has("relevance")).isFalse();

        var replay = mapper.readValue(saved, com.taxonomy.dto.RequirementArchitectureView.class);
        assertThat(replay.getIncludedElements()).extracting(com.taxonomy.dto.RequirementElementView::recordedRelevance)
                .containsExactly(null, 0.0, 0.6);
        assertThat(replay.getIncludedElements()).extracting(com.taxonomy.dto.RequirementElementView::getRelevance)
                .containsExactly(0.0, 0.0, 0.6);
    }

    @Test
    void rendersLiveEditorSceneWithoutDependingOnApplicationTypes() {
        DiagramScene scene = scene();

        byte[] pdf = renderer.render(scene,
                "repo-a / workspace-a / draft\nRevision 3 / checkpoint abcdef1234567890");

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void preservesExactLiveProvenanceInPdfMetadata() throws Exception {
        String provenance = "  repo-a / workspace-a / draft  \nCheckpoint abcdef1234567890  ";

        byte[] pdf = renderer.render(scene(), provenance);

        try (var parsed = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(parsed.getDocumentInformation().getSubject()).isEqualTo(provenance);
        }
    }

    @Test
    void rendersSnapshotSceneThroughNeutralExportMetadata() {
        DiagramScene scene = scene();
        var metadata = new ArchitecturePdfRenderer.SnapshotMetadata(
                "P-001", "REQ-001", "snapshot-1", "GEMINI",
                "feature-a", "abcdef1234567890", List.of("Review relation"));

        byte[] pdf = renderer.render(scene, metadata);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void snapshotInventoryIncludesSuppliedOriginalNodeReasons() throws Exception {
        var metadata = new ArchitecturePdfRenderer.SnapshotMetadata(
                "P-001", "REQ-001", "snapshot-1", "GEMINI", "feature-a", "abcdef1234567890", List.of());
        byte[] bytes = renderer.render(scene(), metadata, Map.of(
                "CP-1", "Original analysis justification. SNAPSHOT-NODE-REASON-END",
                "NOT-IN-SCENE", "EXCLUDED-REASON-MUST-NOT-LEAK"));

        try (var pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            assertThat(pdf.getDocumentInformation().getSubject()).isEqualTo("Requirement-derived architecture snapshot snapshot-1");
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
                    .contains("CP-1", "91%", "Original analysis justification.", "SNAPSHOT-NODE-REASON-END")
                    .doesNotContain("EXCLUDED-REASON-MUST-NOT-LEAK");
        }
    }

    @Test
    void rejectsEmptySceneInsteadOfProducingMisleadingPdf() {
        DiagramScene empty = layout.layout(
                new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", true)));

        assertThatThrownBy(() -> renderer.render(empty, "provenance"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    private DiagramScene scene() {
        return layout.layout(new DiagramModel(
                "Secure architecture",
                List.of(
                        new DiagramNode("CP-1", "Command capability", "Capabilities", 0.91, true, 1),
                        new DiagramNode("CR-1", "Secure exchange", "Core Services", 0.84, false, 3)),
                List.of(new DiagramEdge(
                        "edge-1", "CP-1", "CR-1", "REALIZED_BY", 0.8, "impact")),
                new DiagramLayout("LR", true)));
    }
}
