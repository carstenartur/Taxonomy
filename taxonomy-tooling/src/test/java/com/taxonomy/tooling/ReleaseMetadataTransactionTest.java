package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseMetadataTransactionTest {

    @Test
    void restoresEveryFileWhenAReplacementFailsMidTransaction(
            @TempDir Path root) throws Exception {
        writeFixture(root);
        Map<Path, String> before = snapshot(root);
        AtomicInteger replacements = new AtomicInteger();
        IOException failure = new IOException("simulated third replacement failure");

        assertThatThrownBy(() -> ReleaseMetadataUpdater.update(
                root,
                "1.4.0",
                true,
                LocalDate.of(2026, 8, 15),
                (staged, target) -> {
                    if (replacements.incrementAndGet() == 3) {
                        throw failure;
                    }
                    Files.move(
                            staged,
                            target,
                            StandardCopyOption.REPLACE_EXISTING);
                }))
                .isSameAs(failure);

        assertThat(replacements).hasValue(3);
        assertThat(snapshot(root)).isEqualTo(before);
        try (var paths = Files.walk(root)) {
            assertThat(paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".taxonomy-metadata-"))
                    .toList())
                    .isEmpty();
        }
    }

    private static Map<Path, String> snapshot(Path root) throws Exception {
        LinkedHashMap<Path, String> result = new LinkedHashMap<>();
        for (Path path : metadataPaths(root)) {
            result.put(path, Files.readString(path, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static java.util.List<Path> metadataPaths(Path root) {
        return java.util.List.of(
                root.resolve("CITATION.cff"),
                root.resolve(".zenodo.json"),
                root.resolve("codemeta.json"),
                root.resolve("CITATION.md"),
                root.resolve("deploy/helm/taxonomy/Chart.yaml"));
    }

    private static void writeFixture(Path root) throws Exception {
        write(root.resolve("CITATION.cff"), """
                cff-version: 1.2.0
                title: Taxonomy
                version: "1.4.0-SNAPSHOT"
                """);
        write(root.resolve(".zenodo.json"), """
                {
                  "title": "Taxonomy",
                  "version": "1.4.0-SNAPSHOT"
                }
                """);
        write(root.resolve("codemeta.json"), """
                {
                  "@type": "SoftwareSourceCode",
                  "version": "1.4.0-SNAPSHOT"
                }
                """);
        write(root.resolve("CITATION.md"), """
                # Citation

                ## Author identifier

                ORCID: https://orcid.org/0009-0005-1047-6381

                ## What to cite

                Carsten Hammer. **Taxonomy Architecture Analyzer**. Version 1.4.0-SNAPSHOT. 2026.

                ```bibtex
                @software{taxonomy,
                  author       = {Hammer, Carsten},
                  orcid        = {https://orcid.org/0009-0005-1047-6381},
                  version      = {1.4.0-SNAPSHOT},
                }
                ```
                """);
        write(root.resolve("deploy/helm/taxonomy/Chart.yaml"), """
                apiVersion: v2
                name: taxonomy
                version: 1.4.0
                appVersion: "1.4.0-SNAPSHOT"
                """);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
