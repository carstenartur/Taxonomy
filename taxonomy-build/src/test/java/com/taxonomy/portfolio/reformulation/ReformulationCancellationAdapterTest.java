package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the production API adapter, not an updateReformulation stub. */
class ReformulationCancellationAdapterTest {
    @TempDir Path directory;

    @Test
    void realAdapterAllowsCancellationAndRejectsUnrelatedOperations() throws Exception {
        Path script = copyResource("/reformulation/cancellation-api-contract.cjs", "contract.cjs");
        Path adapter = copyResource("/static/js/api/portfolio-api.js", "portfolio-api.js");
        Path log = directory.resolve("adapter.log");
        Process process = new ProcessBuilder("node", script.toString(), adapter.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done = process.waitFor(30, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        assertThat(done).as(Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        assertThat(Files.readString(log)).contains("REFORMULATION_CANCEL_API_ADAPTER_OK");
    }

    private Path copyResource(String name, String filename) throws Exception {
        Path target = directory.resolve(filename);
        try (var stream = getClass().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Required test resource missing: " + name);
            Files.copy(stream, target);
        }
        return target;
    }
}
