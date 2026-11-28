package com.taxonomy.exchange;

import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import com.taxonomy.archimate.exchange.ArchiMateIds;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import com.taxonomy.extension.api.integration.IntegrationContracts.Relation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArchiMateWorkbenchEndpointsTest {
    private final ArchiMateExchangeCodec codec = new ArchiMateExchangeCodec();

    @ParameterizedTest
    @CsvSource({"USES,Serving", "DEPENDS_ON,Serving", "PART_OF,Composition"})
    void genericImportAndBasedExportPreserveOppositeCanonicalAndNativeDirections(String taxonomyType, String nativeType) {
        byte[] original = model("User Applications", "User Applications", taxonomyType);
        var imported = codec.read(original, "first", true);
        var relation = imported.relations().getFirst();
        assertEquals(nativeType, relation.type());
        assertEquals(taxonomyType, relation.extensions().get("canonicalType"));
        assertEquals(ArchiMateIds.id("element", "consumer"), relation.source());
        assertEquals(ArchiMateIds.id("element", "provider"), relation.target());

        byte[] returned = codec.write(imported);
        var originalXml = ExchangeXml.parse(original);
        var returnedXml = ExchangeXml.parse(returned);
        var returnedRelation = first(returnedXml, "relationship");
        assertEquals(relation.target(), returnedRelation.getAttribute("source"));
        assertEquals(relation.source(), returnedRelation.getAttribute("target"));
        assertEquals(ExchangeXml.semantic(ExchangeXml.xml(first(originalXml, "connection"))),
                ExchangeXml.semantic(ExchangeXml.xml(first(returnedXml, "connection"))));
        var reread = codec.read(returned, "second", true).relations().getFirst();
        assertEquals(relation.source(), reread.source());
        assertEquals(relation.target(), reread.target());
        relation.attributes().forEach((name, value) -> assertEquals(ExchangeXml.semantic(value),
                ExchangeXml.semantic(reread.attributes().get(name))));
    }

    @Test
    void conflictingWorkbenchAndGenericTypeTagsCannotSilentlyChangeCanonicalDirection() {
        var input = codec.read(model("Business Processes", "Core Services", "USES"), null, true);
        var returned = ExchangeXml.parse(codec.write(input));
        var typeDefinition = ExchangeXml.all(returned, ArchiMateExchangeCodec.NS, "propertyDefinition").stream()
                .filter(definition -> "Taxonomy.RelationType".equals(ExchangeXml.text(definition, "name")))
                .findFirst().orElseThrow().getAttribute("identifier");
        var relationship = first(returned, "relationship");
        var property = ExchangeXml.children(ExchangeXml.child(relationship, "properties")).stream()
                .filter(candidate -> typeDefinition.equals(candidate.getAttribute("propertyDefinitionRef")))
                .findFirst().orElseThrow();
        ExchangeXml.child(property, "value").setTextContent("SUPPORTS");
        assertEquals("RELATION_DIRECTION_CONFLICT", assertThrows(ExchangeFormatException.class,
                () -> codec.read(ExchangeXml.write(returned), null, true)).code());
    }

    @Test
    void basedWriteRejectsRetargetingThatContradictsTheRetainedViewConnection() {
        var original = codec.read(model("Business Processes", "Core Services", "USES"), null, true);
        var relation = original.relations().getFirst();
        var edited = new Relation(relation.id(), relation.type(), relation.target(), relation.source(),
                relation.attributes(), relation.extensions());
        var changed = new ExchangeDocument(original.profile(), original.profileVersion(), original.externalVersion(),
                original.completeScope(), original.source(), original.artifacts(), List.of(edited),
                original.placements(), original.metadata(), original.losses());
        assertEquals("VIEW_ENDPOINT_CONFLICT", assertThrows(ExchangeFormatException.class, () -> codec.write(changed)).code());
    }

    @Test
    void explicitAssociationApproximationKeepsItsOriginalDirection() {
        var relation = codec.read(model("Capabilities", "Core Services", "USES"), null, true).relations().getFirst();
        assertEquals("Association", relation.type());
        assertEquals("USES", relation.extensions().get("canonicalType"));
        assertEquals(ArchiMateIds.id("element", "consumer"), relation.source());
        assertEquals(ArchiMateIds.id("element", "provider"), relation.target());
    }

    @ParameterizedTest
    @ValueSource(strings = {"taxonomy-archimate-3.1-v2", "foreign-workbench"})
    void onlyTheNewWorkbenchProfileReversesServingEndpoints(String profile) {
        String xml = new String(model("Business Processes", "Core Services", "USES"), StandardCharsets.UTF_8)
                .replace(ArchiMateExchangeProfile.VERSION, profile);
        byte[] input = xml.getBytes(StandardCharsets.UTF_8);
        var imported = codec.read(input, null, true);
        var nativeRelation = first(ExchangeXml.parse(input), "relationship");
        var relation = imported.relations().getFirst();
        assertEquals(nativeRelation.getAttribute("source"), relation.source());
        assertEquals(nativeRelation.getAttribute("target"), relation.target());
        var returned = first(ExchangeXml.parse(codec.write(imported)), "relationship");
        assertEquals(relation.source(), returned.getAttribute("source"));
        assertEquals(relation.target(), returned.getAttribute("target"));
    }

    private static byte[] model(String consumerType, String providerType, String taxonomyType) {
        var model = new DiagramModel("Archive retrieval", List.of(
                new DiagramNode("consumer", "Records access", consumerType, 1, false, 1),
                new DiagramNode("provider", "Archive service", providerType, 1, false, 2)),
                List.of(new DiagramEdge("use", "consumer", "provider", taxonomyType, 1)), null);
        return new ArchiMateXmlExporter().export(new ArchiMateDiagramService().convert(model));
    }

    private static org.w3c.dom.Element first(Document document, String name) {
        return ExchangeXml.all(document, ArchiMateExchangeCodec.NS, name).getFirst();
    }
}
