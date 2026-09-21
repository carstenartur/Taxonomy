package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReformulationDispatchClaimTest {
    @TempDir Path directory;

    @Test
    void repeatedDispatchCannotFailTheWorkerThatAlreadyOwnsTheRun() throws Exception {
        // Bootstrap and JGit caches are JVM-wide: a second application context in
        // Surefire does not have an independent initial repository checkpoint.
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        // Retain the real coverage agent when this test runs under JaCoCo.
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("jacoco"))
                .forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ReformulationDispatchClaimDriver.class.getName());
        Path log = directory.resolve("dispatch-claim.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done;
        try {
            done = process.waitFor(180, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        String output = Files.readString(log);
        assertThat(done).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("REFORMULATION_DISPATCH_CLAIM_OK");
    }
}
