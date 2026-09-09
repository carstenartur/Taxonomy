package com.taxonomy.exchange;

import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small RDF graph writer for the declared read-only OSLC surface. No HTML or remote URI is executed. */
public final class OslcRdf {
    public static final String RM_PROFILE = "oslc-rm-2.1";
    public static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String DCT = "http://purl.org/dc/terms/";
    public static final String OSLC = "http://open-services.net/ns/core#";
    public static final String RM = "http://open-services.net/ns/rm#";
    public static final String TAX = "https://github.com/carstenartur/Taxonomy/ns/integration#";
    public record Triple(String subject, String predicate, String value, boolean resource) {}
    private final List<Triple> triples = new ArrayList<>();
    public OslcRdf literal(String subject, String predicate, String value) { if (value != null) triples.add(new Triple(subject, predicate, value, false)); return this; }
    public OslcRdf link(String subject, String predicate, String value) { triples.add(new Triple(subject, predicate, value, true)); return this; }
    public OslcRdf type(String subject, String type) { return link(subject, RDF + "type", type); }
    public List<Triple> triples() { return List.copyOf(triples); }
    public byte[] xml() {
        var model = ModelFactory.createDefaultModel();
        for (Triple triple : triples) {
            var subject = model.createResource(iri(triple.subject()));
            var predicate = model.createProperty(iri(triple.predicate()));
            if (triple.resource()) subject.addProperty(predicate, model.createResource(iri(triple.value())));
            else subject.addLiteral(predicate, triple.value());
        }
        // Let the standards writer derive XML QNames, including URN namespaces.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFDataMgr.write(out, model, RDFFormat.RDFXML_PLAIN);
        if (out.size() > ExchangeXml.MAX_BYTES) throw ExchangeXml.invalid("PACKAGE_SIZE", "OSLC output exceeds 16 MiB");
        return out.toByteArray();
    }
    public byte[] turtle() {
        StringBuilder result = new StringBuilder();
        for (Triple t : triples) result.append('<').append(iri(t.subject())).append("> <").append(iri(t.predicate())).append("> ")
                .append(t.resource() ? "<" + iri(t.value()) + ">" : quote(t.value())).append(" .\n");
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
    public byte[] jsonLd() {
        StringBuilder result = new StringBuilder("{\"@graph\":["); boolean comma = false;
        for (Triple t : triples) {
            if (comma) result.append(','); comma = true;
            result.append("{\"@id\":").append(quote(iri(t.subject()))).append(',').append(quote(iri(t.predicate()))).append(':')
                    .append(t.resource() ? "{\"@id\":" + quote(iri(t.value())) + "}" : "{\"@value\":" + quote(t.value()) + "}").append('}');
        }
        return result.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String iri(String value) {
        if (value.chars().anyMatch(c -> c <= 32 || "<>\"{}|^`\\".indexOf(c) >= 0) || !java.net.URI.create(value).isAbsolute())
            throw new IllegalArgumentException("RDF resource must be an absolute safe IRI"); return value;
    }
    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) switch (c) {
            case '\\' -> result.append("\\\\"); case '"' -> result.append("\\\""); case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
            default -> { if (c < 32) result.append(String.format("\\u%04x", (int) c)); else result.append(c); }
        }
        return result.append('"').toString();
    }
}
