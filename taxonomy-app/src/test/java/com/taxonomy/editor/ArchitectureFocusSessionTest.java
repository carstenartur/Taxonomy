package com.taxonomy.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the actual focus queue, without a provider or a browser/server fixture. */
class ArchitectureFocusSessionTest {
    @TempDir Path directory;

    @Test
    void investigationHonoursIntentBudgetAndExactRevision() throws Exception {
        Path script = copy("/architecture-focus-session.cjs", "test.cjs");
        Path source = copy("/static/js/architecture-focus.js", "focus.js");
        Path log = directory.resolve("result.log");
        Process process = new ProcessBuilder(System.getProperty("taxonomy.node.executable", "node"),
                script.toString(), source.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertThat(finished).as(Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
    }

    private Path copy(String resource, String name) throws Exception {
        Path destination = directory.resolve(name);
        try (var input = getClass().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing focus regression resource " + resource);
            Files.copy(input, destination);
        }
        return destination;
    }
}
