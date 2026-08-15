package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TaxonomyToolingTest {

    @Test
    void readPomVersionDefaultsToTheWorkingDirectoryPom(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>fixture</artifactId>
                  <version>2.3.4-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = TaxonomyTooling.run(
                new String[]{"read-pom-version"},
                root,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("2.3.4-SNAPSHOT\n");
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void readPomVersionStillAcceptsAnExplicitFile(@TempDir Path root)
            throws Exception {
        Path nested = root.resolve("nested.xml");
        Files.writeString(nested, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <version>9.8.7</version>
                </project>
                """, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = TaxonomyTooling.run(
                new String[]{"read-pom-version", "--file", nested.toString()},
                root,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("9.8.7\n");
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
