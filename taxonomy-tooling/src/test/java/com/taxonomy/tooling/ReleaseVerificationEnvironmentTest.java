package com.taxonomy.tooling;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseVerificationEnvironmentTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 23})
    void verificationUsesTheReleaseCommitAndPreservesMavenFailures(int status,
            @TempDir Path fixture) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        String script = Files.readString(root.resolve(".github/scripts/release.sh"));
        String function = script.substring(script.indexOf("run_maven_release_check() {"),
                script.indexOf("\nrun_release_plan_check() {"));
        Files.writeString(fixture.resolve("mvnw"), """
                #!/usr/bin/env bash
                printf '%s\\n' "$GITHUB_SHA" > observed-source
                printf '%s\\n' "$@" > observed-arguments
                exit "$TEST_MAVEN_STATUS"
                """);
        assertThat(fixture.resolve("mvnw").toFile().setExecutable(true)).isTrue();
        Path log = fixture.resolve("verification.log");
        ProcessBuilder builder = new ProcessBuilder("bash", "-euc", function
                + "\nrun_maven_release_check release release-check,ci clean verify\n")
                .directory(fixture.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("GITHUB_SHA", "1".repeat(40));
        builder.environment().put("RELEASE_COMMIT", "2".repeat(40));
        builder.environment().put("RELEASE_VERSION", "1.5.1");
        builder.environment().put("NEXT_VERSION", "1.5.2-SNAPSHOT");
        builder.environment().put("TEST_MAVEN_STATUS", Integer.toString(status));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isEqualTo(status);
            assertThat(Files.readString(fixture.resolve("observed-source")).strip())
                    .as("All product evidence must identify the release version commit")
                    .isEqualTo("2".repeat(40));
            assertThat(Files.readAllLines(fixture.resolve("observed-arguments")))
                    .containsExactly("-B", "-Prelease-check,ci", "clean", "verify",
                            "-DreleaseVersion=1.5.1", "-DnextDevelopmentVersion=1.5.2-SNAPSHOT",
                            "-DreleaseCheckCurrentState=release");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
