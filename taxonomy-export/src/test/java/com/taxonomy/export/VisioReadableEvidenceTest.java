package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.visio.*;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisioReadableEvidenceTest {
    @Test void representativeArchitecturePacksFullReasonsWithinIndependentPageBudgets() throws Exception {
        var nodes = new java.util.ArrayList<DiagramNode>();
        var edges = new java.util.ArrayList<DiagramEdge>();
        var reasons = new java.util.LinkedHashMap<String, Map<String, VisioProperty>>();
        String reason = "Each architectural element contributes a documented responsibility to this requirement. ".repeat(10);
        for (int i = 0; i < 100; i++) {
            String id = "N" + i;
            nodes.add(new DiagramNode(id, "Element " + i, "Capability", .5, false, i % 4));
            reasons.put(id, Map.of("taxonomy.presenceReason", VisioProperty.text(reason + "NODE-END-" + i)));
            if (i < 15) edges.add(new DiagramEdge("R" + i, id, "N" + (i + 1), "SUPPORTS", .5, null,
                    new DiagramRelationMetadata((long) i, RelationOrigin.LLM_SUPPORTED, .5,
                            reason + "RELATION-END-" + i, null, null, null, List.of())));
        }
        var graph = new DiagramModel("Representative architecture", nodes, edges, new DiagramLayout("LR", true));
        var document = new VisioDiagramService().convert(graph, new VisioExportMetadata(Map.of(), reasons, Map.of(), List.of()));
        assertThat(document.getPages()).hasSizeLessThanOrEqualTo(VisioPackageValidator.MAX_PAGES);
        assertThat(document.getPages().stream().filter(page -> !page.isEvidencePage()).count())
                .isLessThanOrEqualTo(VisioPackageValidator.MAX_DIAGRAM_PAGES);
        assertThat(document.getPages().stream().filter(VisioPage::isEvidencePage).count())
                .isPositive().isLessThanOrEqualTo(VisioPackageValidator.MAX_EVIDENCE_PAGES);
        assertThat(new VisioPackageBuilder().build(document)).isNotEmpty();
        String visible = document.getPages().stream().flatMap(page -> page.getShapes().stream()).map(VisioShape::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
        for (int i = 0; i < 100; i++) assertThat(visible).contains("NODE-END-" + i);
        for (int i = 0; i < 15; i++) assertThat(visible).contains("RELATION-END-" + i);
    }

    @Test void longNodeAndRelationshipReasonsSurviveAsVisiblePaginatedText() throws Exception {
        String nodeReason = "Der Knoten trägt zur vollständigen Erfüllung der Anforderung bei. ".repeat(90)
                + "VISIO-NODE-REASON-END-7319";
        String relationReason = "Die Beziehung bewahrt die fachliche Abhängigkeit und deren Bedingung. ".repeat(90)
                + "VISIO-RELATION-REASON-END-8462";
        var graph = new DiagramModel("Readable evidence", List.of(
                new DiagramNode("CP-source", "Source", "Capability", .8, false, 0),
                new DiagramNode("BP-target", "Target", "Process", .6, false, 1)),
                List.of(new DiagramEdge("edge-1", "CP-source", "BP-target", "SUPPORTS", .6, "impact",
                        new DiagramRelationMetadata(1L, RelationOrigin.LLM_SUPPORTED, .7,
                                relationReason, null, null, null, List.of()))), new DiagramLayout("LR", true));
        var metadata = new VisioExportMetadata(Map.of(), Map.of("CP-source",
                Map.of("taxonomy.presenceReason", VisioProperty.text(nodeReason))), Map.of(), List.of());
        var document = new VisioDiagramService().convert(graph, metadata);
        byte[] bytes = new VisioPackageBuilder().build(document);
        var review = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/visio-readable-evidence"));
        java.nio.file.Files.write(review.resolve("long-reasons.vsdx"), bytes);
        var parts = VisioHandoffContractTest.parts(bytes);
        assertThat(parts.get("visio/pages/page1.xml")).contains("N=\"Hyperlink\"", "N=\"SubAddress\" V=\"Evidence 1\"");
        StringBuilder visible = new StringBuilder();
        for (var part : parts.entrySet()) {
            if (!part.getKey().matches("visio/pages/page[0-9]+\\.xml")) continue;
            var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                    new ByteArrayInputStream(part.getValue().getBytes(StandardCharsets.UTF_8)));
            var text = xml.getElementsByTagName("Text");
            for (int i = 0; i < text.getLength(); i++) visible.append(text.item(i).getTextContent()).append('\n');
        }
        assertThat(visible.toString()).contains("VISIO-NODE-REASON-END-7319", "VISIO-RELATION-REASON-END-8462");
        assertThat(List.of(visible.toString().split("\\s+")))
                .containsSubsequence(List.of(nodeReason.split("\\s+")))
                .containsSubsequence(List.of(relationReason.split("\\s+")));
        assertThat(document.getPages().getFirst().getShapes()).allSatisfy(shape ->
                assertThat(shape.getHeight()).isLessThanOrEqualTo(1.0));
        assertThat(document.getPages().stream().flatMap(page -> page.getShapes().stream())
                .filter(shape -> shape.getProperties().containsKey("taxonomy.presentationOnly")))
                .isNotEmpty().allSatisfy(shape -> {
                    long lines = shape.getText().lines().count();
                    // 10pt text with 120% line spacing plus a conservative extra allowance.
                    assertThat(shape.getHeight() * 72 - 14.4).isGreaterThanOrEqualTo(lines * 13.3);
                    assertThat(shape.getY() - shape.getHeight() / 2).isGreaterThanOrEqualTo(0);
                    assertThat(shape.getX() + shape.getWidth() / 2).isLessThan(8.3);
                    assertThat(shape.getY() + shape.getHeight() / 2).isLessThan(11.7);
                });
    }
}
