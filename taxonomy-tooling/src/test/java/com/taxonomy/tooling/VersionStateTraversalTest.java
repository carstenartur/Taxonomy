package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VersionStateTraversalTest {

    @Test
    void prunesGitAndGeneratedTreesBeforeParsingPomFiles(@TempDir Path root)
            throws Exception {
        String version = "1.2.9-SNAPSHOT";
        write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version));
        write(root.resolve("CITATION.cff"),
                "version: \"" + version + "\"\n");
        write(root.resolve("CITATION.md"),
                "Carsten Hammer. **Taxonomy Architecture Analyzer**. Version "
                        + version + ". 2026.\n"
                        + "  version      = {" + version + "},\n");
        write(root.resolve(".zenodo.json"),
                "{\"version\":\"" + version + "\"}\n");
        write(root.resolve("codemeta.json"),
                "{\"version\":\"" + version + "\"}\n");

        write(root.resolve("target/pom.xml"), "<deliberately-invalid");
        write(root.resolve(".git/pom.xml"), "<deliberately-invalid");

        VersionStateVerifier.Verification verification =
                VersionStateVerifier.verify(
                        root, "development", version, null);

        assertThat(verification.failures()).isEmpty();
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
