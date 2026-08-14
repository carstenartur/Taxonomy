package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReleasePlanStateNormalizationTest {

    @Test
    void trimsLifecycleStateBeforeValidation(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>fixture</artifactId>
                  <version>1.3.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        ReleasePlanValidator.Result result = ReleasePlanValidator.validate(
                root,
                "1.3.0-SNAPSHOT",
                "1.3.0",
                "1.3.1-SNAPSHOT",
                "  development\n",
                false);

        assertThat(result.state()).isEqualTo("development");
        assertThat(result.pomCount()).isEqualTo(1);
        assertThat(ReleasePlanValidator.expectedCurrentVersion(
                "1.3.0",
                "1.3.1-SNAPSHOT",
                "\t release "))
                .isEqualTo("1.3.0");
    }
}
