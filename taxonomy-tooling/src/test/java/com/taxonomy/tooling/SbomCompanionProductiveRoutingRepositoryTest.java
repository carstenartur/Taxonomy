package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SbomCompanionProductiveRoutingRepositoryTest {

    @Test
    void productiveCallersAndReleaseWorkflowUseOnlyTheJavaImplementation()
            throws Exception {
        Path root = findRepositoryRoot();
        Path buildPomPath = root.resolve("taxonomy-build/pom.xml");
        Path releaseScriptPath = root.resolve(".github/scripts/release.sh");
        Path releaseWorkflowPath = root.resolve(
                ".github/workflows/deploy-release.yml");
        Path removedGenerator = root.resolve(
                ".github/scripts/generate-vex.py");
        String buildPom = Files.readString(buildPomPath, StandardCharsets.UTF_8);
        String releaseScript = Files.readString(
                releaseScriptPath, StandardCharsets.UTF_8);
        String releaseWorkflow = Files.readString(
                releaseWorkflowPath, StandardCharsets.UTF_8);

        assertThat(buildPom)
                .contains("<id>generate-sbom-companion</id>")
                .contains("<executable>java</executable>")
                .contains("taxonomy-tooling-${project.version}.jar")
                .contains("<argument>generate-sbom-companion</argument>")
                .contains("<argument>target/taxonomy-sbom.json</argument>")
                .contains("<argument>target/taxonomy-vex.json</argument>")
                .doesNotContain("${project.build.directory}/taxonomy-sbom.json")
                .doesNotContain("${project.build.directory}/taxonomy-vex.json")
                .doesNotContain("generate-vex.py")
                .doesNotContain("python3");

        assertThat(releaseScript)
                .contains("java -jar \"$TOOLING_JAR\" generate-sbom-companion")
                .contains("--sbom target/taxonomy-sbom.json")
                .contains("--output target/taxonomy-vex.json")
                .doesNotContain("SBOM_FILE")
                .doesNotContain("VEX_FILE")
                .doesNotContain("generate-vex.py")
                .doesNotContain("VEX_HELPER")
                .doesNotContainPattern(
                        "(?<![A-Za-z0-9_-])python(?:3)?(?![A-Za-z0-9_-])");

        assertThat(releaseWorkflow)
                .contains("TOOLING_JAR: ${{ runner.temp }}/taxonomy-tooling.jar")
                .doesNotContain("generate-vex.py")
                .doesNotContain("VEX_HELPER");
        assertThat(removedGenerator).doesNotExist();
    }

    @Test
    void sourceRatchetNoLongerAllowsTheRemovedGenerator() throws Exception {
        Path root = findRepositoryRoot();
        String ratchet = Files.readString(
                root.resolve("taxonomy-tooling/src/test/java/com/taxonomy/tooling/"
                        + "PythonSourceRatchetRepositoryTest.java"),
                StandardCharsets.UTF_8);

        assertThat(ratchet).doesNotContain("generate-vex.py");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            ".github/scripts/release.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
