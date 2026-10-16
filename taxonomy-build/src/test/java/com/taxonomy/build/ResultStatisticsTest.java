package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Offline DOM contract, invoked by the existing Maven/JUnit frontend verification owner. */
class ResultStatisticsTest {
    @TempDir Path directory;

    @Test void reportsScopedCountsWithoutTurningModelProposalsIntoHumanApproval() throws Exception {
        Path script = directory.resolve("contract.cjs");
        try (var input = getClass().getResourceAsStream("/analysis/result-statistics-contract.cjs")) {
            if (input == null) throw new IllegalStateException("Required statistics contract is missing");
            Files.copy(input, script);
        }
        Path source = Files.writeString(directory.resolve("statistics.js"),
                RepositoryResources.applicationResource("static/js/core/taxonomy-result-statistics.js"));
        assertThat(RepositoryResources.applicationResource("static/js/core/taxonomy-analysis-session.js"))
                .contains("/js/core/taxonomy-result-statistics.js");
        Path scoring = Files.writeString(directory.resolve("scoring.js"),
                RepositoryResources.applicationResource("static/js/core/taxonomy-scoring.js"));
        Path log = directory.resolve("statistics.log");
        Process process = new ProcessBuilder(System.getProperty("taxonomy.node.executable"), script.toString(), source.toString(), scoring.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done = process.waitFor(30, TimeUnit.SECONDS);
        if (!done) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertThat(done).as(Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        assertThat(Files.readString(log)).contains("RESULT_STATISTICS_OK");
    }
}
