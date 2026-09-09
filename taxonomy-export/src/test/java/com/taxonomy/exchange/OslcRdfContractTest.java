package com.taxonomy.exchange;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.*;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class OslcRdfContractTest {
    private final OslcRequirementsCodec codec = new OslcRequirementsCodec();
    private static final URI BASE = URI.create("https://provider.example/rm/");
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String rdf(String inner) { return "<rdf:RDF xmlns:rdf=\"" + OslcRdf.RDF + "\" xmlns:dcterms=\"" + OslcRdf.DCT + "\" xmlns:rm=\"" + OslcRdf.RM + "\" xmlns:ex=\"urn:profile:\">" + inner + "</rdf:RDF>"; }
    @Test void typedAndDescriptionSpellingsResolveStableUrisAndPreserveRdfTerms() {
        byte[] xml = bytes(rdf("<rdf:Description rdf:about=\"requirement-1\"><rdf:type rdf:resource=\"" + OslcRdf.RM + "Requirement\"/>"
                + "<dcterms:title xml:lang=\"en\">Title</dcterms:title><dcterms:description>Body</dcterms:description>"
                + "<ex:score rdf:datatype=\"http://www.w3.org/2001/XMLSchema#integer\">42</ex:score><ex:tag>A</ex:tag><ex:tag>B</ex:tag>"
                + "<ex:link rdf:resource=\"linked\"/></rdf:Description>"));
        var document = codec.read(xml, BASE, "\"etag-1\"", BASE.resolve("config-a").toString());
        assertEquals("https://provider.example/rm/requirement-1", document.artifacts().getFirst().id());
        assertFalse(document.completeScope()); assertEquals("\"etag-1\"", document.externalVersion());
        assertTrue(OslcRequirementsCodec.parse(xml, BASE).isIsomorphicWith(OslcRequirementsCodec.parse(codec.write(document), BASE)));
        Artifact original = document.artifacts().getFirst();
        Artifact changed = new Artifact(original.id(), original.kind(), original.type(), "Renamed", original.text(), original.attributes(), original.extensions());
        var result = codec.read(codec.write(new ExchangeDocument(document.profile(), "1", null, false, "", List.of(changed), List.of(), List.of(), document.metadata(), List.of())), BASE, null, null);
        assertEquals(original.id(), result.artifacts().getFirst().id()); assertEquals("Renamed", result.artifacts().getFirst().title());
        assertEquals(2, OslcRequirementsCodec.parse(codec.write(result), BASE).listObjectsOfProperty(ModelFactory.createDefaultModel().createProperty("urn:profile:tag")).toList().size());
    }
    @Test void referenceConsumerReadsAllProviderRepresentationsAsEquivalentRdf() {
        String subject = BASE.resolve("requirements/7/versions/11").toString();
        OslcRdf graph = new OslcRdf().type(subject, OslcRdf.RM + "Requirement").literal(subject, OslcRdf.DCT + "title", "Title <&> \"quoted\"")
                .literal(subject, OslcRdf.DCT + "description", "Two\nlines").link(subject, OslcRdf.DCT + "isVersionOf", BASE.resolve("requirements/7").toString());
        var expected = OslcRequirementsCodec.parse(graph.xml(), BASE);
        for (var format : Map.of(Lang.TURTLE, graph.turtle(), Lang.JSONLD, graph.jsonLd()).entrySet()) {
            var actual = ModelFactory.createDefaultModel(); RDFParser.fromString(new String(format.getValue(), StandardCharsets.UTF_8), format.getKey()).parse(actual);
            assertTrue(expected.isIsomorphicWith(actual));
        }
        assertEquals(1, expected.listResourcesWithProperty(RDF.type, expected.createResource(OslcRdf.RM + "Requirement")).toList().size());
    }
    @Test void discoveryIsBoundedEvidenceAndDoesNotFollowLinksOrTreatOmissionsAsDeletion() {
        byte[] xml = new OslcRdf().type(BASE.toString(), OslcRdf.OSLC + "ServiceProviderCatalog")
                .link(BASE.toString(), OslcRdf.OSLC + "serviceProvider", BASE.resolve("provider").toString()).xml();
        var discovery = codec.discover(xml, BASE, "v1", null);
        assertEquals(List.of(BASE.resolve("provider").toString()), discovery.resources().stream().map(DiscoveryResource::uri).toList());
        assertFalse(codec.read(xml, BASE, "v1", null).completeScope());
        assertThrows(ExchangeFormatException.class, () -> codec.read(bytes(rdf("<rm:Requirement><dcterms:title>Anonymous</dcterms:title></rm:Requirement>")), BASE, null, null));
        assertThrows(ExchangeFormatException.class, () -> codec.read(bytes(rdf("<rm:Requirement rdf:about=\"one\"><ex:structured rdf:parseType=\"Resource\"><ex:value>nested</ex:value></ex:structured></rm:Requirement>")), BASE, null, null));
    }

    @Test void urnPredicatesAndResourcesSurviveEveryRepresentation() {
        String subject = "urn:requirements:one";
        OslcRdf graph = new OslcRdf()
                .literal(subject, "urn:profile:tag", "Evidence\r\n<&>")
                .link(subject, "urn:profile:link", "urn:requirements:two");
        var expected = ModelFactory.createDefaultModel();
        expected.createResource(subject)
                .addLiteral(expected.createProperty("urn:profile:tag"), "Evidence\r\n<&>")
                .addProperty(expected.createProperty("urn:profile:link"),
                        expected.createResource("urn:requirements:two"));
        for (var format : Map.of(Lang.RDFXML, graph.xml(), Lang.TURTLE, graph.turtle(), Lang.JSONLD, graph.jsonLd()).entrySet()) {
            var actual = ModelFactory.createDefaultModel();
            RDFParser.fromString(new String(format.getValue(), StandardCharsets.UTF_8), format.getKey()).parse(actual);
            assertTrue(expected.isIsomorphicWith(actual), format.getKey().toString());
        }
    }

    @Test void everyRepresentationRejectsUnsafeOrRelativeResourceIris() {
        for (String invalid : List.of("relative", "urn:bad value", "urn:bad<value", "urn:bad{value", "urn:bad\\value")) {
            for (OslcRdf graph : List.of(
                    new OslcRdf().literal(invalid, OslcRdf.DCT + "title", "Title"),
                    new OslcRdf().literal(BASE.toString(), invalid, "Title"),
                    new OslcRdf().link(BASE.toString(), OslcRdf.DCT + "relation", invalid))) {
                assertThrows(IllegalArgumentException.class, graph::xml, invalid);
                assertThrows(IllegalArgumentException.class, graph::turtle, invalid);
                assertThrows(IllegalArgumentException.class, graph::jsonLd, invalid);
            }
        }
    }

    @Test void linksRejectMissingIdentitiesBeforeChangingTheGraph() {
        var graph = new OslcRdf().type(BASE.toString(), OslcRdf.RM + "Requirement");
        var before = graph.triples();
        for (int missing = 0; missing < 3; missing++) {
            String[] terms = {BASE.toString(), OslcRdf.DCT + "relation", BASE.resolve("target").toString()};
            terms[missing] = null;
            var error = assertThrows(IllegalArgumentException.class, () -> graph.link(terms[0], terms[1], terms[2]));
            assertEquals("RDF link subject, predicate and value are required", error.getMessage());
            assertEquals(before, graph.triples());
        }
    }

    @Test void literalsRejectMissingIdentitiesBeforeChangingTheGraph() {
        var graph = new OslcRdf().type(BASE.toString(), OslcRdf.RM + "Requirement");
        var before = graph.triples();
        for (int missing = 0; missing < 2; missing++) {
            String[] terms = {BASE.toString(), OslcRdf.DCT + "title"};
            terms[missing] = null;
            var error = assertThrows(IllegalArgumentException.class,
                    () -> graph.literal(terms[0], terms[1], "Title"));
            assertEquals("RDF literal subject and predicate are required", error.getMessage());
            assertEquals(before, graph.triples());
        }
        assertSame(graph, graph.literal(BASE.toString(), OslcRdf.DCT + "title", null));
        assertEquals(before, graph.triples());
    }

    static Stream<Arguments> representations() {
        return Stream.of(
                Arguments.of("RDF/XML", (Function<OslcRdf, byte[]>) OslcRdf::xml),
                Arguments.of("Turtle", (Function<OslcRdf, byte[]>) OslcRdf::turtle),
                Arguments.of("JSON-LD", (Function<OslcRdf, byte[]>) OslcRdf::jsonLd));
    }

    static Stream<Arguments> textRepresentations() {
        return Stream.of(
                Arguments.of("Turtle", (Function<OslcRdf, byte[]>) OslcRdf::turtle),
                Arguments.of("JSON-LD", (Function<OslcRdf, byte[]>) OslcRdf::jsonLd));
    }

    @ParameterizedTest(name = "{0} stops writing before visiting later triples")
    @MethodSource("textRepresentations")
    void oversizedTextOutputStopsBeforeLaterTriplesAreVisited(
            String format, Function<OslcRdf, byte[]> write) {
        var graph = new OslcRdf()
                .literal(BASE.toString(), OslcRdf.DCT + "title", "x".repeat(ExchangeXml.MAX_BYTES + 16 * 1024))
                .literal("invalid-relative-subject", OslcRdf.DCT + "title", "Must not be visited");

        var error = assertThrows(ExchangeFormatException.class, () -> write.apply(graph), format);
        assertEquals("PACKAGE_SIZE", error.code());
        assertEquals("OSLC output exceeds 16 MiB", error.getMessage());
    }

    @Test void textRepresentationsKeepExactEscapingAcrossUnicodeChunks() {
        String padding = "a".repeat(4095);
        String value = padding + "🚀Ä日\\\"\n\r\t\u0001\u001f\b\f";
        String escaped = padding + "🚀Ä日\\\\\\\"\\n\\r\\t\\u0001\\u001f\\u0008\\u000c";
        String predicate = OslcRdf.DCT + "title";
        var graph = new OslcRdf().literal(BASE.toString(), predicate, value);

        assertArrayEquals(bytes("<" + BASE + "> <" + predicate + "> \"" + escaped + "\" .\n"), graph.turtle());
        assertArrayEquals(bytes("{\"@graph\":[{\"@id\":\"" + BASE + "\",\"" + predicate
                + "\":{\"@value\":\"" + escaped + "\"}}]}"), graph.jsonLd());
    }

    @ParameterizedTest(name = "{0} enforces the UTF-8 byte limit")
    @MethodSource("representations")
    void everyRepresentationAcceptsTheByteLimitAndRejectsOneByteMore(
            String format, Function<OslcRdf, byte[]> write) {
        Function<String, OslcRdf> graph = value -> new OslcRdf()
                .literal(BASE.toString(), OslcRdf.DCT + "title", value);
        int overhead = write.apply(graph.apply("x")).length - 1;
        String unicode = "Ä日🚀";
        String atLimit = unicode + "a".repeat(ExchangeXml.MAX_BYTES - overhead - bytes(unicode).length);
        assertEquals(ExchangeXml.MAX_BYTES, write.apply(graph.apply(atLimit)).length, format);

        var error = assertThrows(ExchangeFormatException.class,
                () -> write.apply(graph.apply(atLimit + "a")), format);
        assertEquals("PACKAGE_SIZE", error.code());
        assertEquals("OSLC output exceeds 16 MiB", error.getMessage());
    }

    @Test void multipleXhtmlFragmentsRemainRdfLiteralsUntilAReviewedPlainTextEdit() {
        byte[] source = bytes(rdf("<rm:Requirement rdf:about=\"one\"><dcterms:title>Title</dcterms:title><dcterms:description rdf:parseType=\"Literal\">"
                + "<span xmlns=\"http://www.w3.org/1999/xhtml\">First</span><span xmlns=\"http://www.w3.org/1999/xhtml\">Second</span></dcterms:description></rm:Requirement>"));
        var document = codec.read(source, BASE, null, null); Artifact original = document.artifacts().getFirst();
        assertEquals("FirstSecond", original.text());
        assertTrue(OslcRequirementsCodec.parse(source, BASE).isIsomorphicWith(OslcRequirementsCodec.parse(codec.write(document), BASE)));
        Artifact edited = new Artifact(original.id(), original.kind(), original.type(), original.title(), "Reviewed plain text", original.attributes(), original.extensions());
        var changed = new ExchangeDocument(document.profile(), "1", null, false, "", List.of(edited), List.of(), List.of(), document.metadata(), document.losses());
        assertEquals("OSLC_RICH_TEXT_REPLACED", codec.exportLosses(changed).getFirst().code());
        assertEquals("Reviewed plain text", codec.read(codec.write(changed), BASE, null, null).artifacts().getFirst().text());
    }
}
