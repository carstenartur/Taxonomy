package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseMetadataUpdaterTest {

    @Test
    void appliesOneCoherentReleaseStateWithExplicitDate(@TempDir Path root)
            throws Exception {
        writeFixture(root, "1.4.0", false, true);

        ReleaseMetadataUpdater.Result result = ReleaseMetadataUpdater.update(
                root, "1.4.0", true, LocalDate.of(2026, 8, 15));

        assertThat(result.updatedFiles()).containsExactly(
                "CITATION.cff",
                ".zenodo.json",
                "codemeta.json",
                "CITATION.md",
                "deploy/helm/taxonomy/Chart.yaml");
        assertThat(read(root.resolve("CITATION.cff")))
                .contains("version: \"1.4.0\"")
                .contains("date-released: \"2026-08-15\"");
        assertThat(read(root.resolve("CITATION.md")))
                .contains("Version 1.4.0. 2026.")
                .contains("version      = {1.4.0},")
                .contains("date         = {2026-08-15},")
                .contains(ReleaseMetadataUpdater.ORCID_URL);
        assertThat(readJson(root.resolve(".zenodo.json")))
                .containsEntry("version", "1.4.0")
                .containsEntry("publication_date", "2026-08-15")
                .containsEntry("description", "Größe und Qualität");
        assertThat(readJson(root.resolve("codemeta.json")))
                .containsEntry("version", "1.4.0")
                .containsEntry("datePublished", "2026-08-15");
        assertThat(read(root.resolve("deploy/helm/taxonomy/Chart.yaml")))
                .contains("appVersion: \"1.4.0\"");
        assertThat(VersionStateVerifier.verify(
                root, "release", "1.4.0", "v1.4.0").failures())
                .isEmpty();
    }

    @Test
    void removesReleaseOnlyDatesForDevelopmentSnapshot(@TempDir Path root)
            throws Exception {
        writeFixture(root, "1.4.1-SNAPSHOT", true, false);

        ReleaseMetadataUpdater.update(
                root, "1.4.1-SNAPSHOT", false, null);

        assertThat(read(root.resolve("CITATION.cff")))
                .contains("version: \"1.4.1-SNAPSHOT\"")
                .doesNotContain("date-released:");
        assertThat(read(root.resolve("CITATION.md")))
                .contains("Version 1.4.1-SNAPSHOT. 2026.")
                .contains("version      = {1.4.1-SNAPSHOT},")
                .doesNotContain("date         = {");
        assertThat(readJson(root.resolve(".zenodo.json")))
                .containsEntry("version", "1.4.1-SNAPSHOT")
                .doesNotContainKey("publication_date");
        assertThat(readJson(root.resolve("codemeta.json")))
                .containsEntry("version", "1.4.1-SNAPSHOT")
                .doesNotContainKey("datePublished");
        assertThat(VersionStateVerifier.verify(
                root,
                "development",
                "1.4.1-SNAPSHOT",
                null).failures()).isEmpty();
    }

    @Test
    void validatesEveryTransformationBeforeWritingAnyFile(@TempDir Path root)
            throws Exception {
        writeFixture(root, "1.4.0", false, false);
        Path chart = root.resolve("deploy/helm/taxonomy/Chart.yaml");
        Files.writeString(chart, "apiVersion: v2\nname: taxonomy\n");
        Map<Path, String> before = snapshot(root);

        assertThatThrownBy(() -> ReleaseMetadataUpdater.update(
                root, "1.4.0", true, LocalDate.of(2026, 8, 15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no appVersion field");

        assertThat(snapshot(root)).isEqualTo(before);
    }

    @Test
    void cliUsesExplicitDateAndRejectsDateForDevelopment(@TempDir Path root)
            throws Exception {
        writeFixture(root, "1.4.0", false, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int releaseExit = TaxonomyTooling.run(
                new String[]{
                        "update-release-metadata",
                        "--root", root.toString(),
                        "--version", "1.4.0",
                        "--release",
                        "--date", "2026-08-15"},
                root,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(releaseExit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("1.4.0")
                .contains("release 2026-08-15")
                .contains("5 files");
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();

        errors.reset();
        int invalidExit = TaxonomyTooling.run(
                new String[]{
                        "update-release-metadata",
                        "--root", root.toString(),
                        "--version", "1.4.1-SNAPSHOT",
                        "--date", "2026-08-16"},
                root,
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        assertThat(invalidExit).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("--date is valid only together with --release");
    }

    private static void writeFixture(
            Path root,
            String projectVersion,
            boolean includeReleaseDates,
            boolean omitOrcid) throws Exception {
        write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(projectVersion));
        write(root.resolve("CITATION.cff"),
                "cff-version: 1.2.0\n"
                        + "title: Taxonomy\n"
                        + "version: \"old\"\n"
                        + (includeReleaseDates
                                ? "date-released: \"2026-01-01\"\n"
                                : ""));
        String authorIdentifier = omitOrcid
                ? ""
                : "## Author identifier\n\nORCID: "
                        + ReleaseMetadataUpdater.ORCID_URL + "\n\n";
        String bibtexOrcid = omitOrcid
                ? ""
                : "  orcid        = {" + ReleaseMetadataUpdater.ORCID_URL + "},\n";
        write(root.resolve("CITATION.md"), """
                # Citation

                %s## What to cite

                Carsten Hammer. **Taxonomy Architecture Analyzer**. Version old. 2026.

                ```bibtex
                @software{taxonomy,
                  author       = {Hammer, Carsten},
                %s  version      = {old},
                %s}
                ```
                """.formatted(
                        authorIdentifier,
                        bibtexOrcid,
                        includeReleaseDates
                                ? "  date         = {2026-01-01},\n"
                                : ""));
        write(root.resolve(".zenodo.json"), """
                {
                  "title": "Taxonomy",
                  "description": "Größe und Qualität",
                  "creators": [
                    {
                      "name": "Hammer, Carsten",
                      "orcid": "0009-0005-1047-6381"
                    }
                  ],
                  "version": "old"%s
                }
                """.formatted(includeReleaseDates
                        ? ",\n  \"publication_date\": \"2026-01-01\""
                        : ""));
        write(root.resolve("codemeta.json"), """
                {
                  "@type": "SoftwareSourceCode",
                  "author": {
                    "givenName": "Carsten",
                    "familyName": "Hammer"
                  },
                  "version": "old"%s
                }
                """.formatted(includeReleaseDates
                        ? ",\n  \"datePublished\": \"2026-01-01\""
                        : ""));
        write(root.resolve("deploy/helm/taxonomy/Chart.yaml"), """
                apiVersion: v2
                name: taxonomy
                version: 1.4.0
                appVersion: "old"
                """);
    }

    private static Map<Path, String> snapshot(Path root) throws Exception {
        LinkedHashMap<Path, String> result = new LinkedHashMap<>();
        for (Path path : java.util.List.of(
                root.resolve("CITATION.cff"),
                root.resolve(".zenodo.json"),
                root.resolve("codemeta.json"),
                root.resolve("CITATION.md"),
                root.resolve("deploy/helm/taxonomy/Chart.yaml"))) {
            result.put(path, read(path));
        }
        return result;
    }

    private static Map<String, Object> readJson(Path path) throws Exception {
        return FlatJson.parseObject(read(path));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
