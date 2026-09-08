package com.taxonomy.export;

import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;

import com.taxonomy.archimate.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Commit-tracked synthetic fixture for repeatable acceptance in external consumers. */
class ArchiMateInteropFixtureTest {
    static byte[] fixture() {
        var model = new ArchiMateDiagramService().convert(ArchiMateRoundtripTest.representative());
        model = new ArchiMateExportMetadata("fixture-snapshot-967",
                Map.of("taxonomy.snapshotId", ArchiMateProperty.text("fixture-snapshot-967"),
                        "taxonomy.repositoryId", ArchiMateProperty.text("synthetic-acceptance-repository"),
                        "taxonomy.authoritativeCommit", ArchiMateProperty.text("0000000000000000000000000000000000000000")),
                Map.of(), Map.of(), List.of()).apply(model);
        return new ArchiMateXmlExporter().export(model);
    }

    @Test
    void committedFixtureIsGeneratedByTheCurrentSerializerAndRoundTrips() throws Exception {
        byte[] expected;
        try (var input = getClass().getResourceAsStream("/archimate-v2/model.archimate.xml")) {
            assertNotNull(input);
            expected = input.readAllBytes();
        }
        assertArrayEquals(expected, fixture());
        var reader = new ArchiMateExchangeReader();
        assertEquals(ArchiMateRoundtripTest.representative(), reader.toDiagram(reader.read(expected)));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: ArchiMateInteropFixtureTest <output.xml>");
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, fixture());
    }
}
