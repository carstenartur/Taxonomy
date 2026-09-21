package com.taxonomy.portfolio.reformulation;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Fresh processes exercise commit visibility and an actual forced termination, not a mocked restart. */
public final class ReformulationUsageWorkerHarness {
    private ReformulationUsageWorkerHarness() {}

    static void complete(Path directory) throws Exception {
        Files.createDirectories(directory);
        run(directory, "complete", "REFORMULATION_USAGE_WORKER_OK");
    }

    static void killAndRecover(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path log = directory.resolve("produce.log");
        Process producer = start(directory, "produce", log);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (!Files.exists(directory.resolve("usage-kill-ready")) && producer.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(100);
            check(Files.exists(directory.resolve("usage-kill-ready")), "Producer did not reach committed admission: " + Files.readString(log));
            check(Files.exists(directory.resolve("worker-identity")), "Missing immutable run identity");
            producer.destroyForcibly();
            check(producer.waitFor(15, TimeUnit.SECONDS), "Forced producer did not stop");
            check(producer.exitValue() != 0, "Producer exited normally instead of being killed");
            Files.writeString(directory.resolve("producer-exit.txt"), Integer.toString(producer.exitValue()));
        } finally {
            if (producer.isAlive()) { producer.destroyForcibly(); producer.waitFor(15, TimeUnit.SECONDS); }
        }
        run(directory, "recover", "REFORMULATION_USAGE_KILL_RECOVERY_OK");
        System.out.println("REFORMULATION_USAGE_FORCED_PROCESS_RECOVERY_OK");
    }

    private static void run(Path directory, String mode, String marker) throws Exception {
        Path log = directory.resolve(mode + ".log");
        Process process = start(directory, mode, log);
        try {
            check(process.waitFor(120, TimeUnit.SECONDS), "Application timed out: " + mode);
            String output = Files.readString(log);
            check(process.exitValue() == 0, output);
            check(output.contains(marker), "Missing result marker: " + output);
            System.out.println(marker);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(15, TimeUnit.SECONDS); }
        }
    }
    private static Process start(Path directory, String mode, Path log) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ReformulationUsageWorkerChecks.class.getName());
        command.add(directory.toAbsolutePath().toString()); command.add(mode);
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length > 1 && args[1].equals("complete")) complete(Path.of(args[0]));
        else killAndRecover(Path.of(args[0]));
    }
}
