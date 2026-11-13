package com.taxonomy;

import com.taxonomy.support.ProcessTestDiagnostics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("ci-app-shard-recovery")
class ProductDemoProcessRecoveryTest {

    @Test
    void interruptedNormalAndDemoClaimsKeepTheirOwnSourcesOnPublicRetry(@TempDir Path directory) throws Exception {
        Path producerLog = directory.resolve("producer.log");
        Process producer = start(directory, "produce", producerLog);
        try {
            Path ready = directory.resolve("claims-committed");
            await().atMost(Duration.ofMinutes(8)).until(() -> Files.exists(ready) || !producer.isAlive());
            assertThat(ready).withFailMessage(() -> ProcessTestDiagnostics.describe(producerLog)).exists();
            producer.destroyForcibly();
            assertThat(producer.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(producer.exitValue()).isNotZero();
        } finally {
            stop(producer);
        }

        Path recoveryLog = directory.resolve("recovery.log");
        Process recovery = start(directory, "recover", recoveryLog);
        try {
            assertThat(recovery.waitFor(10, TimeUnit.MINUTES))
                    .withFailMessage(() -> ProcessTestDiagnostics.describe(recoveryLog)).isTrue();
            ProcessTestDiagnostics.assertCompleted(recovery.exitValue(), recoveryLog, ProductDemoProcessRecoveryDriver.COMPLETE);
        } finally {
            stop(recovery);
        }
    }

    private static Process start(Path directory, String mode, Path log) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx" + Runtime.getRuntime().maxMemory());
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ProductDemoProcessRecoveryDriver.class.getName());
        command.add(mode);
        command.add(directory.toAbsolutePath().toString());
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        }
    }
}
