package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseMetadataIdempotencyTest {

    @Test
    void repeatedReleaseTransformationIsByteIdentical(@TempDir Path root)
            throws Exception {
        writeFixture(root, "1.4.0");

        ReleaseMetadataUpdater.update(
                root, "1.4.0", true, LocalDate.of(2031, 8, 15));
        Map<String, String> first = snapshot(root);
        ReleaseMetadataUpdater.update(
                root, "1.4.0", true, LocalDate.of(2031, 8, 15));

        assertThat(snapshot(root)).isEqualTo(first);
    }

    @Test
    void repeatedDevelopmentTransformationIsByteIdentical(@TempDir Path root)
            throws Exception {
        writeFixture(root, "1.4.1-SNAPSHOT");

        ReleaseMetadataUpdater.update(
                root, "1.4.1-SNAPSHOT", false, null);
        Map<String, String> first = snapshot(root);
        ReleaseMetadataUpdater.update(
                root, "1.4.1-SNAPSHOT", false, null);

        assertThat(snapshot(root)).isEqualTo(first);
    }

    private static void writeFixture(Path root, String projectVersion)
            throws Exception {
        write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(projectVersion));
        write(root.resolve("CITATION.cff"), """
                cff-version: 1.2.0
                title: Taxonomy
                version: "old"
                date-released: "2026-01-01"
                """);
        write(root.resolve("CITATION.md"), """
                # Citation

                ## Author identifier

                ORCID: https://orcid.org/0009-0005-1047-6381

                ## What to cite

                Carsten Hammer. **Taxonomy Architecture Analyzer**. Version old. 2031.

                ```bibtex
                @software{taxonomy,
                  author       = {Hammer, Carsten},
                  orcid        = {https://orcid.org/0009-0005-1047-6381},
                  version      = {old},
                  date         = {2026-01-01},
                }
                ```
                """);
        write(root.resolve(".zenodo.json"), """
                {
                  "title": "Taxonomy",
                  "nested": {
                    "text": "Größe"
                  },
                  "version": "old",
                  "publication_date": "2026-01-01"
                }
                """);
        write(root.resolve("codemeta.json"), """
                {
                  "@type": "SoftwareSourceCode",
                  "keywords": [
                    "architecture",
                    "taxonomy"
                  ],
                  "version": "old",
                  "datePublished": "2026-01-01"
                }
                """);
        write(root.resolve("deploy/helm/taxonomy/Chart.yaml"), """
                apiVersion: v2
                name: taxonomy
                version: 1.4.0
                appVersion: "old"
                """);
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String path : List.of(
                "CITATION.cff",
                "CITATION.md",
                ".zenodo.json",
                "codemeta.json",
                "deploy/helm/taxonomy/Chart.yaml")) {
            result.put(path, Files.readString(
                    root.resolve(path), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
