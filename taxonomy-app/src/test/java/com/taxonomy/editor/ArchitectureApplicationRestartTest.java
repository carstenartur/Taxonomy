package com.taxonomy.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureApplicationRestartTest {
    @TempDir Path directory;

    @Test
    void workspaceAndCompleteUndoRedoJournalSurviveTwoFullApplicationRestarts() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("architecture").toAbsolutePath()
                + ";shutdown=true;hsqldb.write_delay=false";
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String phase : List.of("write", "undo", "redo")) {
            Path log = directory.resolve(phase + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx768m", "-cp", classpath, ArchitectureApplicationRestartDriver.class.getName(), url, phase)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(150, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertThat(finished).as("Application process finished: %s\n%s", phase, Files.readString(log)).isTrue();
            assertThat(process.exitValue()).as("Application restart: %s\n%s", phase, Files.readString(log)).isZero();
            assertThat(Files.readString(log)).contains("ARCHITECTURE_RESTART_OK " + phase);
        }
    }
}
