package com.taxonomy.tooling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.taxonomy.tooling.AppTestShardVerifier.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the real archive producer and consumer shell steps with source-bound Java evidence. */
class ReactorCoverageTransferTest {
    @TempDir Path root;
    String sha, tree, workflow;
    Path upstream;
    Map<String, Object> plan;
    @BeforeEach void fixture() throws Exception {
        git("init", "-q"); git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "fixture");
        sha = git("rev-parse", "HEAD"); tree = git("rev-parse", "HEAD^{tree}");
        plan = map("defaultGroup", "core", "excludedTags", List.of(), "requiredExecutions", List.of(), "groups", map("core", map("default", true, "tests", List.of()), "recovery", map("tests", List.of("com.taxonomy.RecoveryTest"), "tag", "ci-recovery"), "browser", map("tests", List.of("com.taxonomy.BrowserTest"), "tag", "ci-browser")));
        write(root.resolve(".mvn/verification-suites.json"), map("applicationTestShards", plan));
        for (String shard : List.of("core", "recovery", "browser")) {
            String name = Character.toUpperCase(shard.charAt(0)) + shard.substring(1) + "Test";
            put("taxonomy-app/src/test/java/com/taxonomy/" + name + ".java", "package com.taxonomy; import org.junit.jupiter.api.*; " + (shard.equals("core") ? "" : "@Tag(\"ci-" + shard + "\") ") + "class " + name + " { @Test void test() {} }");
            delete(root.resolve("taxonomy-app/target/surefire-reports"));
            put("taxonomy-app/target/surefire-reports/TEST-com.taxonomy." + name + ".xml", "<testsuite name=\"com.taxonomy." + name + "\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"1\"><testcase/></testsuite>");
            put("taxonomy-app/target/jacoco.exec", shard + "-coverage"); stage(root, shard, sha, tree, "123.1." + shard);
        }
        put("taxonomy-app/target/classes/com/taxonomy/Fixture.class", "class-fixture");
        put("taxonomy-domain/target/classes/com/taxonomy/Upstream.class", "upstream-class-fixture");
        put("taxonomy-domain/target/surefire-reports/TEST-com.taxonomy.UpstreamTest.xml", "<testsuite/>");
        upstream = put("taxonomy-domain/target/jacoco.exec", "upstream-only-probes");
        Path classes = Path.of(TaxonomyTooling.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).toList()) {
                Path destination = root.resolve("taxonomy-tooling/target/classes").resolve(classes.relativize(file)); Files.createDirectories(destination.getParent()); Files.copy(file, destination);
            }
        }
        workflow = Files.readString(AppTestShardVerifierTest.repository().resolve(".github/workflows/ci-cd.yml"));
    }
    Path put(String path, String text) throws Exception { Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text); return file; }
    String git(String... args) { return GitSupport.require(root, args).strip(); }
    record Result(int status, String text) { }
    Result shell(String script) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", "-e", "-u", "-o", "pipefail", "-c", script).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("GITHUB_SHA", sha); builder.environment().put("GITHUB_STEP_SUMMARY", root.resolve("summary.md").toString());
        builder.environment().put("PATH", Path.of(System.getProperty("java.home")).resolve("bin") + ":" + builder.environment().get("PATH"));
        Path log = root.resolve("process-" + UUID.randomUUID() + ".log"); builder.redirectOutput(log.toFile());
        Process process = builder.start(); assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fixture shell timed out"); return new Result(process.exitValue(), Files.readString(log));
    }
    Result stageArchive() throws Exception { return shell(step("Stage full-reactor class outputs for aggregate coverage")); }
    Result consume() throws Exception { return shell(step("Verify source-bound reactor outputs and shard inputs")); }
    void prepare() throws Exception { Result result = stageArchive(); assertEquals(0, result.status, result.text); }
    String step(String name) {
        String marker = "      - name: " + name + "\n"; int start = workflow.indexOf(marker); assertTrue(start >= 0, name);
        int end = workflow.indexOf("\n      - ", start + marker.length()); String block = workflow.substring(start, end < 0 ? workflow.length() : end);
        int run = block.indexOf("        run: |\n"); assertTrue(run >= 0, name); StringBuilder script = new StringBuilder();
        for (String line : block.substring(run + "        run: |\n".length()).split("\n")) { if (line.startsWith("          ")) script.append(line.substring(10)).append('\n'); else if (line.isBlank()) script.append('\n'); else break; }
        return script.toString();
    }
    @ParameterizedTest @ValueSource(ints = {1, 2}) void realWorkflowAcceptsAllShardsAndWritesValidatedTimings(int browserAttempt) throws Exception {
        Path browserManifest = root.resolve("target/app-test-shards/browser/manifest.json");
        var manifest = read(browserManifest); manifest.put("buildId", "123." + browserAttempt + ".browser"); write(browserManifest, manifest);
        prepare(); Result result = consume(); assertEquals(0, result.status, result.text);
        var timings = read(root.resolve("target/app-test-shards/timings.json")); assertEquals(3L, object(timings.get("tests")).get("classes")); assertEquals(3L, object(timings.get("tests")).get("cases")); assertEquals(List.of("core", "recovery", "browser"), timings.get("coverage"));
        for (String shard : List.of("core", "recovery", "browser")) {
            assertEquals("123." + (shard.equals("browser") ? browserAttempt : 1) + "." + shard, read(root.resolve("target/app-test-shards/" + shard + "/manifest.json")).get("buildId"));
        }
        assertFalse(result.text.contains("NaN")); assertFalse(result.text.contains("undefined"));
    }
    @ParameterizedTest @ValueSource(strings = {"core", "recovery", "browser"}) void realWorkflowFailsWithoutEachShard(String shard) throws Exception {
        prepare(); delete(root.resolve("target/app-test-shards/" + shard)); Result result = consume(); assertNotEquals(0, result.status, result.text); assertTrue(result.text.contains("missing shard artifact: " + shard), result.text);
    }
    @Test void restoresUpstreamModuleProbesAndClassesLosslessly() throws Exception {
        prepare(); byte[] original = Files.readAllBytes(upstream); delete(root.resolve("taxonomy-domain/target")); Result result = consume(); assertEquals(0, result.status, result.text); assertArrayEquals(original, Files.readAllBytes(upstream)); assertEquals("upstream-class-fixture", Files.readString(root.resolve("taxonomy-domain/target/classes/com/taxonomy/Upstream.class")));
    }
    @Test void producerRejectsMissingExecutionDataForTestedProductionModule() throws Exception {
        Files.delete(upstream); Result result = stageArchive(); assertNotEquals(0, result.status); assertTrue(result.text.contains("Missing JaCoCo execution data for tested module: ./taxonomy-domain/target"), result.text);
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "tampered"}) void consumerRejectsUpstreamProbeCorruptionDespiteRefreshedArchiveDigest(String corruption) throws Exception {
        prepare(); Result unpack = shell("tar -xzf target/core-reactor-outputs.tar.gz -C ."); assertEquals(0, unpack.status, unpack.text);
        if (corruption.equals("missing")) Files.delete(upstream); else Files.writeString(upstream, "changed upstream probes");
        Result archive = shell("""
                find taxonomy-app/target/classes taxonomy-domain/target/classes taxonomy-tooling/target/classes -type f -print0 > target/corrupt-files
                find taxonomy-app taxonomy-domain -type f -path '*/target/jacoco.exec' -print0 >> target/corrupt-files
                printf '%s\\0' target/core-reactor-files.sha256 >> target/corrupt-files
                tar --null -T target/corrupt-files -czf target/core-reactor-outputs.tar.gz
                sha256sum target/core-reactor-outputs.tar.gz > target/core-reactor-outputs.sha256
                """);
        assertEquals(0, archive.status, archive.text); Result result = consume(); assertNotEquals(0, result.status, result.text); assertTrue(result.text.contains("taxonomy-domain/target/jacoco.exec"), result.text);
    }
    @Test void emptyTestOnlyClassesDirectoryRequiresNoProbeData() throws Exception {
        Files.createDirectories(root.resolve("taxonomy-build/target/classes")); put("taxonomy-build/target/surefire-reports/TEST-com.taxonomy.PolicyTest.xml", "<testsuite/>"); Result result = stageArchive(); assertEquals(0, result.status, result.text);
    }
    @Test void producerAndConsumerBindArchiveToExactSourceTree() throws Exception {
        prepare(); put("target/core-reactor-outputs.tree", "c".repeat(40) + "\n"); assertNotEquals(0, consume().status);
    }
}
