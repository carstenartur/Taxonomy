package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SbomCompanionArtifactOrderRepositoryTest {

    @Test
    void generationRunsInMavenVerifyAndBeforeReleaseArtifactCollection()
            throws Exception {
        Path root = findRepositoryRoot();
        String buildPom = Files.readString(
                root.resolve("taxonomy-build/pom.xml"),
                StandardCharsets.UTF_8);
        String releaseScript = Files.readString(
                root.resolve(".github/scripts/release.sh"),
                StandardCharsets.UTF_8);

        int execution = buildPom.indexOf("<id>generate-sbom-companion</id>");
        int verifyPhase = buildPom.indexOf("<phase>verify</phase>", execution);
        int javaCommand = buildPom.indexOf(
                "<argument>generate-sbom-companion</argument>", execution);
        assertThat(execution).isGreaterThanOrEqualTo(0);
        assertThat(verifyPhase).isGreaterThan(execution);
        assertThat(javaCommand).isGreaterThan(verifyPhase);

        int releaseGeneration = releaseScript.indexOf(
                "java -jar \"$TOOLING_JAR\" generate-sbom-companion");
        int artifactCollection = releaseScript.indexOf(
                "collect_release_artifacts", releaseGeneration);
        assertThat(releaseGeneration).isGreaterThanOrEqualTo(0);
        assertThat(artifactCollection).isGreaterThan(releaseGeneration);
        assertThat(releaseScript)
                .contains("--sbom target/taxonomy-sbom.json")
                .contains("--output target/taxonomy-vex.json");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
