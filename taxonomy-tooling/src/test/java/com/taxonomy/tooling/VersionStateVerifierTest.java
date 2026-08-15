package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VersionStateVerifierTest {

    @Test
    void acceptsConsistentDevelopmentAndReleaseStates(@TempDir Path root)
            throws Exception {
        writeRepository(root, "1.2.9-SNAPSHOT", false);
        var development = VersionStateVerifier.verify(
                root, "development", null, null);
        assertThat(development.successful()).isTrue();

        writeRepository(root, "1.2.9", true);
        var release = VersionStateVerifier.verify(
                root, "release", "1.2.9", "v1.2.9");
        assertThat(release.successful()).isTrue();
    }

    @Test
    void rejectsSnapshotReleaseAndReleaseTagInDevelopmentMode(
            @TempDir Path root) throws Exception {
        writeRepository(root, "1.2.9-SNAPSHOT", false);

        var release = VersionStateVerifier.verify(root, "release", null, null);
        var taggedDevelopment = VersionStateVerifier.verify(
                root, "development", null, "v1.2.9-SNAPSHOT");

        assertThat(release.failures())
                .anyMatch(message -> message.contains("not a valid release version"));
        assertThat(taggedDevelopment.failures())
                .contains("A release tag is only valid in release mode");
    }

    @Test
    void rejectsUnexpectedTagAndExplicitExpectedVersion(@TempDir Path root)
            throws Exception {
        writeRepository(root, "1.2.9", true);
        assertThat(VersionStateVerifier.verify(
                root, "release", null, "v1.2.8").failures())
                .contains("Tag 'v1.2.8' != expected 'v1.2.9'");

        writeRepository(root, "1.2.9-SNAPSHOT", false);
        assertThat(VersionStateVerifier.verify(
                root,
                "development",
                "1.2.10-SNAPSHOT",
                null).failures())
                .contains("Root Maven version '1.2.9-SNAPSHOT' != expected '1.2.10-SNAPSHOT'");
    }

    @Test
    void rejectsPomHelmCitationAndArchiveDrift(@TempDir Path root)
            throws Exception {
        writeRepository(root, "1.2.9-SNAPSHOT", false);
        write(root.resolve("module/pom.xml"), modulePom("1.2.8-SNAPSHOT"));
        write(root.resolve("deploy/helm/taxonomy/Chart.yaml"), """
                apiVersion: v2
                name: taxonomy
                appVersion: "1.2.8-SNAPSHOT"
                """);
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

        var verification = VersionStateVerifier.verify(
                root, "development", null, null);

        assertThat(verification.failures())
                .anyMatch(message -> message.contains("module/pom.xml parent version"))
                .contains(
                        "Helm Chart.yaml appVersion does not match the Maven version",
                        "CITATION.cff version does not match the Maven version",
                        "CITATION.cff release-date state does not match the requested mode",
                        ".zenodo.json version does not match",
                        ".zenodo.json release-date state does not match the requested mode",
                        "codemeta.json version does not match",
                        "codemeta.json release-date state does not match the requested mode");
    }

    private static void writeRepository(
            Path root,
            String version,
            boolean release) throws Exception {
        write(root.resolve("pom.xml"), rootPom(version));
        write(root.resolve("module/pom.xml"), modulePom(version));
        write(root.resolve("CITATION.cff"),
                "version: \"" + version + "\"\n"
                        + (release ? "date-released: \"2026-08-10\"\n" : ""));
        write(root.resolve("CITATION.md"),
                "Carsten Hammer. **Taxonomy Architecture Analyzer**. Version "
                        + version + ". 2026.\n"
                        + "  version      = {" + version + "},\n"
                        + (release ? "  date         = {2026-08-10},\n" : ""));
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

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
