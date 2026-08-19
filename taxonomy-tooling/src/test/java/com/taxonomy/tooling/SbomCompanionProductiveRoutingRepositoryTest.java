package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SbomCompanionProductiveRoutingRepositoryTest {

    @Test
    void productiveMavenAndReleasePathsUseJavaInsteadOfPython() throws Exception {
        Path root = findRepositoryRoot();
        String buildPom = Files.readString(
                root.resolve("taxonomy-build/pom.xml"),
                StandardCharsets.UTF_8);
        String releaseScript = Files.readString(
                root.resolve(".github/scripts/release.sh"),
                StandardCharsets.UTF_8);

        assertThat(buildPom)
                .contains("<id>generate-sbom-companion</id>")
                .contains("<executable>java</executable>")
                .contains("taxonomy-tooling-${project.version}.jar")
                .contains("<argument>generate-sbom-companion</argument>")
                .contains("target/taxonomy-sbom.json")
                .contains("target/taxonomy-vex.json")
                .doesNotContain("generate-vex.py")
                .doesNotContain("<executable>python3</executable>");
        assertThat(releaseScript)
                .contains("java -jar \"$TOOLING_JAR\" generate-sbom-companion")
                .contains("--sbom target/taxonomy-sbom.json")
                .contains("--output target/taxonomy-vex.json")
                .doesNotContain("python3 \"$VEX_HELPER\"");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))
                    && Files.isRegularFile(current.resolve("CITATION.cff"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
