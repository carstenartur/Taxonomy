package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SbomCompanionGeneratorTest {

    private static final Instant TIMESTAMP =
            Instant.parse("2026-08-15T01:02:03.987Z");
    private static final UUID SERIAL =
            UUID.fromString("12345678-1234-5678-9abc-1234567890ab");

    @Test
    void generatesDeterministicNonAssertionCompanion(@TempDir Path root)
            throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Path output = root.resolve("nested/taxonomy-vex.json");
        Files.writeString(sbom, """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "serialNumber": "urn:uuid:source-bom",
                  "version": 7,
                  "components": [
                    {"name": "one"},
                    {"name": "two"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        SbomCompanionGenerator.Result result =
                SbomCompanionGenerator.generate(
                        sbom, output, TIMESTAMP, SERIAL);

        assertThat(result.timestamp())
                .isEqualTo(Instant.parse("2026-08-15T01:02:03Z"));
        assertThat(result.sourceSerialNumber())
                .isEqualTo("urn:uuid:source-bom");
        assertThat(result.sourceVersion()).isEqualTo("7");
        assertThat(result.componentCount()).isEqualTo(2);

        Map<String, Object> companion = FlatJson.parseObject(
                Files.readString(output, StandardCharsets.UTF_8));
        assertThat(companion)
                .containsEntry("bomFormat", "CycloneDX")
                .containsEntry("specVersion", "1.6")
                .containsEntry("version", 1L)
                .containsEntry("serialNumber", "urn:uuid:" + SERIAL);
        assertThat(companion.get("vulnerabilities"))
                .isEqualTo(List.of());

        Map<String, Object> metadata = object(companion.get("metadata"));
        assertThat(metadata.get("timestamp"))
                .isEqualTo("2026-08-15T01:02:03Z");
        Map<String, String> properties = properties(metadata.get("properties"));
        assertThat(properties)
                .containsEntry("taxonomy:sbom-ref", "urn:uuid:source-bom")
                .containsEntry("taxonomy:sbom-version", "7")
                .containsEntry("vex:assessment-status", "not-assessed")
                .containsEntry(
                        "vex:policy",
                        SbomCompanionGenerator.ASSESSMENT_POLICY);

        String rendered = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(rendered)
                .contains("\"vulnerabilities\": []")
                .contains("not-assessed")
                .doesNotContain("\"state\": \"not_affected\"")
                .doesNotContain("\"state\": \"resolved\"")
                .doesNotContain("\"state\": \"exploitable\"")
                .doesNotContain("\"analysis\":");
        assertThat(FlatJson.pretty(companion) + "\n").isEqualTo(rendered);
    }

    @Test
    void removesStaleOutputWhenSbomIsMissingOrMalformed(@TempDir Path root)
            throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Path output = root.resolve("taxonomy-vex.json");
        Files.writeString(output, "stale", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SbomCompanionGenerator.generate(
                sbom, output, TIMESTAMP, SERIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SBOM file is missing");
        assertThat(output).doesNotExist();

        Files.writeString(sbom, """
                {
                  "serialNumber": "urn:uuid:source",
                  "components": {"not": "an array"}
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(output, "stale-again", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SbomCompanionGenerator.generate(
                sbom, output, TIMESTAMP, SERIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("components must be an array");
        assertThat(output).doesNotExist();
    }

    @Test
    void removesStaleOutputWhenFinalWriteFails(@TempDir Path root)
            throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Path output = root.resolve("taxonomy-vex.json");
        Files.writeString(sbom, "{\"components\": []}\n", StandardCharsets.UTF_8);
        Files.writeString(output, "stale", StandardCharsets.UTF_8);
        IOException writeFailure = new IOException("simulated write failure");

        assertThatThrownBy(() -> SbomCompanionGenerator.generate(
                sbom,
                output,
                TIMESTAMP,
                SERIAL,
                (ignoredPath, ignoredContent) -> {
                    throw writeFailure;
                }))
                .isSameAs(writeFailure);

        assertThat(output).doesNotExist();
    }

    @Test
    void supportsSingleCharacterOutputFileNames(@TempDir Path root)
            throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Path output = root.resolve("a");
        Files.writeString(sbom, "{}\n", StandardCharsets.UTF_8);

        SbomCompanionGenerator.Result result =
                SbomCompanionGenerator.generate(
                        sbom, output, TIMESTAMP, SERIAL);

        assertThat(result.output())
                .isEqualTo(output.toAbsolutePath().normalize());
        assertThat(output).isRegularFile();
    }

    @Test
    void rejectsSourceAndOutputAliasing(@TempDir Path root) throws Exception {
        Path sbom = root.resolve("taxonomy-sbom.json");
        Files.writeString(sbom, "{}", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SbomCompanionGenerator.generate(
                sbom, sbom, TIMESTAMP, SERIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from the source SBOM");
        assertThat(Files.readString(sbom, StandardCharsets.UTF_8))
                .isEqualTo("{}");
    }

    @Test
    void cliAcceptsDeterministicInputsAndReportsInvalidTimestamp(
            @TempDir Path root) throws Exception {
        Path sbom = root.resolve("source.json");
        Path output = root.resolve("companion.json");
        Files.writeString(sbom, """
                {
                  "serialNumber": "urn:uuid:source",
                  "version": 2,
                  "components": []
                }
                """, StandardCharsets.UTF_8);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int success = TaxonomyTooling.run(
                new String[]{
                        "generate-sbom-companion",
                        "--sbom", sbom.toString(),
                        "--output", output.toString(),
                        "--timestamp", "2026-08-15T01:02:03Z",
                        "--serial-number", SERIAL.toString()},
                root,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(success).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("SBOM companion document generated")
                .contains("Vulnerability assessment: not-assessed")
                .contains("SBOM serial: urn:uuid:source")
                .contains("Components in SBOM: 0");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();

        stderr.reset();
        int invalid = TaxonomyTooling.run(
                new String[]{
                        "generate-sbom-companion",
                        "--sbom", sbom.toString(),
                        "--output", output.toString(),
                        "--timestamp", "not-an-instant"},
                root,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertThat(invalid).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .startsWith("::error::")
                .contains("not-an-instant");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static Map<String, String> properties(Object value) {
        assertThat(value).isInstanceOf(List.class);
        List<?> entries = (List<?>) value;
        LinkedHashMap<String, String> result = entries.stream()
                .map(SbomCompanionGeneratorTest::object)
                .collect(Collectors.toMap(
                        entry -> String.valueOf(entry.get("name")),
                        entry -> String.valueOf(entry.get("value")),
                        (left, right) -> right,
                        LinkedHashMap::new));
        return result;
    }
}
