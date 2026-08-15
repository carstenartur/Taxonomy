package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleasePlanCoordinatePropertyTest {

    @Test
    void resolvesInternalDependencyCoordinatesFromMavenProperties(
            @TempDir Path root) throws Exception {
        write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>1.3.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <properties>
                    <internal.module>module-a</internal.module>
                  </properties>
                  <modules><module>module-a</module></modules>
                  <dependencies>
                    <dependency>
                      <groupId>${project.groupId}</groupId>
                      <artifactId>${internal.module}</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        write(root.resolve("module-a/pom.xml"), modulePom());

        ReleasePlanValidator.Result result = validate(root);

        assertThat(result.pomCount()).isEqualTo(2);
    }

    @Test
    void reportsUnresolvedCoordinatePropertiesBeforeSnapshotClassification(
            @TempDir Path root) throws Exception {
        write(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>1.3.0-SNAPSHOT</version>
                  <dependencies>
                    <dependency>
                      <groupId>${missing.group}</groupId>
                      <artifactId>external</artifactId>
                      <version>9.0.0-SNAPSHOT</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThatThrownBy(() -> validate(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved coordinate property")
                .hasMessageContaining("${missing.group}:external");
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

    private static String modulePom() {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.taxonomy</groupId>
                    <artifactId>taxonomy</artifactId>
                    <version>1.3.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>module-a</artifactId>
                </project>
                """;
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
