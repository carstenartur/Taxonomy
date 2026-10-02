package com.taxonomy.acceptance;

import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Real application process; only the outbound provider exchange is replaced. */
class ReformulationScenarioAcceptanceTest {
    @Test void authenticatedAnalysisDecisionsAdoptionExportsAndRestartPreserveEvidence() throws Exception {
        Path output = Path.of("target/reformulation-scenario-acceptance", "run-" + UUID.randomUUID());
        Files.createDirectories(output);
        launch(output, "write");
        launch(output, "read");
    }
    static void launch(Path output, String mode) throws Exception {
        Path log = output.resolve(mode + "-application.log");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx" + Runtime.getRuntime().maxMemory());
        for (String name : BrowserSession.JVM_PROPERTIES) {
            String value = System.getProperty(name); if (value != null) command.add("-D" + name + "=" + value);
        }
        java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(value -> value.startsWith("-javaagent:") && value.contains("jacoco")).forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ReformulationScenarioApplication.class.getName(), output.toAbsolutePath().toString(), mode));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(240, TimeUnit.SECONDS)).as("Application timeout: %s", log).isTrue();
            String evidence = Files.readString(log);
            assertThat(process.exitValue()).as(evidence).isZero();
            assertThat(evidence).contains("REFORMULATION_SCENARIO_" + mode.toUpperCase(Locale.ROOT) + "_OK");
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
