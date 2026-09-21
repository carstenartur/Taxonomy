package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReformulationLocalRecoveryTest {
    @TempDir Path directory;

    @Test void expiredLocalClaimCannotBlockItsSuccessor() throws Exception {
        run("capacity", "REFORMULATION_LOCAL_CAPACITY_OK");
    }
    @Test void gracefulStopPreventsNewWorkWithoutDestroyingRecovery() throws Exception {
        run("shutdown", "REFORMULATION_GRACEFUL_STOP_OK");
    }
    private void run(String mode, String marker) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
        command.add("-cp"); command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ReformulationLocalRecoveryChecks.class.getName()); command.add(mode);
        Path log = directory.resolve(mode + ".log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(180, TimeUnit.SECONDS)).as("Fresh application process completed: " + mode).isTrue();
            String output = Files.readString(log);
            assertThat(child.exitValue()).as(output).isZero();
            assertThat(output).contains(marker);
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue(); }
        }
    }
}
