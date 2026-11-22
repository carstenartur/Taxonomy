package com.taxonomy.export;

import com.structurizr.dsl.StructurizrDslParser;
import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class CatalogueUuidExchangeTest {
    @Test void exportsKnownCatalogueIdentityAsElementMetadataAlongsideEachFormatIdentity() throws Exception {
        String uuid = "4726e390-d42b-4dc4-bc4a-a259045fd10d";
        String key = "taxonomy.catalogueUuid";
        var known = new DiagramNode("CP-1023", "Capability", "Capabilities", .9, true, 1,
                0, false, null, false, null, null, uuid);
        var local = new DiagramNode("LOCAL-1", "Project service", "Core Services", .8, false, 2);
        var model = new DiagramModel("Catalogue identity", List.of(known, local), List.of(), new DiagramLayout("LR", true));

        var reader = new ArchiMateExchangeReader();
        var exchange = reader.read(new ArchiMateXmlExporter().export(new ArchiMateDiagramService().convert(model)));
        var knownElement = exchange.elements().stream().filter(e -> e.id().equals(known.id())).findFirst().orElseThrow();
        var localElement = exchange.elements().stream().filter(e -> e.id().equals(local.id())).findFirst().orElseThrow();
        assertEquals(uuid, knownElement.properties().get(key).value());
        assertFalse(localElement.properties().containsKey(key));
        assertEquals(model.nodes(), reader.toDiagram(exchange).nodes());

        var dsl = new StructurizrExportService().export(model);
        assertTrue(dsl.contains("cP1023 = element"));
        assertTrue(dsl.contains("lOCAL1 = element"));
        var parser = new StructurizrDslParser();
        parser.setRestricted(true);
        parser.parse(dsl);
        var elements = parser.getWorkspace().getModel().getElements();
        assertEquals(2, elements.size());
        for (var element : elements) {
            var properties = element.getProperties();
            String id = properties.get("taxonomy.id");
            assertTrue(List.of(known.id(), local.id()).contains(id));
            assertEquals(id.equals(known.id()) ? uuid : null, properties.get(key));
        }

        var namespace = UUID.fromString("f017403a-5739-47a9-9ea0-e75342f5dcb7");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(SparxDiagramHandoff.build(model, namespace)))) {
            assertEquals("architecture.xmi", zip.getNextEntry().getName());
            var resources = new SparxXmiCodec("3").read(zip.readAllBytes(), "fresh-copy", false).artifacts();
            assertEquals(2, resources.size());
            for (var resource : resources) {
                Map<String, String> tags = resource.attributes();
                String id = tags.get("tag:taxonomy.sourceId");
                assertTrue(List.of(known.id(), local.id()).contains(id));
                assertEquals(SparxMappingProfile.externalId(namespace, "node\0" + id), resource.id());
                assertEquals(id.equals(known.id()) ? uuid : null, tags.get("tag:" + key));
            }
        }
    }
}
