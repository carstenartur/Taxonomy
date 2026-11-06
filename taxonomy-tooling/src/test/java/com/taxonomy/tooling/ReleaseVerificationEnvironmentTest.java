package com.taxonomy.tooling;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseVerificationEnvironmentTest {
    @ParameterizedTest
    @CsvSource({"false, 0", "false, 23", "true, 0", "true, 23"})
    void verificationSelectsTheReleaseSuiteAndPreservesSourceAndMavenFailures(boolean skipTests, int status,
            @TempDir Path fixture) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        String script = Files.readString(root.resolve(".github/scripts/release.sh"));
        String function = script.substring(script.indexOf("run_maven_release_check() {"),
                script.indexOf("\nrun_release_plan_check() {"));
        String selection = script.substring(script.indexOf("if [[ \"$SKIP_TESTS\" == \"true\" ]]; then"),
                script.indexOf("\njava -jar \"$TOOLING_JAR\" generate-sbom-companion"));
        Files.writeString(fixture.resolve("mvnw"), """
                #!/usr/bin/env bash
                printf '%s\\n' "$GITHUB_SHA" > observed-source
                printf '%s\\n' "$@" > observed-arguments
                exit "$TEST_MAVEN_STATUS"
                """);
        assertThat(fixture.resolve("mvnw").toFile().setExecutable(true)).isTrue();
        Path log = fixture.resolve("verification.log");
        ProcessBuilder builder = new ProcessBuilder("bash", "-euc", function + "\n" + selection)
                .directory(fixture.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("GITHUB_SHA", "1".repeat(40));
        builder.environment().put("RELEASE_COMMIT", "2".repeat(40));
        builder.environment().put("RELEASE_VERSION", "1.5.1");
        builder.environment().put("NEXT_VERSION", "1.5.2-SNAPSHOT");
        builder.environment().put("SKIP_TESTS", Boolean.toString(skipTests));
        builder.environment().put("TEST_MAVEN_STATUS", Integer.toString(status));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isEqualTo(status);
            assertThat(Files.readString(fixture.resolve("observed-source")).strip())
                    .as("All product evidence must identify the release version commit")
                    .isEqualTo("2".repeat(40));
            if (skipTests) {
                assertThat(Files.readAllLines(fixture.resolve("observed-arguments")))
                        .containsExactly("-B", "-Prelease-check", "clean", "package", "-DskipTests",
                                "-DreleaseVersion=1.5.1", "-DnextDevelopmentVersion=1.5.2-SNAPSHOT",
                                "-DreleaseCheckCurrentState=release");
            } else {
                assertThat(Files.readAllLines(fixture.resolve("observed-arguments")))
                        .containsExactly("-B", "-Prelease-check,ci", "clean", "verify", "-DrunOnnxTests=true",
                                "-DreleaseVersion=1.5.1", "-DnextDevelopmentVersion=1.5.2-SNAPSHOT",
                                "-DreleaseCheckCurrentState=release");
            }
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
