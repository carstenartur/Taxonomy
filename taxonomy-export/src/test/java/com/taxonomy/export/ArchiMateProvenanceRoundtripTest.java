package com.taxonomy.export;

import com.taxonomy.archimate.exchange.*;
import com.taxonomy.diagram.*;
import com.taxonomy.dto.*;
import com.taxonomy.model.SeedType;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real normative XML must preserve the same frozen evidence as the neutral diagram. */
class ArchiMateProvenanceRoundtripTest {
    private final ArchiMateDiagramService converter = new ArchiMateDiagramService();
    private final ArchiMateXmlExporter exporter = new ArchiMateXmlExporter();
    private final ArchiMateExchangeReader reader = new ArchiMateExchangeReader();

    @Test void fullProvenanceSurvivesRepeatedActualXmlRoundTrips() {
        var source = new RelationSearchModel.Node("CP-1", "CP", "Source 漢字", "description\r\n<&>", false);
        var target = new RelationSearchModel.Node("CR-1", "CR", "Target", "description", true);
        var contribution = new RelationSearchModel.Contribution(source, "scoped contribution", "Exact original quote <&>\r\n", "source condition");
        var decision = new RelationSearchModel.Decision("CR-1", RelationSearchModel.Outcome.VERIFIED,
                "decision contribution", "decision quote", RelationSearchModel.Necessity.ALTERNATIVE,
                "decision condition", "alternative group", "specific rationale", "open question");
        var outgoing = new RelationSearchModel.Edge(contribution, target, "USES", RelationSearchModel.Direction.OUTGOING, decision);
        var incoming = new RelationSearchModel.Edge(contribution, target, "SUPPORTS", RelationSearchModel.Direction.INCOMING,
                new RelationSearchModel.Decision(null, null, null, "", null, null, "", null, null));
        var metadata = new DiagramRelationMetadata(Long.MAX_VALUE, RelationOrigin.LLM_SUPPORTED, .123456789,
                "included <&>\r\n漢字", "derivation", "presence", SeedType.values()[0], List.of(outgoing, incoming,
                new RelationSearchModel.Edge(contribution, target, "USES", RelationSearchModel.Direction.OUTGOING, null)));
        var expected = graph(metadata);
        var model = converter.convert(expected);
        byte[] xml = exporter.export(model);
        for (int index = 0; index < 3; index++) {
            var restored = reader.read(xml);
            assertEquals(expected, reader.toDiagram(restored));
            assertEquals(model, restored);
            assertArrayEquals(xml, exporter.export(restored));
            model = restored;
        }
        String text = new String(xml, StandardCharsets.UTF_8);
        assertTrue(text.contains("taxonomy.provenance.origin"));
        assertTrue(text.contains("LLM_SUPPORTED"));
        assertTrue(text.contains("Exact original quote &lt;&amp;&gt;"));
    }

    @Test void absenceEmptyProvenanceAndExplicitZeroConfidenceRemainDifferent() {
        var absentConfidence = new DiagramRelationMetadata(null, null, null, "", null, null, null, List.of());
        var zeroConfidence = new DiagramRelationMetadata(null, RelationOrigin.SUGGESTED_CANDIDATE, 0d, null, "", null, null, List.of());
        for (var metadata : Arrays.asList(null, absentConfidence, zeroConfidence)) {
            var expected = graph(metadata);
            var restored = reader.toDiagram(reader.read(exporter.export(converter.convert(expected))));
            assertEquals(expected, restored);
        }
    }

    @Test void malformedTypedProvenanceFailsRatherThanBeingSilentlyDiscarded() {
        var metadata = new DiagramRelationMetadata(42L, RelationOrigin.LLM_SUPPORTED, .75, null, null, null, null, List.of());
        var model = converter.convert(graph(metadata));
        for (var mutation : List.<java.util.function.Consumer<Map<String, com.taxonomy.archimate.ArchiMateProperty>>>of(
                p -> p.remove("taxonomy.provenance.version"),
                p -> p.put("taxonomy.provenance.version", com.taxonomy.archimate.ArchiMateProperty.text("future")),
                p -> p.put("taxonomy.provenance.confidence", com.taxonomy.archimate.ArchiMateProperty.text("0.75")),
                p -> p.put("taxonomy.provenance.confidence", new com.taxonomy.archimate.ArchiMateProperty("number", "1e400")),
                p -> p.put("taxonomy.provenance.relationId", new com.taxonomy.archimate.ArchiMateProperty("number", "42.5")),
                p -> p.put("taxonomy.provenance.origin", com.taxonomy.archimate.ArchiMateProperty.text("UNKNOWN_ORIGIN")),
                p -> p.put("taxonomy.provenance.evidenceCount", com.taxonomy.archimate.ArchiMateProperty.number(Integer.MAX_VALUE)),
                p -> p.put("taxonomy.provenance.evidence.0.type", com.taxonomy.archimate.ArchiMateProperty.text("orphan")),
                p -> p.put("taxonomy.provenance.futureField", com.taxonomy.archimate.ArchiMateProperty.text("unrepresented meaning")))) {
            var relationship = model.relationships().getFirst();
            var properties = new LinkedHashMap<>(relationship.properties());
            mutation.accept(properties);
            var changed = new com.taxonomy.archimate.ArchiMateRelationship(relationship.id(), relationship.sourceId(),
                    relationship.targetId(), relationship.archiMateType(), relationship.accessType(), relationship.name(), properties);
            var invalid = new com.taxonomy.archimate.ArchiMateModel(model.id(), model.title(), model.elements(),
                    List.of(changed), model.organizations(), model.views(), model.properties(), model.losses());
            byte[] xml = exporter.export(invalid);
            assertDoesNotThrow(() -> ArchiMateSchema.parse(xml));
            assertThrows(RuntimeException.class, () -> reader.read(xml));
        }
    }

    private static DiagramModel graph(DiagramRelationMetadata metadata) {
        return new DiagramModel("Frozen architecture", List.of(
                new DiagramNode("CP-1", "Source", "Capabilities", .8, true, 1),
                new DiagramNode("CR-1", "Target", "Core Services", .7, false, 2)),
                List.of(new DiagramEdge("e1", "CP-1", "CR-1", "USES", .8, "impact", metadata)), new DiagramLayout("LR", true));
    }
}
