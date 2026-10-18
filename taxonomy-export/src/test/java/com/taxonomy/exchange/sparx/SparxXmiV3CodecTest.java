package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeFormatException;
import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Taxonomy profile assertions; this synthetic XMI is not an Enterprise Architect desktop test. */
class SparxXmiV3CodecTest {
    private static final String A = "{11111111-1111-4111-8111-111111111111}";
    private static final String B = "{22222222-2222-4222-8222-222222222222}";
    private static final String R = "{33333333-3333-4333-8333-333333333333}";
    private static final String MODEL = "{00000000-0000-4000-8000-000000000000}";
    private static final String QUOTE = "F4: Warnungen müssen unverändert an die zuständigen Stellen gehen.\nA1: Nur nach Freigabe.";
    private static final Map<RelationType, String> TRANSPORTS = Map.ofEntries(
            Map.entry(RelationType.DEPENDS_ON, "Dependency"), Map.entry(RelationType.REALIZES, "Association"),
            Map.entry(RelationType.COMMUNICATES_WITH, "InformationFlow"), Map.entry(RelationType.RELATED_TO, "Association"),
            Map.entry(RelationType.CONSUMES, "Usage"), Map.entry(RelationType.CONTAINS, "Composition"),
            Map.entry(RelationType.SUPPORTS, "Association"), Map.entry(RelationType.USES, "Association"),
            Map.entry(RelationType.FULFILLS, "Association"), Map.entry(RelationType.ASSIGNED_TO, "Association"),
            Map.entry(RelationType.PRODUCES, "Association"), Map.entry(RelationType.REQUIRES, "Association"));
    private static final Set<RelationType> LEGACY = EnumSet.of(RelationType.DEPENDS_ON, RelationType.REALIZES,
            RelationType.COMMUNICATES_WITH, RelationType.RELATED_TO, RelationType.CONSUMES, RelationType.CONTAINS);

    @ParameterizedTest @EnumSource(RelationType.class)
    void exactTaxonomyTypeDirectionIdentityAndEvidenceSurviveBothDirections(RelationType type) {
        assertEquals(EnumSet.allOf(RelationType.class), TRANSPORTS.keySet(), "Every real enum value needs an explicit transport decision");
        for (String direction : List.of("Source -> Destination", "Destination -> Source")) {
            var original = document("3", relation(type, direction));
            var codec = new SparxXmiCodec("3");
            byte[] bytes = codec.write(original);
            var returned = codec.read(bytes, "returned", true);
            assertEquals("3", returned.profileVersion());
            assertEquals(original.relations(), returned.relations(), type + " / " + direction);
            assertEquals(original.artifacts(), returned.artifacts());
            assertEquals(original.metadata(), returned.metadata(), "The source text is model evidence, not generated notes");
            assertArrayEquals(bytes, codec.write(original));
            var xml = ExchangeXml.parse(bytes);
            var packaged = ExchangeXml.all(xml, null, "packagedElement").stream()
                    .filter(e -> SparxMappingProfile.xmiId(R, false).equals(e.getAttributeNS(SparxMappingProfile.XMI, "id")))
                    .findFirst().orElseThrow();
            assertEquals(type.name(), packaged.getAttribute("name"), "The exact type must be visible");
            String encoded = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(encoded.contains("Taxonomy::" + type.name()));
            assertTrue(returned.losses().stream().noneMatch(l -> l.disposition() == LossDisposition.UNSUPPORTED), returned.losses().toString());
        }
    }

    @ParameterizedTest @EnumSource(RelationType.class)
    void legacyProfilesKeepTheirSixMappingsAndNeverAcquireTheNewAssociationMeanings(RelationType type) {
        for (String version : List.of("1", "2")) {
            var codec = new SparxXmiCodec(version);
            String legacyTransport = type == RelationType.REALIZES ? "Realization" : TRANSPORTS.get(type);
            var relation = new Relation(R, legacyTransport, A, B, Map.of(),
                    Map.of("canonicalType", type.name(), "direction", "Source -> Destination"));
            if (LEGACY.contains(type)) {
                var returned = codec.read(codec.write(document(version, relation)), "legacy", true);
                assertEquals(type.name(), returned.relations().getFirst().extensions().get("canonicalType"));
            } else {
                assertEquals("SPARX_RELATION_UNMAPPED", assertThrows(ExchangeFormatException.class,
                        () -> codec.write(document(version, relation))).code());
                assertNull(SparxMappingProfile.eaRelation(type.name()), "The legacy mapping API is immutable");
            }
        }
    }

    @Test void passiveRepositoryRealizesKeepsCapabilityToServiceWithoutAnOppositeUmlRealizationClaim() {
        // RelationType.REALIZES and USER_GUIDE: a capability is made real by a service (CP -> Service).
        // UML Realization instead says that its source/client implements its destination/supplier.
        var original = document("3", relation(RelationType.REALIZES, "Source -> Destination"));
        var typed = new ExchangeDocument(original.profile(), original.profileVersion(), original.externalVersion(), original.completeScope(), "",
                List.of(new Artifact(A, ArtifactKind.ELEMENT, "UseCase", "Information capability", "The capability is made real by the service.",
                                Map.of("tag:taxonomy.elementType", "Capability"), Map.of("canonicalType", "Capability")),
                        new Artifact(B, ArtifactKind.ELEMENT, "Interface", "Information service", "This service makes the capability available.",
                                Map.of("tag:taxonomy.elementType", "CoreService"), Map.of("canonicalType", "CoreService"))),
                original.relations(), original.placements(), original.metadata(), original.losses());
        var codec = new SparxXmiCodec("3"); byte[] bytes = codec.write(typed);
        var returned = codec.read(bytes, "passive-realizes", true).relations().getFirst();
        assertEquals(A, returned.source()); assertEquals(B, returned.target());
        assertEquals("Association", returned.type());
        assertEquals("REALIZES", returned.attributes().get("tag:taxonomy.relationType"));
        assertEquals("Taxonomy::REALIZES", returned.extensions().get("stereotype"));
        var connector = ExchangeXml.all(ExchangeXml.parse(bytes), null, "packagedElement").stream()
                .filter(e -> SparxMappingProfile.xmiId(R, false).equals(e.getAttributeNS(SparxMappingProfile.XMI, "id")))
                .findFirst().orElseThrow();
        assertEquals("uml:Association", connector.getAttributeNS(SparxMappingProfile.XMI, "type"));
        assertFalse(connector.hasAttribute("client")); assertFalse(connector.hasAttribute("supplier"));
        assertTrue(SparxRelationProfileV3.ASSOCIATION_TRANSPORTS.contains("REALIZES"));
        var misleading = copy(returned, "Realization", returned.attributes(), returned.extensions());
        assertThrows(ExchangeFormatException.class, () -> codec.write(document("3", misleading)));
        assertEquals("Realization", SparxMappingProfile.eaRelation("REALIZES", "1"));
        assertEquals("Realization", SparxMappingProfile.eaRelation("REALIZES", "2"));
    }

    @ParameterizedTest @EnumSource(RelationType.class)
    void umlEndpointFieldsAndNavigationAgreeWithTheDeclaredDirection(RelationType type) {
        for (String direction : List.of("Source -> Destination", "Destination -> Source")) {
            var xml = ExchangeXml.parse(new SparxXmiCodec("3").write(document("3", relation(type, direction))));
            var connector = ExchangeXml.all(xml, null, "packagedElement").stream()
                    .filter(e -> SparxMappingProfile.xmiId(R, false).equals(e.getAttributeNS(SparxMappingProfile.XMI, "id")))
                    .findFirst().orElseThrow();
            boolean reverse = direction.equals("Destination -> Source");
            String from = SparxMappingProfile.xmiId(reverse ? B : A, false), to = SparxMappingProfile.xmiId(reverse ? A : B, false);
            if (type == RelationType.COMMUNICATES_WITH) {
                assertEquals(from, connector.getAttribute("informationSource"));
                assertEquals(to, connector.getAttribute("informationTarget"));
                assertFalse(connector.hasAttribute("client")); assertFalse(connector.hasAttribute("supplier"));
            } else if (Set.of("Association", "Composition").contains(TRANSPORTS.get(type))) {
                var ends = ExchangeXml.children(connector).stream().filter(e -> "ownedEnd".equals(e.getLocalName())).toList();
                assertEquals(2, ends.size());
                assertEquals(ends.get(reverse ? 0 : 1).getAttributeNS(SparxMappingProfile.XMI, "id"), connector.getAttribute("navigableOwnedEnd"));
                if (type == RelationType.CONTAINS) {
                    assertEquals("composite", ends.get(reverse ? 0 : 1).getAttribute("aggregation"));
                    assertEquals("", ends.get(reverse ? 1 : 0).getAttribute("aggregation"));
                }
            } else {
                assertEquals(from, connector.getAttribute("client"));
                assertEquals(to, connector.getAttribute("supplier"));
            }
        }
    }

    @Test void defaultAndVersionBoundariesRemainExplicit() {
        var legacy = document("1", new Relation(R, "Dependency", A, B, Map.of(), Map.of("canonicalType", "DEPENDS_ON")));
        assertEquals("1", new SparxXmiCodec().read(new SparxXmiCodec().write(legacy), "v", true).profileVersion());
        var v3 = document("3", relation(RelationType.SUPPORTS, "Source -> Destination"));
        for (String version : List.of("1", "2")) {
            assertEquals("PROFILE_VERSION_CHANGED", assertThrows(ExchangeFormatException.class,
                    () -> new SparxXmiCodec(version).write(v3)).code());
        }
        assertThrows(ExchangeFormatException.class, () -> new SparxXmiCodec("4"));
        assertThrows(ExchangeFormatException.class, () -> new SparxOslcAmCodec("3"), "Profile 3 defines XMI only; remote PCS contracts stay at 1/2");
    }

    @Test void outputRejectsMissingUnknownOrContradictorySemanticDeclarations() {
        var codec = new SparxXmiCodec("3");
        var good = relation(RelationType.SUPPORTS, "Source -> Destination");
        for (String key : List.of("tag:taxonomy.relationType", "tag:taxonomy.relationDirection", "tag:taxonomy.mappingProfile")) {
            var attributes = new TreeMap<>(good.attributes()); attributes.remove(key);
            assertThrows(ExchangeFormatException.class, () -> codec.write(document("3", copy(good, good.type(), attributes, good.extensions()))), key);
        }
        for (String invalid : List.of("MADE_UP", "ENABLES", "supports", "RELATED_TO")) {
            var attributes = new TreeMap<>(good.attributes()); attributes.put("tag:taxonomy.relationType", invalid);
            assertThrows(ExchangeFormatException.class, () -> codec.write(document("3", copy(good, good.type(), attributes, good.extensions()))), invalid);
        }
        for (var change : List.of(Map.entry("canonicalType", "RELATED_TO"), Map.entry("direction", "Destination -> Source"),
                Map.entry("direction", "Bi-Directional"), Map.entry("direction", "Unspecified"),
                Map.entry("stereotype", "Taxonomy::RELATED_TO"))) {
            var extensions = new TreeMap<>(good.extensions()); extensions.put(change.getKey(), change.getValue());
            assertThrows(ExchangeFormatException.class, () -> codec.write(document("3", copy(good, good.type(), good.attributes(), extensions))), change.toString());
        }
        assertThrows(ExchangeFormatException.class, () -> codec.write(document("3", copy(good, "Dependency", good.attributes(), good.extensions()))));
    }

    @Test void readerRejectsConflictsAcrossUmlEaTagsAndPreservedEvidence() {
        var codec = new SparxXmiCodec("3");
        for (RelationType type : List.of(RelationType.SUPPORTS, RelationType.DEPENDS_ON, RelationType.CONTAINS)) {
            byte[] bytes = codec.write(document("3", relation(type, "Source -> Destination")));
            var xml = ExchangeXml.parse(bytes);
            var relation = ExchangeXml.all(xml, null, "packagedElement").stream()
                    .filter(e -> SparxMappingProfile.xmiId(R, false).equals(e.getAttributeNS(SparxMappingProfile.XMI, "id")))
                    .findFirst().orElseThrow();
            if (type == RelationType.DEPENDS_ON) relation.setAttribute("supplier", SparxMappingProfile.xmiId(A, false));
            else ExchangeXml.children(relation).stream().filter(e -> "ownedEnd".equals(e.getLocalName())).findFirst().orElseThrow()
                    .setAttribute("type", SparxMappingProfile.xmiId(B, false));
            assertThrows(ExchangeFormatException.class, () -> codec.read(ExchangeXml.write(xml), "edited", true), type.name());
        }
        var source = new String(codec.write(document("3", relation(RelationType.SUPPORTS, "Source -> Destination"))), StandardCharsets.UTF_8);
        for (String changed : List.of(source.replace("stereotype=\"Taxonomy::SUPPORTS\"", "stereotype=\"Taxonomy::USES\""),
                source.replace("direction=\"Source -&gt; Destination\"", "direction=\"Destination -&gt; Source\""),
                source.replace("exporterVersion=\"sparx-xmi-2.1@3\"", "exporterVersion=\"sparx-xmi-2.1@2\""))) {
            assertNotEquals(source, changed, "The adversarial fixture must actually mutate the representation");
            assertThrows(ExchangeFormatException.class, () -> codec.read(changed.getBytes(StandardCharsets.UTF_8), "edited", true));
        }
    }

    @Test void missingVisibleStereotypeUnknownTransportAndConflictingNotesAreRejected() {
        var codec = new SparxXmiCodec("3");
        String source = new String(codec.write(document("3", relation(RelationType.SUPPORTS, "Source -> Destination"))), StandardCharsets.UTF_8);
        for (String changed : List.of(source.replace("stereotype=\"Taxonomy::SUPPORTS\"", ""),
                source.replace("xmi:type=\"uml:Association\"", "xmi:type=\"uml:UnknownConnector\""),
                source.replace("<body>" + QUOTE, "<body>Changed note"))) {
            assertNotEquals(source, changed, "Each fixture must mutate the representation");
            assertThrows(ExchangeFormatException.class, () -> codec.read(changed.getBytes(StandardCharsets.UTF_8), "changed", true));
        }
    }

    @Test void requirementTextAndDigestRemainExactAndInvalidSourcePairsFailClosed() {
        var codec = new SparxXmiCodec("3");
        var original = document("3", relation(RelationType.PRODUCES, "Source -> Destination"));
        var metadata = new TreeMap<>(original.metadata());
        metadata.put("taxonomy.requirementText", QUOTE);
        metadata.put("taxonomy.requirementSha256", com.taxonomy.exchange.ReqifExchangeCodec.digest(QUOTE.getBytes(StandardCharsets.UTF_8)));
        var valid = new ExchangeDocument(original.profile(), original.profileVersion(), original.externalVersion(), original.completeScope(),
                original.source(), original.artifacts(), original.relations(), original.placements(), metadata, original.losses());
        assertEquals(metadata, codec.read(codec.write(valid), "returned", true).metadata());
        metadata.put("taxonomy.requirementSha256", "0".repeat(64));
        var invalid = new ExchangeDocument(valid.profile(), valid.profileVersion(), valid.externalVersion(), valid.completeScope(),
                valid.source(), valid.artifacts(), valid.relations(), valid.placements(), metadata, valid.losses());
        assertEquals("SPARX_SOURCE_EVIDENCE", assertThrows(ExchangeFormatException.class, () -> codec.write(invalid)).code());
    }

    @Test void anUnboundModelNoteHasAnExplicitUnsupportedLossInsteadOfDisappearingSilently() {
        var codec = new SparxXmiCodec("3");
        var xml = ExchangeXml.parse(codec.write(document("3", relation(RelationType.SUPPORTS, "Source -> Destination"))));
        var model = ExchangeXml.all(xml, SparxMappingProfile.UML, "Model").getFirst();
        var comment = ExchangeXml.append(model, null, "ownedComment");
        comment.setAttributeNS(SparxMappingProfile.XMI, "xmi:type", "uml:Comment");
        comment.setAttributeNS(SparxMappingProfile.XMI, "xmi:id", "unbound-model-note");
        String note = "Surface-water flooding is outside scope; retain the official warning channels.";
        ExchangeXml.text(comment, null, "body", note);
        var returned = codec.read(ExchangeXml.write(xml), "model-notes", true);
        assertTrue(returned.source().contains(note), "The original unbound note remains available for explicit review");
        assertTrue(returned.losses().stream().anyMatch(loss -> loss.code().equals("SPARX_CONSTRUCT_EXCLUDED")
                        && loss.field().equals("ownedComment") && loss.disposition() == LossDisposition.UNSUPPORTED),
                "Profile 3 must report the model note it cannot bind to declared requirement evidence");
    }

    private static Relation relation(RelationType type, String direction) {
        return new Relation(R, TRANSPORTS.get(type), A, B,
                Map.of("name", type.name(), "description", QUOTE, "tag:taxonomy.mappingProfile", "sparx-xmi-2.1@3",
                        "tag:taxonomy.relationType", type.name(), "tag:taxonomy.relationDirection", direction,
                        "tag:taxonomy.quote", QUOTE, "tag:taxonomy.condition", "Nur nach Freigabe"),
                Map.of("canonicalType", type.name(), "direction", direction, "stereotype", "Taxonomy::" + type.name(),
                        "taxonomy:status", "proposed", "taxonomy:relationProvenance", "{\"quote\":\"F4\",\"necessity\":\"REQUIRED\"}",
                        "internalIdentity", "{44444444-4444-4444-8444-444444444444}"));
    }

    private static Relation copy(Relation relation, String type, Map<String, String> attributes, Map<String, String> extensions) {
        return new Relation(relation.id(), type, relation.source(), relation.target(), attributes, extensions);
    }

    private static ExchangeDocument document(String version, Relation relation) {
        return new ExchangeDocument(SparxMappingProfile.PROFILE, version, "source", true, "",
                List.of(new Artifact(A, ArtifactKind.ELEMENT, "Component", "Warnung & Quelle", QUOTE, Map.of(), Map.of("canonicalType", "Component")),
                        new Artifact(B, ArtifactKind.ELEMENT, "Component", "Empfänger", "Unveränderte Zielbeschreibung", Map.of(), Map.of("canonicalType", "Component"))),
                List.of(relation), List.of(), Map.of("identifier", MODEL, "title", "Flood", "taxonomy.sourceText", QUOTE), List.of());
    }
}
