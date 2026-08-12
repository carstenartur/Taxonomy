package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit-owned contract tests for the retained release-state Python adapter.
 *
 * <p>The adapter remains because release.sh copies it into a temporary location
 * and executes it while switching between detached release tags and the next
 * development main. Test ownership, however, belongs to the Maven reactor.</p>
 */
class VersionStateAdapterContractTest {

    @Test
    void acceptsConsistentDevelopmentState(@TempDir Path root) throws Exception {
        writeRepository(root, "1.2.9-SNAPSHOT", false);

        AdapterResult result = run(root, "development");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output())
                .contains("Repository version state is consistent")
                .contains("1.2.9-SNAPSHOT")
                .contains("development");
    }

    @Test
    void acceptsConsistentReleaseStateAndExactTag(@TempDir Path root)
            throws Exception {
        writeRepository(root, "1.2.9", true);

        AdapterResult result = run(root, "release", "--tag", "v1.2.9");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output())
                .contains("Repository version state is consistent")
                .contains("1.2.9")
                .contains("release");
    }

    @Test
    void rejectsSnapshotOnReleasePathAndReleaseTagInDevelopmentMode(
            @TempDir Path root) throws Exception {
        writeRepository(root, "1.2.9-SNAPSHOT", false);

        AdapterResult release = run(root, "release");
        AdapterResult taggedDevelopment = run(
                root, "development", "--tag", "v1.2.9-SNAPSHOT");

        assertThat(release.exitCode()).isNotZero();
        assertThat(release.output()).contains("not a valid release version");
        assertThat(taggedDevelopment.exitCode()).isNotZero();
        assertThat(taggedDevelopment.output())
                .contains("release tag is only valid in release mode");
    }

    @Test
    void rejectsUnexpectedReleaseTag(@TempDir Path root) throws Exception {
        writeRepository(root, "1.2.9", true);

        AdapterResult result = run(root, "release", "--tag", "v1.2.8");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("Tag 'v1.2.8' != expected 'v1.2.9'");
    }

    @Test
    void rejectsRootChildAndHelmVersionDrift(@TempDir Path root) throws Exception {
        writeRepository(root, "1.2.9-SNAPSHOT", false);
        write(root.resolve("module/pom.xml"), modulePom("1.2.8-SNAPSHOT"));
        write(root.resolve("deploy/helm/taxonomy/Chart.yaml"), """
                apiVersion: v2
                name: taxonomy
                appVersion: "1.2.8-SNAPSHOT"
                """);

        AdapterResult result = run(root, "development");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("module/pom.xml parent version")
                .contains("Helm Chart.yaml appVersion");
    }

    @Test
    void rejectsCitationArchiveAndReleaseDateDrift(@TempDir Path root)
            throws Exception {
        writeRepository(root, "1.2.9-SNAPSHOT", false);
        write(root.resolve("CITATION.cff"), """
                version: "1.2.8-SNAPSHOT"
                date-released: "2026-08-10"
                """);
        write(root.resolve(".zenodo.json"), """
                {"version":"1.2.8-SNAPSHOT","publication_date":"2026-08-10"}
                """);
        write(root.resolve("codemeta.json"), """
                {"version":"1.2.8-SNAPSHOT","datePublished":"2026-08-10"}
                """);

        AdapterResult result = run(root, "development");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("CITATION.cff version")
                .contains("CITATION.cff release-date state")
                .contains(".zenodo.json version")
                .contains(".zenodo.json release-date state")
                .contains("codemeta.json version")
                .contains("codemeta.json release-date state");
    }

    @Test
    void rejectsExplicitExpectedVersionMismatch(@TempDir Path root)
            throws Exception {
        writeRepository(root, "1.2.9-SNAPSHOT", false);

        AdapterResult result = run(
                root,
                "development",
                "--expected-version",
                "1.2.10-SNAPSHOT");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("Root Maven version '1.2.9-SNAPSHOT'")
                .contains("expected '1.2.10-SNAPSHOT'");
    }

    private static AdapterResult run(
            Path fixtureRoot,
            String mode,
            String... extra) throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        Path script = repositoryRoot.resolve(".github/scripts/check-version-state.py");
        List<String> command = new ArrayList<>(List.of(
                "python3",
                script.toString(),
                "--root",
                fixtureRoot.toString(),
                "--mode",
                mode));
        command.addAll(List.of(extra));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new AdapterResult(exitCode, output);
    }

    private static void writeRepository(
            Path root,
            String version,
            boolean release) throws IOException {
        write(root.resolve("pom.xml"), rootPom(version));
        write(root.resolve("module/pom.xml"), modulePom(version));

        String releaseDate = release
                ? "date-released: \"2026-08-10\"\n"
                : "";
        write(root.resolve("CITATION.cff"),
                "version: \"" + version + "\"\n" + releaseDate);

        String bibtexDate = release
                ? "  date         = {2026-08-10},\n"
                : "";
        write(root.resolve("CITATION.md"),
                "Carsten Hammer. **Taxonomy Architecture Analyzer**. Version "
                        + version + ". 2026.\n"
                        + "  version      = {" + version + "},\n"
                        + bibtexDate);

        write(root.resolve(".zenodo.json"), release
                ? "{\"version\":\"" + version
                        + "\",\"publication_date\":\"2026-08-10\"}\n"
                : "{\"version\":\"" + version + "\"}\n");
        write(root.resolve("codemeta.json"), release
                ? "{\"version\":\"" + version
                        + "\",\"datePublished\":\"2026-08-10\"}\n"
                : "{\"version\":\"" + version + "\"}\n");
        write(root.resolve("deploy/helm/taxonomy/Chart.yaml"), """
                apiVersion: v2
                name: taxonomy
                appVersion: "%s"
                """.formatted(version));
    }

    private static String rootPom(String version) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version);
    }

    private static String modulePom(String version) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.taxonomy</groupId>
                    <artifactId>taxonomy</artifactId>
                    <version>%s</version>
                  </parent>
                  <artifactId>module</artifactId>
                </project>
                """.formatted(version);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(
                    ".github/scripts/check-version-state.py"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private record AdapterResult(int exitCode, String output) {
    }
}
