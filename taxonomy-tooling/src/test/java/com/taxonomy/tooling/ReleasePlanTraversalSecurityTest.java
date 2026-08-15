package com.taxonomy.tooling;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleasePlanTraversalSecurityTest {

    @Test
    void rejectsDeclaredModuleThatEscapesThroughASymbolicLink(
            @TempDir Path root) throws Exception {
        writeRootPom(root, "linked-module");
        Path outside = root.resolveSibling(
                root.getFileName() + "-outside-module");
        writeModulePom(outside);
        try {
            Files.createSymbolicLink(root.resolve("linked-module"), outside);
        } catch (UnsupportedOperationException | IOException failure) {
            Assumptions.abort("Symbolic links are unavailable: " + failure);
        }

        assertThatThrownBy(() -> validate(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link")
                .hasMessageContaining("linked-module");
    }

    @Test
    void prunesGitAndGeneratedTreesWhenLookingForReleaseBackups(
            @TempDir Path root) throws Exception {
        writeRootPom(root, null);
        write(root.resolve(".git/objects/pom.xml.releaseBackup"), "ignored");
        write(root.resolve("target/generated/pom.xml.releaseBackup"), "ignored");

        assertThat(validate(root).pomCount()).isEqualTo(1);

        write(root.resolve("src/pom.xml.releaseBackup"), "stale");
        assertThatThrownBy(() -> validate(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("src/pom.xml.releaseBackup")
                .hasMessageContaining("stale Maven Release Plugin backup");
    }

    private static ReleasePlanValidator.Result validate(Path root)
            throws Exception {
        return ReleasePlanValidator.validate(
                root,
                "1.3.0-SNAPSHOT",
                "1.3.0",
                "1.3.1-SNAPSHOT",
                "development",
                false);
    }

    private static void writeRootPom(Path root, String module)
            throws Exception {
        String modules = module == null
                ? ""
                : "<modules><module>" + module + "</module></modules>";
        write(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>1.3.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  %s
                </project>
                """.formatted(modules));
    }

    private static void writeModulePom(Path module) throws Exception {
        write(module.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.taxonomy</groupId>
                    <artifactId>taxonomy</artifactId>
                    <version>1.3.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>linked-module</artifactId>
                </project>
                """);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
