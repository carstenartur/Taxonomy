package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the retained version-state release adapter against the real checkout. */
class VersionStateRepositoryTest {

    @Test
    void repositoryMetadataMatchesTheRequestedBuildState() throws Exception {
        Path root = findRepositoryRoot();
        String mode = environmentOrDefault("VERSION_STATE_MODE", "development");
        String expectedVersion = environmentOrNull("VERSION_STATE_EXPECTED_VERSION");
        String tag = environmentOrNull("VERSION_STATE_TAG");

        List<String> command = new ArrayList<>(List.of(
                "python3",
                root.resolve(".github/scripts/check-version-state.py").toString(),
                "--root",
                root.toString(),
                "--mode",
                mode));
        if (expectedVersion != null) {
            command.add("--expected-version");
            command.add(expectedVersion);
        }
        if (tag != null) {
            command.add("--tag");
            command.add(tag);
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

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String environmentOrNull(String name) {
        String value = System.getenv(name);
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
}
