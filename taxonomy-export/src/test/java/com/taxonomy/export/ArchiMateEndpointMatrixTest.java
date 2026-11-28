package com.taxonomy.export;

import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import com.taxonomy.exchange.ExchangeXml;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Independent upstream input for the ordered endpoint rules in the exported TSV. */
class ArchiMateEndpointMatrixTest {
    private record Pair(String source, String target) { }

    @Test
    void everyNativeEndpointRowMatchesThePinnedArchiMatrixInTheOriginalDirection() throws Exception {
        Document keys = upstream("relationships-keys.xml", "19d86924efc3364d26c162fe4e6643f37fd9493a0effc80ce5e2aa648fb6803e");
        Document matrix = upstream("relationships.xml", "82cc8d11b174f3edf700fc8d6e3e061d4d9d8e65e5ec0f3c323d94f98a2681e7");
        assertEquals("3.2", matrix.getDocumentElement().getAttribute("version"));

        Map<String, String> names = new HashMap<>();
        for (var key : ExchangeXml.children(keys.getDocumentElement())) {
            String name = key.getAttribute("relationship");
            assertTrue(name.endsWith("Relationship"));
            assertNull(names.put(key.getAttribute("char"), name.substring(0, name.length() - "Relationship".length())));
        }

        Set<String> concepts = ArchiMateExchangeProfile.mappings().stream()
                .filter(mapping -> "element".equals(mapping.scope()))
                .map(ArchiMateExchangeProfile.Mapping::targetType).collect(Collectors.toSet());
        Map<Pair, Set<String>> expected = new HashMap<>();
        for (var source : ExchangeXml.children(matrix.getDocumentElement())) {
            String from = source.getAttribute("concept");
            if (!concepts.contains(from)) continue;
            for (var target : ExchangeXml.children(source)) {
                String to = target.getAttribute("concept");
                if (!concepts.contains(to)) continue;
                var allowed = new HashSet<String>();
                for (char letter : target.getAttribute("relations").toCharArray()) {
                    String name = names.get(String.valueOf(letter));
                    assertNotNull(name, "Unknown upstream key " + letter);
                    assertTrue(allowed.add(name), "Repeated relationship key for " + from + " to " + to);
                }
                assertNull(expected.put(new Pair(from, to), allowed), "Duplicate upstream endpoint rule");
            }
        }
        assertFalse(concepts.isEmpty());
        assertEquals(concepts.size() * concepts.size(), expected.size(), "A supported ordered pair is missing upstream");

        Map<Pair, Set<String>> actual = new HashMap<>();
        var lines = new String(ArchiMateExchangeProfile.resourceBytes(), StandardCharsets.UTF_8).lines().toList();
        var header = Arrays.asList(lines.getFirst().split("\t", -1));
        for (String line : lines.subList(1, lines.size())) {
            var fields = line.split("\t", -1);
            if (!"endpoints".equals(fields[header.indexOf("scope")])) continue;
            Pair pair = new Pair(fields[header.indexOf("sourceType")], fields[header.indexOf("targetType")]);
            String allowed = fields[header.indexOf("allowedRelationships")];
            Set<String> rule = allowed.isEmpty() ? Set.of() : Set.of(allowed.split(";"));
            assertNull(actual.put(pair, rule), "Duplicate exported rule for " + pair);
        }
        assertEquals(expected.keySet(), actual.keySet(), "Missing or additional ordered endpoint pairs");
        expected.forEach((pair, allowed) -> assertEquals(allowed, actual.get(pair), pair.toString()));
    }

    private static Document upstream(String filename, String sha256) throws IOException, NoSuchAlgorithmException {
        try (var resource = ArchiMateEndpointMatrixTest.class.getResourceAsStream("/archimate-3.2-matrix/" + filename + ".gz")) {
            assertNotNull(resource, filename);
            try (var gzip = new GZIPInputStream(resource)) {
                byte[] bytes = gzip.readAllBytes();
                assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), filename);
                return ExchangeXml.parse(bytes);
            }
        }
    }
}
