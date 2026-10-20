package com.taxonomy.exchange;

import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import com.taxonomy.extension.api.integration.IntegrationContracts.Relation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArchiMateProposalStatusTest {
    private final ArchiMateExchangeCodec codec = new ArchiMateExchangeCodec();

    @Test void legacyProposedRelationshipRemainsProposedWithoutLosingItsSourceEvidence() {
        var document = read("taxonomy.reviewStatus", "PROPOSED", null);
        var relation = document.relations().getFirst();
        assertEquals("proposed", relation.extensions().get("taxonomy:status"));
        assertEquals("SUPPORTS", relation.extensions().get("canonicalType"));
        assertEquals("service", relation.source()); assertEquals("process", relation.target());
        assertEquals("taxonomy.reviewStatus", relation.extensions().get("definition:review"));
        assertTrue(relation.attributes().get("review").contains("PROPOSED"));
        assertTrue(relation.extensions().get("xml").contains("propertyDefinitionRef=\"review\""));
        var returned = codec.read(codec.write(document), "returned", true).relations().getFirst();
        assertEquals("proposed", returned.extensions().get("taxonomy:status"));
        assertEquals(ExchangeXml.semantic(relation.attributes().get("review")),
                ExchangeXml.semantic(returned.attributes().get("review")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "rejected", "proposed", "INVALID", "", " "})
    void explicitCanonicalStatusWinsEvenWhenInvalidOrBlank(String status) {
        var relation = read("taxonomy.reviewStatus", "PROPOSED", status).relations().getFirst();
        assertTrue(relation.extensions().containsKey("taxonomy:status"));
        assertEquals(status, relation.extensions().get("taxonomy:status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"proposed", "Proposed", " PROPOSED", "PROPOSED ", "ACCEPTED", "REJECTED", "", "UNKNOWN"})
    void otherReviewValuesDoNotInventCanonicalStatus(String review) {
        assertFalse(read("taxonomy.reviewStatus", review, null).relations().getFirst()
                .extensions().containsKey("taxonomy:status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Taxonomy.reviewStatus", "taxonomy.reviewstatus", "custom.reviewStatus", "taxonomy.status"})
    void similarlyNamedPropertiesDoNotInventCanonicalStatus(String name) {
        assertFalse(read(name, "PROPOSED", null).relations().getFirst()
                .extensions().containsKey("taxonomy:status"));
    }

    @Test void modelAndElementReviewPropertiesAreNotInheritedByRelationships() {
        String review = property("review", "PROPOSED");
        var document = codec.read(model(review, review, "", "taxonomy.reviewStatus"), "source", true);
        assertFalse(document.relations().getFirst().extensions().containsKey("taxonomy:status"));
        assertTrue(document.artifacts().getFirst().attributes().containsKey("review"));
        assertTrue(document.metadata().get("properties").contains("PROPOSED"));
    }

    @Test void externalRelationshipsWithoutReviewMetadataKeepTheirExistingDefault() {
        assertFalse(codec.read(model("", "", "", "taxonomy.reviewStatus"), "source", true)
                .relations().getFirst().extensions().containsKey("taxonomy:status"));
    }

    @Test void laterAcceptedEditOverridesRetainedHistoricalProposalOnRewrite() {
        var original = read("taxonomy.reviewStatus", "PROPOSED", null);
        var relation = original.relations().getFirst();
        var extensions = new LinkedHashMap<>(relation.extensions()); extensions.put("taxonomy:status", "accepted");
        var accepted = new Relation(relation.id(), relation.type(), relation.source(), relation.target(),
                relation.attributes(), extensions);
        var updated = new ExchangeDocument(original.profile(), original.profileVersion(), original.externalVersion(),
                original.completeScope(), original.source(), original.artifacts(), List.of(accepted),
                original.placements(), original.metadata(), original.losses());
        var returned = codec.read(codec.write(updated), "edited", true).relations().getFirst();
        assertEquals("accepted", returned.extensions().get("taxonomy:status"));
        assertEquals(ExchangeXml.semantic(relation.attributes().get("review")),
                ExchangeXml.semantic(returned.attributes().get("review")));
    }

    private ExchangeDocument read(String name, String review, String canonical) {
        String properties = property("review", review) + (canonical == null ? "" : property("status", canonical));
        return codec.read(model("", "", properties, name), "source", true);
    }

    private static String property(String reference, String value) {
        // All test values are literal text without XML markup.
        return "<property propertyDefinitionRef=\"" + reference + "\"><value>" + value + "</value></property>";
    }

    private static String properties(String content) {
        return content.isEmpty() ? "" : "<properties>" + content + "</properties>";
    }

    private static byte[] model(String modelProperties, String elementProperties, String relationProperties, String reviewName) {
        return ("""
                <model xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" identifier="model">
                  <name>Proposal status</name>
                  %s
                  <elements>
                    <element identifier="service" xsi:type="ApplicationService"><name>Service</name>%s</element>
                    <element identifier="process" xsi:type="BusinessProcess"><name>Process</name></element>
                  </elements>
                  <relationships>
                    <relationship identifier="support" xsi:type="Serving" source="service" target="process">%s</relationship>
                  </relationships>
                  <propertyDefinitions>
                    <propertyDefinition identifier="review" type="string"><name>%s</name></propertyDefinition>
                    <propertyDefinition identifier="status" type="string"><name>Taxonomy.status</name></propertyDefinition>
                  </propertyDefinitions>
                </model>
                """).formatted(properties(modelProperties), properties(elementProperties), properties(relationProperties), reviewName)
                .getBytes(StandardCharsets.UTF_8);
    }
}
