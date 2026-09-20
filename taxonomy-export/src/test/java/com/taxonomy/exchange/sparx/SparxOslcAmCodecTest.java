package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeFormatException;
import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.extension.api.integration.IntegrationContracts.ArtifactKind;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import static org.assertj.core.api.Assertions.*;

class SparxOslcAmCodecTest {
    private static final URI BASE = URI.create("https://pcs.example/model/oslc/am/");
    private static final String P = "{10000000-0000-0000-0000-000000000001}";
    private static final String E = "{20000000-0000-0000-0000-000000000002}";
    private final SparxOslcAmCodec codec = new SparxOslcAmCodec();

    @Test void mapsDocumentedAmResourcesAndKeepsHierarchyWithoutClaimingDeletionAuthority() {
        var rdf = resource(new OslcRdf(), "pk_" + P, "Package", "Flood information", "");
        resource(rdf, "el_" + E, "Component", "Observation reader", "Published data only")
                .literal(uri("el_" + E), SparxOslcAmCodec.SS + "parentresourceidentifier", "pk_" + P);
        var document = codec.read(List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"), "\"v1\"", rdf.xml())), BASE);
        assertThat(document.profile()).isEqualTo(SparxOslcAmCodec.PROFILE);
        assertThat(document.completeScope()).isFalse();
        assertThat(document.source()).isEmpty();
        assertThat(document.artifacts()).extracting(a -> a.id()).containsExactly(P, E);
        assertThat(document.artifacts().getFirst().kind()).isEqualTo(ArtifactKind.SPECIFICATION);
        assertThat(document.artifacts().getLast().extensions()).containsEntry("canonicalType", "Component");
        assertThat(document.placements()).anySatisfy(p -> {
            assertThat(p.artifactId()).isEqualTo(E);
            assertThat(p.parentId()).isEqualTo("placement:" + P);
        });
        assertThat(document.losses()).extracting(l -> l.code()).contains("SPARX_AM_FEATURES_NOT_FETCHED");
    }

    @Test void rejectsRmOnlyDataGuidCollisionsAndIncompletePageChains() {
        byte[] a = resource(new OslcRdf(), "el_" + E, "Component", "A", "").xml();
        var page = new SparxOslcAmCodec.Page(BASE.resolve("qc/"), null, a);
        assertThatThrownBy(() -> codec.read(List.of(page, page), BASE)).isInstanceOf(ExchangeFormatException.class);
        byte[] rm = new OslcRdf().type(uri("el_" + E), OslcRdf.RM + "Requirement").xml();
        assertThatThrownBy(() -> codec.read(List.of(new SparxOslcAmCodec.Page(BASE, null, rm)), BASE))
                .isInstanceOf(ExchangeFormatException.class);
        var partial = resource(new OslcRdf(), "el_" + E, "Component", "A", "")
                .link(BASE.resolve("qc/").toString(), OslcRdf.OSLC + "nextPage", BASE.resolve("qc/?page=2").toString());
        assertThatThrownBy(() -> codec.read(List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"), null, partial.xml())), BASE))
                .isInstanceOf(ExchangeFormatException.class);
    }

    @Test void discoversOnlyTheAmQueryAndRejectsReflectedCredentials() {
        var rdf = new OslcRdf().type(BASE.resolve("sp/").toString(), OslcRdf.OSLC + "ServiceProvider")
                .link(BASE.resolve("sp/service").toString(), OslcRdf.OSLC + "domain", SparxOslcAmCodec.AM)
                .link(BASE.resolve("sp/query").toString(), OslcRdf.OSLC + "resourceType", SparxOslcAmCodec.AM + "Resource")
                .link(BASE.resolve("sp/query").toString(), OslcRdf.OSLC + "queryBase", BASE.resolve("qc/").toString());
        assertThat(codec.queryBase(rdf.xml(), BASE.resolve("sp/"))).isEqualTo(BASE.resolve("qc/"));
        byte[] secret = resource(new OslcRdf(), "el_" + E, "Component", "A", "")
                .literal(uri("el_" + E), SparxOslcAmCodec.SS + "useridentifier", "secret").xml();
        assertThatThrownBy(() -> codec.read(List.of(new SparxOslcAmCodec.Page(BASE, null, secret)), BASE))
                .isInstanceOf(ExchangeFormatException.class).hasMessageNotContaining("secret");
    }

    @Test void readsDocumentedNestedStereotypeAndReportsAmbiguousMultipleNames() {
        String xml = new String(resource(new OslcRdf(), "el_" + E, "Class", "Oversight", "").xml(),
                java.nio.charset.StandardCharsets.UTF_8);
        String nested = "<ss:stereotype xmlns:ss=\"" + SparxOslcAmCodec.SS + "\">"
                + "<ss:stereotypename><ss:name>BusinessRole</ss:name>"
                + "</ss:stereotypename></ss:stereotype>";
        xml = xml.replace("</rdf:Description>", nested + "</rdf:Description>");
        var document = codec.read(List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"), null,
                xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))), BASE);
        assertThat(document.artifacts().getFirst().extensions()).containsEntry("stereotype", "BusinessRole")
                .containsEntry("canonicalType", "BusinessRole");
        String multiple = xml.replace(nested, nested + nested.replace("BusinessRole", "Component"));
        document = codec.read(List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"), null,
                multiple.getBytes(java.nio.charset.StandardCharsets.UTF_8))), BASE);
        assertThat(document.artifacts().getFirst().extensions()).doesNotContainKey("canonicalType");
        assertThat(document.losses()).extracting(l -> l.code()).contains("SPARX_AM_STEREOTYPE_UNMAPPED");
    }

    @Test void versionTwoPreservesDuplicateTagsAndFeatureOwnership() {
        var v2 = new SparxOslcAmCodec("2");
        var roots = List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"), null,
                resource(new OslcRdf(), "el_" + E, "Component", "Owner", "").xml()));
        String first = "tv_{30000000-0000-0000-0000-000000000003}";
        String second = "tv_{40000000-0000-0000-0000-000000000004}";
        OslcRdf tags = new OslcRdf();
        for (String id : List.of(first, second)) tags.literal(uri(id), OslcRdf.DCT + "identifier", id)
                .literal(uri(id), OslcRdf.DCT + "title", "owner")
                .literal(uri(id), SparxOslcAmCodec.SS + "value", id)
                .literal(uri(id), OslcRdf.DCT + "description", "Tag notes");
        var collection = new SparxOslcAmCodec.Collection("taggedvalues", "el_" + E,
                List.of(new SparxOslcAmCodec.Page(BASE.resolve("taggedvalues/el_" + E.replace("{", "%7B").replace("}", "%7D") + "/"), null, tags.xml())));
        var document = v2.read(roots, List.of(collection, collection("linkedresources", "el_" + E, new OslcRdf()), collection("attributes", "el_" + E, new OslcRdf()), collection("operations", "el_" + E, new OslcRdf())), BASE);
        assertThat(document.profileVersion()).isEqualTo("2");
        assertThat(document.completeScope()).isFalse();
        assertThat(document.artifacts().stream().filter(a -> a.kind().name().equals("FEATURE")))
                .hasSize(2).allSatisfy(a -> {
                    assertThat(a.type()).isEqualTo("tagged-value");
                    assertThat(a.extensions()).containsEntry("owner", E);
                    assertThat(a.attributes()).containsEntry("attribute:" + OslcRdf.DCT + "description", "Tag notes");
                });
        assertThat(document.artifacts().stream().filter(a -> a.id().equals(E)).findFirst().orElseThrow().attributes())
                .doesNotContainKey("tag:owner");
        assertThat(document.losses()).extracting(l -> l.code()).contains("SPARX_TAG_DUPLICATE_UNMAPPED")
                .doesNotContain("SPARX_AM_FEATURES_NOT_FETCHED");
    }

    @Test void featurePrefixesAreValidatedByKind() {
        assertThat(SparxMappingProfile.prefixedGuid("at_" + E, java.util.Set.of("at_"))).isEqualTo(E);
        assertThatThrownBy(() -> SparxMappingProfile.prefixedGuid("lt_" + E, java.util.Set.of("at_")))
                .isInstanceOf(ExchangeFormatException.class);
    }

    @Test void xmiAndAmContractFixturesHaveIdenticalFeatureAndConnectorMeanings() throws Exception {
        var am = contractV2("Source -> Destination", false, false);
        byte[] xml;
        try (var in = getClass().getResourceAsStream("/interoperability/sparx/semantic-v2.xmi")) { xml = Objects.requireNonNull(in).readAllBytes(); }
        var xmi = new SparxXmiCodec("2").read(xml, null, false);
        assertThat(semanticArtifacts(am)).isEqualTo(semanticArtifacts(xmi));
        assertThat(am.relations().getFirst().source()).isEqualTo("{11111111-1111-4111-8111-111111111111}");
        assertThat(am.relations().getFirst().target()).isEqualTo("{22222222-2222-4222-8222-222222222222}");
        assertThat(am.relations().getFirst().extensions()).containsEntry("direction", "Source -> Destination");
        assertThat(am.losses()).extracting(MappingLoss::code).contains("SPARX_TAG_DUPLICATE_UNMAPPED");
    }
    @Test void outsideScopeAndConflictingDuplicateConnectorsFailOrPreserveExplicitly() {
        var external = contractV2("Destination -> Source", true, false);
        assertThat(external.relations()).isEmpty();
        assertThat(external.artifacts()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo("external-connector");
            assertThat(a.extensions()).containsEntry("preservedOnly", "true");
        });
        assertThat(external.losses()).extracting(MappingLoss::code).contains("SPARX_AM_ENDPOINT_OUTSIDE_SCOPE");
        assertThatThrownBy(() -> contractV2("Source -> Destination", false, true))
                .isInstanceOf(ExchangeFormatException.class).extracting(e -> ((ExchangeFormatException)e).code()).isEqualTo("DUPLICATE_IDENTITY");
    }
    @Test void featureIdentityCannotMasqueradeAsAnInScopeConnectorEndpoint() {
        String connector="{44444444-4444-4444-8444-444444444444}";
        String model="{55555555-5555-4555-8555-555555555555}";
        var document=SparxSemanticExchangeAssembler.assemble("sparx-oslc-am-2.0","2",false,"fixture",
                Map.of("identifier",model),
                List.of(new SparxSemanticExchangeAssembler.Resource(E,ArtifactKind.ELEMENT,"Component","Owner","",null,null,Map.of(),Map.of())),
                List.of(new SparxSemanticExchangeAssembler.Feature(P,E,"attribute","Field","",null,Map.of(),Map.of())),
                List.of(new SparxSemanticExchangeAssembler.Connector(connector,E,P,"Dependency","Source -> Destination",Map.of(),Map.of())),List.of());
        assertThat(document.relations()).isEmpty();
        assertThat(document.artifacts()).anySatisfy(a->{
            assertThat(a.id()).isEqualTo(connector);
            assertThat(a.type()).isEqualTo("external-connector");
            assertThat(a.extensions()).containsEntry("owner",E).containsEntry("target",P);
        });
    }

    @Test void missingCollectionsAndWrongPrefixesCannotMasqueradeAsCompleteV2Reads() {
        var pages=List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"),null,resource(new OslcRdf(),"el_"+E,"Component","Owner","").xml()));
        assertThatThrownBy(()->new SparxOslcAmCodec("2").read(pages,List.of(),BASE))
                .isInstanceOf(ExchangeFormatException.class).extracting(e->((ExchangeFormatException)e).code()).isEqualTo("SPARX_AM_INCOMPLETE");
        assertThatThrownBy(()->new SparxOslcAmCodec("2").read(pages,List.of(collection("attributes","el_"+E,feature(new OslcRdf(),"lt_"+P,"Wrong"))),BASE))
                .isInstanceOf(ExchangeFormatException.class).extracting(e->((ExchangeFormatException)e).code()).isEqualTo("SPARX_GUID_REQUIRED");
    }
    @Test void unknownStructuredAndLiteralFeatureFieldsRemainBoundedEvidence() {
        String id="at_"+P;
        var rdf=feature(new OslcRdf(),id,"Field").literal(uri(id),"https://fixture.example/custom","literal")
                .link(uri(id),"https://fixture.example/structured",BASE+"nested")
                .literal(BASE+"nested","https://fixture.example/inner","inline linked structure");
        var doc=singleAttribute(rdf);
        var a=doc.artifacts().stream().filter(v->v.kind()==ArtifactKind.FEATURE).findFirst().orElseThrow();
        assertThat(a.attributes()).containsEntry("attribute:https://fixture.example/custom","literal");
        assertThat(a.extensions().get("rdf:https://fixture.example/structured")).contains("inline linked structure");
        assertThat(doc.losses()).anySatisfy(l->{assertThat(l.artifactId()).isEqualTo(P);assertThat(l.disposition()).isEqualTo(LossDisposition.PRESERVED_EXTENSION);});
        var tooMany=feature(new OslcRdf(),id,"Field");
        for(int i=0;i<129;i++) tooMany.literal(uri(id),"https://fixture.example/p"+i,"value");
        assertThatThrownBy(()->singleAttribute(tooMany)).isInstanceOf(ExchangeFormatException.class)
                .extracting(e->((ExchangeFormatException)e).code()).isEqualTo("SPARX_AM_PROPERTY_LIMIT");
    }
    @Test void evidenceSortsCollectionsAndRetainsFeatureChanges() {
        var evidence=new SparxAmReadEvidence();var reversed=new SparxAmReadEvidence();
        var root=new SparxOslcAmCodec.Page(BASE.resolve("qc/"),null,resource(new OslcRdf(),"el_"+E,"Component","Fixed","").xml());
        var tags=new SparxOslcAmCodec.Page(BASE.resolve("taggedvalues/"),"one",new OslcRdf().xml());
        evidence.add("qc","ROOT",root);evidence.add("taggedvalues",E,tags);
        reversed.add("taggedvalues",E,tags);reversed.add("qc","ROOT",root);
        assertThat(evidence.version()).isEqualTo(reversed.version());
        var changed=new SparxAmReadEvidence(); changed.add("qc","ROOT",root);
        changed.add("taggedvalues",E,new SparxOslcAmCodec.Page(tags.resource(),"two",tags.content()));
        assertThat(changed.version()).isNotEqualTo(evidence.version());
    }
    @Test void structuredCyclesAndAmbiguousStereotypesAreNeverFlattenedOrMappedByDefault() {
        String id="at_"+P;
        var cycle=feature(new OslcRdf(),id,"Field").link(uri(id),"https://fixture.example/nested",BASE+"nested")
                .link(BASE+"nested","https://fixture.example/nested",BASE+"nested");
        assertThatThrownBy(()->singleAttribute(cycle)).isInstanceOf(ExchangeFormatException.class)
                .extracting(e->((ExchangeFormatException)e).code()).isEqualTo("SPARX_AM_STRUCTURED_CYCLE");
        var root=resource(new OslcRdf(),"el_"+E,"Class","Owner","")
                .literal(uri("el_"+E),SparxOslcAmCodec.SS+"stereotype","BusinessRole")
                .literal(uri("el_"+E),SparxOslcAmCodec.SS+"stereotype","Component");
        var collections=new ArrayList<SparxOslcAmCodec.Collection>();
        for(String kind:List.of("linkedresources","taggedvalues","attributes","operations")) collections.add(collection(kind,"el_"+E,new OslcRdf()));
        var document=new SparxOslcAmCodec("2").read(List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"),null,root.xml())),collections,BASE);
        assertThat(document.artifacts().getFirst().extensions()).doesNotContainKey("canonicalType");
        assertThat(document.losses()).extracting(MappingLoss::code).contains("SPARX_AM_STEREOTYPE_UNMAPPED");
    }

    private static ExchangeDocument singleAttribute(OslcRdf attribute) {
        var pages=List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"),null,resource(new OslcRdf(),"el_"+E,"Component","Owner","").xml()));
        var collections=List.of(collection("linkedresources","el_"+E,new OslcRdf()),collection("taggedvalues","el_"+E,new OslcRdf()),
                collection("attributes","el_"+E,attribute),collection("operations","el_"+E,new OslcRdf()),collection("taggedvalues","at_"+P,new OslcRdf()));
        return new SparxOslcAmCodec("2").read(pages,collections,BASE);
    }

    @Test void distinctReifiedConnectorsBetweenTheSameEndpointsRemainDistinct() {
        var roots=resource(new OslcRdf(),"el_"+E,"Component","Source","");
        resource(roots,"el_"+P,"Component","Target","");
        var links=connector(E,P,"Source -> Destination");
        String detail=BASE+"link#second";
        links.type(detail,OslcRdf.RDF+"Statement").link(detail,OslcRdf.RDF+"subject",uri("el_"+E))
                .link(detail,OslcRdf.RDF+"predicate",SparxOslcAmCodec.SS+"Dependency").link(detail,OslcRdf.RDF+"object",uri("el_"+P))
                .literal(detail,OslcRdf.DCT+"identifier","lt_{88888888-8888-4888-8888-888888888888}")
                .literal(detail,SparxOslcAmCodec.SS+"direction","Source -> Destination");
        var collections=new ArrayList<SparxOslcAmCodec.Collection>();
        for(String owner:List.of("el_"+E,"el_"+P)) for(String kind:List.of("linkedresources","taggedvalues","attributes","operations"))
            collections.add(collection(kind,owner,owner.equals("el_"+E)&&kind.equals("linkedresources")?links:new OslcRdf()));
        var document=new SparxOslcAmCodec("2").read(List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"),null,roots.xml())),collections,BASE);
        assertThat(document.relations()).hasSize(2);
    }

    @Test void laterConnectorPagesRejectEveryUnassociatedDescriptionIdentity() {
        for (String orphanId : List.of("lt_{88888888-8888-4888-8888-888888888888}",
                "lt_{44444444-4444-4444-8444-444444444444}")) {
            var orphan = new OslcRdf().literal(BASE + "link#orphan", OslcRdf.DCT + "identifier", orphanId)
                    .literal(BASE + "link#orphan", SparxOslcAmCodec.SS + "direction", "Source -> Destination");
            assertThatThrownBy(() -> twoConnectorPages(orphan))
                    .isInstanceOf(ExchangeFormatException.class)
                    .extracting(e -> ((ExchangeFormatException) e).code()).isEqualTo("SPARX_AM_CONNECTOR");
        }
    }

    @Test void associatedConnectorRepeatedOnLaterPageStillDeduplicates() {
        assertThat(twoConnectorPages(connector(E, P, "Source -> Destination")).relations()).hasSize(1);
    }

    private static ExchangeDocument twoConnectorPages(OslcRdf second) {
        var roots = resource(new OslcRdf(), "el_" + E, "Component", "Source", "");
        resource(roots, "el_" + P, "Component", "Target", "");
        var first = collection("linkedresources", "el_" + E, connector(E, P, "Source -> Destination"));
        URI endpoint = first.pages().getFirst().resource();
        URI next = URI.create(endpoint + "?page=2");
        var links = connector(E, P, "Source -> Destination")
                .link(endpoint.toString(), OslcRdf.OSLC + "nextPage", next.toString());
        var collections = new ArrayList<SparxOslcAmCodec.Collection>();
        for (String owner : List.of("el_" + E, "el_" + P)) {
            for (String kind : List.of("linkedresources", "taggedvalues", "attributes", "operations")) {
                collections.add(owner.equals("el_" + E) && kind.equals("linkedresources")
                        ? new SparxOslcAmCodec.Collection(kind, owner, List.of(
                                new SparxOslcAmCodec.Page(endpoint, null, links.xml()),
                                new SparxOslcAmCodec.Page(next, null, second.xml())))
                        : collection(kind, owner, new OslcRdf()));
            }
        }
        return new SparxOslcAmCodec("2").read(
                List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"), null, roots.xml())), collections, BASE);
    }

    private static List<Artifact> semanticArtifacts(ExchangeDocument d) {
        return d.artifacts().stream().map(a -> {
            var e = new TreeMap<>(a.extensions()); e.remove("externalIdentifier"); e.remove("uri");
            return new Artifact(a.id(), a.kind(), a.type(), a.title(), a.text(), a.attributes(), e);
        }).toList();
    }
    private static ExchangeDocument contractV2(String direction, boolean outside, boolean conflict) {
        String a="{11111111-1111-4111-8111-111111111111}", b="{22222222-2222-4222-8222-222222222222}";
        String p="{33333333-3333-4333-8333-333333333333}", q="{33333333-3333-4333-8333-333333333334}";
        var rdf=resource(new OslcRdf(),"pk_"+p,"Package","Outer","");
        resource(rdf,"pk_"+q,"Package","Inner","").literal(uri("pk_"+q),SparxOslcAmCodec.SS+"parentresourceidentifier","pk_"+p);
        resource(rdf,"el_"+a,"Component","First","").literal(uri("el_"+a),SparxOslcAmCodec.SS+"parentresourceidentifier","pk_"+q);
        if (!outside) resource(rdf,"el_"+b,"Component","Second","").literal(uri("el_"+b),SparxOslcAmCodec.SS+"parentresourceidentifier","pk_"+q);
        var collections=new ArrayList<SparxOslcAmCodec.Collection>();
        for(String root: outside?List.of("pk_"+p,"pk_"+q,"el_"+a):List.of("pk_"+p,"pk_"+q,"el_"+a,"el_"+b)) {
            collections.add(collection("linkedresources",root,root.equals("el_"+a)||root.equals("el_"+b)?connector(a,b,conflict&&root.equals("el_"+b)?"Bi-Directional":direction):new OslcRdf()));
            var tags=new OslcRdf();
            if(root.equals("el_"+a)) for(int i=1;i<=2;i++) feature(tags,"tv_{66666666-6666-4666-8666-66666666666"+i+"}","owner").literal(uri("tv_{66666666-6666-4666-8666-66666666666"+i+"}"),SparxOslcAmCodec.SS+"value",i==1?"Alice":"Bob");
            collections.add(collection("taggedvalues",root,tags));
            if(root.startsWith("el_")) {
                var attribute=new OslcRdf(); var operation=new OslcRdf();
                String at="at_{55555555-5555-4555-8555-555555555551}",op="op_{55555555-5555-4555-8555-555555555552}";
                if(root.equals("el_"+a)) {
                    feature(attribute,at,"count").literal(uri(at),SparxOslcAmCodec.SS+"scope","private").literal(uri(at),SparxOslcAmCodec.SS+"classifiername","Integer").literal(uri(at),SparxOslcAmCodec.SS+"position","0");
                    feature(operation,op,"read").literal(uri(op),SparxOslcAmCodec.SS+"scope","public").literal(uri(op),SparxOslcAmCodec.SS+"position","0");
                    collections.add(collection("taggedvalues",at,new OslcRdf())); collections.add(collection("taggedvalues",op,new OslcRdf()));
                    String pr="pr_{55555555-5555-4555-8555-555555555553}";
                    collections.add(collection("parameters",op,feature(new OslcRdf(),pr,"limit").literal(uri(pr),SparxOslcAmCodec.SS+"paramdirection","in").literal(uri(pr),SparxOslcAmCodec.SS+"position","0")));
                }
                collections.add(collection("attributes",root,attribute)); collections.add(collection("operations",root,operation));
            }
        }
        return new SparxOslcAmCodec("2").read(List.of(new SparxOslcAmCodec.Page(BASE.resolve("qc/"),null,rdf.xml())),collections,BASE);
    }
    private static OslcRdf connector(String from,String to,String direction) {
        String source=uri("el_"+from),target=uri("el_"+to),link=BASE+"linkedresources/link#ID";
        return new OslcRdf().type(source,SparxOslcAmCodec.AM+"Resource").literal(source,OslcRdf.DCT+"identifier","el_"+from)
                .link(source,SparxOslcAmCodec.SS+"Dependency",target)
                .type(link,OslcRdf.RDF+"Statement").link(link,OslcRdf.RDF+"subject",source)
                .link(link,OslcRdf.RDF+"predicate",SparxOslcAmCodec.SS+"Dependency").link(link,OslcRdf.RDF+"object",target)
                .literal(link,OslcRdf.DCT+"identifier","lt_{44444444-4444-4444-8444-444444444444}")
                .literal(link,SparxOslcAmCodec.SS+"direction",direction);
    }
    private static OslcRdf feature(OslcRdf rdf,String id,String title) { return rdf.literal(uri(id),OslcRdf.DCT+"identifier",id).literal(uri(id),OslcRdf.DCT+"title",title); }
    private static SparxOslcAmCodec.Collection collection(String kind,String owner,OslcRdf rdf) {
        return new SparxOslcAmCodec.Collection(kind,owner,List.of(new SparxOslcAmCodec.Page(BASE.resolve(kind+"/"+owner.replace("{","%7B").replace("}","%7D")+"/"),null,rdf.xml())));
    }

    private static OslcRdf resource(OslcRdf rdf, String id, String type, String title, String description) {
        return rdf.type(uri(id), SparxOslcAmCodec.AM + "Resource")
                .literal(uri(id), OslcRdf.DCT + "identifier", id).literal(uri(id), OslcRdf.DCT + "type", type)
                .literal(uri(id), OslcRdf.DCT + "title", title).literal(uri(id), OslcRdf.DCT + "description", description);
    }
    private static String uri(String id) { return BASE + "resource/" + id.replace("{", "%7B").replace("}", "%7D") + "/"; }
}
