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

class CodeQlSarifGateTest {

    @Test
    void blocksHighSecuritySeverityAndErrorLevelAcrossReports(
            @TempDir Path root) throws Exception {
        Path javaReport = root.resolve("java.sarif");
        Path scriptReport = root.resolve("javascript.sarif");
        writeSarif(javaReport, "java/high", "8.1", "warning", "high severity");
        writeSarif(scriptReport, "js/error", "not-a-number", "error", "error level");

        CodeQlSarifGate.Inspection inspection = CodeQlSarifGate.inspect(
                List.of(javaReport, scriptReport), 7.0);

        assertThat(inspection.resultCount()).isEqualTo(2);
        assertThat(inspection.blocking())
                .extracting(CodeQlSarifGate.Finding::ruleId)
                .containsExactly("java/high", "js/error");
        assertThat(inspection.blocking().get(0).securitySeverity())
                .isEqualTo(8.1);
        assertThat(inspection.blocking().get(1).securitySeverity())
                .isZero();
    }

    @Test
    void lowSeverityWarningPassesAndWritesDeterministicReport(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path report = root.resolve("evidence/codeql-gate.json");
        writeSarif(sarif, "java/low", "6.9", "warning", "reviewed warning");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = CodeQlSarifGate.run(
                new String[]{
                        sarif.toString(),
                        "--threshold", "7.0",
                        "--report", report.toString()},
                root,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .isEqualTo("CodeQL results: 1; blocking: 0\n");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
        Map<String, Object> payload = FlatJson.parseObject(
                Files.readString(report, StandardCharsets.UTF_8));
        assertThat(payload)
                .containsEntry("resultCount", 1L)
                .containsEntry("threshold", 7.0)
                .containsEntry("status", "PASS")
                .containsEntry("blocking", List.of());
        assertThat(FlatJson.pretty(payload) + "\n")
                .isEqualTo(Files.readString(report, StandardCharsets.UTF_8));
    }

    @Test
    void blockingCliWritesFailEvidenceAndFindingDiagnostic(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path report = root.resolve("codeql-gate.json");
        writeSarif(sarif, "java/high", "9.0", "warning", "unsafe flow");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = CodeQlSarifGate.run(
                new String[]{sarif.toString(), "--report", report.toString()},
                root,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("[warning/security-severity=9.0]")
                .contains("java/high: unsafe flow");
        assertThat(FlatJson.parseObject(
                Files.readString(report, StandardCharsets.UTF_8)))
                .containsEntry("status", "FAIL");
    }

    @Test
    void missingOrMalformedInputRemovesStaleReport(@TempDir Path root)
            throws Exception {
        Path report = root.resolve("codeql-gate.json");
        Files.writeString(report, "stale", StandardCharsets.UTF_8);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int missing = CodeQlSarifGate.run(
                new String[]{"missing.sarif", "--report", report.toString()},
                root,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(missing).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("no SARIF files supplied");
        assertThat(report).doesNotExist();

        Path malformed = root.resolve("malformed.sarif");
        Files.writeString(malformed, "{\"runs\": {}}", StandardCharsets.UTF_8);
        Files.writeString(report, "stale-again", StandardCharsets.UTF_8);
        errors.reset();

        int invalid = CodeQlSarifGate.run(
                new String[]{malformed.toString(), "--report", report.toString()},
                root,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(invalid).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("SARIF runs must be an array");
        assertThat(report).doesNotExist();
    }

    @Test
    void invalidThresholdAndStructuredScalarFailClosed(@TempDir Path root)
            throws Exception {
        Path sarif = root.resolve("result.sarif");
        writeSarif(sarif, "java/rule", "5.0", "warning", "message");

        assertThatThrownBy(() -> CodeQlSarifGate.inspect(
                List.of(sarif), Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite non-negative");

        Files.writeString(sarif, """
                {
                  "runs": [{
                    "tool": {"driver": {"rules": []}},
                    "results": [{"ruleId": {"nested": true}}]
                  }]
                }
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CodeQlSarifGate.inspect(
                List.of(sarif), 7.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scalar field");
    }

    private static void writeSarif(
            Path path,
            String ruleId,
            String severity,
            String level,
            String message) throws Exception {
        String content = """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"rules": [{
                      "id": "%s",
                      "properties": {"security-severity": "%s"}
                    }]}},
                    "results": [{
                      "ruleId": "%s",
                      "level": "%s",
                      "message": {"text": "%s"}
                    }]
                  }]
                }
                """.formatted(ruleId, severity, ruleId, level, message);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
