package com.taxonomy.portfolio.reformulation;

import org.springframework.test.context.TestContextManager;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Gives the post-adoption reanalysis its own real heap, without mocking resource admission. */
public final class AdoptedLineageReanalysisProcess {
    private static final String COMPLETED = "ADOPTED_LINEAGE_FRESH_JVM_OK";

    private AdoptedLineageReanalysisProcess() { }

    static void verify() throws Exception {
        Path directory = Path.of("target/surefire-reports/adopted-lineage");
        Files.createDirectories(directory);
        Path log = directory.resolve("application-process.log");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // Preserve the enclosing test JVM's heap ceiling, rather than enlarging the budget.
        long maximumHeap = Runtime.getRuntime().maxMemory();
        command.add("-Xmx" + maximumHeap);
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(AdoptedLineageReanalysisProcess.class.getName());
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.add(Long.toString(maximumHeap));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(300, TimeUnit.SECONDS))
                    .as("Fresh lineage application must finish; evidence: %s", log).isTrue();
            String output = Files.readString(log);
            assertThat(child.exitValue()).as(output).isZero();
            assertThat(output).contains(COMPLETED);
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 2) throw new IllegalArgumentException("Expected parent PID and heap ceiling");
        assertThat(ProcessHandle.current().pid()).isNotEqualTo(Long.parseLong(args[0]));
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(Long.parseLong(args[1]));
        var scenario = new AdoptedLineageRealReanalysisTest.Scenario();
        var context = new TestContextManager(AdoptedLineageRealReanalysisTest.Scenario.class);
        var method = AdoptedLineageRealReanalysisTest.Scenario.class.getDeclaredMethod("verifyScenario");
        Throwable failure = null;
        boolean methodStarted = false, executionStarted = false;
        try {
            context.beforeTestClass();
            context.prepareTestInstance(scenario);
            context.beforeTestMethod(scenario, method);
            methodStarted = true;
            context.beforeTestExecution(scenario, method);
            executionStarted = true;
            System.out.println("Lineage scenario heap: "
                    + com.taxonomy.analysis.service.AnalysisMemoryGuard.heapSample());
            // Keep real persistence, adoption, analysis, ancestry and checkpoint assertions.
            // Only the outbound LLM transport is replaced inside the scenario.
            scenario.verifyScenario();
        } catch (Throwable problem) {
            failure = problem;
        } finally {
            final Throwable scenarioFailure = failure;
            if (executionStarted) failure = cleanup(failure,
                    () -> context.afterTestExecution(scenario, method, scenarioFailure));
            if (methodStarted) failure = cleanup(failure,
                    () -> context.afterTestMethod(scenario, method, scenarioFailure));
            failure = cleanup(failure, context::afterTestClass);
        }
        if (failure != null) throw failure;
        System.out.println(COMPLETED);
    }

    private static Throwable cleanup(Throwable failure, Cleanup cleanup) {
        try { cleanup.run(); }
        catch (Throwable problem) {
            if (failure == null) return problem;
            if (problem != failure) failure.addSuppressed(problem);
        }
        return failure;
    }

    @FunctionalInterface
    private interface Cleanup { void run() throws Exception; }
}
