package com.taxonomy;

import com.taxonomy.acceptance.ScenarioRelationQuality;
import com.taxonomy.acceptance.ScenarioCallJournal;
import com.taxonomy.support.ProcessTestDiagnostics;
import com.taxonomy.testsupport.BrowserSession;
import org.springframework.test.context.TestContextManager;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Gives the complete scenario its own real heap, without mocking resource admission. */
public final class ScenarioAcceptanceProcess {
    private static final String COMPLETED = "SCENARIO_FRESH_JVM_ACCEPTANCE_OK";
    private static final tools.jackson.databind.ObjectMapper EVIDENCE_JSON = new tools.jackson.databind.ObjectMapper();
    // A CI-heap measurement completed 80 continuations in ten minutes while only
    // 51/105 authored claims were verified. Real HSQL persistence and full saved
    // evidence dominate this workload even with local provider playback. Allow
    // forty minutes for both complete passes and export/reopen, within the
    // dedicated workflow's sixty-minute overall budget. This is a measured
    // workload allowance, not a per-cycle guarantee; the 128-cycle/pass cap,
    // 24-call provider budget, inherited heap and quality gates stay unchanged.
    static final java.time.Duration SCENARIO_TIMEOUT = java.time.Duration.ofMinutes(40);

    private ScenarioAcceptanceProcess() { }

    static void verify() throws Exception {
        Path directory = Path.of(System.getProperty("scenario.output", "target/scenario-acceptance"));
        Files.createDirectories(directory);
        clearEvidence(directory);
        String runId = java.util.UUID.randomUUID().toString();
        var identity = runIdentity(com.taxonomy.acceptance.ScenarioLlmPlayback.flood().fixture(), runId);
        Path log = directory.resolve("application-process.log");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // Preserve the enclosing test JVM's heap ceiling, rather than enlarging the budget.
        long maximumHeap = Runtime.getRuntime().maxMemory();
        command.add("-Xmx" + maximumHeap);
        command.add("-Dscenario.runId=" + runId);
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .forEach(command::add);
        // The scenario owns its application configuration. Forward only explicit non-secret
        // UI test flags on argv; environment and working directory remain inherited.
        System.getProperties().stringPropertyNames().stream().sorted()
                .filter(ScenarioAcceptanceProcess::testProperty)
                .forEach(key -> command.add("-D" + key + "=" + System.getProperty(key)));
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ScenarioAcceptanceProcess.class.getName());
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.add(Long.toString(maximumHeap));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(SCENARIO_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as(() -> "Fresh scenario application must finish; " + ProcessTestDiagnostics.describe(log)).isTrue();
            ProcessTestDiagnostics.assertCompleted(child.exitValue(), log, COMPLETED);
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
            }
            // Assemble the compatibility JSON artifact exactly once, including
            // when the child failed or was killed between cycle checkpoints.
            recoverEvidence(directory, identity);
        }
    }

    private static boolean testProperty(String key) {
        return key.equals("taxonomy.plugins.directory") || key.equals("generateScreenshots") || key.equals("java.awt.headless") || key.equals("scenario.output")
                || BrowserSession.JVM_PROPERTIES.contains(key);
    }

    static void recoverEvidence(Path directory, tools.jackson.databind.JsonNode expected) throws Exception {
        Exception failure = null;
        try { materializeCheckpoint(directory, expected); }
        catch (Exception invalid) { failure = invalid; }
        try {
            Path journal = directory.resolve("llm-calls.jsonl");
            if (Files.exists(journal)) ScenarioCallJournal.assemble(journal, directory.resolve("llm-calls.json"));
        } catch (Exception invalid) {
            if (failure == null) failure = invalid; else failure.addSuppressed(invalid);
        }
        if (failure != null) throw failure;
    }

    static tools.jackson.databind.JsonNode runIdentity(tools.jackson.databind.JsonNode fixture, String runId) {
        return EVIDENCE_JSON.createObjectNode().put("runId", runId).put("scenario", fixture.path("id").asText())
                .put("fixtureSha256", com.taxonomy.acceptance.ScenarioJsonFingerprint.sha256(fixture))
                .put("sourceRevision", System.getenv().getOrDefault("GITHUB_SHA", "local-working-tree"))
                .put("ciRun", System.getenv().getOrDefault("GITHUB_RUN_ID", "local"));
    }

    static void materializeCheckpoint(Path directory, tools.jackson.databind.JsonNode expected) throws Exception {
        Path checkpoint = directory.resolve("checkpoint.json");
        if (!Files.exists(checkpoint)) return;
        var saved = EVIDENCE_JSON.readTree(checkpoint.toFile());
        var snapshot = saved.path("snapshot");
        var run = saved.path("run");
        String snapshotId = snapshot.at("/summary/id").asText();
        if (saved.path("schemaVersion").asInt() != 1 || !snapshot.isObject() || !run.isObject() || snapshotId.isBlank()
                || !snapshotId.equals(run.path("snapshotId").asText())
                || !com.taxonomy.acceptance.ScenarioJsonFingerprint.sha256(snapshot).equals(saved.path("snapshotSha256").asText())
                || !run.path("fixtureSha256").asText().matches("[0-9a-f]{64}")
                || run.path("sourceRevision").asText().isBlank()
                || !run.path("projectId").isIntegralNumber() || !run.path("requirementId").isIntegralNumber()
                || !run.path("projectId").equals(snapshot.at("/summary/projectId"))
                || !run.path("requirementId").equals(snapshot.at("/summary/requirementId")))
            throw new IllegalArgumentException("Checkpoint does not bind a complete snapshot to its original run identity");
        for (String field : java.util.List.of("runId", "scenario", "fixtureSha256", "sourceRevision", "ciRun")) {
            if (!expected.path(field).isString() || expected.path(field).asText().isBlank()
                    || !expected.path(field).equals(run.path(field)))
                throw new IllegalArgumentException("Checkpoint differs from the parent-pinned run identity: " + field);
        }
        saveArtifact(directory.resolve("snapshot.json"), snapshot);
        saveArtifact(directory.resolve("run.json"), run);
    }

    static void saveArtifact(Path target, tools.jackson.databind.JsonNode value) throws Exception {
        Path temporary = Files.createTempFile(target.getParent(), ".scenario-", ".json");
        try {
            EVIDENCE_JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static void clearEvidence(Path directory) throws Exception {
        ScenarioRelationQuality.clearReports(directory);
        for (String report : java.util.List.of("classification-quality.json", "architecture-quality.json",
                "integration-quality.json", "prompt-evidence-quality.json", "quality.json", "run.json", "snapshot.json",
                "checkpoint.json", "continuation-history.json", "playback-coverage.json", "llm-calls.json", "llm-calls.jsonl"))
            Files.deleteIfExists(directory.resolve(report));
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 2) throw new IllegalArgumentException("Expected parent PID and heap ceiling");
        assertThat(ProcessHandle.current().pid()).isNotEqualTo(Long.parseLong(args[0]));
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(Long.parseLong(args[1]));
        var scenario = new ScenarioArchitectureAcceptanceTest.Scenario();
        var context = new TestContextManager(ScenarioArchitectureAcceptanceTest.Scenario.class);
        var method = ScenarioArchitectureAcceptanceTest.class.getDeclaredMethod("verifyScenario");
        Throwable failure = null;
        boolean methodStarted = false, executionStarted = false;
        try {
            context.beforeTestClass();
            context.prepareTestInstance(scenario);
            context.beforeTestMethod(scenario, method);
            methodStarted = true;
            context.beforeTestExecution(scenario, method);
            executionStarted = true;
            System.out.println("Scenario heap: "
                    + com.taxonomy.analysis.service.AnalysisMemoryGuard.heapSample());
            // This is the unchanged complete scenario, including its real HTTP, persistence,
            // two-pass analysis, export/reopen, mutation and optional browser assertions.
            scenario.verifyScenario();
            ScenarioRelationQuality.verify(scenario.output);
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
