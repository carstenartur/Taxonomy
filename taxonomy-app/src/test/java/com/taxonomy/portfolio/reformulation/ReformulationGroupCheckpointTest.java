package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Separate JVMs are required because Git bootstrap state is JVM-global. */
class ReformulationGroupCheckpointTest {
    @TempDir Path directory;

    @Test
    void committedGroupsSurviveRestartAndInactiveRunsCannotReuseThem() throws Exception {
        for (String phase : List.of("write", "read")) {
            Path log = directory.resolve(phase + ".log");
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Xmx768m");
            ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
            command.add("-cp");
            command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
            command.add(ReformulationGroupCheckpointDriver.class.getName());
            command.add(phase); command.add(directory.toAbsolutePath().toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                boolean done = process.waitFor(180, TimeUnit.SECONDS);
                assertThat(done).as("%s phase: %s", phase, Files.readString(log)).isTrue();
                assertThat(process.exitValue()).as(Files.readString(log)).isZero();
                assertThat(Files.readString(log)).contains(phase.equals("write")
                        ? "REFORMULATION_GROUP_WRITE_OK calls=1" : "REFORMULATION_GROUP_RESTART_OK calls=2");
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("Child JVM terminated").isTrue();
                }
            }
        }
    }
}
