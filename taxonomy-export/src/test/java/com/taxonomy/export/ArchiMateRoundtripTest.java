package com.taxonomy.export;

import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.archimate.exchange.ArchiMateIds;
import com.taxonomy.archimate.exchange.ArchiMateSchema;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;

import com.taxonomy.archimate.*;
import com.taxonomy.diagram.*;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Canonical graph -> normative XML -> clean profile import -> semantic comparison. */
class ArchiMateRoundtripTest {
    private final ArchiMateDiagramService converter = new ArchiMateDiagramService();
    private final ArchiMateXmlExporter exporter = new ArchiMateXmlExporter();
    private final ArchiMateExchangeReader reader = new ArchiMateExchangeReader();

    @Test
    void canonicalGraphPropertiesOrganizationsAndMultipleViewsSurviveRepeatedImportExport() {
        DiagramModel graph = representative();
        ArchiMateModel model = converter.convert(graph);
        model = new ArchiMateExportMetadata("snapshot-frozen-42", Map.of(
                "taxonomy.snapshotId", ArchiMateProperty.text("frozen-42"),
                "taxonomy.authoritativeCommit", ArchiMateProperty.text("a".repeat(40))),
                Map.of("space id", Map.of("taxonomy.decisionRationale", ArchiMateProperty.text("Approved: Grüße & 漢字\r\nEvidence"),
                        "taxonomy.reviewStatus", ArchiMateProperty.text("ACCEPTED"))), Map.of(), List.of()).apply(model);
        byte[] original = exporter.export(model);
        for (int i = 0; i < 3; i++) {
            ArchiMateModel imported = reader.read(original);
            assertEquals(graph, reader.toDiagram(imported));
            assertEquals(model, imported); // includes qualifiers, all typed properties and view membership/geometry
            assertEquals(2, imported.views().size());
            assertArrayEquals(original, exporter.export(imported));
            model = imported;
        }
    }

    @Test
    void profileCoversAllDomainRelationshipTypesAndEveryDeclaredMappingValidates() {
        for (RelationType type : RelationType.values()) assertNotNull(ArchiMateExchangeProfile.relationship(type.name()));
        var elements = ArchiMateExchangeProfile.mappings().stream().filter(m -> m.scope().equals("element"))
                .map(m -> new DiagramNode(m.sourceType(), "Identical display name", m.sourceType(), 0.5, false, 1)).toList();
        var relations = ArchiMateExchangeProfile.mappings().stream().filter(m -> m.scope().equals("relationship"))
                .map(m -> new DiagramEdge(m.sourceType(), elements.getFirst().id(), elements.getLast().id(), m.sourceType(), .75, "trace")).toList();
        var graph = new DiagramModel("All declared mappings", elements, relations, new DiagramLayout("LR", true));
        var model = converter.convert(graph);
        var imported = reader.read(exporter.export(model));
        assertEquals(graph, reader.toDiagram(imported));
        assertEquals(elements.size() + relations.size(), imported.losses().size());
        assertTrue(imported.losses().stream().allMatch(loss -> loss.kind().equals("MAPPED")));
    }

    @Test
    void identifiersAreInjectiveAcrossDomainsAndStableAcrossNamesOrderingAndLayout() {
        List<String> sourceIds = List.of("a b", "a-b", "a_b", "rel-a", "view-1", "漢字", "id-node-0000000161", "a:b");
        Set<String> external = new HashSet<>();
        for (String source : sourceIds) {
            for (String domain : List.of("model", "element", "relationship", "view", "property")) {
                String id = ArchiMateIds.id(domain, source);
                assertTrue(id.matches("[A-Za-z_][A-Za-z0-9_.-]*"));
                assertTrue(external.add(id));
                assertEquals(List.of(source), ArchiMateIds.decode(domain, id));
            }
        }
        assertNotEquals(ArchiMateIds.id("node", "a", "bc"), ArchiMateIds.id("node", "ab", "c"));
        DiagramModel graph = representative();
        List<DiagramNode> changed = new ArrayList<>(graph.nodes().stream().map(n ->
                new DiagramNode(n.id(), "Renamed", n.type(), .5, n.anchor(), n.layer() + 2, n.depth(),
                        n.selectedForImpact(), n.parentId(), false)).toList());
        Collections.reverse(changed);
        var before = converter.convert(graph);
        var after = converter.convert(new DiagramModel("Different title", changed, graph.edges(), new DiagramLayout("TB", false)));
        assertEquals(before.id(), after.id());
        Set<String> beforeIds = elementXmlIds(exporter.export(before));
        assertEquals(beforeIds, elementXmlIds(exporter.export(after)));
    }

    @Test
    void allSelectedObjectsAreExportedOrNamedInLossReport() {
        var nodes = new ArrayList<>(representative().nodes());
        nodes.add(new DiagramNode("container", "Visual container", "Unknown visual group", .0, false, 1, 0, false, null, true));
        var edges = new ArrayList<>(representative().edges());
        edges.add(new DiagramEdge("container-edge", "container", "space id", "CONTAINS", .5));
        var model = reader.read(exporter.export(converter.convert(new DiagramModel("Containers", nodes, edges, new DiagramLayout("LR", true)))));
        Set<String> elements = new HashSet<>(model.elements().stream().map(ArchiMateElement::id).toList());
        Set<String> relationships = new HashSet<>(model.relationships().stream().map(ArchiMateRelationship::id).toList());
        model.losses().stream().filter(l -> l.kind().equals("OMITTED")).forEach(loss -> {
            if (loss.scope().equals("element")) elements.add(loss.id());
            if (loss.scope().equals("relationship")) relationships.add(loss.id());
        });
        assertEquals(new HashSet<>(nodes.stream().map(DiagramNode::id).toList()), elements);
        assertEquals(new HashSet<>(edges.stream().map(DiagramEdge::id).toList()), relationships);
    }

    @Test
    void unknownSemanticTypesFailWithoutImplicitFallback() {
        assertThrows(IllegalArgumentException.class, () -> converter.convert(new DiagramModel("Unsupported",
                List.of(new DiagramNode("n", "Name", "Secret new type", .5, false, 1)), List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> ArchiMateExchangeProfile.relationship("INVENTED"));
        assertThrows(IllegalArgumentException.class, () -> ArchiMateExchangeProfile.element(null));
    }

    @Test
    void boundedLargeUnicodeModelValidatesAndRetainsEveryIdentity() {
        var nodes = IntStream.range(0, 1000).mapToObj(i -> new DiagramNode("node " + i,
                "Übereinstimmung 漢字 🌍 ".repeat(i % 7 + 1), "Capabilities", .123456789, false, i % 8)).toList();
        var graph = new DiagramModel("Large model", nodes, List.of(), new DiagramLayout("LR", true));
        assertEquals(graph, reader.toDiagram(reader.read(exporter.export(converter.convert(graph)))));
        var tooMany = new DiagramModel("too many", Collections.nCopies(ArchiMateSchema.MAX_ELEMENTS + 1, nodes.getFirst()), List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> converter.convert(tooMany));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "\u0001", "\u000b", "\ufffe", "\ud800", "\udfff"})
    void rejectsUnsafeXmlCharactersInsteadOfReplacingOrEmittingThem(String invalid) {
        var graph = new DiagramModel("Unsafe " + invalid,
                List.of(new DiagramNode("n", "Safe", "Capabilities", .5, false, 1)), List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> exporter.export(converter.convert(graph)));
        assertThrows(IllegalArgumentException.class, () -> ArchiMateIds.id("element", invalid));
    }

    @Test
    void normativeValidationRejectsMissingAbstractTypesInvalidStylesIdsAndReferences() {
        String xml = new String(exporter.export(converter.convert(representative())), StandardCharsets.UTF_8);
        for (String invalid : List.of(
                xml.replaceFirst(" xsi:type=\"Element\"", ""),
                xml.replaceFirst(" xsi:type=\"Relationship\"", ""),
                xml.replaceFirst("xsi:type=\"Element\"", "xsi:type=\"Capability\""),
                xml.replaceFirst("<style lineWidth=\"3\">", "<style><lineWidth>3</lineWidth>"),
                xml.replaceFirst("identifier=\"[^\"]+\"", "identifier=\"invalid id\""),
                xml.replaceFirst("elementRef=\"[^\"]+\"", "elementRef=\"missing\""),
                xml.replaceFirst("relationshipRef=\"[^\"]+\"", "relationshipRef=\"missing\""),
                xml.replaceFirst("propertyDefinitionRef=\"[^\"]+\"", "propertyDefinitionRef=\"missing\""),
                xml.replaceFirst("source=\"[^\"]+\"", "source=\"missing\""),
                xml.replaceFirst("r=\"255\"", "r=\"300\""))) {
            assertNotEquals(xml, invalid, "negative fixture must change the input");
            assertThrows(IllegalArgumentException.class, () -> ArchiMateSchema.parse(invalid.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void rejectsDanglingDuplicateAndMisroutedModelReferencesBeforeSerialization() {
        var model = converter.convert(representative());
        var duplicate = new ArchiMateModel(model.id(), model.title(), List.of(model.elements().getFirst(), model.elements().getFirst()),
                List.of(), Map.of(), List.of(), model.properties(), List.of());
        assertThrows(IllegalArgumentException.class, () -> exporter.export(duplicate));
        var relation = new ArchiMateRelationship("bad", "missing", "space id", "Association", null, "bad");
        var badReference = new ArchiMateModel(model.id(), model.title(), model.elements(), List.of(relation), Map.of(), List.of(), model.properties(), List.of());
        assertThrows(IllegalArgumentException.class, () -> exporter.export(badReference));
        var view = model.view();
        var connection = new ArchiMateViewConnection("bad", "r", "space-id", "space id");
        var badView = new ArchiMateView(view.id(), view.name(), view.nodes(), List.of(connection));
        var misrouted = new ArchiMateModel(model.id(), model.title(), model.elements(), model.relationships(), Map.of(), List.of(badView), model.properties(), List.of());
        assertThrows(IllegalArgumentException.class, () -> exporter.export(misrouted));
    }

    @Test
    void rejectsDtdsSpoofedPropertyTypesAndWrongOriginalIdentities() {
        String xml = new String(exporter.export(converter.convert(representative())), StandardCharsets.UTF_8);
        String dtd = xml.replace("?><model", "?><!DOCTYPE model [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><model");
        assertThrows(IllegalArgumentException.class, () -> reader.read(dtd.getBytes(StandardCharsets.UTF_8)));
        String wrongId = xml.replace("<value xml:lang=\"en\">space id</value>", "<value xml:lang=\"en\">different</value>");
        assertThrows(IllegalArgumentException.class, () -> reader.read(wrongId.getBytes(StandardCharsets.UTF_8)));
        String typed = xml.replace("type=\"boolean\"", "type=\"string\"");
        assertThrows(IllegalArgumentException.class, () -> reader.read(typed.getBytes(StandardCharsets.UTF_8)));
    }

    private Set<String> elementXmlIds(byte[] xml) {
        var document = ArchiMateSchema.parse(xml);
        Set<String> ids = new HashSet<>();
        var elements = document.getElementsByTagNameNS(ArchiMateSchema.NAMESPACE, "element");
        for (int i = 0; i < elements.getLength(); i++) ids.add(((org.w3c.dom.Element) elements.item(i)).getAttribute("identifier"));
        return ids;
    }

    @Test
    void schemaValidButUnsupportedExtensionsFailWithoutSilentSemanticLoss() {
        String xml = new String(exporter.export(converter.convert(representative())), StandardCharsets.UTF_8);
        for (String extension : List.of(
                xml.replaceFirst("</name>", "</name><documentation xml:lang=\"en\">Extra model meaning</documentation>"),
                xml.replaceFirst("</name>", "</name><name xml:lang=\"de\">Weitere Sprachfassung</name>"),
                xml.replaceFirst("<fillColor r=", "<fillColor a=\"25\" r="))) {
            byte[] bytes = extension.getBytes(StandardCharsets.UTF_8);
            assertDoesNotThrow(() -> ArchiMateSchema.parse(bytes));
            assertThrows(IllegalArgumentException.class, () -> reader.read(bytes));
        }
    }

    static DiagramModel representative() {
        return new DiagramModel("Long Unicode name: Grüße 漢字 🌍 & < > \" ' ".repeat(10), List.of(
                new DiagramNode("space id", "Duplicate name", "Capabilities", .9123456789, true, 1, 2, false, null, false),
                new DiagramNode("space-id", "Duplicate name", "Core Services", .78654321, false, 2, 3, true, "space id", false),
                new DiagramNode("other", "Other", "Information Products", .2, false, 3)),
                List.of(new DiagramEdge("r", "space id", "space-id", "USES", .876543, "dependency"),
                        new DiagramEdge("read", "space-id", "other", "CONSUMES", .234567, "data")), new DiagramLayout("LR", true));
    }
}
