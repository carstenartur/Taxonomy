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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Security boundary for the configurable CodeQL severity threshold. */
class CodeQlSarifThresholdTest {

    @Test
    void thresholdCannotMoveOutsideTheSarifSeverityDomain(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        writeSarif(sarif, "10.0");

        for (double threshold : List.of(
                -0.1,
                10.1,
                Double.NaN,
                Double.POSITIVE_INFINITY)) {
            assertThatThrownBy(() -> CodeQlSarifGate.inspect(
                    List.of(sarif), threshold))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 0.0 and 10.0");
        }

        CodeQlSarifGate.Inspection upperBoundary =
                CodeQlSarifGate.inspect(List.of(sarif), 10.0);
        assertThat(upperBoundary.blocking())
                .singleElement()
                .satisfies(finding -> assertThat(
                        finding.securitySeverity()).isEqualTo(10.0));
    }

    @Test
    void invalidCliThresholdRemovesStalePassEvidence(
            @TempDir Path root) throws Exception {
        Path sarif = root.resolve("result.sarif");
        Path report = root.resolve("codeql-gate.json");
        writeSarif(sarif, "4.0");
        Files.writeString(report, "stale-pass", StandardCharsets.UTF_8);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exit = CodeQlSarifGate.run(
                new String[]{
                        sarif.toString(),
                        "--threshold", "10.1",
                        "--report", report.toString()},
                root,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("threshold must be between 0.0 and 10.0");
        assertThat(report).doesNotExist();
    }

    private static void writeSarif(Path path, String severity)
            throws Exception {
        Files.writeString(path, """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "CodeQL", "rules": [{
                      "id": "java/rule",
                      "properties": {"security-severity": "%s"}
                    }]}},
                    "results": [{
                      "ruleId": "java/rule",
                      "level": "warning",
                      "message": {"text": "finding"}
                    }]
                  }]
                }
                """.formatted(severity), StandardCharsets.UTF_8);
    }
}
