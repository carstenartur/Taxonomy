package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes the shipped chart to verify variant selection and offline model settings. */
class HelmImageVariantTest {
    private static final String REPOSITORY = "ghcr.io/carstenartur/taxonomy";
    @TempDir Path temporaryDirectory;

    @Test
    void packagedReleaseDerivesTheMatchingStandardOrOnnxTag() throws Exception {
        Path chart = chartWithAppVersion("9.8.7");

        assertThat(render(chart).successfulOutput())
                .contains("image: \"" + REPOSITORY + ":v9.8.7\"");
        assertThat(render(chart, "-f", overlay().toString()).successfulOutput())
                .contains("image: \"" + REPOSITORY + ":v9.8.7-onnx\"");
    }

    @Test
    void sourceChartAcceptsOnnxCommitTagWithOfflineRuntimeSettings() throws Exception {
        String tag = "sha-" + "a".repeat(40) + "-onnx";
        String output = render(chart(), "-f", overlay().toString(),
                "--set-string", "image.tag=" + tag).successfulOutput();
        assertThat(output).contains("image: \"" + REPOSITORY + ":" + tag + "\"");
        assertOfflineSettings(output);
    }

    @Test
    void onnxDigestSelectsTheExactImageAndPreservesOfflineRuntimeSettings() throws Exception {
        String digest = "sha256:" + "b".repeat(64);
        String output = render(chart(), "-f", overlay().toString(),
                "--set-string", "image.digest=" + digest).successfulOutput();
        assertThat(output).contains("image: \"" + REPOSITORY + "@" + digest + "\"");
        assertOfflineSettings(output);
    }

    @Test
    void sourceSnapshotsStillRequireAnExplicitImmutableReference() throws Exception {
        Path chart = chartWithAppVersion("9.8.7-SNAPSHOT");
        Render absent = render(chart, "-f", overlay().toString());
        assertThat(absent.exitCode()).isNotZero();
        assertThat(absent.output()).contains("image.tag or image.digest is required");

        Render mutable = render(chart, "-f", overlay().toString(),
                "--set-string", "image.tag=latest-onnx");
        assertThat(mutable.exitCode()).isNotZero();
        assertThat(mutable.output()).contains("image.tag must be an immutable release tag");
    }

    @Test
    void unknownVariantIsRejectedInsteadOfSilentlySelectingTheStandardImage() throws Exception {
        Render result = render(chart(), "--set-string", "image.variant=unknown",
                "--set-string", "image.tag=v9.8.7");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("image.variant must be standard or onnx");
    }

    private Path chartWithAppVersion(String appVersion) throws IOException {
        Path chart = temporaryDirectory.resolve("chart");
        Path source = chart();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = chart.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        Path metadata = chart.resolve("Chart.yaml");
        Files.writeString(metadata, Files.readString(metadata)
                .replaceAll("(?m)^appVersion:.*$", "appVersion: \"" + appVersion + "\""));
        return chart;
    }

    private static void assertOfflineSettings(String output) {
        assertEnvironment(output, "LLM_PROVIDER", "LOCAL_ONNX");
        assertEnvironment(output, "TAXONOMY_EMBEDDING_ENABLED", "true");
        assertEnvironment(output, "TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false");
        assertEnvironment(output, "TAXONOMY_EMBEDDING_MODEL_DIR",
                "/opt/taxonomy/models/multilingual-minilm");
        assertEnvironment(output, "DJL_CACHE_DIR", "/tmp/djl-cache");
        assertEnvironment(output, "RUST_FLAVOR", "cpu");
        assertThat(output)
                .contains("-Xmx768m")
                .contains("readOnlyRootFilesystem: true");
    }

    private static void assertEnvironment(String output, String name, String value) {
        assertThat(output).containsPattern("name: " + Pattern.quote(name)
                + "\\s+value: " + Pattern.quote("\"" + value + "\""));
    }

    private Render render(Path chart, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("helm", "template", "taxonomy", chart.toString(),
                "--set", "existingSecret=taxonomy-test-secrets"));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporaryDirectory, "helm-", ".log");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (IOException failure) {
            assumeTrue(false, "Helm is unavailable: " + failure.getMessage());
            throw failure;
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Helm render completed: %s", command).isTrue();
        return new Render(process.exitValue(), Files.readString(log));
    }

    private static Path overlay() {
        return chart().resolve("values-onnx.yaml");
    }

    private static Path chart() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null) {
            Path chart = root.resolve("deploy/helm/taxonomy");
            if (Files.isRegularFile(chart.resolve("Chart.yaml"))) return chart;
            root = root.getParent();
        }
        throw new IllegalStateException("Taxonomy Helm chart not found");
    }

    private record Render(int exitCode, String output) {
        String successfulOutput() {
            assertThat(exitCode).as("Helm output: %s", output).isZero();
            return output;
        }
    }
}
