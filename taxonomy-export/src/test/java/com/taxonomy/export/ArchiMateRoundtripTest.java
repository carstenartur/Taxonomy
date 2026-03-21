package com.taxonomy.export;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roundtrip test: build a DiagramModel → export as ArchiMate XML → verify XML structure.
 */
class ArchiMateRoundtripTest {

    private final ArchiMateDiagramService archiMateService = new ArchiMateDiagramService();
    private final ArchiMateXmlExporter xmlExporter = new ArchiMateXmlExporter();

    @Test
    void roundtripProducesValidXml() throws Exception {
        var nodes = List.of(
            new DiagramNode("n1", "User Portal", "UserApplication", 0.9, false, 1),
            new DiagramNode("n2", "Auth Service", "CoreService", 0.8, false, 2)
        );
        var edges = List.of(
            new DiagramEdge("e1", "n1", "n2", "USES", 1.0)
        );
        var diagram = new DiagramModel("test-diagram", nodes, edges, null);

        // Convert to ArchiMate model
        ArchiMateModel archiModel = archiMateService.convert(diagram);
        assertThat(archiModel).isNotNull();
        assertThat(archiModel.elements()).hasSize(2);
        assertThat(archiModel.relationships()).hasSize(1);

        // Export to XML
        byte[] xml = xmlExporter.export(archiModel);
        assertThat(xml).isNotEmpty();

        // Verify it's valid XML
        var dbf = DocumentBuilderFactory.newInstance();
        var db = dbf.newDocumentBuilder();
        var doc = db.parse(new ByteArrayInputStream(xml));
        assertThat(doc).isNotNull();

        // Verify XML contains expected elements
        String xmlStr = new String(xml);
        assertThat(xmlStr).contains("User Portal");
        assertThat(xmlStr).contains("Auth Service");
    }

    @Test
    void exportThenReExportIsStable() throws Exception {
        var nodes = List.of(
            new DiagramNode("n1", "Portal", "UserApplication", 0.9, false, 1)
        );
        var diagram = new DiagramModel("stable-test", nodes, List.of(), null);

        // First export
        ArchiMateModel model1 = archiMateService.convert(diagram);
        byte[] xml1 = xmlExporter.export(model1);

        // Second export from same input
        ArchiMateModel model2 = archiMateService.convert(diagram);
        byte[] xml2 = xmlExporter.export(model2);

        String s1 = new String(xml1);
        String s2 = new String(xml2);
        assertThat(s1).contains("Portal");
        assertThat(s2).contains("Portal");
        // Both should have same element count patterns
        assertThat(countOccurrences(s1, "<element")).isEqualTo(countOccurrences(s2, "<element"));
    }

    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
