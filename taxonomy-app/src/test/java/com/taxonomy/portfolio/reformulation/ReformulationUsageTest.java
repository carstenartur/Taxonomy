package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same checks as the standalone evidence; fresh JVMs avoid the shared one-time Git bootstrap. */
class ReformulationUsageTest {
    @TempDir Path directory;

    @Test
    void durableStartsLateResultsAndUnknownOutcomesSurviveRestart() throws Exception {
        runApplication("write", "REFORMULATION_USAGE_PERSISTENCE_OK");
        runApplication("read", "REFORMULATION_USAGE_RESTART_OK");
    }

    private void runApplication(String mode, String marker) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ReformulationUsageChecks.class.getName());
        command.add(directory.resolve("application").toString());
        command.add(mode);
        execute(command, mode, 180, marker);
    }
    private void execute(List<String> command, String phase, int seconds, String marker) throws Exception {
        Path log = directory.resolve(phase + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean finished = process.waitFor(seconds, TimeUnit.SECONDS);
            assertTrue(finished, () -> "Timed out: " + phase + " (" + log + ")");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains(marker), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(15, TimeUnit.SECONDS)); }
        }
    }
}
