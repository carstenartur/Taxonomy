package com.taxonomy.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import com.taxonomy.dsl.mapping.ExternalRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link UafXmlParser}.
 */
class UafXmlParserTest {

    private UafXmlParser parser;

    @BeforeEach
    void setUp() {
        parser = new UafXmlParser();
    }

    @Test
    void fileFormat() {
        assertThat(parser.fileFormat()).isEqualTo("xml");
    }

    @Test
    void parsesSampleFile() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/uaf-sample.xml")) {
            assertThat(is).as("uaf-sample.xml resource").isNotNull();
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            assertThat(result.elements()).isNotEmpty();
            assertThat(result.relations()).isNotEmpty();
        }
    }

    @Test
    void parsesAllElementTypes() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/uaf-sample.xml")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            List<String> types = result.elements().stream().map(ExternalElement::type).toList();
            assertThat(types).contains("Capability", "OperationalActivity", "ServiceFunction", "System", "Organization");
        }
    }

    @Test
    void parsesRelations() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/uaf-sample.xml")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            assertThat(result.relations()).hasSizeGreaterThanOrEqualTo(3);
            List<String> relTypes = result.relations().stream().map(ExternalRelation::type).toList();
            assertThat(relTypes).contains("Implements", "Supports", "IsAssignedTo");
        }
    }

    @Test
    void parsesElementNames() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/uaf-sample.xml")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            List<String> names = result.elements().stream().map(ExternalElement::name).toList();
            assertThat(names).contains("Provide Logistics", "Track Shipment", "Logistics Tracking Service");
        }
    }

    @Test
    void parsesInlineXml() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <packagedElement xmi:id="e1" xsi:type="uaf:Capability" name="TestCap"/>
                  <packagedElement xmi:id="e2" xsi:type="uaf:System" name="TestSys"/>
                  <ownedConnector xmi:id="r1" xsi:type="uaf:DependsOn" source="e1" target="e2"/>
                </xmi:XMI>
                """;
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.elements()).hasSize(2);
        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().get(0).type()).isEqualTo("DependsOn");
    }

    @Test
    void parsesUnknownTypeGracefully() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <packagedElement xmi:id="e1" xsi:type="uaf:CustomWidget" name="Widget"/>
                </xmi:XMI>
                """;
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.elements()).hasSize(1);
        assertThat(result.elements().get(0).type()).isEqualTo("CustomWidget");
    }

    @Test
    void emptyXmlProducesEmptyModel() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001"/>
                """;
        ExternalParser.ParsedExternalModel result = parser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.elements()).isEmpty();
        assertThat(result.relations()).isEmpty();
    }

    @Test
    void extractsElementIds() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/uaf-sample.xml")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            List<String> ids = result.elements().stream().map(ExternalElement::id).toList();
            assertThat(ids).contains("cap-1", "act-1", "svc-1", "sys-1", "org-1");
        }
    }

    @Test
    void relationSourceAndTargetIds() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/uaf-sample.xml")) {
            ExternalParser.ParsedExternalModel result = parser.parse(is);
            ExternalRelation first = result.relations().stream()
                    .filter(r -> r.type().equals("Implements"))
                    .findFirst()
                    .orElse(null);
            assertThat(first).isNotNull();
            assertThat(first.sourceId()).isEqualTo("cap-1");
            assertThat(first.targetId()).isEqualTo("svc-1");
        }
    }
}
