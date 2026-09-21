package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReformulationEncodingTest {
    @TempDir Path directory;
    @Test void newEncodingNeverReusesLossyHistoryButKeepsItsOwnCache() throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ReformulationEncodingChecks.class.getName());
        Path log = directory.resolve("encoding.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Encoding application timed out");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("REFORMULATION_ENCODING_CACHE_OK"), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(15, TimeUnit.SECONDS)); }
        }
    }
}
