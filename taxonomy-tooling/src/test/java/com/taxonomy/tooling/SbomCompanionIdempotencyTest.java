package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SbomCompanionIdempotencyTest {

    @Test
    void identicalSourceTimestampAndSerialProduceByteIdenticalOutput(
            @TempDir Path root) throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Path output = root.resolve("taxonomy-vex.json");
        Instant timestamp = Instant.parse("2031-08-15T10:11:12.999Z");
        UUID serial = UUID.fromString(
                "76543210-4321-6789-abcd-0987654321ab");
        Files.writeString(sbom, """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "serialNumber": "urn:uuid:source",
                  "version": 3,
                  "components": [
                    {"name": "Größe"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        SbomCompanionGenerator.generate(
                sbom, output, timestamp, serial);
        byte[] first = Files.readAllBytes(output);
        SbomCompanionGenerator.generate(
                sbom, output, timestamp, serial);

        assertThat(Files.readAllBytes(output)).isEqualTo(first);
        assertThat(Files.readString(output, StandardCharsets.UTF_8))
                .contains("2031-08-15T10:11:12Z")
                .contains("urn:uuid:" + serial)
                .contains("Größe");
    }
}
