package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic occupied-geometry, saved-reason and print DOM regressions, owned by Maven. */
class DecisionDiagramReadingTest {
    @TempDir Path directory;

    @Test void decisionDiagramKeepsReasonsGroupsAndPrintedPagesReadable() throws Exception {
        Path script = directory.resolve("decision-diagram-contract.cjs");
        try (var input = getClass().getResourceAsStream("/analysis/decision-diagram-contract.cjs")) {
            if (input == null) throw new IllegalStateException("Missing decision diagram regression");
            Files.copy(input, script);
        }
        Path source = Files.writeString(directory.resolve("views.js"),
                RepositoryResources.applicationResource("static/js/core/taxonomy-views.js"));
        Path log = directory.resolve("diagram.log");
        Process process = new ProcessBuilder(System.getProperty("taxonomy.node.executable"), script.toString(), source.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done = process.waitFor(30, TimeUnit.SECONDS);
        if (!done) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertThat(done).as(Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        assertThat(Files.readString(log)).contains("DECISION_DIAGRAM_READING_OK");
    }
}
