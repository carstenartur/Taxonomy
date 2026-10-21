package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real finalizer and publication verifier against independent lane reports. */
class QualityEvidenceFinalizationTest {
    private static final String QUALITY = "target/quality-reports/";
    private static final String REPORT = "failsafe-reports/TEST-com.taxonomy.ObservabilityPerformanceIT.xml";
    private static final String CORE_REPORT = QUALITY + "tests/taxonomy-app/target/" + REPORT;
    private static final String DEDICATED_REPORT = "target/observability-performance/tests/taxonomy-app/target/" + REPORT;
    private static final String CORE = xml("core-disabled", true);
    private static final String DEDICATED = xml("dedicated-executed", false);

    @TempDir Path checkout;
    private String publicationAttempt = "2";

    @Test
    void keepsBothOriginalReportsWhenDedicatedAndCorePathsCollide() throws Exception {
        fixture();

        for (int attempt = 0; attempt < 2; attempt++) {
            Result result = finalizeEvidence();

            assertThat(result.exit()).as(result.output()).isZero();
            assertThat(read(CORE_REPORT)).isEqualTo(CORE);
            assertThat(read(QUALITY + "tests/observability-performance/taxonomy-app/target/" + REPORT))
                    .isEqualTo(DEDICATED);
            Map<?, ?> counts = (Map<?, ?>) FlatJson.parseObject(read(QUALITY + "quality-summary.json")).get("tests");
            assertThat(((Number) counts.get("tests")).intValue()).isEqualTo(4);
            assertThat(((Number) counts.get("passed")).intValue()).isEqualTo(3);
            assertThat(((Number) counts.get("skipped")).intValue()).isEqualTo(1);
            assertThat(((Number) counts.get("reportFiles")).intValue()).isEqualTo(4);
            assertThat(checkout.resolve(QUALITY + "evidence/observability-performance/tests")).doesNotExist();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void preservesApplicationEvidenceAcrossPartialAndPublicationRetries(int browserAttempt) throws Exception {
        fixture();
        applicationBuildId("123.1");
        applicationBuildId("browser", "123." + browserAttempt);

        Result result = finalizeEvidence();

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(FlatJson.parseObject(read(QUALITY + "quality-summary.json")))
                .containsEntry("buildId", "123.2");
        for (String shard : List.of("core", "browser", "recovery")) {
            assertThat(FlatJson.parseObject(read(QUALITY + "evidence/application-test-shards/" + shard + "/manifest.json")))
                    .containsEntry("buildId", "123." + (shard.equals("browser") ? browserAttempt : 1) + "." + shard);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"124.1", "123.3", "123.0", "123.invalid", "123", "0123.1", "123.01"})
    void refusesForeignFutureOrMalformedApplicationCohorts(String buildId) throws Exception {
        fixture();
        applicationBuildId(buildId);

        Result result = finalizeEvidence();

        assertThat(result.exit()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("application shard");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "invalid", "01", ""})
    void refusesMalformedPublicationAttempts(String attempt) throws Exception {
        fixture();
        publicationAttempt = attempt;
        applicationBuildId("123.1");

        Result result = finalizeEvidence();

        assertThat(result.exit()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("application shard");
    }

    @ParameterizedTest
    @ValueSource(strings = {"report-bytes", "missing-report", "coverage-bytes", "commit", "sourceTree", "buildId", "counter"})
    void refusesManifestDriftEvenWhenThePublishedSummaryIsInternallyConsistent(String mutation) throws Exception {
        fixture();
        // Keep the fixture independent of the report-path collision tested above.
        Files.delete(checkout.resolve(DEDICATED_REPORT));
        assertThat(finalizeEvidence().exit()).isZero();

        String manifest = QUALITY + "evidence/application-test-shards/core/manifest.json";
        switch (mutation) {
            case "report-bytes" -> put(CORE_REPORT, xml("changed-core-result", false));
            case "missing-report" -> Files.delete(checkout.resolve(CORE_REPORT));
            case "coverage-bytes" -> put(QUALITY + "evidence/application-test-shards/core/coverage.exec", "changed execution data");
            case "commit" -> put(manifest, read(manifest).replace("\"HEAD\"", "\"other-commit\""));
            case "sourceTree" -> put(manifest, read(manifest).replace(tree(), "0".repeat(40)));
            case "buildId" -> put(manifest, read(manifest).replace("123.2.core", "124.2.core"));
            case "counter" -> put(manifest, read(manifest).replace("\"tests\": 1", "\"tests\": 2"));
            default -> throw new IllegalArgumentException(mutation);
        }
        Result result = finalizeEvidence();

        assertThat(result.exit()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("application shard");
    }

    private void fixture() throws Exception {
        assertThat(run(List.of("git", "init", "-q")).exit()).isZero();
        assertThat(run(List.of("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "fixture")).exit()).isZero();
        Path repository = repositoryRoot();
        for (String script : List.of("generate-quality-site.py", "verify-quality-publication.py")) {
            put(".github/scripts/" + script, Files.readString(repository.resolve(".github/scripts/" + script)));
        }
        Files.copy(repository.resolve("mvnw"), checkout.resolve("mvnw"));
        assertThat(checkout.resolve("mvnw").toFile().setExecutable(true)).isTrue();
        try (var files = Files.walk(repository.resolve(".mvn/wrapper"))) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path destination = checkout.resolve(repository.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
        put(QUALITY + "coverage/jacoco.xml", "<report><counter type=\"INSTRUCTION\" covered=\"1\" missed=\"0\"/></report>");
        put(QUALITY + "coverage/index.html", "fixture aggregate coverage");
        put("target/ui-verification/summary.json", "{}");
        put("target/observability-performance/ci-scope.json", "{\"executed\":true}");
        put(DEDICATED_REPORT, DEDICATED);
        for (String shard : List.of("core", "browser", "recovery")) {
            String report = shard.equals("core") ? CORE : xml(shard, false);
            String destination = shard.equals("core") ? CORE_REPORT
                    : QUALITY + "tests/application-test-shards/" + shard + "/" + REPORT;
            put(destination, report);
            String evidence = QUALITY + "evidence/application-test-shards/" + shard + "/";
            String coverage = "fixture execution data " + shard;
            put(evidence + "coverage.exec", coverage);
            put(evidence + "manifest.json", FlatJson.pretty(Map.of(
                    "schemaVersion", 1, "shard", shard, "commit", "HEAD", "sourceTree", tree(),
                    "buildId", "123.2." + shard,
                    "coverage", Map.of("path", "coverage.exec", "sha256", sha256(coverage)),
                    "reports", List.of(Map.of("path", "tests/" + REPORT, "className", "com.taxonomy.ObservabilityPerformanceIT",
                            "sha256", sha256(report), "tests", 1, "skipped", shard.equals("core") ? 1 : 0,
                            "failures", 0, "errors", 0, "failureElements", List.of())),
                    "timing", Map.of("durationSeconds", 1, "suites", 1))));
        }
    }

    private Result finalizeEvidence() throws Exception {
        return run(List.of("bash", repositoryRoot().resolve(".github/scripts/finalize-quality-evidence.sh").toString()));
    }

    private void applicationBuildId(String buildId) throws Exception {
        for (String shard : List.of("core", "browser", "recovery")) {
            applicationBuildId(shard, buildId);
        }
    }

    private void applicationBuildId(String shard, String buildId) throws Exception {
        String path = QUALITY + "evidence/application-test-shards/" + shard + "/manifest.json";
        var manifest = new java.util.LinkedHashMap<>(FlatJson.parseObject(read(path)));
        manifest.put("buildId", buildId + "." + shard);
        put(path, FlatJson.pretty(manifest));
    }

    private String tree() throws Exception {
        Result result = run(List.of("git", "rev-parse", "HEAD^{tree}"));
        assertThat(result.exit()).as(result.output()).isZero();
        return result.output().strip();
    }

    private Result run(List<String> command) throws Exception {
        Path log = Files.createTempFile(checkout, "process-", ".log");
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command)).directory(checkout.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("GITHUB_SHA", "HEAD", "GITHUB_RUN_ID", "123", "GITHUB_RUN_ATTEMPT", publicationAttempt));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("bounded evidence fixture").isTrue();
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void put(String relative, String value) throws Exception {
        Path destination = checkout.resolve(relative);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, value);
    }

    private String read(String relative) throws Exception {
        return Files.readString(checkout.resolve(relative));
    }

    private static String xml(String name, boolean skipped) {
        return "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"" + (skipped ? 1 : 0)
                + "\"><testcase classname=\"com.taxonomy.ObservabilityPerformanceIT\" name=\"" + name + "\">"
                + (skipped ? "<skipped message=\"dedicated lane owns execution\"/>" : "") + "</testcase></testsuite>";
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve(".github/scripts/finalize-quality-evidence.sh"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find Taxonomy checkout");
        return root;
    }

    private record Result(int exit, String output) { }
}
