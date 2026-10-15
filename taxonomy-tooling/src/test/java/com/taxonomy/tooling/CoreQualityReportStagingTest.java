package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the actual staging shell command against isolated report trees. */
class CoreQualityReportStagingTest {
    @TempDir
    Path root;

    @Test
    void stagesEachOriginalOnceWithoutRecursivelyCollectingItsOwnOutput() throws Exception {
        fixture();
        // Exceed a pipe buffer so streaming discovery would reach its own copies.
        String source = "taxonomy-fixture-" + "x".repeat(180) + "/target/surefire-reports";
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            String name = source + "/TEST-%04d.xml".formatted(i);
            put(name, "<testsuite name=\"case-" + i + "\" tests=\"1\" failures=\"0\" errors=\"0\"/>");
            expected.add(name);
        }
        coverage();

        Result result = stage(Map.of());

        assertEquals(0, result.status(), result.output());
        assertEquals(expected.stream().sorted().toList(), stagedFiles());
    }

    @Test
    void preservesModuleOwnershipUnusualNamesCoverageAndProvenanceAcrossRepeatedStaging() throws Exception {
        fixture();
        List<String> names = List.of(
                "taxonomy-one/target/surefire-reports/TEST-shared.xml",
                "taxonomy-two/target/failsafe-reports/TEST-shared.xml",
                "taxonomy-two/target/failsafe-reports/diagnostic with space\nand-ü.txt",
                "taxonomy-two/target/failsafe-reports/failure.dump",
                "taxonomy-two/target/failsafe-reports/failure.dumpstream");
        List<String> contents = List.of(
                "<testsuite name=\"shared\"><testcase name=\"module-one\"/></testsuite>",
                "<testsuite name=\"shared\"><testcase name=\"module-two\"/></testsuite>",
                "report 2\n", "report 3\n", "report 4\n");
        for (int i = 0; i < names.size(); i++) {
            put(names.get(i), contents.get(i));
        }
        put("taxonomy-one/target/surefire-reports/unrelated.json", "fixture report\n");
        coverage();
        put("taxonomy-coverage/target/site/jacoco-aggregate/index.html", "coverage");
        put("target/maven-verification.log", "BUILD SUCCESS");
        put("target/ui-verification/large-results/report.json", "{\"heapIncreaseBytes\":1234}");
        put("target/quality-reports/tests/stale.txt", "fixture report\n");

        for (int attempt = 0; attempt < 2; attempt++) {
            Result result = stage(Map.of());

            assertEquals(0, result.status(), result.output());
            assertEquals(names.stream().sorted().toList(), stagedFiles());
            for (int i = 0; i < names.size(); i++) {
                assertEquals(contents.get(i), read("target/quality-reports/tests/" + names.get(i)));
            }
            assertEquals("<report/>", read("target/quality-reports/coverage/jacoco.xml"));
            assertEquals("coverage", read("target/quality-reports/coverage/index.html"));
            assertEquals("BUILD SUCCESS", read("target/quality-reports/evidence/maven-verification.log"));
            assertEquals("{\"heapIncreaseBytes\":1234}",
                    read("target/quality-reports/evidence/large-result-budget.json"));
            assertTrue(read("target/quality-reports/README.txt").matches(
                    "(?s).*Commit: HEAD\\nSource tree: [0-9a-f]{40}\\nCore build ID: 123\\.2\\.core.*"));
        }
    }

    @Test
    void doesNotHideAFailedSourceInventoryBehindASuccessfulCopyLoop() throws Exception {
        fixture();
        coverage();
        // Only failure injection replaces find; successful paths use real traversal.
        put("bin/find", "#!/bin/sh\necho \"inventory failure\" >&2\nexit 23\n");
        Files.setPosixFilePermissions(root.resolve("bin/find"), PosixFilePermissions.fromString("rwxr-xr-x"));

        Result result = stage(Map.of("PATH", root.resolve("bin") + ":" + System.getenv("PATH")));

        assertEquals(23, result.status(), result.output());
        assertTrue(result.stderr().contains("inventory failure"), result.output());
        assertFalse(Files.exists(root.resolve("target/quality-reports/README.txt")));
    }

    @Test
    void rejectsMissingAggregateCoverageRatherThanPublishingSuccess() throws Exception {
        fixture();
        put("taxonomy-fixture/target/surefire-reports/TEST-one.xml", "<testsuite/>");
        put("target/ui-verification/large-results/report.json", "{\"heapIncreaseBytes\":41208924}");

        Result result = stage(Map.of());

        assertEquals(1, result.status(), result.output());
        assertTrue(result.stdout().contains("Aggregate JaCoCo XML is missing"), result.output());
        assertEquals("{\"heapIncreaseBytes\":41208924}",
                read("target/quality-reports/evidence/large-result-budget.json"));
        assertFalse(Files.exists(root.resolve("target/quality-reports/README.txt")));
    }

    @Test
    void aggregatesIsolatedApplicationReportsAndPreservesEveryShardCoverageInput() throws Exception {
        fixture();
        String core = "<testsuite name=\"com.taxonomy.CoreTest\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                + "<testcase name=\"core\"/></testsuite>";
        String recovery = "<testsuite name=\"com.taxonomy.RestartTest\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                + "<testcase name=\"restart\"/></testsuite>";
        put("taxonomy-app/target/surefire-reports/TEST-com.taxonomy.CoreTest.xml", core);
        coverage();
        put("target/app-test-shards/core/manifest.json", "{\"shard\":\"core\"}");
        put("target/app-test-shards/core/coverage.exec", "core execution data");
        put("target/app-test-shards/recovery/manifest.json", "{\"shard\":\"recovery\"}");
        put("target/app-test-shards/recovery/coverage.exec", "recovery execution data");
        put("target/app-test-shards/recovery/tests/surefire-reports/TEST-com.taxonomy.RestartTest.xml", recovery);
        put("target/app-test-shards/timings.json", "{\"tests\":{\"classes\":2}}");

        Result result = stage(Map.of());

        assertEquals(0, result.status(), result.output());
        assertEquals(recovery, read("target/quality-reports/tests/application-test-shards/recovery/"
                + "surefire-reports/TEST-com.taxonomy.RestartTest.xml"));
        assertEquals(core, read("target/quality-reports/tests/taxonomy-app/target/"
                + "surefire-reports/TEST-com.taxonomy.CoreTest.xml"));
        for (String shard : List.of("core", "recovery")) {
            String evidence = "target/quality-reports/evidence/application-test-shards/" + shard;
            assertEquals(shard + " execution data", read(evidence + "/coverage.exec"));
            assertEquals("{\"shard\":\"" + shard + "\"}", read(evidence + "/manifest.json"));
        }
        assertEquals("{\"tests\":{\"classes\":2}}",
                read("target/quality-reports/evidence/application-test-shards/timings.json"));
    }

    private void fixture() throws Exception {
        Result init = run(List.of("git", "init", "-q"), Map.of());
        assertEquals(0, init.status(), init.output());
        Result commit = run(List.of("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "fixture"), Map.of());
        assertEquals(0, commit.status(), commit.output());
    }

    private void coverage() throws IOException {
        put("taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml", "<report/>");
    }

    private void put(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative));
    }

    private List<String> stagedFiles() throws IOException {
        Path tests = root.resolve("target/quality-reports/tests");
        try (var paths = Files.walk(tests)) {
            return paths.filter(Files::isRegularFile).map(tests::relativize).map(Path::toString).sorted().toList();
        }
    }

    private Result stage(Map<String, String> environment) throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();
        while (repository != null && !Files.isRegularFile(repository.resolve(".github/scripts/stage-core-quality-reports.sh"))) {
            repository = repository.getParent();
        }
        if (repository == null) {
            throw new IllegalStateException("Cannot locate Taxonomy repository root");
        }
        var configured = new java.util.HashMap<>(Map.of(
                "GITHUB_SHA", "HEAD", "GITHUB_RUN_ID", "123", "GITHUB_RUN_ATTEMPT", "2"));
        configured.putAll(environment);
        return run(List.of("bash", repository.resolve(".github/scripts/stage-core-quality-reports.sh").toString()), configured);
    }

    private Result run(List<String> command, Map<String, String> environment) throws Exception {
        Path stdout = Files.createTempFile(root, "stdout-", ".log");
        Path stderr = Files.createTempFile(root, "stderr-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        assertTrue(finished, () -> "Timed out: " + command);
        return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    }

    private record Result(int status, String stdout, String stderr) {
        String output() {
            return stdout + stderr;
        }
    }
}
