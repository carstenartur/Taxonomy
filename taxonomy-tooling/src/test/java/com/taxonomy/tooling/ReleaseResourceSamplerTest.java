package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseResourceSamplerTest {
    private static final Map<String, String> ENVIRONMENT =
            Map.of("GITHUB_RUN_ID", "123", "GITHUB_RUN_ATTEMPT", "2");

    @Test
    void recordsSourceIdentityAndMarksMissingSamplesIncomplete(@TempDir Path temporary) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        Path output = temporary.resolve("resources");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(console);
        Map<String, String> environment = Map.of("GITHUB_RUN_ID", "123", "GITHUB_RUN_ATTEMPT", "2");

        assertThat(TaxonomyTooling.run(new String[]{"release-resources", "--mode", "init",
                "--root", root.toString(), "--output", output.toString(), "--release-version", "1.5.3"},
                environment, root, log, log)).as(console.toString()).isZero();
        Map<String, Object> identity = FlatJson.parseObject(Files.readString(output.resolve("identity.json")));
        assertThat(identity.get("source_sha")).isEqualTo(GitSupport.require(root, "rev-parse", "HEAD").strip());
        assertThat(identity.get("run_id").toString()).isEqualTo("123");
        assertThat(identity.get("run_attempt").toString()).isEqualTo("2");
        assertThat(identity.get("release_version")).isEqualTo("1.5.3");

        Files.writeString(output.resolve("unrelated-secret.txt"), "DO_NOT_ARCHIVE_THIS");
        assertThat(TaxonomyTooling.run(new String[]{"release-resources", "--mode", "finalize",
                "--output", output.toString()}, environment, root, log, log)).isZero();
        String manifest = Files.readString(output.resolve("manifest.json"));
        assertThat(FlatJson.parseObject(manifest).get("complete")).isEqualTo(false);
        assertThat(manifest).doesNotContain("unrelated-secret", "DO_NOT_ARCHIVE_THIS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INT", "TERM"})
    void stopsOnlyItsOwnMonitorAndFinalizesNumericEvidence(String signal,
            @TempDir Path temporary) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        Path output = temporary.resolve("resources");
        assertThat(command(root, output, "init", "--release-version", "1.5.3")).isZero();
        String sentinel = "SECRET_MUST_NOT_APPEAR_7b212";
        Path visibleWorkloadPid = temporary.resolve("workload.pid");
        Process workload = new ProcessBuilder("bash", "-c",
                "read -r stat < /proc/self/stat; printf '%s\\n' \"${stat%% *}\" > \"$1\"; exec sleep 60",
                sentinel, visibleWorkloadPid.toString()).start();
        ProcessBuilder monitorBuilder = new ProcessBuilder(javaCommand(root, output, "sample"))
                .directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("monitor.log").toFile());
        monitorBuilder.environment().putAll(ENVIRONMENT);
        monitorBuilder.environment().put("DIAGNOSTIC_TEST_SECRET", sentinel);
        Process monitor = monitorBuilder.start();
        try {
            Path metrics = output.resolve("metrics.jsonl");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((!Files.exists(metrics) || Files.size(metrics) == 0)
                    && monitor.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(monitor.isAlive()).as(Files.readString(temporary.resolve("monitor.log"))).isTrue();
            assertThat(Files.size(metrics)).isPositive();
            Map<String, Object> first = FlatJson.parseObject(Files.readAllLines(metrics).getFirst());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> processes = (List<Map<String, Object>>) first.get("processes");
            assertThat(processes).anySatisfy(row ->
                    assertThat(row.get("pid").toString()).isEqualTo(Files.readString(visibleWorkloadPid).strip()));
            assertThat(processes).anySatisfy(row ->
                    assertThat(row.get("pid").toString()).isEqualTo(first.get("monitor_pid").toString()));

            Process kill = new ProcessBuilder("kill", "-" + signal, Long.toString(monitor.pid())).start();
            assertThat(kill.waitFor(2, TimeUnit.SECONDS)).isTrue();
            assertThat(kill.exitValue()).isZero();
            assertThat(monitor.waitFor(3, TimeUnit.SECONDS)).as("monitor handles " + signal).isTrue();
            assertThat(workload.isAlive()).as("observed workload must not be killed").isTrue();
            assertThat(command(root, output, "finalize")).isZero();
            String manifestText = Files.readString(output.resolve("manifest.json"));
            Map<String, Object> manifest = FlatJson.parseObject(manifestText);
            assertThat(manifest.get("complete")).isEqualTo(true);
            assertThat(manifest.get("samples").toString()).isEqualTo("1");
            @SuppressWarnings("unchecked")
            Map<String, Object> evidence = (Map<String, Object>) manifest.get("metrics");
            assertThat(evidence.get("sha256")).isEqualTo(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(metrics))));
            for (String name : List.of("identity.json", "metrics.jsonl", "manifest.json")) {
                assertThat(Files.readString(output.resolve(name)))
                        .doesNotContain(sentinel, "command", "environment", "sleep");
            }
            assertThat(Files.readString(temporary.resolve("monitor.log"))).doesNotContain(sentinel);
        } finally {
            monitor.destroyForcibly();
            workload.destroyForcibly();
            monitor.waitFor(5, TimeUnit.SECONDS);
            workload.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsInvalidIdentityWithoutEchoingIt(@TempDir Path temporary) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(bytes);
        assertThat(TaxonomyTooling.run(new String[]{"release-resources", "--mode", "init",
                "--output", temporary.resolve("resources").toString(), "--release-version", "SECRET-VERSION"},
                ENVIRONMENT, root, log, log)).isNotZero();
        assertThat(bytes.toString()).doesNotContain("SECRET-VERSION").contains("diagnostics");
        assertThat(Files.exists(temporary.resolve("resources/identity.json"))).isFalse();
    }

    @Test
    void interruptedMonitorDoesNotPretendToHaveCompleteEvidence(@TempDir Path temporary) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        Path output = temporary.resolve("resources");
        assertThat(command(root, output, "init", "--release-version", "1.5.3")).isZero();
        Files.writeString(output.resolve("metrics.jsonl"), "{\"sample\":0}\n");
        assertThat(command(root, output, "finalize")).isZero();
        Map<String, Object> manifest = FlatJson.parseObject(Files.readString(output.resolve("manifest.json")));
        assertThat(manifest.get("complete")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) manifest.get("metrics");
        assertThat(metrics.get("sha256")).isNull();
    }

    @Test
    void dropsProcessNamesAndKeepsMissingCountersUnavailable(@TempDir Path temporary) throws Exception {
        String[] fields = new String[22];
        java.util.Arrays.fill(fields, "0");
        fields[0] = "S";
        fields[1] = "12";
        fields[11] = "30";
        fields[12] = "40";
        fields[19] = "50";
        fields[21] = "60";
        Map<String, Long> row = ReleaseResourceSampler.processStat(
                "99 (SECRET (odd) name\n) " + String.join(" ", fields));
        assertThat(row).containsEntry("pid", 99L).containsEntry("ppid", 12L)
                .containsEntry("user_ticks", 30L).containsEntry("system_ticks", 40L)
                .containsEntry("start_ticks", 50L).containsEntry("rss_pages", 60L);
        assertThat(FlatJson.pretty(row)).doesNotContain("SECRET", "name");
        Path memory = temporary.resolve("memory");
        Files.writeString(memory, "MemAvailable: 123 kB\nMemTotal: invalid\nSwapFree: -1\nSECRET: 444\n");
        assertThat(ReleaseResourceSampler.counters(memory,
                List.of("MemAvailable", "MemTotal", "SwapFree", "SwapTotal"), true))
                .containsEntry("MemAvailable", 123L).containsEntry("MemTotal", null)
                .containsEntry("SwapFree", null).containsEntry("SwapTotal", null).doesNotContainKey("SECRET");
        Files.delete(memory);
        assertThat(ReleaseResourceSampler.counters(memory, List.of("MemTotal"), true))
                .containsEntry("MemTotal", null);
    }

    @Test
    void boundsProcessRowsAndUsesCurrentCgroupOnly(@TempDir Path temporary) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        Path proc = Files.createDirectory(temporary.resolve("proc"));
        Path self = Files.createDirectory(proc.resolve("self"));
        Path groups = Files.createDirectory(temporary.resolve("cgroup"));
        Path current = Files.createDirectory(groups.resolve("current"));
        Files.writeString(self.resolve("cgroup"), "0::/current\n");
        Files.writeString(current.resolve("memory.current"), "4096\n");
        Files.writeString(current.resolve("memory.max"), "max\n");
        Files.writeString(current.resolve("memory.events"), "oom 2\noom_kill 1\nsecret 123\n");
        String[] fields = new String[22];
        java.util.Arrays.fill(fields, "0");
        fields[0] = "S";
        for (int pid = 1; pid <= 514; pid++) {
            Path entry = Files.createDirectory(proc.resolve(Integer.toString(pid)));
            fields[21] = Integer.toString(pid);
            Files.writeString(entry.resolve("stat"), pid + " (secret) " + String.join(" ", fields));
        }
        Map<String, Object> snapshot = ReleaseResourceSampler.snapshot(root, proc, groups);
        assertThat(snapshot).containsEntry("processes_observed", 514).containsEntry("processes_omitted", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Long>> processes = (List<Map<String, Long>>) snapshot.get("processes");
        assertThat(processes).hasSize(512);
        assertThat(processes.getFirst()).containsEntry("pid", 514L);
        assertThat(processes.getLast()).containsEntry("pid", 3L);
        @SuppressWarnings("unchecked")
        Map<String, Object> cgroup = (Map<String, Object>) snapshot.get("current_cgroup");
        assertThat(cgroup).containsEntry("available", true).containsEntry("memory_current_bytes", 4096L)
                .containsEntry("memory_max_bytes", null).containsEntry("memory_max_unlimited", true);
        assertThat(FlatJson.pretty(snapshot)).doesNotContain("secret", "current/");

        Files.writeString(self.resolve("cgroup"), "0::/../../secret\n");
        Map<String, Object> unsupported = ReleaseResourceSampler.snapshot(root, proc, groups);
        @SuppressWarnings("unchecked")
        Map<String, Object> unavailable = (Map<String, Object>) unsupported.get("current_cgroup");
        assertThat(unavailable).containsEntry("available", false).containsEntry("memory_current_bytes", null);
    }

    @Test
    void timesOutAndReapsOnlyItsOwnReadOnlyChild(@TempDir Path temporary) throws Exception {
        Path childPid = temporary.resolve("child.pid");
        long started = System.nanoTime();
        assertThat(ReleaseResourceSampler.boundedCommand(temporary, "bash", "-c",
                "echo $$ > child.pid; exec sleep 60")).isNull();
        assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(3));
        long pid = Long.parseLong(Files.readString(childPid).strip());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void workflowUsesIndependentObservationAndFixedNumericArtifactPaths() throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        String workflow = Files.readString(root.resolve(".github/workflows/deploy-release.yml"));
        String stageName = "      - name: Build and stage release, then prepare next development version";
        int start = workflow.indexOf(stageName);
        String stage = workflow.substring(start, workflow.indexOf("\n      - ", start + stageName.length()));
        assertThat(stage).contains("run: \"$RUNNER_TEMP/release.sh\"")
                .doesNotContain("continue-on-error", "background:", "tee ", "trap ");
        assertThat(workflow).contains("background: true", "cancel: release_resources",
                "release-resources --mode init", "release-resources --mode sample",
                "release-resources --mode finalize", "retention-days: 7");
        assertThat(workflow.indexOf("cancel: release_resources")).isGreaterThan(start)
                .isLessThan(workflow.indexOf("release-resources --mode finalize"));
    }

    @Test
    void deliveryGuardRejectsWrappedBuildMissingCancelAndUnsafeArchives(@TempDir Path temporary) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        Path log = temporary.resolve("guard.log");
        String script = """
                import importlib.util
                from pathlib import Path
                spec = importlib.util.spec_from_file_location('guard', '.github/scripts/check-release-delivery-contract.py')
                guard = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(guard)
                original = Path('.github/workflows/deploy-release.yml').read_text()
                assert guard.resource_diagnostic_failures(original) == []
                changes = [
                    ('cancel: release_resources', 'run: echo missing-cancel'),
                    ('cancel: release_resources', 'if: always()\\n        cancel: release_resources'),
                    ('run: "$RUNNER_TEMP/release.sh"', 'run: "$RUNNER_TEMP/release.sh" | tee output.log'),
                    ('/metrics.jsonl', '/**'),
                    ('if: always()', 'if: success()'),
                    ('background: true', 'background: false'),
                    ('retention-days: 7', 'retention-days: 90'),
                ]
                for before, after in changes:
                    assert before in original
                    assert guard.resource_diagnostic_failures(original.replace(before, after)), before
                """;
        Process process = new ProcessBuilder("python3", "-c", script).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static int command(Path root, Path output, String mode, String... extra) {
        List<String> arguments = new ArrayList<>(List.of("release-resources", "--mode", mode,
                "--root", root.toString(), "--output", output.toString()));
        arguments.addAll(List.of(extra));
        return TaxonomyTooling.run(arguments.toArray(String[]::new), ENVIRONMENT, root,
                System.out, System.err);
    }

    private static List<String> javaCommand(Path root, Path output, String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms16m", "-Xmx64m", "-cp", System.getProperty("java.class.path"),
                TaxonomyTooling.class.getName(), "release-resources", "--mode", mode,
                "--root", root.toString(), "--output", output.toString());
    }
}
