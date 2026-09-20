package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeFormatException;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SparxXmiCodecTest {
    private final SparxXmiCodec codec = new SparxXmiCodec();
    private static final String A = "{11111111-1111-4111-8111-111111111111}";
    private static final String B = "{22222222-2222-4222-8222-222222222222}";
    private static final String P = "{33333333-3333-4333-8333-333333333333}";
    private static final String R = "{44444444-4444-4444-8444-444444444444}";

    @Test void aliasesResolveToOneGuidWithoutUsingNames() {
        assertEquals(A, SparxMappingProfile.guid("EAID_11111111_1111_4111_8111_111111111111"));
        assertEquals(P, SparxMappingProfile.guid("EAPK_33333333_3333_4333_8333_333333333333"));
        assertEquals(A, SparxMappingProfile.guid(A.toLowerCase(Locale.ROOT)));
        assertThrows(ExchangeFormatException.class, () -> SparxMappingProfile.guid("Display name"));
    }

    @Test void fixturePreservesPackagesRequirementsTagsAndDirectedRelationships() throws Exception {
        var model = fixture();
        assertEquals(4, model.artifacts().size());
        assertEquals(ArtifactKind.SPECIFICATION, artifact(model, P).kind());
        assertEquals("System", artifact(model, A).extensions().get("canonicalType"));
        assertEquals("System notes", artifact(model, A).text());
        assertEquals("Team A", artifact(model, A).attributes().get("tag:owner"));
        assertEquals(1, model.relations().size());
        assertEquals(A, model.relations().getFirst().source());
        assertEquals(B, model.relations().getFirst().target());
        assertEquals("Source -> Destination", model.relations().getFirst().extensions().get("direction"));
        assertTrue(model.artifacts().stream().anyMatch(a -> a.kind() == ArtifactKind.REQUIREMENT));
        assertTrue(model.losses().stream().anyMatch(l -> l.code().equals("SPARX_LAYOUT_EXCLUDED")));
    }

    @Test void semanticRoundTripAndRepeatedExportsAreDeterministic() throws Exception {
        var original = fixture();
        byte[] output = codec.write(original);
        assertArrayEquals(output, codec.write(original));
        var returned = codec.read(output, "returned", true);
        assertEquals(original.artifacts(), returned.artifacts());
        assertEquals(original.relations(), returned.relations());
        assertEquals(original.placements(), returned.placements());
        assertFalse(new String(output, StandardCharsets.UTF_8).contains("geometry="));
    }

    @Test void renameAndMoveRetainTheObjectGuid() throws Exception {
        var original = fixture();
        var objects = original.artifacts().stream().map(a -> a.id().equals(A)
                ? new Artifact(a.id(), a.kind(), a.type(), "Renamed system", a.text(), a.attributes(), a.extensions()) : a).toList();
        var placements = original.placements().stream().map(p -> p.artifactId().equals(A)
                ? new Placement(p.id(), p.containerId(), null, p.artifactId(), p.position(), p.attributes()) : p).toList();
        var returned = codec.read(codec.write(copy(original, objects, original.relations(), placements)), "v2", true);
        assertEquals("Renamed system", artifact(returned, A).title());
        assertNull(returned.placements().stream().filter(p -> p.artifactId().equals(A)).findFirst().orElseThrow().parentId());
    }

    @Test void packageDependencyReferencesResolveToDeclaredUmlObjects() throws Exception {
        var original = fixture();
        for (boolean packageIsSource : List.of(true, false)) {
            var relation = new Relation(R, "Dependency", packageIsSource ? P : A,
                    packageIsSource ? A : P, Map.of(), Map.of("canonicalType", "DEPENDS_ON"));
            var xml = com.taxonomy.exchange.ExchangeXml.parse(codec.write(copy(original,
                    original.artifacts(), List.of(relation), original.placements())));
            var declared = new HashSet<String>();
            var nodes = xml.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                var node = (org.w3c.dom.Element) nodes.item(i);
                if (node.hasAttributeNS(SparxMappingProfile.XMI, "id"))
                    declared.add(node.getAttributeNS(SparxMappingProfile.XMI, "id"));
            }
            int dependencies = 0;
            for (int i = 0; i < nodes.getLength(); i++) {
                var node = (org.w3c.dom.Element) nodes.item(i);
                if (!"uml:Dependency".equals(node.getAttributeNS(SparxMappingProfile.XMI, "type"))) continue;
                dependencies++;
                assertTrue(declared.contains(node.getAttribute("client")), "Unresolved UML client");
                assertTrue(declared.contains(node.getAttribute("supplier")), "Unresolved UML supplier");
            }
            assertEquals(1, dependencies);
        }
    }

    @Test void elementTransportTypeMustAgreeWithItsDeclaredCanonicalMeaning() throws Exception {
        var original = fixture();
        var changed = original.artifacts().stream().map(a -> a.id().equals(B)
                ? new Artifact(a.id(), a.kind(), "Component", a.title(), a.text(), Map.of(),
                        Map.of("canonicalType", "System")) : a).toList();
        assertEquals("SPARX_ELEMENT_UNMAPPED", assertThrows(ExchangeFormatException.class,
                () -> codec.write(copy(original, changed, original.relations(), original.placements()))).code());
    }

    @Test void explicitElementMappingPreservesCanonicalMeaningOnReturn() throws Exception {
        var original = fixture();
        var changed = original.artifacts().stream().map(a -> a.id().equals(B)
                ? new Artifact(a.id(), a.kind(), "Component", a.title(), a.text(),
                        Map.of("tag:taxonomy.elementType", "UserApplication"),
                        Map.of("canonicalType", "UserApplication")) : a).toList();
        var returned = codec.read(codec.write(copy(original, changed, original.relations(), original.placements())), "v2", true);
        assertEquals("UserApplication", artifact(returned, B).extensions().get("canonicalType"));
    }

    @Test void relationTransportTypeCannotSilentlyChangeCanonicalMeaning() throws Exception {
        var original = fixture();
        var relation = new Relation(R, "Dependency", A, B, Map.of(), Map.of("canonicalType", "REALIZES"));
        assertEquals("SPARX_RELATION_UNMAPPED", assertThrows(ExchangeFormatException.class,
                () -> codec.write(copy(original, original.artifacts(), List.of(relation), original.placements()))).code());
    }

    @Test void layoutChangesAreNotSemanticChanges() throws Exception {
        String xml = fixtureText();
        var first = codec.read(bytes(xml), "v1", true);
        var second = codec.read(bytes(xml.replace("Left=0", "Left=800")), "v2", true);
        assertEquals(first.artifacts(), second.artifacts());
        assertEquals(first.relations(), second.relations());
        assertEquals(first.placements(), second.placements());
        assertEquals(first.metadata(), second.metadata());
    }

    @Test void mappedTaxonomyPropertiesSurviveTransportEncoding() throws Exception {
        var model = fixture();
        var objects = model.artifacts().stream().map(a -> {
            if (!a.id().equals(A)) return a;
            var extensions = new TreeMap<>(a.extensions()); extensions.put("taxonomy:x-owner", "Alice");
            return new Artifact(a.id(), a.kind(), a.type(), a.title(), a.text(), a.attributes(), extensions);
        }).toList();
        var relation = model.relations().getFirst();
        var extensions = new TreeMap<>(relation.extensions()); extensions.put("taxonomy:status", "proposed");
        var changed = new Relation(relation.id(), relation.type(), relation.source(), relation.target(), relation.attributes(), extensions);
        var returned = codec.read(codec.write(copy(model, objects, List.of(changed), model.placements())), "v2", true);
        assertEquals("Alice", artifact(returned, A).extensions().get("taxonomy:x-owner"));
        assertEquals("proposed", returned.relations().getFirst().extensions().get("taxonomy:status"));
    }

    @Test void duplicateGuidsTaxonomyIdsAndProfileChangesAreRejected() throws Exception {
        String xml = fixtureText();
        assertThrows(ExchangeFormatException.class, () -> codec.read(bytes(xml.replace("EAID_22222222_2222_4222_8222_222222222222", "EAID_11111111_1111_4111_8111_111111111111")), "v1", true));
        String tag = "<tag name=\"taxonomy.id\" value=\"99999999-9999-4999-8999-999999999999\"/>";
        assertThrows(ExchangeFormatException.class, () -> codec.read(bytes(xml.replace("<tags>", "<tags>" + tag)), "v1", true));
        assertThrows(ExchangeFormatException.class, () -> codec.read(bytes(xml.replace("<tags>", "<tags><tag name=\"taxonomy.mappingProfile\" value=\"sparx-xmi-2.1@99\"/>")), "v1", true));
    }

    @Test void malformedUnsafeAndForeignNamespaceInputsFailClosed() {
        for (String xml : List.of("<broken>", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>",
                "<XMI xmlns='https://untrusted.test'><Model/></XMI>", "<xmi:XMI xmlns:xmi='http://schema.omg.org/spec/XMI/2.1'>" + "<p>".repeat(100) + "</p>".repeat(100) + "</xmi:XMI>"))
            assertThrows(ExchangeFormatException.class, () -> codec.read(bytes(xml), null, true));
    }

    @Test void danglingRelationsAndRejectedHierarchyParentsCannotBeExported() throws Exception {
        var original = fixture();
        assertThrows(ExchangeFormatException.class, () -> codec.write(copy(original,
                original.artifacts().stream().filter(a -> !a.id().equals(B)).toList(), original.relations(), original.placements())));
        assertThrows(ExchangeFormatException.class, () -> codec.write(copy(original, original.artifacts(), original.relations(),
                original.placements().stream().filter(p -> !p.artifactId().equals(P)).toList())));
    }

    @Test void unsupportedTypesAndPropertiesAreReportedWithoutCoercion() throws Exception {
        var model = codec.read(bytes(fixtureText().replace("uml:Component", "uml:StateMachine")), null, false);
        assertFalse(model.completeScope());
        assertNull(artifact(model, B).extensions().get("canonicalType"));
        assertTrue(model.losses().stream().anyMatch(l -> B.equals(l.artifactId()) && l.disposition() == LossDisposition.UNSUPPORTED));
        assertThrows(ExchangeFormatException.class, () -> codec.write(model));
    }

    @Test void connectorEndMetadataAndBidirectionalSemanticsAreExplicitLosses() throws Exception {
        String xml = fixtureText().replace("<source xmi:idref=\"EAID_11111111_1111_4111_8111_111111111111\"/>",
                "<source xmi:idref=\"EAID_11111111_1111_4111_8111_111111111111\" visibility=\"private\"><role name=\"consumer\"/><type multiplicity=\"1..*\"/></source>")
                .replace("Source -&gt; Destination", "Bi-Directional");
        var model = codec.read(bytes(xml), "v1", true);
        assertTrue(model.losses().stream().anyMatch(l -> R.equals(l.artifactId()) && l.field().equals("source.role")));
        assertTrue(model.losses().stream().anyMatch(l -> R.equals(l.artifactId()) && l.field().equals("source.type")));
        assertTrue(model.losses().stream().anyMatch(l -> R.equals(l.artifactId()) && l.field().equals("source.visibility")));
        assertTrue(model.losses().stream().anyMatch(l -> l.code().equals("SPARX_DIRECTION_UNMAPPED")));
    }

    @Test void attributesOperationsParametersAndDuplicateTagsRoundTripInVersionTwo() throws Exception {
        var v2 = new SparxXmiCodec("2");
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/interoperability/sparx/semantic-v2.xmi")) { fixture = Objects.requireNonNull(input).readAllBytes(); }
        var original = v2.read(fixture, "fixture", false);
        assertEquals("2", original.profileVersion());
        assertEquals(5, original.artifacts().stream().filter(a -> a.kind().name().equals("FEATURE")).count());
        assertFalse(artifact(original, A).attributes().containsKey("tag:owner"));
        byte[] written = v2.write(original);
        var returned = v2.read(written, "returned", false);
        assertEquals(original.artifacts(), returned.artifacts());
        assertEquals(original.relations(), returned.relations());
        assertEquals(original.placements(), returned.placements());
        assertArrayEquals(written, v2.write(original));
        assertThrows(ExchangeFormatException.class, () -> codec.write(original));
        assertThrows(ExchangeFormatException.class, () -> v2.read(fixtureText().replace("<tags>",
                "<tags><tag name=\"taxonomy.mappingProfile\" value=\"sparx-xmi-2.1@1\"/>").getBytes(StandardCharsets.UTF_8), null, false));
    }

    @Test void versionTwoRejectsWrongOwnersPositionsCollisionsAndLossyFeatures() throws Exception {
        var v2=new SparxXmiCodec("2"); String xml;
        try(var in=getClass().getResourceAsStream("/interoperability/sparx/semantic-v2.xmi")) {xml=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
        var original=v2.read(bytes(xml),null,false);
        var features=original.artifacts().stream().filter(a->a.kind()==ArtifactKind.FEATURE).toList();
        for(String key:List.of("owner","position","externalIdentifier")) {
            var changed=original.artifacts().stream().map(a->{
                if(!a.type().equals("parameter"))return a;
                var ext=new TreeMap<>(a.extensions());ext.put(key,key.equals("owner")?A:key.equals("position")?"-1":"lt_"+a.id());
                return new Artifact(a.id(),a.kind(),a.type(),a.title(),a.text(),a.attributes(),ext);
            }).toList();
            assertThrows(ExchangeFormatException.class,()->v2.write(copy(original,changed,original.relations(),original.placements())));
        }
        var missingOwner=original.artifacts().stream().map(a->{
            if(!a.type().equals("parameter"))return a;
            var ext=new TreeMap<>(a.extensions());ext.remove("owner");
            return new Artifact(a.id(),a.kind(),a.type(),a.title(),a.text(),a.attributes(),ext);
        }).toList();
        assertEquals("SPARX_FEATURE_OWNER",assertThrows(ExchangeFormatException.class,
                ()->v2.write(copy(original,missingOwner,original.relations(),original.placements()))).code());
        var duplicate=new ArrayList<>(original.artifacts());duplicate.add(features.getFirst());
        assertThrows(ExchangeFormatException.class,()->v2.write(copy(original,duplicate,original.relations(),original.placements())));
        var unsupported=v2.read(bytes(xml.replace("name=\"count\" scope=\"private\" classifierName=\"Integer\" position=\"0\"/>",
                "name=\"count\" scope=\"private\" classifierName=\"Integer\" position=\"0\"><ownedRule name=\"unsupported\"/></ownedAttribute>")),null,false);
        assertTrue(unsupported.losses().stream().anyMatch(l->l.code().equals("SPARX_FEATURE_EXCLUDED")));
        assertEquals("SPARX_FEATURE_EXPORT_UNSUPPORTED",assertThrows(ExchangeFormatException.class,()->v2.write(unsupported)).code());
    }

    @Test void versionTwoPreservesSiblingOrderAndFeatureOwnerRenameAndMove() throws Exception {
        var v2=new SparxXmiCodec("2"); byte[] xml;
        try(var in=getClass().getResourceAsStream("/interoperability/sparx/semantic-v2.xmi")) {xml=Objects.requireNonNull(in).readAllBytes();}
        var original=v2.read(xml,null,false);
        assertEquals(0,original.placements().stream().filter(p->p.artifactId().equals(P)).findFirst().orElseThrow().position());
        var placements=original.placements().stream().map(p->new Placement(p.id(),p.containerId(),p.parentId(),p.artifactId(),
                p.artifactId().equals(A)?1:p.artifactId().equals(B)?0:p.position(),p.attributes())).toList();
        var artifacts=original.artifacts().stream().map(a->{
            if(!a.type().equals("attribute"))return a;
            var ext=new TreeMap<>(a.extensions());ext.put("owner",B);ext.put("position","3");
            return new Artifact(a.id(),a.kind(),a.type(),"renamed",a.text(),a.attributes(),ext);
        }).toList();
        var changed=copy(original,artifacts,original.relations(),placements);
        var returned=v2.read(v2.write(changed),null,false);
        assertEquals(changed.artifacts(),returned.artifacts());assertEquals(changed.placements(),returned.placements());
    }

    @Test void versionTwoPreservesTagPositionNotesAndUnknownFieldsAndValidatesProjectionTags() throws Exception {
        var v2=new SparxXmiCodec("2"); String xml;
        try(var in=getClass().getResourceAsStream("/interoperability/sparx/semantic-v2.xmi")) {xml=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
        var original=v2.read(bytes(xml.replace("name=\"First\"", "name=\"First\" reviewHint=\"retain me\"")
                .replace("name=\"owner\" value=\"Alice\"", "name=\"owner\" value=\"Alice\" position=\"4\" notes=\"Tag notes\" custom=\"retain tag field\"")),null,false);
        assertEquals("retain me",artifact(original,A).attributes().get("xml:reviewHint"));
        var tag=original.artifacts().stream().filter(a->a.type().equals("tagged-value")&&a.text().equals("Alice")).findFirst().orElseThrow();
        assertEquals("4",tag.extensions().get("position"));
        assertEquals("Tag notes",tag.attributes().get("attribute:http://purl.org/dc/terms/description"));
        assertEquals("retain tag field",tag.attributes().get("xml:custom"));
        assertTrue(original.losses().stream().anyMatch(l->l.disposition()==LossDisposition.PRESERVED_EXTENSION));
        assertEquals(original.artifacts(),v2.read(v2.write(original),null,false).artifacts());
        String wrong=xml.replace("<tags>","<tags><tag projection=\"true\" name=\"taxonomy.mappingProfile\" value=\"sparx-xmi-2.1@1\"/>");
        assertEquals("PROFILE_VERSION_CHANGED",assertThrows(ExchangeFormatException.class,()->v2.read(bytes(wrong),null,false)).code());
    }

    @Test void unsupportedFeatureExtensionDetailsReportLossAndBlockExport() throws Exception {
        String xml;
        try (var input = getClass().getResourceAsStream("/interoperability/sparx/semantic-v2.xmi")) {
            xml = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        var v2 = new SparxXmiCodec("2");
        for (String id : List.of("{55555555-5555-4555-8555-555555555551}",
                "{55555555-5555-4555-8555-555555555552}", "{55555555-5555-4555-8555-555555555553}")) {
            String detail = "<feature xmi:idref=\"" + id + "\"><unsupportedFeatureDetail value=\"MUST-NOT-DISAPPEAR\"/></feature>";
            var document = v2.read(bytes(xml.replace("</elements>", detail + "</elements>")), null, false);
            assertTrue(document.losses().stream().anyMatch(loss -> id.equals(loss.artifactId())
                    && "unsupportedFeatureDetail".equals(loss.field())
                    && loss.disposition() == LossDisposition.UNSUPPORTED));
            assertEquals("SPARX_FEATURE_EXPORT_UNSUPPORTED",
                    assertThrows(ExchangeFormatException.class, () -> v2.write(document)).code());
        }
    }

    private ExchangeDocument fixture() throws Exception { return codec.read(bytes(fixtureText()), "v1", true); }
    private String fixtureText() throws Exception {
        try (var input = getClass().getResourceAsStream("/interoperability/sparx/semantic-model.xmi")) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static Artifact artifact(ExchangeDocument d, String id) { return d.artifacts().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ExchangeDocument copy(ExchangeDocument d, List<Artifact> a, List<Relation> r, List<Placement> p) {
        return new ExchangeDocument(d.profile(), d.profileVersion(), d.externalVersion(), d.completeScope(), d.source(), a, r, p, d.metadata(), d.losses());
    }
}
