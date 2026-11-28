package com.taxonomy.export;

import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;

import com.taxonomy.archimate.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Commit-tracked synthetic fixture for repeatable acceptance in external consumers. */
class ArchiMateInteropFixtureTest {
    static byte[] fixture() {
        var model = new ArchiMateDiagramService().convert(ArchiMateRoundtripTest.representative());
        model = new ArchiMateExportMetadata("fixture-snapshot-967",
                Map.of("taxonomy.snapshotId", ArchiMateProperty.text("fixture-snapshot-967"),
                        "taxonomy.repositoryId", ArchiMateProperty.text("synthetic-acceptance-repository"),
                        "taxonomy.authoritativeCommit", ArchiMateProperty.text("0000000000000000000000000000000000000000")),
                Map.of(), Map.of(), List.of()).apply(model);
        return new ArchiMateXmlExporter().export(model);
    }

    @Test
    void committedFixtureIsGeneratedByTheCurrentSerializerAndRoundTrips() throws Exception {
        byte[] expected;
        try (var input = getClass().getResourceAsStream("/archimate-v3/model.archimate.xml")) {
            assertNotNull(input);
            expected = input.readAllBytes();
        }
        assertArrayEquals(expected, fixture());
        var reader = new ArchiMateExchangeReader();
        assertEquals(ArchiMateRoundtripTest.representative(), reader.toDiagram(reader.read(expected)));
    }

    @Test
    void legacyFixtureRetainsItsOriginalServingAndCanonicalDirection() throws Exception {
        var reader = new ArchiMateExchangeReader();
        try (var input = getClass().getResourceAsStream("/archimate-v2/model.archimate.xml")) {
            assertNotNull(input);
            byte[] original = input.readAllBytes();
            var model = reader.read(original);
            assertEquals("Serving", model.relationships().getFirst().archiMateType());
            assertEquals(ArchiMateRoundtripTest.representative(), reader.toDiagram(model));
            assertArrayEquals(original, new ArchiMateXmlExporter().export(model));
        }
    }

    /** Distinct endpoint IDs on the two sides also exercise pairs of the same native concept. */
    private static DiagramModel allEndpointPairs() {
        Map<String, String> concepts = new TreeMap<>();
        var relationshipTypes = new ArrayList<String>();
        for (var mapping : ArchiMateExchangeProfile.mappings()) {
            if ("element".equals(mapping.scope())) {
                concepts.putIfAbsent(mapping.targetType(), mapping.sourceType());
            } else if ("relationship".equals(mapping.scope())) {
                relationshipTypes.add(mapping.sourceType());
            }
        }
        var nodes = new ArrayList<DiagramNode>();
        concepts.forEach((concept, type) -> {
            nodes.add(new DiagramNode("from-" + concept, "Source " + concept, type, 1, false, 0));
            nodes.add(new DiagramNode("to-" + concept, "Target " + concept, type, 1, false, 1));
        });
        var edges = new ArrayList<DiagramEdge>();
        for (String source : concepts.keySet()) {
            for (String target : concepts.keySet()) {
                for (String relationship : relationshipTypes) {
                    edges.add(new DiagramEdge(relationship + "-" + source + "-" + target, "from-" + source, "to-" + target, relationship, 1));
                }
            }
        }
        return new DiagramModel("All supported ArchiMate endpoint pairs", nodes, edges, null);
    }

    @Test
    void everySupportedDirectedPairKeepsItsCanonicalRelationshipAndDeclaresItsProjection() {
        var graph = allEndpointPairs();
        var projected = new ArchiMateDiagramService().convert(graph);
        assertEquals(graph, new ArchiMateExchangeReader().toDiagram(projected));
        long approximations = projected.losses().stream().filter(loss -> loss.kind().equals("APPROXIMATED")).count();
        long associations = projected.relationships().stream().filter(relation -> relation.archiMateType().equals("Association")
                && !relation.name().equals("RELATED_TO")).count();
        assertTrue(approximations > 0 && approximations < graph.edges().size());
        assertEquals(associations, approximations);
    }

    public static void main(String[] args) throws Exception {
        boolean allPairs = args.length == 2 && "--all-pairs".equals(args[1]);
        if ((args.length != 1 && !allPairs) || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: ArchiMateInteropFixtureTest <output.xml> [--all-pairs]");
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        byte[] bytes = allPairs
                ? new ArchiMateXmlExporter().export(new ArchiMateDiagramService().convert(allEndpointPairs()))
                : fixture();
        Files.write(output, bytes);
    }
}
