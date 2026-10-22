package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the production language-selection code without a provider call. */
class ContentLanguageBrowserTest {
    @TempDir Path directory;

    @Test
    void separateLanguagesFollowTheSelectedUserAndPreserveUnknownEvidence() throws Exception {
        Path script = copy("/content-language.cjs", "contract.cjs");
        Path source = copy("/static/js/shared/content-language.js", "language.js");
        run(script.toString(), source.toString());
    }

    @Test
    void reportDialogSendsItsOwnLanguageWithoutRelabelingHistoricalEvidence() throws Exception {
        Path script = Path.of("src/test/js/ux-state-recovery.cjs").toAbsolutePath();
        assertThat(script).isRegularFile();
        run("--test-name-pattern=decision document language|new requirement writes", script.toString());
    }

    private void run(String... arguments) throws Exception {
        Path log = directory.resolve("result.log");
        var command = new java.util.ArrayList<String>();
        command.add(System.getProperty("taxonomy.node.executable", "node"));
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertThat(finished).as(Files.readString(log)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
    }

    private Path copy(String resource, String name) throws Exception {
        Path path = directory.resolve(name);
        try (var input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            Files.copy(input, path);
        }
        return path;
    }
}
