package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelmOciEvidenceToolTest {

    private static final String SOURCE_COMMIT = "a".repeat(40);
    private static final String ARCHIVE_SHA = "b".repeat(64);
    private static final String MANIFEST_SHA = "c".repeat(64);

    @Test
    void writesDeterministicPulledBackPublicationEvidence(@TempDir Path root)
            throws Exception {
        Path output = root.resolve("nested/evidence.json");

        HelmOciEvidenceTool.Result result = HelmOciEvidenceTool.generate(
                output,
                "oci://ghcr.io/carstenartur/charts/taxonomy",
                "1.4.0",
                "v1.4.0",
                SOURCE_COMMIT,
                "published",
                ARCHIVE_SHA,
                MANIFEST_SHA);

        assertThat(result.output()).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(result.version()).isEqualTo("1.4.0");
        assertThat(result.releaseTag()).isEqualTo("v1.4.0");

        String rendered = Files.readString(output, StandardCharsets.UTF_8);
        Map<String, Object> evidence = FlatJson.parseObject(rendered);
        assertThat(evidence)
                .containsEntry("schemaVersion", 1L)
                .containsEntry(
                        "chart",
                        "oci://ghcr.io/carstenartur/charts/taxonomy")
                .containsEntry("version", "1.4.0")
                .containsEntry("appVersion", "1.4.0")
                .containsEntry("sourceCommit", SOURCE_COMMIT)
                .containsEntry(
                        "image",
                        "ghcr.io/carstenartur/taxonomy:v1.4.0")
                .containsEntry("upgradeStrategy", "Recreate")
                .containsEntry("publicationStatus", "published")
                .containsEntry("archiveSha256", ARCHIVE_SHA)
                .containsEntry("renderedManifestSha256", MANIFEST_SHA)
                .containsEntry(
                        "verification",
                        "pulled-back-and-rendered-identically");
        assertThat(rendered).isEqualTo(FlatJson.pretty(evidence) + "\n");
    }

    @Test
    void rejectsMutableOrContradictoryEvidenceAndRemovesStaleOutput(
            @TempDir Path root) throws Exception {
        Path output = root.resolve("evidence.json");
        Files.writeString(output, "stale", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> HelmOciEvidenceTool.generate(
                output,
                "oci://ghcr.io/carstenartur/charts/taxonomy",
                "1.4.0-SNAPSHOT",
                "v1.4.0-SNAPSHOT",
                SOURCE_COMMIT,
                "published",
                ARCHIVE_SHA,
                MANIFEST_SHA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-SNAPSHOT");
        assertThat(output).doesNotExist();

        assertThatThrownBy(() -> HelmOciEvidenceTool.generate(
                output,
                "oci://ghcr.io/carstenartur/charts/taxonomy",
                "1.4.0",
                "v1.4.1",
                SOURCE_COMMIT,
                "published",
                ARCHIVE_SHA,
                MANIFEST_SHA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal v<version>");
        assertThat(output).doesNotExist();
    }

    @Test
    void cliGeneratesEvidenceAndReportsInvalidDigest(@TempDir Path root)
            throws Exception {
        Path output = root.resolve("evidence.json");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int success = HelmOciEvidenceTool.run(
                command(output, ARCHIVE_SHA),
                root,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(success).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Helm OCI evidence generated")
                .contains("1.4.0")
                .contains("v1.4.0");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(output).isRegularFile();

        stderr.reset();
        int failure = HelmOciEvidenceTool.run(
                command(output, "not-a-sha"),
                root,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertThat(failure).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .startsWith("::error::")
                .contains("archiveSha256");
        assertThat(output).doesNotExist();
    }

    @Test
    void publicationWorkflowUsesJavaEvidenceAuthority() throws Exception {
        Path root = findRepositoryRoot();
        String workflow = Files.readString(
                root.resolve(".github/workflows/publish-helm-oci.yml"),
                StandardCharsets.UTF_8);

        assertThat(workflow)
                .contains("Build Java release evidence tooling")
                .contains("com.taxonomy.tooling.HelmOciEvidenceTool")
                .contains("taxonomy-tooling-${RELEASE_VERSION}.jar")
                .contains("--archive-sha256 \"$archive_sha\"")
                .contains("--manifest-sha256 \"$manifest_sha\"")
                .doesNotContain("python3")
                .doesNotContain("import json");
    }

    private static String[] command(Path output, String archiveSha) {
        return new String[]{
                "--output", output.toString(),
                "--chart", "oci://ghcr.io/carstenartur/charts/taxonomy",
                "--version", "1.4.0",
                "--release-tag", "v1.4.0",
                "--source-commit", SOURCE_COMMIT,
                "--publication-status", "published",
                "--archive-sha256", archiveSha,
                "--manifest-sha256", MANIFEST_SHA};
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve(".github"))
                    && Files.isDirectory(current.resolve("taxonomy-tooling"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
}
