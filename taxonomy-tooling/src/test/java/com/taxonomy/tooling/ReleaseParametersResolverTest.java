package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseParametersResolverTest {

    @Test
    void dispatchPreservesAnExactMajorTransition() {
        var parameters = ReleaseParametersResolver.resolve(
                "workflow_dispatch",
                Map.of(
                        "INPUT_NEXT_VERSION_INCREMENT", "patch",
                        "INPUT_NEXT_DEVELOPMENT_VERSION", " 2.0.0-SNAPSHOT ",
                        "INPUT_SKIP_TESTS", "false",
                        "INPUT_DRY_RUN", "false"),
                null,
                "1.2.9-SNAPSHOT");

        assertThat(parameters.outputs()).containsExactly(
                Map.entry("release_version", "1.2.9"),
                Map.entry("next_development_version", "2.0.0-SNAPSHOT"),
                Map.entry("skip_tests", "false"),
                Map.entry("dry_run", "false"),
                Map.entry("resume_staged_release", "false"));
    }

    @Test
    void dispatchDerivesPatchMinorAndMajorVersions() {
        assertThat(dispatch("patch").nextDevelopmentVersion())
                .isEqualTo("1.2.10-SNAPSHOT");
        assertThat(dispatch("minor").nextDevelopmentVersion())
                .isEqualTo("1.3.0-SNAPSHOT");
        assertThat(dispatch("major").nextDevelopmentVersion())
                .isEqualTo("2.0.0-SNAPSHOT");
    }

    @Test
    void exactNextVersionMakesTheIncrementIrrelevant() {
        var parameters = ReleaseParametersResolver.resolve(
                "workflow_dispatch",
                Map.of(
                        "INPUT_NEXT_VERSION_INCREMENT", "not-used",
                        "INPUT_NEXT_DEVELOPMENT_VERSION", "3.7.4-SNAPSHOT"),
                null,
                "1.2.9-SNAPSHOT");

        assertThat(parameters.nextDevelopmentVersion())
                .isEqualTo("3.7.4-SNAPSHOT");
    }

    @Test
    void pushPreservesReviewedResumeParameters() {
        var parameters = ReleaseParametersResolver.resolve(
                "push",
                Map.of(),
                Map.of(
                        "release_version", "1.2.9 ",
                        "next_development_version", " 1.3.0-SNAPSHOT",
                        "skip_tests", false,
                        "dry_run", false,
                        "resume_staged_release", true),
                null);

        assertThat(parameters.releaseVersion()).isEqualTo("1.2.9");
        assertThat(parameters.nextDevelopmentVersion())
                .isEqualTo("1.3.0-SNAPSHOT");
        assertThat(parameters.resumeStagedRelease()).isTrue();
    }

    @Test
    void normalPushMayLeaveTheNextVersionEmpty() {
        var parameters = ReleaseParametersResolver.resolve(
                "push",
                Map.of(),
                Map.of(
                        "release_version", "1.3.0",
                        "skip_tests", false,
                        "dry_run", false),
                null);

        assertThat(parameters.nextDevelopmentVersion()).isEmpty();
    }

    @Test
    void resumeRequiresANextVersionAndCannotBeADryRun() {
        assertThatThrownBy(() -> ReleaseParametersResolver.resolve(
                "push",
                Map.of(),
                Map.of(
                        "release_version", "1.2.9",
                        "next_development_version", "",
                        "resume_staged_release", true),
                null))
                .hasMessageContaining("next_development_version is required");

        assertThatThrownBy(() -> ReleaseParametersResolver.resolve(
                "push",
                Map.of(),
                Map.of(
                        "release_version", "1.2.9",
                        "next_development_version", "1.3.0-SNAPSHOT",
                        "dry_run", true,
                        "resume_staged_release", true),
                null))
                .hasMessageContaining("cannot be combined with dry_run");
    }

    @Test
    void invalidIncrementAndRepositoryVersionFailClosed() {
        assertThatThrownBy(() -> dispatch("custom"))
                .hasMessageContaining("patch, minor or major");
        assertThatThrownBy(() -> ReleaseParametersResolver.resolve(
                "workflow_dispatch",
                Map.of("INPUT_NEXT_VERSION_INCREMENT", "patch"),
                null,
                "1.2.9"))
                .hasMessageContaining("must use X.Y.Z-SNAPSHOT");
    }

    @Test
    void nonAdvancingVersionsGiveTriggerSpecificGuidance() {
        assertThatThrownBy(() -> ReleaseParametersResolver.resolve(
                "workflow_dispatch",
                Map.of("INPUT_NEXT_DEVELOPMENT_VERSION", "1.3.0-SNAPSHOT"),
                null,
                "1.3.0-SNAPSHOT"))
                .hasMessageContaining(
                        "current project version 1.3.0-SNAPSHOT means this run releases 1.3.0")
                .hasMessageContaining("patch, minor or major");

        assertThatThrownBy(() -> ReleaseParametersResolver.resolve(
                "push",
                Map.of(),
                Map.of(
                        "release_version", "1.2.9",
                        "next_development_version", "1.2.9-SNAPSHOT"),
                null))
                .hasMessageContaining("release request publishes 1.2.9")
                .hasMessageContaining("must be newer");
    }

    @Test
    void cliWritesStableGitHubOutputOrder(@TempDir Path root) throws Exception {
        writePom(root, "1.2.9-SNAPSHOT");
        Path output = root.resolve("github-output");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = TaxonomyTooling.run(
                new String[]{"resolve-release-parameters"},
                Map.of(
                        "EVENT_NAME", "workflow_dispatch",
                        "INPUT_NEXT_VERSION_INCREMENT", "patch",
                        "INPUT_NEXT_DEVELOPMENT_VERSION", "2.0.0-SNAPSHOT",
                        "INPUT_SKIP_TESTS", "false",
                        "INPUT_DRY_RUN", "false",
                        "GITHUB_OUTPUT", output.toString()),
                root,
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                new PrintStream(errors));

        assertThat(exitCode).as(errors.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(Files.readAllLines(output, StandardCharsets.UTF_8))
                .containsExactly(
                        "release_version=1.2.9",
                        "next_development_version=2.0.0-SNAPSHOT",
                        "skip_tests=false",
                        "dry_run=false",
                        "resume_staged_release=false");
    }

    @Test
    void cliReportsMissingRequiredEnvironment(@TempDir Path root) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exitCode = TaxonomyTooling.run(
                new String[]{"resolve-release-parameters"},
                Map.of("GITHUB_OUTPUT", root.resolve("output").toString()),
                root,
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                new PrintStream(errors));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("::error::EVENT_NAME environment variable is required");
    }

    private static ReleaseParametersResolver.Parameters dispatch(String increment) {
        return ReleaseParametersResolver.resolve(
                "workflow_dispatch",
                Map.of(
                        "INPUT_NEXT_VERSION_INCREMENT", increment,
                        "INPUT_NEXT_DEVELOPMENT_VERSION", ""),
                null,
                "1.2.9-SNAPSHOT");
    }

    private static void writePom(Path root, String version) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version), StandardCharsets.UTF_8);
    }
}
