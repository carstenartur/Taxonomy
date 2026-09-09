package com.taxonomy.exchange;

import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    public OslcRdf literal(String subject, String predicate, String value) {
        if (value == null) return this;
        if (subject == null || predicate == null)
            throw new IllegalArgumentException("RDF literal subject and predicate are required");
        triples.add(new Triple(subject, predicate, value, false)); return this;
    }
    public OslcRdf link(String subject, String predicate, String value) {
        if (subject == null || predicate == null || value == null)
            throw new IllegalArgumentException("RDF link subject, predicate and value are required");
        triples.add(new Triple(subject, predicate, value, true)); return this;
    }
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
        ByteArrayOutputStream out = new BoundedOutput();
        RDFDataMgr.write(out, model, RDFFormat.RDFXML_PLAIN);
        return out.toByteArray();
    }
    public byte[] turtle() {
        return utf8(result -> {
            for (Triple t : triples) {
                result.append('<').append(iri(t.subject())).append("> <").append(iri(t.predicate())).append("> ");
                if (t.resource()) result.append('<').append(iri(t.value())).append('>');
                else quote(result, t.value());
                result.append(" .\n");
            }
        });
    }
    public byte[] jsonLd() {
        return utf8(result -> {
            result.append("{\"@graph\":["); boolean comma = false;
            for (Triple t : triples) {
                if (comma) result.append(','); comma = true;
                result.append("{\"@id\":"); quote(result, iri(t.subject())); result.append(',');
                quote(result, iri(t.predicate())); result.append(':');
                result.append(t.resource() ? "{\"@id\":" : "{\"@value\":");
                quote(result, t.resource() ? iri(t.value()) : t.value()); result.append("}}");
            }
            result.append("]}");
        });
    }
    @FunctionalInterface
    private interface TextOutput { void write(Writer output) throws IOException; }

    private static byte[] utf8(TextOutput action) {
        var output = new BoundedOutput();
        try (var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            action.write(writer);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot serialize OSLC output", failure);
        }
        return output.toByteArray();
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            requireSpace(1);
            super.write(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireSpace(length);
            super.write(bytes, offset, length);
        }

        private void requireSpace(int length) {
            if (length > ExchangeXml.MAX_BYTES - count)
                throw ExchangeXml.invalid("PACKAGE_SIZE", "OSLC output exceeds 16 MiB");
        }
    }
    private static String iri(String value) {
        if (value == null || value.chars().anyMatch(c -> c <= 32 || "<>\"{}|^`\\".indexOf(c) >= 0) || !java.net.URI.create(value).isAbsolute())
            throw new IllegalArgumentException("RDF resource must be an absolute safe IRI"); return value;
    }
    private static void quote(Writer result, String value) throws IOException {
        result.append('"');
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean escape = c == '\\' || c == '"' || c < 32;
            if (escape || i - start == 4096) {
                result.write(value, start, i - start);
                start = i;
            }
            if (!escape) continue;
            switch (c) {
                case '\\' -> result.append("\\\\"); case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                default -> result.append("\\u00")
                        .append("0123456789abcdef".charAt(c >>> 4))
                        .append("0123456789abcdef".charAt(c & 15));
            }
            start = i + 1;
        }
        result.write(value, start, value.length() - start);
        result.append('"');
    }
}
