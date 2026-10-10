package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Execute the productive shell boundaries and inspect the delivered archive. */
class ReleaseDistributionArchiveTest {
    private static final String VERSION = "1.5.0";
    private static final String ARCHIVE = "taxonomy-1.5.0-distribution.tar.gz";
    private static final List<String> FEATURES = List.of(
            "analysis", "architecture", "interop", "portfolio", "reporting", "templates");

    @Test
    void archivePreservesTheCompleteRunnableDistribution(@TempDir Path root)
            throws Exception {
        fixture(root);

        Result result = collect(root);

        assertThat(result.exitCode()).as(result.output()).isZero();
        Path artifacts = root.resolve("target/release-artifacts");
        assertThat(artifacts.resolve(ARCHIVE)).isRegularFile();
        Result archiveChecksum = run(artifacts, Map.of(),
                "sha256sum", "--check", ARCHIVE + ".sha256");
        assertThat(archiveChecksum.exitCode()).as(archiveChecksum.output()).isZero();
        Path extracted = Files.createDirectories(root.resolve("unpacked distribution"));
        Result unpack = run(extracted, Map.of(), "tar", "-xzf",
                artifacts.resolve(ARCHIVE).toString());
        assertThat(unpack.exitCode()).as(unpack.output()).isZero();
        Path distribution = extracted.resolve("taxonomy-" + VERSION);
        List<String> expected = new ArrayList<>(List.of("app.jar", "README.md", "SHA256SUMS",
                "LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md", "release_notes.md",
                "plugins/taxonomy-mermaid-plugin-1.5.0.jar",
                "plugins/taxonomy-mermaid-plugin-1.5.0.jar.sha256"));
        for (String feature : FEATURES) {
            expected.add("features/taxonomy-" + feature + "-" + VERSION + ".jar");
        }
        try (var paths = Files.walk(distribution)) {
            assertThat(paths.filter(Files::isRegularFile)
                    .map(path -> distribution.relativize(path).toString().replace('\\', '/')))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
        assertThat(Files.readString(distribution.resolve("app.jar"))).isEqualTo("host bytes\n");
        for (String feature : FEATURES) {
            assertThat(Files.readString(distribution.resolve(
                    "features/taxonomy-" + feature + "-" + VERSION + ".jar")))
                    .isEqualTo(feature + " bytes\n");
        }
        assertThat(Files.readString(distribution.resolve(
                "plugins/taxonomy-mermaid-plugin-1.5.0.jar"))).isEqualTo("mermaid bytes\n");
        assertThat(Files.readString(distribution.resolve("README.md")))
                .contains("Java 21", "cd taxonomy-1.5.0", "java -jar app.jar",
                        "TAXONOMY_ADMIN_PASSWORD", "features/", "plugins/");
        Result contentsChecksum = run(distribution, Map.of(),
                "sha256sum", "--check", "SHA256SUMS");
        assertThat(contentsChecksum.exitCode()).as(contentsChecksum.output()).isZero();
        Files.writeString(distribution.resolve("features/taxonomy-analysis-1.5.0.jar"), "modified");
        Result modified = run(distribution, Map.of(), "sha256sum", "--check", "SHA256SUMS");
        assertThat(modified.exitCode()).isNotZero();
    }

    @Test
    void missingStartupFeaturePreventsReleaseCollection(@TempDir Path root) throws Exception {
        fixture(root);
        Files.delete(root.resolve("taxonomy-app/target/features/taxonomy-analysis-1.5.0.jar"));

        Result result = collect(root);

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("analysis");
        assertThat(root.resolve("target/release-artifacts/" + ARCHIVE)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-plugin", "missing-checksum", "corrupt-checksum"})
    void invalidStandardPluginPreventsReleaseCollection(String problem, @TempDir Path root)
            throws Exception {
        fixture(root);
        Path plugin = root.resolve("taxonomy-app/target/plugins/taxonomy-mermaid-plugin-1.5.0.jar");
        Path checksum = plugin.resolveSibling(plugin.getFileName() + ".sha256");
        switch (problem) {
            case "missing-plugin" -> Files.delete(plugin);
            case "missing-checksum" -> Files.delete(checksum);
            case "corrupt-checksum" -> Files.writeString(checksum,
                    "0".repeat(64) + " *" + plugin.getFileName() + "\n");
            default -> throw new IllegalArgumentException(problem);
        }

        Result result = collect(root);

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("plugin");
        assertThat(root.resolve("target/release-artifacts/" + ARCHIVE)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"archive", "checksum"})
    void publicationRejectsMissingDistributionAsset(String missing, @TempDir Path root)
            throws Exception {
        String assets = imageAssets() + (missing.equals("archive")
                ? ARCHIVE + ".sha256\n" : ARCHIVE + "\n");

        Result result = publicationAssets(root, assets);

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).contains("distribution");
    }

    @Test
    void publicationAcceptsCompleteDistributionAssets(@TempDir Path root) throws Exception {
        Result result = publicationAssets(root,
                imageAssets() + ARCHIVE + "\n" + ARCHIVE + ".sha256\n");
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    private static Result collect(Path root) throws Exception {
        String script = Files.readString(repository().resolve(".github/scripts/release.sh"));
        String function = between(script, "collect_release_artifacts() {", "\nmaterialize_commit() {");
        return run(root, Map.of("RELEASE_VERSION", VERSION), "bash", "-c",
                "set -euo pipefail\nfail() { echo \"::error::$*\"; exit 1; }\n"
                        + function + "\ncollect_release_artifacts\n");
    }

    private static Result publicationAssets(Path root, String assets) throws Exception {
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path gh = bin.resolve("gh");
        Files.writeString(gh, "#!/usr/bin/env bash\nprintf '%s' \"$TEST_RELEASE_ASSETS\"\n");
        assertThat(gh.toFile().setExecutable(true)).isTrue();
        String workflow = Files.readString(repository().resolve(".github/workflows/deploy-release.yml"));
        String publication = workflow.substring(workflow.indexOf("- name: Publish complete release"));
        String checks = between(publication, "          assets=$(gh release view",
                "          docker buildx imagetools inspect");
        return run(root, Map.of("RELEASE_VERSION", VERSION, "TEST_RELEASE_ASSETS", assets,
                        "PATH", bin + ":" + System.getenv("PATH")),
                "bash", "-c", "set -euo pipefail\ntag=v${RELEASE_VERSION}\n" + checks);
    }

    private static String imageAssets() {
        return "taxonomy-1.5.0-image-evidence.json\ntaxonomy-1.5.0-image-trivy.sarif\n"
                + "taxonomy-1.5.0-release.sha256\n";
    }

    private static void fixture(Path root) throws Exception {
        write(root.resolve("taxonomy-app/target/taxonomy-app-1.5.0.jar"), "host bytes\n");
        for (String feature : FEATURES) {
            write(root.resolve("taxonomy-app/target/features/taxonomy-" + feature + "-1.5.0.jar"),
                    feature + " bytes\n");
        }
        Path plugin = root.resolve("taxonomy-app/target/plugins/taxonomy-mermaid-plugin-1.5.0.jar");
        write(plugin, "mermaid bytes\n");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(plugin)));
        write(plugin.resolveSibling(plugin.getFileName() + ".sha256"),
                digest + " *" + plugin.getFileName() + "\n");
        write(root.resolve("taxonomy-tooling/target/taxonomy-tooling-1.5.0.jar"), "build tool\n");
        for (String name : List.of("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md", "release_notes.md")) {
            write(root.resolve(name), name + " fixture\n");
        }
        for (String name : List.of("taxonomy-sbom.json", "taxonomy-sbom.xml", "taxonomy-vex.json")) {
            write(root.resolve("target/" + name), "fixture\n");
        }
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private static String between(String value, String start, String end) {
        int first = value.indexOf(start);
        int last = value.indexOf(end, first);
        assertThat(first).as(start).isGreaterThanOrEqualTo(0);
        assertThat(last).as(end).isGreaterThan(first);
        return value.substring(first, last);
    }

    private static Result run(Path root, Map<String, String> environment, String... command)
            throws Exception {
        Path output = Files.createTempFile(root, "release-distribution-test-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Release distribution command exceeded 20 seconds");
        }
        String text = Files.readString(output, StandardCharsets.UTF_8);
        Files.delete(output);
        return new Result(process.exitValue(), text);
    }

    private static Path repository() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve(".github/scripts/release.sh"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Taxonomy repository not found");
        return root;
    }

    private record Result(int exitCode, String output) { }
}
