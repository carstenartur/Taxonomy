package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import java.io.StringReader;
import static org.assertj.core.api.Assertions.assertThat;

class DiagramQaRegressionTest {
    @Test void wideLabelsAndIdentifiersFitAndFullTextRemainsAccessible() throws Exception {
        String label = "W".repeat(90) + " END-OF-LABEL <literal>";
        var scene = new LayeredDiagramLayoutService().layout(new DiagramModel("Wide",
            List.of(new DiagramNode("W".repeat(90), label, "CommunicationsService", .8, false, 1)),
            List.of(), new DiagramLayout("LR", true)));
        String svg = new SvgDiagramRenderer().render(scene);
        assertThat(svg).contains("END-OF-LABEL &lt;literal&gt;");
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        var texts = doc.getElementsByTagName("text");
        for (int i = 0; i < texts.getLength(); i++) {
            var text = (org.w3c.dom.Element) texts.item(i);
            if (!(text.getParentNode() instanceof org.w3c.dom.Element group) || !group.getAttribute("class").equals("architecture-node")) continue;
            int style = "bold".equals(text.getAttribute("font-weight")) ? Font.BOLD : Font.PLAIN;
            var font = new Font("Arial", style, 1).deriveFont(Float.parseFloat(text.getAttribute("font-size")));
            double width = font.getStringBounds(text.getTextContent(), new FontRenderContext(null, true, true)).getWidth();
            assertThat(width).as(text.getTextContent()).isLessThanOrEqualTo(210);
        }
    }

    @Test void canonicalTypesShareTheirRootAndDisplayPalette() {
        String[][] aliases = {{"CP", "Capability", "Capabilities"}, {"BP", "Process", "Business Processes"},
            {"CR", "CoreService", "Core Services"}, {"CI", "COIService", "COI Services"},
            {"CO", "CommunicationsService", "Communications Services"}, {"UA", "UserApplication", "User Applications"},
            {"IP", "InformationProduct", "Information Products"}, {"BR", "BusinessRole", "Business Roles"}};
        for (String[] family : aliases) {
            assertThat(DiagramPresentation.fill(family[1])).isEqualTo(DiagramPresentation.fill(family[0])).isNotEqualTo("#F1F5F9");
            assertThat(DiagramPresentation.fill(family[2])).isEqualTo(DiagramPresentation.fill(family[0]));
        }
    }

    @Test void proposedAndDerivedRelationsNeverCollapseToTheSameExport() {
        var projection = new DiagramProjectionService();
        var view = new RequirementArchitectureView();
        var relation = new RequirementRelationshipView();
        relation.setSourceCode("CP-1"); relation.setTargetCode("CR-1"); relation.setRelationType("REALIZES");
        relation.setRelationId(42L); relation.setPropagatedRelevance(.8); relation.setConfidence(.75);
        relation.setIncludedBecause("original inclusion"); relation.setDerivationReason("specific rationale");
        relation.setPresenceReason("specific presence");
        view.setIncludedRelationships(List.of(relation));
        relation.setOrigin(RelationOrigin.IMPACT_DERIVED);
        var derived = projection.projectRaw(view, "T");
        relation.setOrigin(RelationOrigin.LLM_SUPPORTED);
        var proposed = projection.projectRaw(view, "T");
        assertThat(proposed).isNotEqualTo(derived);
        var captured = proposed.edges().getFirst().metadata();
        assertThat(captured.relationId()).isEqualTo(42L);
        assertThat(captured.origin()).isEqualTo(RelationOrigin.LLM_SUPPORTED);
        assertThat(captured.confidence()).isEqualTo(.75);
        assertThat(captured.includedBecause()).isEqualTo("original inclusion");
        assertThat(captured.derivationReason()).isEqualTo("specific rationale");
        assertThat(captured.presenceReason()).isEqualTo("specific presence");
    }
    @Test void provenanceIsStructuredAndVisibleAcrossSvgPdfAndVisio() throws Exception {
        var source = new RelationSearchModel.Node("CP-1", "CP", "Source", "", false);
        var target = new RelationSearchModel.Node("CR-1", "CR", "Target", "", false);
        var contribution = new RelationSearchModel.Contribution(source, "Scoped contribution", "exact original quote", "when required");
        var decision = new RelationSearchModel.Decision("CR-1", RelationSearchModel.Outcome.VERIFIED,
            "interpreted contribution", "decision quote", RelationSearchModel.Necessity.OPTIONAL,
            "specific condition", "alternative A", "evidence rationale", "open question");
        var evidence = new RelationSearchModel.Edge(contribution, target, "REALIZES", RelationSearchModel.Direction.OUTGOING, decision);
        var metadata = new DiagramRelationMetadata(42L, RelationOrigin.LLM_SUPPORTED, .75,
            "original inclusion", "specific rationale", "specific presence", null, List.of(evidence));
        var model = model(new DiagramEdge("e1", "CP-1", "CR-1", "REALIZES", .8, "impact", metadata));
        var scene = new LayeredDiagramLayoutService().layout(model);
        assertThat(scene.edges().getFirst().metadata()).isEqualTo(metadata);
        String svg = new SvgDiagramRenderer().render(scene);
        assertThat(svg).contains("stroke-dasharray=\"6 4\"", "LLM_SUPPORTED", "Relation ID: 42", "specific rationale",
            "Confidence: 0.75", "exact original quote", "Relationships - AI-proposed: 1",
            "<relationId>42</relationId>", "<origin>LLM_SUPPORTED</origin>", "<necessity>OPTIONAL</necessity>");
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(new ArchitecturePdfRenderer().render(scene, "test provenance"))) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertThat(text).contains("AI-proposed", "LLM_SUPPORTED", "Relation ID: 42", "specific rationale", "Confidence: 0.75",
                "exact original quote", "evidence rationale", "alternative A");
        }
        var visio = new VisioDiagramService().convert(model);
        var edge = visio.getPages().getFirst().getConnects().getFirst();
        assertThat(edge.getProperties().get("taxonomy.origin").value()).isEqualTo("LLM_SUPPORTED");
        assertThat(edge.getProperties().get("taxonomy.evidence1quote").value()).isEqualTo("exact original quote");
        assertThat(edge.getProperties().get("taxonomy.evidence1necessity").value()).isEqualTo("OPTIONAL");
        assertThat(visio.getPages().getFirst().getLegend().text()).contains("AI-proposed: 1", "Human approval is not recorded");
        byte[] vsdx = new VisioPackageBuilder().build(visio);
        var entries = new java.util.HashMap<String, String>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(vsdx))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                entries.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(entries.get("visio/pages/page1.xml")).contains("#9333EA", "AI-proposed", "exact original quote")
            .contains("N=\"LinePattern\" V=\"2\"");
    }

    @Test void nullOriginsAndLegacyEdgesRemainExplicitlyUnrecorded() {
        assertThat(DiagramPresentation.relationshipStatus(null)).isEqualTo("Origin unrecorded");
        var absent = new DiagramRelationMetadata(null, null, null, null, null, null, null, List.of());
        assertThat(DiagramPresentation.relationshipDetails(absent)).contains("Origin: unrecorded").doesNotContain("Confidence: 0.0");
        assertThat(DiagramPresentation.relationshipStatus(absent)).isEqualTo("Origin unrecorded");
        var legacyProvisional = new DiagramRelationMetadata(null, null, null,
            "provisional (AI-suggested, not yet confirmed)", null, null, null, List.of());
        assertThat(DiagramPresentation.relationshipStatus(legacyProvisional)).isEqualTo("AI-proposed");
        assertThat(DiagramPresentation.relationshipDetails(legacyProvisional)).contains("Origin: unrecorded");
    }

    @Test void selectionRetainsDistinctRecordedRelationshipsWithEqualEndpointsAndType() {
        var derived = new DiagramRelationMetadata(1L, RelationOrigin.IMPACT_DERIVED, .8, null, "derived", null, null, List.of());
        var suggested = new DiagramRelationMetadata(2L, RelationOrigin.SUGGESTED_CANDIDATE, .8, null, "suggested", null, null, List.of());
        var graph = model(new DiagramEdge("e1", "CP-1", "CR-1", "REALIZES", .8, "impact", derived),
            new DiagramEdge("e2", "CP-1", "CR-1", "REALIZES", .8, "impact", suggested));
        var curated = new ConfigurableDiagramSelectionPolicy(DiagramSelectionConfig.defaultImpact()).apply(graph);
        assertThat(curated.edges()).extracting(DiagramEdge::metadata).containsExactlyInAnyOrder(derived, suggested);
    }

    @Test void horizontalConnectorCaptionsFitTheGapBetweenNodeBoxes() throws Exception {
        var m = new DiagramRelationMetadata(null, RelationOrigin.LLM_SUPPORTED, null, null, null, null, null, List.of());
        var scene = new LayeredDiagramLayoutService().layout(model(new DiagramEdge("one", "CP-1", "CR-1", "W".repeat(80), .8, "impact", m)));
        var svg = new SvgDiagramRenderer().render(scene);
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        var texts = doc.getElementsByTagName("text");
        double gap = scene.edges().getFirst().targetX() - scene.edges().getFirst().sourceX() - 12;
        for (int i = 0; i < texts.getLength(); i++) {
            var text = (org.w3c.dom.Element) texts.item(i);
            if (!(text.getParentNode() instanceof org.w3c.dom.Element group) || !group.getAttribute("class").equals("architecture-edge")) continue;
            assertThat(Double.parseDouble(text.getAttribute("textLength"))).isLessThanOrEqualTo(gap);
            var bounds = new Font("Arial", Font.PLAIN, 11).getStringBounds(text.getTextContent(), new FontRenderContext(null, true, true));
            double baseline = Double.parseDouble(text.getAttribute("y")) - scene.edges().getFirst().targetY();
            double arrowHalfHeight = 4 * (1.2 + .8 * 1.8);
            assertThat(baseline + bounds.getMaxY() < -arrowHalfHeight || baseline + bounds.getMinY() > arrowHalfHeight)
                .as("Caption glyphs must avoid the arrowhead, even where horizontal bounds overlap").isTrue();
        }
    }

    private static DiagramModel model(DiagramEdge... edges) {
        return new DiagramModel("Provenance", List.of(
            new DiagramNode("CP-1", "Source", "Capability", .8, true, 1),
            new DiagramNode("CR-1", "Target", "CoreService", .8, false, 3)), List.of(edges), new DiagramLayout("LR", true));
    }

}
