package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class UafXmlParserStructureTest {
    @Test
    void nestedNamesCommentsAndConnectorsKeepTheirOwningElement() throws Exception {
        var result = parse("""
                <model xmlns:xmi="http://www.omg.org/spec/XMI/20131001">
                  <ownedElement xmi:id="owner" xmi:type="uaf:Capability">
                    <name>Owner name</name>
                    <ownedComment body="Owner description"/>
                    <ownedConnector target="target" type="uaf:Implements"><metadata/></ownedConnector>
                    <connector source="explicit" target="target" type="DependsOn"/>
                    <connector source="ignored"/>
                  </ownedElement>
                  <packagedElement id="target" type="System" name="Target">
                    <description>Target description</description>
                    <ownedComment>Final description</ownedComment>
                  </packagedElement>
                  <packagedElement name="No identity"/>
                </model>
                """);
        assertThat(result.elements()).extracting(ExternalElement::id, ExternalElement::name,
                ExternalElement::description, ExternalElement::type).containsExactly(
                tuple("owner", "Owner name", "Owner description", "Capability"),
                tuple("target", "Target", "Final description", "System"));
        assertThat(result.relations()).extracting(r -> r.sourceId(), r -> r.targetId(), r -> r.type())
                .containsExactly(tuple("owner", "target", "Implements"),
                        tuple("explicit", "target", "DependsOn"));
    }

    @Test
    void legacyIdentitiesAndTopLevelRelationsAreParsedWithoutTreatingMetadataAsElements() throws Exception {
        var result = parse("""
                <model xmlns:xmi="http://www.omg.org/XMI"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <packagedElement xmi:id="old" xsi:type="uaf:System" name="Old"/>
                  <ownedElement id="plain" name="Plain"/>
                  <packagedElement id="edge" type="Implements" source="old" target="plain">
                    <nested><packagedElement id="metadata"/></nested>
                  </packagedElement>
                  <connector source="plain" target="old" type="uaf:DependsOn"><metadata/></connector>
                  <ownedConnector source="old" target="plain"/>
                  <connector source="old"/>
                  <connector target="plain"/>
                </model>
                """);
        assertThat(result.elements()).extracting(ExternalElement::id, ExternalElement::type)
                .containsExactly(tuple("old", "System"), tuple("plain", "Unknown"));
        assertThat(result.relations()).extracting(r -> r.sourceId(), r -> r.targetId(), r -> r.type())
                .containsExactly(tuple("old", "plain", "Implements"),
                        tuple("plain", "old", "DependsOn"), tuple("old", "plain", "Unknown"));
    }

    private static ExternalParser.ParsedExternalModel parse(String xml) throws Exception {
        return new UafXmlParser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
