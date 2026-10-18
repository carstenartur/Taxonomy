package com.taxonomy.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.diagram.*;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RelationSearchModel.*;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.model.RelationType;
import com.taxonomy.model.SeedType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class SparxDiagramHandoffV3Test {
    private static final UUID NAMESPACE = UUID.fromString("2da4e8f2-afc4-4f5b-87ae-e1b295a913d4");

    @ParameterizedTest @EnumSource(RelationType.class)
    void actualFreshCopyExporterPreservesEachExactRelationType(RelationType type) throws Exception {
        var graph = graph(type.name(), null);
        var parts = unzip(SparxDiagramHandoff.build(graph, NAMESPACE));
        var decoded = new SparxXmiCodec("3").read(parts.get("architecture.xmi"), "fresh-copy", false);
        var relation = decoded.relations().getFirst();
        assertEquals(type.name(), relation.extensions().get("canonicalType"));
        assertEquals(type.name(), relation.attributes().get("tag:taxonomy.relationType"));
        assertEquals("Source -> Destination", relation.attributes().get("tag:taxonomy.relationDirection"));
        assertEquals("Taxonomy::" + type.name(), relation.extensions().get("stereotype"));
        var source = decoded.artifacts().stream().filter(a -> "n1".equals(a.attributes().get("tag:taxonomy.sourceId"))).findFirst().orElseThrow();
        var target = decoded.artifacts().stream().filter(a -> "n2".equals(a.attributes().get("tag:taxonomy.sourceId"))).findFirst().orElseThrow();
        assertEquals(source.id(), relation.source()); assertEquals(target.id(), relation.target());
        var manifest = new ObjectMapper().readTree(parts.get("manifest.json"));
        assertEquals("sparx-xmi-2.1@3", manifest.path("exchangeProfile").asText());
        assertFalse(manifest.path("syncCheckpointChanged").asBoolean());
        assertEquals("not-certified", manifest.path("productCompatibility").asText());
    }

    @Test void allRecordedProvenanceQuotesConditionsAndDecisionFieldsRemainReviewable() throws Exception {
        var source = new Node("n1", "BP", "Ausgabe", "Ausgabe für den Lageprozess", false);
        var target = new Node("n2", "IP", "Lagebild", "Überflutungsflächen", false);
        var contribution = new Contribution(source, "Veröffentlichung der freigegebenen Lage", "F4: Nur freigegebene Warnungen ausgeben.", "A1: Freigabe liegt vor");
        var decision = new Decision("n2", Outcome.MATCH, "Gültiges Lagebild verteilen", "F5: Der Originaltext bleibt erhalten.",
                Necessity.REQUIRED, "Bei aktiver Warnstufe", "Lagequellen", "Verteilung benötigt die freigegebene Lage", "Ist die Warnstufe aktiv?");
        var metadata = new DiagramRelationMetadata(42L, RelationOrigin.LLM_SUPPORTED, .83,
                "provisional (AI-suggested, not yet confirmed)", "Aus F4 und F5 abgeleitet", "Freigegebene Lage bereitstellen",
                SeedType.SOURCE_DERIVED, List.of(new Edge(contribution, target, "PRODUCES", Direction.OUTGOING, decision)));
        var parts = unzip(SparxDiagramHandoff.build(graph("PRODUCES", metadata), NAMESPACE));
        var relation = new SparxXmiCodec("3").read(parts.get("architecture.xmi"), "fresh-copy", false).relations().getFirst();
        var mapper = new ObjectMapper();
        assertEquals(metadata, mapper.readValue(relation.attributes().get("tag:taxonomy.relationProvenance"), DiagramRelationMetadata.class));
        var expected = com.taxonomy.archimate.exchange.ArchiMateRelationProvenance.write(metadata);
        expected.forEach((key, field) -> assertEquals(field.value(), relation.attributes().get("tag:" + key), key));
        String notes = relation.attributes().get("description");
        for (String exact : List.of(contribution.text(), contribution.quote(), contribution.condition(), decision.quote(), decision.condition(),
                decision.rationale(), decision.question(), metadata.presenceReason(), "AI-proposed")) assertTrue(notes.contains(exact), exact);
        assertEquals("AI-proposed", relation.attributes().get("tag:taxonomy.provenanceStatus"));
        assertEquals("proposed", relation.extensions().get("taxonomy:status"));
        assertFalse(relation.attributes().containsKey("tag:taxonomy.id"), "Fresh-copy GUIDs do not assert an existing integration identity");
        assertArrayEquals(SparxDiagramHandoff.build(graph("PRODUCES", metadata), NAMESPACE), SparxDiagramHandoff.build(graph("PRODUCES", metadata), NAMESPACE));
    }

    private static DiagramModel graph(String type, DiagramRelationMetadata metadata) {
        return new DiagramModel("Flood working view", List.of(
                new DiagramNode("n1", "Ausgabe", "Business Processes", .6, true, 0),
                new DiagramNode("n2", "Lagebild", "Information Products", .45, false, 1)),
                List.of(new DiagramEdge("relation", "n1", "n2", type, .4, "requirement", metadata)), new DiagramLayout("LR", true));
    }

    private static Map<String, byte[]> unzip(byte[] value) throws Exception {
        var parts = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(value))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) parts.put(entry.getName(), zip.readAllBytes());
        }
        return parts;
    }
}
