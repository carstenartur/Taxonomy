package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class VersionStateRepositoryTest {

    @Test
    void repositoryMetadataMatchesTheRequestedBuildState() throws Exception {
        Path root = findRepositoryRoot();
        BuildState state = resolveBuildState(
                System.getenv(), System.getProperties());
        VersionStateVerifier.Verification verification =
                VersionStateVerifier.verify(
                        root,
                        state.mode(),
                        state.expectedVersion(),
                        state.tag());
        String reportText = verification.successful()
                ? "Repository version state is consistent: "
                        + verification.version() + " (" + verification.mode() + ").\n"
                : "Inconsistent repository version state:\n- "
                        + String.join("\n- ", verification.failures()) + "\n";
        Path report = root.resolve("target/version-state-report.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, reportText, StandardCharsets.UTF_8);
        System.out.print(reportText);

        assertThat(verification.failures())
                .as("repository version state; inspect %s", report)
                .isEmpty();
    }

    @Test
    void releaseAndAdvancedMavenStatesSelectTheCorrectContracts() {
        Properties release = new Properties();
        release.setProperty("releaseCheckCurrentState", "release");
        release.setProperty("releaseVersion", "1.4.0");
        assertThat(resolveBuildState(Map.of(), release))
                .extracting(BuildState::mode, BuildState::expectedVersion, BuildState::tag)
                .containsExactly("release", "1.4.0", null);

        Properties advanced = new Properties();
        advanced.setProperty("releaseCheckCurrentState", "advanced");
        advanced.setProperty("nextDevelopmentVersion", "1.4.1-SNAPSHOT");
        assertThat(resolveBuildState(Map.of(), advanced))
                .extracting(BuildState::mode, BuildState::expectedVersion, BuildState::tag)
                .containsExactly("development", "1.4.1-SNAPSHOT", null);
    }

    @Test
    void releasePathsUseTheJavaToolingBoundaryWithoutRemovedPythonAdapters()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = Files.readString(
                root.resolve(".github/workflows/deploy-release.yml"),
                StandardCharsets.UTF_8);
        String script = Files.readString(
                root.resolve(".github/scripts/release.sh"),
                StandardCharsets.UTF_8);

        assertThat(workflow)
                .contains("taxonomy-tooling.jar")
                .contains("resolve-release-parameters")
                .doesNotContain("resolve-release-parameters.py")
                .doesNotContain("check-version-state.py");
        assertThat(script)
                .contains("java -jar \"$TOOLING_JAR\" check-version-state")
                .contains("-DreleaseCheckCurrentState=\"$state\"")
                .contains("-DreleaseVersion=\"$RELEASE_VERSION\"")
                .contains("-DnextDevelopmentVersion=\"$NEXT_VERSION\"")
                .doesNotContain("python3");
        assertThat(root.resolve(".github/scripts/resolve-release-parameters.py"))
                .doesNotExist();
        assertThat(root.resolve(".github/scripts/check-version-state.py"))
                .doesNotExist();
        assertThat(root.resolve(".github/scripts/check-release-plan.py"))
                .doesNotExist();
    }

    static BuildState resolveBuildState(
            Map<String, String> environment,
            Properties properties) {
        String releaseState = normalize(
                properties.getProperty("releaseCheckCurrentState"));
        String fallbackMode = "release".equals(releaseState)
                ? "release"
                : "development";
        String mode = normalize(environment.get("VERSION_STATE_MODE"));
        if (mode == null) {
            mode = fallbackMode;
        }
        String expected = normalize(
                environment.get("VERSION_STATE_EXPECTED_VERSION"));
        if (expected == null) {
            expected = expectedVersionFor(mode, releaseState, properties);
        }
        return new BuildState(
                mode,
                expected,
                normalize(environment.get("VERSION_STATE_TAG")));
    }

    private static String expectedVersionFor(
            String mode,
            String releaseState,
            Properties properties) {
        if ("release".equals(mode)) {
            return normalize(properties.getProperty("releaseVersion"));
        }
        if ("advanced".equals(releaseState)) {
            return normalize(properties.getProperty("nextDevelopmentVersion"));
        }
        if ("development".equals(releaseState)) {
            String releaseVersion = normalize(
                    properties.getProperty("releaseVersion"));
            return releaseVersion == null ? null : releaseVersion + "-SNAPSHOT";
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))
                    && Files.isRegularFile(current.resolve("CITATION.cff"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }

    record BuildState(String mode, String expectedVersion, String tag) {
    }
}
