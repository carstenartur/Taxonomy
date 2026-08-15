package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
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
                .contains("taxonomy:sbom-sha256")
                .contains("Größe");
    }

    @Test
    void defaultCliGenerationIsStableAndBoundToTheExactSourceSbom(
            @TempDir Path root) throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Path output = root.resolve("taxonomy-vex.json");
        Files.writeString(sbom, """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "serialNumber": "urn:uuid:stable-source",
                  "version": 4,
                  "metadata": {
                    "timestamp": "2031-08-15T10:11:12.999Z"
                  },
                  "components": [
                    {"name": "one"},
                    {"name": "two"}
                  ]
                }
                """, StandardCharsets.UTF_8);
        String[] arguments = {
                "generate-sbom-companion",
                "--sbom", sbom.toString(),
                "--output", output.toString()};
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int firstExit = TaxonomyTooling.run(
                arguments,
                root,
                new PrintStream(firstOutput, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        byte[] first = Files.readAllBytes(output);
        Map<String, Object> firstCompanion = FlatJson.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        String firstSerial = String.valueOf(firstCompanion.get("serialNumber"));
        FileTime retainedTimestamp = FileTime.from(
                Instant.parse("2020-01-02T03:04:05Z"));
        Files.setLastModifiedTime(output, retainedTimestamp);

        int secondExit = TaxonomyTooling.run(
                arguments,
                root,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(firstExit).isZero();
        assertThat(secondExit).isZero();
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(Files.readAllBytes(output)).isEqualTo(first);
        assertThat(Files.getLastModifiedTime(output))
                .isEqualTo(retainedTimestamp);
        assertThat(firstOutput.toString(StandardCharsets.UTF_8))
                .contains("SBOM SHA-256:");
        assertThat(firstSerial)
                .matches("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-"
                        + "[89ab][0-9a-f]{3}-[0-9a-f]{12}");

        Map<String, Object> companion = FlatJson.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        assertThat(companion.get("serialNumber")).isEqualTo(firstSerial);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata =
                (Map<String, Object>) companion.get("metadata");
        assertThat(metadata.get("timestamp"))
                .isEqualTo("2031-08-15T10:11:12Z");
    }
}
