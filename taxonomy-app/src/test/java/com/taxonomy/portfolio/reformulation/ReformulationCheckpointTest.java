package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ReformulationCheckpointTest {
    @TempDir Path directory;
    @Test void completedStepsAndQuestionsSurviveSeparateApplicationJvms() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("checkpoints").toAbsolutePath() + ";shutdown=true;hsqldb.write_delay=false";
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String phase : List.of("write", "read")) {
            Path log = directory.resolve(phase + ".log");
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx768m", "-cp", cp, ReformulationCheckpointDriver.class.getName(), url, phase, directory.resolve("identity").toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean done = process.waitFor(180, TimeUnit.SECONDS);
            if (!done) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            assertThat(done).as("Checkpoint phase %s: %s", phase, Files.readString(log)).isTrue();
            assertThat(process.exitValue()).as("Checkpoint phase %s: %s", phase, Files.readString(log)).isZero();
            assertThat(Files.readString(log)).contains("REFORMULATION_CHECKPOINT_OK " + phase);
        }
    }
}
