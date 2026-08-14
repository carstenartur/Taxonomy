package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseMetadataEdgeCaseTest {

    @Test
    void missingHelmChartFailsBeforeAnyMetadataWrite(@TempDir Path root)
            throws Exception {
        writeRequiredMetadata(root, "2031");
        Map<Path, String> before = snapshot(root);

        assertThatThrownBy(() -> ReleaseMetadataUpdater.update(
                root, "1.4.0", true, LocalDate.of(2031, 8, 15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Required release metadata file is missing")
                .hasMessageContaining("Chart.yaml");

        assertThat(snapshot(root)).isEqualTo(before);
    }

    @Test
    void preservesTheCitationYearWhileReplacingOnlyTheVersion(@TempDir Path root)
            throws Exception {
        writeRequiredMetadata(root, "2031");
        write(root.resolve("deploy/helm/taxonomy/Chart.yaml"), """
                apiVersion: v2
                name: taxonomy
                version: 1.4.0
                appVersion: "old"
                """);

        ReleaseMetadataUpdater.update(
                root, "1.4.0", true, LocalDate.of(2031, 8, 15));

        assertThat(read(root.resolve("CITATION.md")))
                .contains("Version 1.4.0. 2031.")
                .doesNotContain("Version old.");
    }

    @Test
    void invalidIsoDateReturnsControlledCliError(@TempDir Path root)
            throws Exception {
        writeRequiredMetadata(root, "2031");
        write(root.resolve("deploy/helm/taxonomy/Chart.yaml"), """
                apiVersion: v2
                name: taxonomy
                version: 1.4.0
                appVersion: "old"
                """);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = TaxonomyTooling.run(
                new String[]{
                        "update-release-metadata",
                        "--root", root.toString(),
                        "--version", "1.4.0",
                        "--release",
                        "--date", "not-a-date"},
                root,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .startsWith("::error::")
                .contains("not-a-date");
    }

    private static void writeRequiredMetadata(Path root, String year)
            throws Exception {
        write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>1.4.0</version>
                </project>
                """);
        write(root.resolve("CITATION.cff"), """
                cff-version: 1.2.0
                title: Taxonomy
                version: "old"
                """);
        write(root.resolve("CITATION.md"), """
                # Citation

                ## Author identifier

                ORCID: https://orcid.org/0009-0005-1047-6381

                ## What to cite

                Carsten Hammer. **Taxonomy Architecture Analyzer**. Version old. %s.

                ```bibtex
                @software{taxonomy,
                  author       = {Hammer, Carsten},
                  orcid        = {https://orcid.org/0009-0005-1047-6381},
                  version      = {old},
                }
                ```
                """.formatted(year));
        write(root.resolve(".zenodo.json"), """
                {
                  "title": "Taxonomy",
                  "version": "old"
                }
                """);
        write(root.resolve("codemeta.json"), """
                {
                  "@type": "SoftwareSourceCode",
                  "version": "old"
                }
                """);
    }

    private static Map<Path, String> snapshot(Path root) throws Exception {
        LinkedHashMap<Path, String> result = new LinkedHashMap<>();
        for (Path path : java.util.List.of(
                root.resolve("CITATION.cff"),
                root.resolve("CITATION.md"),
                root.resolve(".zenodo.json"),
                root.resolve("codemeta.json"))) {
            result.put(path, read(path));
        }
        return result;
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
