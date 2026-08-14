package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
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
    void blocksHighSecuritySeverityAndErrorLevelFindings(@TempDir Path root)
            throws Exception {
        Path report = root.resolve("analysis.sarif");
        Files.writeString(report, sarif("8.4", "warning", "high finding")
                .replace("__SECOND__", result("plain-error", "error", "error finding")),
                StandardCharsets.UTF_8);

        CodeQlSarifGate.Result result = CodeQlSarifGate.inspect(
                List.of(report), 7.0);

        assertThat(result.resultCount()).isEqualTo(2);
        assertThat(result.blocking())
                .extracting(CodeQlSarifGate.Finding::ruleId)
                .containsExactly("security-high", "plain-error");
        assertThat(result.successful()).isFalse();
    }

    @Test
    void passesLowSeverityWarningsAndWritesStableEvidence(@TempDir Path root)
            throws Exception {
        Path sarif = root.resolve("analysis.sarif");
        Files.writeString(sarif,
                sarif("4.2", "warning", "low finding")
                        .replace("__SECOND__", ""),
                StandardCharsets.UTF_8);
        CodeQlSarifGate.Result result = CodeQlSarifGate.inspect(
                List.of(sarif), 7.0);
        Path evidence = root.resolve("target/gate.json");

        CodeQlSarifGate.writeReport(evidence, result);

        assertThat(result.successful()).isTrue();
        assertThat(Files.readString(evidence))
                .contains("\"status\": \"PASS\"")
                .contains("\"resultCount\": 1")
                .contains("\"threshold\": 7.0")
                .contains("\"blocking\": []");
    }

    @Test
    void missingReportsFailClosed(@TempDir Path root) {
        assertThatThrownBy(() -> CodeQlSarifGate.inspect(
                List.of(root.resolve("missing.sarif")), 7.0))
                .hasMessageContaining("no SARIF files supplied");
    }

    @Test
    void cliReturnsOneAndStillWritesFailureEvidence(@TempDir Path root)
            throws Exception {
        Path sarif = root.resolve("analysis.sarif");
        Files.writeString(sarif,
                sarif("9.1", "warning", "critical path")
                        .replace("__SECOND__", ""),
                StandardCharsets.UTF_8);
        Path evidence = root.resolve("gate.json");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = TaxonomyTooling.run(
                new String[]{
                        "check-codeql-sarif",
                        "--report", evidence.toString(),
                        "--threshold", "7.0",
                        "--", sarif.toString()},
                Map.of(),
                root,
                new PrintStream(output),
                new PrintStream(errors));

        assertThat(exitCode).isEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("CodeQL results: 1; blocking: 1");
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("security-high")
                .contains("critical path");
        assertThat(Files.readString(evidence))
                .contains("\"status\": \"FAIL\"");
    }

    private static String sarif(
            String severity,
            String level,
            String message) {
        return """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"rules": [{
                      "id": "security-high",
                      "properties": {"security-severity": "%s"}
                    }, {
                      "id": "plain-error",
                      "properties": {}
                    }]}},
                    "results": [
                      %s
                      __SECOND__
                    ]
                  }]
                }
                """.formatted(
                        severity,
                        result("security-high", level, message));
    }

    private static String result(
            String ruleId,
            String level,
            String message) {
        String prefix = "security-high".equals(ruleId) ? "" : ",";
        return prefix + """
                {
                  "ruleId": "%s",
                  "level": "%s",
                  "message": {"text": "%s"}
                }
                """.formatted(ruleId, level, message);
    }
}
