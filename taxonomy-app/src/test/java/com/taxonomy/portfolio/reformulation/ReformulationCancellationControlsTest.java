package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ReformulationCancellationControlsTest {
    @TempDir Path directory;

    @Test void activeRunsCanBeCancelledWithoutDiscardingUnsavedText() throws Exception {
        Path script = resource("/reformulation/cancellation-contract.cjs", "contract.cjs");
        Path source = resource("/static/js/portfolio/requirement-reformulation.js", "workspace.js");
        Path log = directory.resolve("controls.log");
        Process process = new ProcessBuilder("node", script.toString(), source.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done = process.waitFor(30, TimeUnit.SECONDS);
        if (!done) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertThat(done).as(Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        assertThat(Files.readString(log)).contains("REFORMULATION_CANCEL_CONTROLS_OK");
    }

    private Path resource(String name, String filename) throws Exception {
        Path target = directory.resolve(filename);
        try (var stream = getClass().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Required test resource missing: " + name);
            Files.copy(stream, target);
        }
        return target;
    }
}
