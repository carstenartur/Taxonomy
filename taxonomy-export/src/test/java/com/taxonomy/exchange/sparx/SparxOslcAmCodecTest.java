package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ExchangeFormatException;
import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.extension.api.integration.IntegrationContracts.ArtifactKind;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
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

    private static OslcRdf resource(OslcRdf rdf, String id, String type, String title, String description) {
        return rdf.type(uri(id), SparxOslcAmCodec.AM + "Resource")
                .literal(uri(id), OslcRdf.DCT + "identifier", id).literal(uri(id), OslcRdf.DCT + "type", type)
                .literal(uri(id), OslcRdf.DCT + "title", title).literal(uri(id), OslcRdf.DCT + "description", description);
    }
    private static String uri(String id) { return BASE + "resource/" + id.replace("{", "%7B").replace("}", "%7D") + "/"; }
}
