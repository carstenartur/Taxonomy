package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the retained version-state release adapter against the real checkout. */
class VersionStateRepositoryTest {

    @Test
    void repositoryMetadataMatchesTheRequestedBuildState() throws Exception {
        Path root = findRepositoryRoot();
        BuildState buildState = resolveBuildState(
                System.getenv(), System.getProperties());

        List<String> command = new ArrayList<>(List.of(
                "python3",
                root.resolve(".github/scripts/check-version-state.py").toString(),
                "--root",
                root.toString(),
                "--mode",
                buildState.mode()));
        if (buildState.expectedVersion() != null) {
            command.add("--expected-version");
            command.add(buildState.expectedVersion());
        }
        if (buildState.tag() != null) {
            command.add("--tag");
            command.add(buildState.tag());
        }

        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        Path report = root.resolve("target/version-state-report.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, output, StandardCharsets.UTF_8);
        System.out.print(output);

        assertThat(exitCode)
                .as("repository version state; inspect %s%n%s", report, output)
                .isZero();
    }

    @Test
    void releaseMavenStateSelectsReleaseContractWithoutWorkflowEnvironment() {
        Properties properties = new Properties();
        properties.setProperty("releaseCheckCurrentState", "release");
        properties.setProperty("releaseVersion", "1.4.0");

        BuildState buildState = resolveBuildState(Map.of(), properties);

        assertThat(buildState)
                .extracting(
                        BuildState::mode,
                        BuildState::expectedVersion,
                        BuildState::tag)
                .containsExactly("release", "1.4.0", null);
    }

    @Test
    void advancedMavenStateSelectsNextDevelopmentContract() {
        Properties properties = new Properties();
        properties.setProperty("releaseCheckCurrentState", "advanced");
        properties.setProperty("nextDevelopmentVersion", "1.4.1-SNAPSHOT");

        BuildState buildState = resolveBuildState(Map.of(), properties);

        assertThat(buildState)
                .extracting(
                        BuildState::mode,
                        BuildState::expectedVersion,
                        BuildState::tag)
                .containsExactly("development", "1.4.1-SNAPSHOT", null);
    }

    @Test
    void releaseWorkflowUsesTheJUnitOwnedContractWithoutPythonUnittestDuplication()
            throws Exception {
        Path root = findRepositoryRoot();
        Path releaseWorkflow = root.resolve(".github/workflows/deploy-release.yml");
        Path releaseScript = root.resolve(".github/scripts/release.sh");
        Path obsoletePythonTest = root.resolve(
                ".github/scripts/test-check-version-state.py");
        String workflow = Files.readString(releaseWorkflow, StandardCharsets.UTF_8);
        String script = Files.readString(releaseScript, StandardCharsets.UTF_8);

        assertThat(workflow)
                .as("release workflow must retain the runtime version-state adapter")
                .contains("check-version-state.py");
        assertThat(workflow)
                .as("JUnit owns the adapter contract; workflows must not run Python unittest")
                .doesNotContain("test-check-version-state.py");
        assertThat(script)
                .as("release Maven invocations must expose their exact lifecycle state")
                .contains("-DreleaseCheckCurrentState=\"$state\"")
                .contains("-DreleaseVersion=\"$RELEASE_VERSION\"")
                .contains("-DnextDevelopmentVersion=\"$NEXT_VERSION\"");
        assertThat(obsoletePythonTest)
                .as("duplicate Python unittest implementation must be removed")
                .doesNotExist();
    }

    static BuildState resolveBuildState(
            Map<String, String> environment,
            Properties systemProperties) {
        String releaseState = normalize(
                systemProperties.getProperty("releaseCheckCurrentState"));
        String fallbackMode = "release".equals(releaseState)
                ? "release"
                : "development";
        String mode = normalize(environment.get("VERSION_STATE_MODE"));
        if (mode == null) {
            mode = fallbackMode;
        }

        String expectedVersion = normalize(
                environment.get("VERSION_STATE_EXPECTED_VERSION"));
        if (expectedVersion == null) {
            expectedVersion = expectedVersionFor(
                    mode, releaseState, systemProperties);
        }
        String tag = normalize(environment.get("VERSION_STATE_TAG"));
        return new BuildState(mode, expectedVersion, tag);
    }

    private static String expectedVersionFor(
            String mode,
            String releaseState,
            Properties systemProperties) {
        if ("release".equals(mode)) {
            return normalize(systemProperties.getProperty("releaseVersion"));
        }
        if ("advanced".equals(releaseState)) {
            return normalize(systemProperties.getProperty(
                    "nextDevelopmentVersion"));
        }
        if ("development".equals(releaseState)) {
            String releaseVersion = normalize(
                    systemProperties.getProperty("releaseVersion"));
            return releaseVersion == null
                    ? null
                    : releaseVersion + "-SNAPSHOT";
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
            if (Files.isRegularFile(current.resolve(
                    ".github/scripts/check-version-state.py"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }

    record BuildState(
            String mode,
            String expectedVersion,
            String tag) {
    }
}
