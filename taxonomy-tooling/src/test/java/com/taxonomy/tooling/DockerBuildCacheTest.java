package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs the repository build entry point; Docker is doubled only at its CLI boundary. */
class DockerBuildCacheTest {
    @TempDir Path temp;
    @Test void ordinaryBuildRequiresNoBuilderOrPersistentCache() throws Exception {
        Result result = run(Map.of(), "standard");
        assertThat(result.exit).isZero();
        assertThat(result.calls).contains("build\n", "--target\nstandard\n", "VCS_REF=" + "a".repeat(40));
        assertThat(result.calls).doesNotContain("buildx", "--cache-from", "--cache-to", "--load");
    }
    @Test void explicitBuilderWithoutPersistenceStillLoadsARealImage() throws Exception {
        Result result = run(Map.of("TAXONOMY_DOCKER_BUILDER", "chosen-builder"), "standard");
        assertThat(result.exit).isZero();
        assertThat(result.calls).contains("buildx\nbuild\n--load\n--builder\nchosen-builder\n");
        assertThat(result.calls).doesNotContain("--cache-from", "--cache-to");
    }
    @Test void successfulBuildPublishesCacheEffectiveness() throws Exception {
        Path summary = temp.resolve("summary");
        Result result = run(Map.of("GITHUB_STEP_SUMMARY", summary.toString()), "standard");
        assertThat(result.exit).isZero();
        assertThat(Files.readString(summary)).contains("imported=false", "CACHED steps=1", "verification run freshly through Maven");
    }
    @Test void missingCacheStillBuildsAndExportsBothVariants() throws Exception {
        Path cache = temp.resolve("cache");
        for (String target : List.of("standard", "onnx")) {
            Result result = run(Map.of("TAXONOMY_DOCKER_CACHE_DIRECTORY", cache.toString(),
                    "TAXONOMY_DOCKER_BUILDER", "test-builder"), target);
            assertThat(result.exit).isZero();
            assertThat(result.calls).contains("buildx\nbuild\n", "--load\n", "--builder\ntest-builder\n",
                    "--target\n" + target + "\n", "--cache-to\ntype=local,dest=", "mode=max");
            if (target.equals("standard")) assertThat(result.calls).doesNotContain("--cache-from");
            else assertThat(result.calls).contains("--cache-from\ntype=local,src=");
            assertThat(Files.isRegularFile(cache.resolve("current/index.json"))).isTrue();
        }
    }
    @Test void invalidCacheIndexIsIgnored() throws Exception {
        Path cache = temp.resolve("cache"); Files.createDirectories(cache.resolve("current"));
        Files.writeString(cache.resolve("current/index.json"), "broken");
        Result result = run(Map.of("TAXONOMY_DOCKER_CACHE_DIRECTORY", cache.toString()), "onnx");
        assertThat(result.exit).isZero();
        assertThat(result.calls).doesNotContain("--cache-from");
    }
    @Test void unusableImportedCacheRetriesARealUncachedBuild() throws Exception {
        Path cache = temp.resolve("cache"); Files.createDirectories(cache.resolve("current"));
        Files.writeString(cache.resolve("current/index.json"), "{\"schemaVersion\":2,\"manifests\":[]}");
        Result result = run(Map.of("TAXONOMY_DOCKER_CACHE_DIRECTORY", cache.toString(), "FAIL_IMPORT", "true"), "standard");
        assertThat(result.exit).isZero();
        assertThat(result.calls.split("--tag", -1)).hasSize(3);
        assertThat(result.calls.split("--cache-from", -1)).hasSize(2);
        assertThat(result.output).contains("retrying a real build without external cache");
    }
    @Test void realBuildFailureCannotBeConvertedIntoSuccess() throws Exception {
        Result result = run(Map.of("TAXONOMY_DOCKER_CACHE_DIRECTORY", temp.resolve("cache").toString(),
                "FAIL_ALL", "true"), "onnx");
        assertThat(result.exit).isEqualTo(17);
    }
    @Test void failedBuildPreservesTheLastGoodCacheAndCleansOnlyItsOwnTemporaryExport() throws Exception {
        Path cache = temp.resolve("cache"); Files.createDirectories(cache.resolve("current"));
        String goodIndex = "{\"schemaVersion\":2,\"manifests\":[]}";
        Files.writeString(cache.resolve("current/index.json"), goodIndex);
        Files.writeString(cache.resolve("user-file"), "keep me");
        Result result = run(Map.of("TAXONOMY_DOCKER_CACHE_DIRECTORY", cache.toString(), "FAIL_ALL", "true"), "standard");
        assertThat(result.exit).isEqualTo(17);
        assertThat(Files.readString(cache.resolve("current/index.json"))).isEqualTo(goodIndex);
        assertThat(Files.readString(cache.resolve("user-file"))).isEqualTo("keep me");
        try (var entries = Files.list(cache)) {
            assertThat(entries.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder("current", "user-file");
        }
    }
    @Test void unknownTargetFailsBeforeDocker() throws Exception {
        Result result = run(Map.of(), "unexpected");
        assertThat(result.exit).isNotZero();
        assertThat(result.calls).isEmpty();
        assertThat(result.output).contains("Usage: build-production-image.sh");
    }
    private Result run(Map<String,String> env, String target) throws Exception {
        Path bin = temp.resolve("bin"); Files.createDirectories(bin);
        Path calls = temp.resolve("calls"); Files.deleteIfExists(calls);
        Path docker = bin.resolve("docker");
        Files.writeString(docker, """
                #!/bin/bash
                set -eu
                printf '%s\\n' "$@" >> "$CALLS"
                [[ "${FAIL_ALL:-}" != true ]] || exit 17
                if [[ "${FAIL_IMPORT:-}" == true && " $* " == *' --cache-from '* ]]; then exit 18; fi
                for arg in "$@"; do
                  if [[ "$arg" == type=local,dest=* ]]; then
                    dest="${arg#type=local,dest=}"; dest="${dest%%,*}"
                    mkdir -p "$dest"
                    echo '{"schemaVersion":2,"manifests":[]}' > "$dest/index.json"
                  fi
                done
                echo '#1 CACHED'
                """);
        assertThat(docker.toFile().setExecutable(true)).isTrue();
        var builder = new ProcessBuilder("bash", ReleaseOrchestrationChecks.repositoryRoot().resolve(
                ".github/scripts/build-production-image.sh").toString(), "taxonomy:test", "a".repeat(40), target);
        builder.environment().remove("GITHUB_STEP_SUMMARY");
        builder.environment().remove("TAXONOMY_DOCKER_CACHE_DIRECTORY");
        builder.environment().remove("TAXONOMY_DOCKER_BUILDER");
        builder.environment().putAll(env);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("CALLS", calls.toString());
        Path log = temp.resolve("output");
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), Files.readString(log), Files.exists(calls) ? Files.readString(calls) : "");
    }
    record Result(int exit, String output, String calls) { }
}
