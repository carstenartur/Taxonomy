package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeQlSarifGateTest {

    @Test
    void blocksExtensionSecuritySeverityAndExplicitErrorInStableOrder(
            @TempDir Path root) throws Exception {
        Path javaReport = root.resolve("a-java.sarif");
        Path scriptReport = root.resolve("z-javascript.sarif");
        writeExtensionSarif(
                javaReport,
                "java/high",
                "8.1",
                null,
                "warning",
                "high severity");
        writeDriverSarif(
                scriptReport,
                "js/error",
                "0.0",
                "error",
                "error level");

        CodeQlSarifGate.Inspection inspection = CodeQlSarifGate.inspect(
                List.of(scriptReport, javaReport), 7.0);

        assertThat(inspection.resultCount()).isEqualTo(2);
        assertThat(inspection.sarifFiles())
                .containsExactly(
                        javaReport.toAbsolutePath().normalize(),
                        scriptReport.toAbsolutePath().normalize());
        assertThat(inspection.blocking())
                .extracting(CodeQlSarifGate.Finding::ruleId)
                .containsExactly("java/high", "js/error");
        assertThat(inspection.blocking().get(0).securitySeverity())
                .isEqualTo(8.1);
        assertThat(inspection.blocking().get(1).securitySeverity())
                .isZero();
    }

    @Test
    void realCodeQlExtensionShapeResolvesReferencedRuleMetadata(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("codeql.sarif");
        writeExtensionSarif(
                sarif,
                "java/sql-injection",
                "8.8",
                null,
                "error",
                "path reaches SQL execution");

        CodeQlSarifGate.Inspection inspection = CodeQlSarifGate.inspect(
                List.of(sarif), 7.0);

        assertThat(inspection.blocking()).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("java/sql-injection");
                    assertThat(finding.securitySeverity()).isEqualTo(8.8);
                    assertThat(finding.level()).isEqualTo("warning");
                });
    }

    @Test
    void missingResultLevelPreservesTheExplicitLevelPolicy(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("default-error.sarif");
        writeExtensionSarif(
                sarif,
                "java/medium",
                "6.1",
                null,
                "error",
                "rule default is intentionally not a result override");

        CodeQlSarifGate.Inspection inspection = CodeQlSarifGate.inspect(
                List.of(sarif), 7.0);

        assertThat(inspection.resultCount()).isEqualTo(1);
        assertThat(inspection.blocking()).isEmpty();
    }

    @Test
    void noFindingsInAValidRunPasses(@TempDir Path root) throws Exception {
        Path sarif = root.resolve("empty.sarif");
        Files.writeString(sarif, """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "CodeQL", "rules": []}}
                  }]
                }
                """, StandardCharsets.UTF_8);

        CodeQlSarifGate.Inspection inspection = CodeQlSarifGate.inspect(
                List.of(sarif), 7.0);

        assertThat(inspection.resultCount()).isZero();
        assertThat(inspection.blocking()).isEmpty();
    }

    @Test
    void everySuppliedInputMustExistEvenWhenAnotherReportIsValid(
            @TempDir Path root) throws Exception {
        Path valid = root.resolve("valid.sarif");
        Path missing = root.resolve("missing.sarif");
        writeDriverSarif(
                valid, "java/low", "2.0", "warning", "valid report");

        assertThatThrownBy(() -> CodeQlSarifGate.inspect(
                List.of(valid, missing), 7.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "SARIF input is missing or not a regular file")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void lowSeverityWarningPassesAndWritesDeterministicReport(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path report = root.resolve("evidence/codeql-gate.json");
        writeDriverSarif(
                sarif, "java/low", "6.9", "warning", "reviewed warning");
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
                .containsEntry("threshold", new BigDecimal("7.0"))
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
        writeDriverSarif(
                sarif, "java/high", "9.0", "warning", "unsafe flow");
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
    void missingMalformedOrInvalidArgumentsRemoveStaleReport(
            @TempDir Path root) throws Exception {
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
                .contains("SARIF input is missing or not a regular file");
        assertThat(report).doesNotExist();

        Path malformed = root.resolve("malformed.sarif");
        Files.writeString(malformed, """
                {"version": "2.1.0", "runs": {}}
                """, StandardCharsets.UTF_8);
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

        Files.writeString(report, "stale-threshold", StandardCharsets.UTF_8);
        errors.reset();
        int invalidThreshold = CodeQlSarifGate.run(
                new String[]{
                        malformed.toString(),
                        "--report", report.toString(),
                        "--threshold", "not-a-number"},
                root,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(invalidThreshold).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("CodeQL gate failed");
        assertThat(report).doesNotExist();
    }

    @Test
    void malformedStructureAndSeverityFailClosed(@TempDir Path root)
            throws Exception {
        Path sarif = root.resolve("result.sarif");

        Files.writeString(sarif, "{\"runs\": []}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("SARIF version is required");

        Files.writeString(sarif, """
                {"version": "2.1.0", "runs": []}
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("at least one run");

        Files.writeString(sarif, """
                {"version": "2.1.0", "runs": [{}]}
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("SARIF tool is required");

        Files.writeString(sarif, """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "CodeQL", "rules": []}},
                    "results": [{
                      "ruleId": "missing/rule",
                      "message": {"text": "message"}
                    }]
                  }]
                }
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("without matching tool metadata");

        writeDriverSarif(
                sarif, "java/rule", "not-a-number", "warning", "message");
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("security-severity must be numeric");

        writeDriverSarif(
                sarif, "java/rule", "NaN", "warning", "message");
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("security-severity must be finite");

        writeDriverSarif(
                sarif, "java/rule", "10.1", "warning", "message");
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("between 0.0 and 10.0");

        writeDriverSarif(
                sarif, "java/rule", "4.0", "fatal", "message");
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("level is unsupported");
    }

    @Test
    void malformedRuleIdsMessagesAndDuplicateMetadataFailClosed(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");

        Files.writeString(sarif, """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "CodeQL", "rules": [{
                      "id": "java/rule",
                      "properties": {"security-severity": "4.0"}
                    }]}},
                    "results": [{
                      "ruleId": {"nested": true},
                      "message": {"text": "message"}
                    }]
                  }]
                }
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("ruleId must be a string");

        Files.writeString(sarif, """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {
                      "driver": {"name": "CodeQL", "rules": [{"id": "same"}]},
                      "extensions": [{"name": "queries", "rules": [{"id": "same"}] }]
                    }
                  }]
                }
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("duplicate rule id 'same'");

        Files.writeString(sarif, """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "CodeQL", "rules": [{"id": "java/rule"}]}},
                    "results": [{"ruleId": "java/rule", "message": {}}]
                  }]
                }
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspect(sarif))
                .hasMessageContaining("must contain text or markdown");
    }

    @Test
    void publicToolingCommandDelegatesToTheSameGate(@TempDir Path root)
            throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path report = root.resolve("codeql-gate.json");
        writeDriverSarif(sarif, "java/low", "4.0", "warning", "safe");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = TaxonomyTooling.run(
                new String[]{
                        "check-codeql-sarif",
                        sarif.toString(),
                        "--report", report.toString()},
                root,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("CodeQL results: 1; blocking: 0");
        assertThat(report).isRegularFile();
    }

    private static CodeQlSarifGate.Inspection inspect(Path sarif)
            throws Exception {
        return CodeQlSarifGate.inspect(List.of(sarif), 7.0);
    }

    private static void writeDriverSarif(
            Path path,
            String ruleId,
            String severity,
            String level,
            String message) throws Exception {
        String content = """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "CodeQL", "rules": [{
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
                """.formatted(
                ruleId, severity, ruleId, level, message);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void writeExtensionSarif(
            Path path,
            String ruleId,
            String severity,
            String explicitLevel,
            String defaultLevel,
            String message) throws Exception {
        String level = explicitLevel == null
                ? ""
                : "\n                      \"level\": \""
                        + explicitLevel + "\",";
        String content = """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {
                      "driver": {"name": "CodeQL", "rules": []},
                      "extensions": [
                        {"name": "codeql-action/pr-diff-range"},
                        {
                          "name": "codeql/java-queries",
                          "rules": [{
                            "id": "%s",
                            "defaultConfiguration": {"level": "%s"},
                            "properties": {"security-severity": "%s"}
                          }]
                        }
                      ]
                    },
                    "results": [{
                      "ruleId": "%s",
                      "rule": {
                        "id": "%s",
                        "index": 0,
                        "toolComponent": {"index": 1}
                      },%s
                      "message": {"text": "%s"}
                    }]
                  }]
                }
                """.formatted(
                ruleId,
                defaultLevel,
                severity,
                ruleId,
                ruleId,
                level,
                message);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
