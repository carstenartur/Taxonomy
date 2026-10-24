package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Run the actual final publication shell against a local Git remote. */
class ReleasePublicationMainGuardTest {
    private static final String IMAGE_DIGEST = "sha256:" + "a".repeat(64);
    private static final String ONNX_IMAGE_DIGEST = "sha256:" + "b".repeat(64);

    @Test
    void unchangedMainPublishesFromTheDetachedReleaseSource(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root, "unchanged");

        Result result = publish(fixture);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(Files.readAllLines(root.resolve("effects")))
                .containsExactly("publish", "deploy");
        assertThat(GitSupport.require(fixture.work(), "rev-parse", "HEAD").strip())
                .isEqualTo(fixture.releaseSha()).isNotEqualTo(fixture.expectedMain());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "moved", "missing-main", "fetch-error", "already-published-moved", "missing-expected-sha"
    })
    void unverifiedFinalMainPreventsPublicationAndDeployment(String problem, @TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root, problem);

        Result result = publish(fixture);

        assertThat(Files.readAllLines(root.resolve("checks")))
                .startsWith("notes", "assets", "image-inspection", "image-inspection", "draft-state");
        assertThat(result.exitCode()).as(problem + ": " + result.output()).isNotZero();
        assertThat(Files.readString(root.resolve("effects")))
                .as("no publication or deployment after %s", problem).isEmpty();
        assertThat(GitSupport.require(fixture.work(), "rev-parse", "HEAD").strip())
                .as("the release source stays detached and immutable")
                .isEqualTo(fixture.releaseSha());
    }

    private static Fixture fixture(Path root, String problem) throws Exception {
        Path remote = root.resolve("origin.git");
        Path work = Files.createDirectories(root.resolve("release source"));
        GitSupport.require(root, "init", "--bare", "--initial-branch=main", remote.toString());
        GitSupport.require(work, "init", "--initial-branch=main");
        GitSupport.require(work, "config", "user.name", "Release guard test");
        GitSupport.require(work, "config", "user.email", "release-guard@example.invalid");
        GitSupport.require(work, "commit", "--allow-empty", "-m", "immutable release source");
        String releaseSha = GitSupport.require(work, "rev-parse", "HEAD").strip();
        GitSupport.require(work, "tag", "v1.5.0");
        GitSupport.require(work, "commit", "--allow-empty", "-m", "verified final main");
        String expectedMain = GitSupport.require(work, "rev-parse", "HEAD").strip();
        GitSupport.require(work, "remote", "add", "origin", remote.toString());
        GitSupport.require(work, "push", "origin", "HEAD:refs/heads/main");

        if (problem.equals("moved") || problem.equals("already-published-moved")) {
            GitSupport.require(work, "commit", "--allow-empty", "-m", "main advanced after its gates");
            GitSupport.require(work, "push", "origin", "HEAD:refs/heads/main");
        } else if (problem.equals("missing-main")) {
            GitSupport.require(root, "--git-dir=" + remote, "update-ref", "-d", "refs/heads/main");
        } else if (problem.equals("fetch-error")) {
            GitSupport.require(work, "remote", "set-url", "origin", root.resolve("missing.git").toString());
        }
        // A cached tracking ref must not substitute for the fresh remote check.
        GitSupport.require(work, "update-ref", "refs/remotes/origin/main", expectedMain);
        GitSupport.require(work, "checkout", "--detach", "v1.5.0");

        Path bin = Files.createDirectories(root.resolve("bin"));
        executable(bin.resolve("gh"), """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "$1 $2" == "release edit" ]]; then
                  printf 'publish\n' >> "$TEST_EFFECTS"
                  printf 'false\n' > "$TEST_DRAFT_STATE"
                elif [[ "$1 $2" == "release view" && "$5" == "assets" ]]; then
                  printf 'assets\\n' >> "$TEST_CHECKS"
                  printf '%s\n' taxonomy-1.5.0-distribution.tar.gz \
                    taxonomy-1.5.0-distribution.tar.gz.sha256 \
                    taxonomy-1.5.0-image-evidence.json taxonomy-1.5.0-image-trivy.sarif \
                    taxonomy-1.5.0-release.sha256 \
                    taxonomy-1.5.0-onnx-image-evidence.json taxonomy-1.5.0-onnx-image-trivy.sarif \
                    taxonomy-1.5.0-onnx-release.sha256
                elif [[ "$1 $2" == "release view" && "$5" == "isDraft" ]]; then
                  printf 'draft-state\\n' >> "$TEST_CHECKS"
                  cat "$TEST_DRAFT_STATE"
                else
                  echo "Unexpected gh command: $*" >&2
                  exit 99
                fi
                """);
        executable(bin.resolve("docker"),
                "#!/usr/bin/env bash\nprintf 'image-inspection\\n' >> \"$TEST_CHECKS\"\n");
        executable(bin.resolve("notes-validator"),
                "#!/usr/bin/env bash\nprintf 'notes\\n' >> \"$TEST_CHECKS\"\n");
        executable(bin.resolve("curl"),
                "#!/usr/bin/env bash\nprintf 'deploy\\n' >> \"$TEST_EFFECTS\"\n");
        Files.writeString(root.resolve("effects"), "");
        Files.writeString(root.resolve("checks"), "");
        Files.writeString(root.resolve("draft-state"),
                problem.equals("already-published-moved") ? "false\n" : "true\n");
        Files.createDirectories(work.resolve("target"));
        Files.writeString(work.resolve("target/release-image-evidence.json"),
                "{\"digest\":\"" + IMAGE_DIGEST + "\"}\n");
        Files.writeString(work.resolve("target/release-onnx-image-evidence.json"), """
                {"digest":"%s","image":"ghcr.io/release-guard-test/taxonomy@%s",
                 "imageVariant":"onnx","sourceCommit":"%s","releaseVersion":"1.5.0",
                 "scan":{"result":"passed"}}
                """.formatted(ONNX_IMAGE_DIGEST, ONNX_IMAGE_DIGEST, releaseSha));
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", bin + ":" + System.getenv("PATH"));
        environment.put("GITHUB_REPOSITORY_OWNER", "release-guard-test");
        environment.put("RELEASE_VERSION", "1.5.0");
        environment.put("IMAGE_DIGEST", IMAGE_DIGEST);
        environment.put("ONNX_IMAGE_DIGEST", ONNX_IMAGE_DIGEST);
        environment.put("RELEASE_SOURCE_SHA", releaseSha);
        environment.put("EXPECTED_MAIN_SHA", problem.equals("missing-expected-sha") ? "" : expectedMain);
        environment.put("RELEASE_NOTES_VALIDATOR", bin.resolve("notes-validator").toString());
        environment.put("RENDER_DEPLOY_HOOK_URL", "https://deployment.invalid/release");
        environment.put("TEST_EFFECTS", root.resolve("effects").toString());
        environment.put("TEST_CHECKS", root.resolve("checks").toString());
        environment.put("TEST_DRAFT_STATE", root.resolve("draft-state").toString());
        environment.put("RUNNER_TEMP", root.toString());
        return new Fixture(work, releaseSha, expectedMain, environment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "standard-digest", "variant", "source", "scan", "release"})
    void invalidOnnxEvidencePreventsPublication(String problem, @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "unchanged");
        Path evidence = fixture.work().resolve("target/release-onnx-image-evidence.json");
        String payload = Files.readString(evidence);
        switch (problem) {
            case "missing" -> Files.delete(evidence);
            case "standard-digest" -> Files.writeString(evidence, payload.replace(ONNX_IMAGE_DIGEST, IMAGE_DIGEST));
            case "variant" -> Files.writeString(evidence, payload.replace("onnx", "standard"));
            case "source" -> Files.writeString(evidence, payload.replace(fixture.releaseSha(), "0".repeat(40)));
            case "scan" -> Files.writeString(evidence, payload.replace("passed", "failed"));
            case "release" -> Files.writeString(evidence, payload.replace("1.5.0", "1.4.0"));
            default -> throw new IllegalArgumentException(problem);
        }
        Result result = publish(fixture);
        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(Files.readString(root.resolve("effects"))).isEmpty();
    }

    @Test
    void evidenceBindingWritesSeparateDigestBoundVariantArtifacts(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "unchanged");
        executable(root.resolve("bin/gh"), "#!/usr/bin/env bash\nexit 0\n");
        Path chart = fixture.work().resolve("deploy/helm/taxonomy");
        Files.createDirectories(chart);
        Files.writeString(chart.resolve("Chart.yaml"), "fixture chart");
        executable(root.resolve("bin/helm"), """
                #!/usr/bin/env bash
                set -euo pipefail
                digest=''
                for arg in "$@"; do
                  if [[ "$arg" == image.digest=* ]]; then digest="${arg#image.digest=}"; fi
                done
                if [[ "$digest" == "$ONNX_IMAGE_DIGEST" && "$*" != *values-onnx.yaml* ]]; then
                  echo 'ONNX manifest must apply the ONNX values overlay' >&2
                  exit 1
                fi
                printf 'image: "ghcr.io/%s/taxonomy@%s"\n' "$GITHUB_REPOSITORY_OWNER" "$digest"
                printf 'readOnlyRootFilesystem: true\nrunAsUser: 10001\n'
                """);
        Files.writeString(fixture.work().resolve("release-image-trivy.sarif"), "standard scan");
        Files.writeString(fixture.work().resolve("release-onnx-image-trivy.sarif"), "onnx scan");
        Result result = runStep(fixture, "Bind release evidence and Helm deployment to immutable image digest");
        assertThat(result.exitCode()).as(result.output()).isZero();
        Path artifacts = root.resolve("helm-release");
        assertThat(Files.readString(artifacts.resolve("taxonomy-1.5.0-image-evidence.json")))
                .contains(IMAGE_DIGEST).doesNotContain(ONNX_IMAGE_DIGEST);
        assertThat(Files.readString(artifacts.resolve("taxonomy-1.5.0-onnx-image-evidence.json")))
                .contains(ONNX_IMAGE_DIGEST, "\"imageVariant\": \"onnx\"", fixture.releaseSha())
                .doesNotContain(IMAGE_DIGEST);
        assertThat(Files.readString(artifacts.resolve("taxonomy-1.5.0-onnx-kubernetes-digest.yaml")))
                .contains(ONNX_IMAGE_DIGEST).doesNotContain(IMAGE_DIGEST);
        for (String suffix : new String[]{"", "onnx-"}) {
            Process checksum = new ProcessBuilder("sha256sum", "-c", "taxonomy-1.5.0-" + suffix + "release.sha256")
                    .directory(artifacts.toFile()).redirectErrorStream(true).start();
            assertThat(checksum.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(checksum.exitValue()).isZero();
        }
    }

    @Test
    void invalidOnnxBuildDigestCannotProducePassingEvidence(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, "unchanged");
        fixture.environment().put("ONNX_IMAGE_DIGEST", "taxonomy:v1.5.0-onnx");
        Result result = runStep(fixture, "Bind release evidence and Helm deployment to immutable image digest");
        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(root.resolve("helm-release")).doesNotExist();
    }

    private static Result publish(Fixture fixture) throws Exception {
        return runStep(fixture, "Publish complete release and trigger deployment");
    }

    private static Result runStep(Fixture fixture, String stepName) throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (repository != null && !Files.isRegularFile(repository.resolve(".github/workflows/deploy-release.yml"))) {
            repository = repository.getParent();
        }
        if (repository == null) throw new IllegalStateException("Taxonomy repository not found");
        String workflow = Files.readString(repository.resolve(".github/workflows/deploy-release.yml"));
        int step = workflow.indexOf("      - name: " + stepName);
        assertThat(step).as("actual final publication step").isGreaterThanOrEqualTo(0);
        int start = workflow.indexOf("        run: |\n", step);
        assertThat(start).as("actual publication shell").isGreaterThan(step);
        start += "        run: |\n".length();
        int end = workflow.indexOf("\n      - ", start);
        String script = workflow.substring(start, end < 0 ? workflow.length() : end).lines()
                .map(line -> line.startsWith("          ") ? line.substring(10) : line)
                .collect(Collectors.joining("\n", "", "\n"));
        Path output = Files.createTempFile(fixture.work(), "publication-", ".log");
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", script)
                .directory(fixture.work().toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(fixture.environment());
        builder.environment().remove("BASH_ENV");
        Process process = builder.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Final release publication fixture exceeded 20 seconds");
            }
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void executable(Path path, String script) throws Exception {
        Files.writeString(path, script);
        assertThat(path.toFile().setExecutable(true)).isTrue();
    }

    private record Fixture(Path work, String releaseSha, String expectedMain, Map<String, String> environment) { }
    private record Result(int exitCode, String output) { }
}
