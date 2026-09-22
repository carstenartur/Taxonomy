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
class ReformulationProgressTest {
    @TempDir Path directory;

    @Test
    void inspectPersistedResultsWithoutMutationAndAfterRestart() throws Exception {
        runApplication("write", "REFORMULATION_PROGRESS_HTTP_OK");
        runApplication("read", "REFORMULATION_PROGRESS_RESTART_OK");
    }

    @Test
    void existingResultsAndQuestionsSurviveSchemaIndexUpdate() throws Exception {
        runApplication("write-legacy-indexes", "REFORMULATION_INDEX_UPGRADE_FIXTURE_OK");
        runApplication("read", "REFORMULATION_PROGRESS_RESTART_OK");
    }

    @Test
    void actualApiAndProgressControlsFenceLateReadsAndNeverWrite() throws Exception {
        Path script = resource("/reformulation/progress-contract.cjs", "progress.cjs");
        Path api = resource("/static/js/api/portfolio-api.js", "api.js");
        Path workspace = resource("/static/js/portfolio/requirement-reformulation.js", "workspace.js");
        execute(List.of("node", script.toString(), api.toString(), workspace.toString()), "controls", 30,
                "REFORMULATION_PROGRESS_CONTROLS_OK");
    }

    private void runApplication(String mode, String marker) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ReformulationProgressChecks.class.getName());
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
    private Path resource(String name, String filename) throws Exception {
        Path target = directory.resolve(filename);
        try (var source = getClass().getResourceAsStream(name)) {
            if (source == null) throw new IllegalStateException("Missing test resource: " + name);
            Files.copy(source, target);
        }
        return target;
    }
}
