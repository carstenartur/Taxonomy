package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeQlSarifBaselineTest {

    @Test
    void exactFingerprintAcceptsOnlyTheReviewedOccurrence(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path baseline = root.resolve("baseline.json");
        writeSarif(
                sarif,
                result("reviewed-fingerprint:1", "reviewed occurrence")
                        + ",\n"
                        + result("new-fingerprint:1", "new occurrence"));
        writeBaseline(
                baseline,
                entry(
                        "java/high",
                        "src/Reviewed.java",
                        "reviewed-fingerprint:1",
                        "Reviewed in issue #857"));

        CodeQlSarifGate.Inspection inspection = CodeQlSarifGate.inspect(
                List.of(sarif), 7.0, baseline);

        assertThat(inspection.resultCount()).isEqualTo(2);
        assertThat(inspection.acceptedBaseline())
                .extracting(
                        CodeQlSarifGate.Finding::primaryLocationLineHash)
                .containsExactly("reviewed-fingerprint:1");
        assertThat(inspection.blocking())
                .extracting(
                        CodeQlSarifGate.Finding::primaryLocationLineHash)
                .containsExactly("new-fingerprint:1");
        assertThat(inspection.unusedBaseline()).isEmpty();
    }

    @Test
    void findingWithoutFingerprintCannotUseTheBaseline(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path baseline = root.resolve("baseline.json");
        writeSarif(sarif, resultWithoutFingerprint("unfingerprinted"));
        writeBaseline(
                baseline,
                entry(
                        "java/high",
                        "src/Reviewed.java",
                        "reviewed-fingerprint:1",
                        "Reviewed in issue #857"));

        CodeQlSarifGate.Inspection inspection = CodeQlSarifGate.inspect(
                List.of(sarif), 7.0, baseline);

        assertThat(inspection.acceptedBaseline()).isEmpty();
        assertThat(inspection.blocking()).singleElement()
                .extracting(CodeQlSarifGate.Finding::ruleId)
                .isEqualTo("java/high");
        assertThat(inspection.unusedBaseline()).singleElement()
                .extracting(
                        CodeQlSarifGate.BaselineEntry::primaryLocationLineHash)
                .isEqualTo("reviewed-fingerprint:1");
    }

    @Test
    void duplicateAndMalformedBaselineEntriesFailClosed(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path baseline = root.resolve("baseline.json");
        writeSarif(sarif, result("reviewed-fingerprint:1", "message"));
        String duplicate = entry(
                "java/high",
                "src/Reviewed.java",
                "reviewed-fingerprint:1",
                "Reviewed in issue #857");
        Files.writeString(
                baseline,
                """
                {
                  "schemaVersion": 1,
                  "entries": [
                %s,
                %s
                  ]
                }
                """.formatted(duplicate, duplicate),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CodeQlSarifGate.inspect(
                List.of(sarif), 7.0, baseline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate entry")
                .hasMessageContaining("reviewed-fingerprint:1");

        Files.writeString(
                baseline,
                """
                {
                  "schemaVersion": 2,
                  "entries": []
                }
                """,
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CodeQlSarifGate.inspect(
                List.of(sarif), 7.0, baseline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported CodeQL baseline schemaVersion");
    }

    @Test
    void cliWritesAcceptedAndUnusedBaselineEvidence(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path baseline = root.resolve("baseline.json");
        Path report = root.resolve("evidence/codeql-gate.json");
        writeSarif(sarif, result("reviewed-fingerprint:1", "reviewed"));
        writeBaseline(
                baseline,
                entry(
                        "java/high",
                        "src/Reviewed.java",
                        "reviewed-fingerprint:1",
                        "Reviewed in issue #857"),
                entry(
                        "java/other",
                        "src/Unused.java",
                        "unused-fingerprint:1",
                        "Tracked but absent from this diff analysis"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = CodeQlSarifGate.run(
                new String[]{
                        sarif.toString(),
                        "--baseline", baseline.toString(),
                        "--report", report.toString()},
                root,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(
                        OutputStream.nullOutputStream(),
                        true,
                        StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("CodeQL results: 1; blocking: 0")
                .contains("CodeQL baseline accepted: 1; unused: 1");
        Map<String, Object> evidence = FlatJson.parseObject(
                Files.readString(report, StandardCharsets.UTF_8));
        assertThat(evidence)
                .containsEntry("status", "PASS")
                .containsKey("baselineFile");
        assertThat((List<?>) evidence.get("acceptedBaseline")).hasSize(1);
        assertThat((List<?>) evidence.get("unusedBaseline")).hasSize(1);
        assertThat((List<?>) evidence.get("blocking")).isEmpty();
    }

    private static void writeSarif(Path path, String results)
            throws Exception {
        Files.writeString(
                path,
                """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "CodeQL", "rules": [{
                      "id": "java/high",
                      "properties": {"security-severity": "8.1"}
                    }]}},
                    "results": [
                %s
                    ]
                  }]
                }
                """.formatted(results),
                StandardCharsets.UTF_8);
    }

    private static String result(String fingerprint, String message) {
        return """
                    {
                      "ruleId": "java/high",
                      "level": "warning",
                      "message": {"text": "%s"},
                      "locations": [{
                        "physicalLocation": {
                          "artifactLocation": {"uri": "src/Reviewed.java"}
                        }
                      }],
                      "partialFingerprints": {
                        "primaryLocationLineHash": "%s"
                      }
                    }""".formatted(message, fingerprint);
    }

    private static String resultWithoutFingerprint(String message) {
        return """
                    {
                      "ruleId": "java/high",
                      "level": "warning",
                      "message": {"text": "%s"},
                      "locations": [{
                        "physicalLocation": {
                          "artifactLocation": {"uri": "src/Reviewed.java"}
                        }
                      }]
                    }""".formatted(message);
    }

    private static String entry(
            String ruleId,
            String artifactUri,
            String fingerprint,
            String rationale) {
        return """
                    {
                      "ruleId": "%s",
                      "artifactUri": "%s",
                      "primaryLocationLineHash": "%s",
                      "rationale": "%s"
                    }""".formatted(
                            ruleId,
                            artifactUri,
                            fingerprint,
                            rationale);
    }

    private static void writeBaseline(Path path, String... entries)
            throws Exception {
        Files.writeString(
                path,
                """
                {
                  "schemaVersion": 1,
                  "entries": [
                %s
                  ]
                }
                """.formatted(String.join(",\n", entries)),
                StandardCharsets.UTF_8);
    }
}
