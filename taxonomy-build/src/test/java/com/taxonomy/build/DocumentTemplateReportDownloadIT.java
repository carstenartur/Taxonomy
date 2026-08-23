package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browser-level acceptance test for the versioned decision-report template.
 *
 * <p>The test deliberately crosses both process boundaries involved in production:
 * Testcontainers starts the packaged Taxonomy application, while the pinned Playwright
 * installation drives a real browser, locates the Word/WebDAV surface and downloads the
 * generated DOCX through the visible administration link.</p>
 */
@EnabledIfSystemProperty(named = "documentTemplateE2E", matches = "true")
class DocumentTemplateReportDownloadIT {

    private static final String APP_RUNTIME_IMAGE =
            "eclipse-temurin@sha256:"
                    + "d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13";
    private static final String TEST_REPORT_NAME =
            "decision-rationale-template-test.docx";

    @Test
    void playwrightDownloadsARealTemplateBackedReportFromThePackagedApplication()
            throws Exception {
        Path repository = repositoryRoot();
        Path applicationJar = applicationJar(repository);
        Path output = repository.resolve(
                "target/ui-verification/document-template-report");
        recreateDirectory(output);

        ImageFromDockerfile image = new ImageFromDockerfile(
                "taxonomy-document-template-report-e2e", false)
                .withFileFromPath("app.jar", applicationJar)
                .withDockerfileFromBuilder(builder -> builder
                        .from(APP_RUNTIME_IMAGE)
                        .workDir("/app")
                        .copy("app.jar", "app.jar")
                        .expose(8080)
                        .entryPoint("java", "-jar", "app.jar")
                        .build());

        try (GenericContainer<?> application = new GenericContainer<>(image)
                .withEnv("TAXONOMY_ADMIN_PASSWORD", "admin")
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_INIT_ASYNC", "true")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "false")
                .withEnv("LLM_MOCK", "true")
                .withExposedPorts(8080)
                .withStartupTimeout(Duration.ofMinutes(4))
                .waitingFor(Wait.forHttp("/login")
                        .forPort(8080)
                        .forStatusCode(200))) {
            application.start();
            String baseUrl = "http://" + application.getHost() + ":"
                    + application.getMappedPort(8080);
            runPlaywright(repository, output, baseUrl);
        }

        Path managementScreenshot =
                output.resolve("document-template-management.png");
        Path downloadedReport = output.resolve(TEST_REPORT_NAME);
        Path evidence = output.resolve("report-download-evidence.json");

        assertFile(managementScreenshot, 1_000);
        assertFile(downloadedReport, 1_000);
        assertFile(evidence, 100);
        if (Boolean.parseBoolean(
                System.getenv().getOrDefault(
                        "TAXONOMY_RENDER_DOCX_PREVIEW", "false"))) {
            assertFile(output.resolve(
                    "decision-rationale-template-test-report.png"), 1_000);
        }

        assertDownloadedDocx(downloadedReport);
    }

    private static void runPlaywright(
            Path repository,
            Path output,
            String baseUrl) throws Exception {
        Path script = repository.resolve(
                ".github/scripts/document-template-report-download.mjs");
        assertTrue(Files.isRegularFile(script),
                "Playwright acceptance script is missing: " + script);

        Path log = output.resolve("playwright.log");
        ProcessBuilder builder = new ProcessBuilder(
                nodeExecutable(repository),
                script.toString());
        builder.directory(repository.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        builder.environment().put("TAXONOMY_BASE_URL", baseUrl);
        builder.environment().put("TAXONOMY_UI_USERNAME", "admin");
        builder.environment().put("TAXONOMY_UI_PASSWORD", "admin");
        builder.environment().put("TAXONOMY_UI_OUTPUT_DIR", output.toString());

        Process process = builder.start();
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError(
                    "Playwright report-download acceptance timed out; see " + log);
        }
        String outputText = Files.exists(log)
                ? Files.readString(log, StandardCharsets.UTF_8)
                : "";
        assertEquals(0, process.exitValue(),
                "Playwright report-download acceptance failed:\n" + outputText);
    }

    private static void assertDownloadedDocx(Path docx) throws IOException {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            String contentTypes = readEntry(zip, "[Content_Types].xml");
            assertTrue(contentTypes.contains(
                            "wordprocessingml.document.main+xml"),
                    "Downloaded artifact must declare the DOCX main content type");
            assertFalse(contentTypes.contains(
                            "wordprocessingml.template.main+xml"),
                    "Downloaded report must not remain a DOTX package");

            String document = readEntry(zip, "word/document.xml");
            assertTrue(document.contains("Taxonomy template test report"),
                    "Downloaded report must contain the synthetic preview title");
            assertTrue(document.contains(
                            "The active Word template was opened and materialized successfully."),
                    "Downloaded report must contain generated report content");

            String customProperties = readEntry(zip, "docProps/custom.xml");
            assertTrue(customProperties.contains("TaxonomyTemplateId"));
            assertTrue(customProperties.contains("TaxonomyTemplateCommit"));
            assertTrue(customProperties.contains(
                    "TaxonomyTemplatePackageSha256"));
        }
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry, "DOCX is missing " + name);
        try (var input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertFile(Path file, long minimumBytes)
            throws IOException {
        assertTrue(Files.isRegularFile(file), "Expected file is missing: " + file);
        assertTrue(Files.size(file) >= minimumBytes,
                "Expected file is unexpectedly small: " + file);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(".github"))
                    && Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("taxonomy-app"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not locate the Taxonomy repository root");
    }

    private static Path applicationJar(Path repository) throws IOException {
        Path target = repository.resolve("taxonomy-app/target");
        if (!Files.isDirectory(target)) {
            throw new IllegalStateException(
                    "taxonomy-app/target is missing; build the packaged application first");
        }
        try (Stream<Path> files = Files.list(target)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .matches("taxonomy-app-.*\\.jar"))
                    .filter(path -> !path.getFileName().toString()
                            .startsWith("original-"))
                    .filter(path -> !path.getFileName().toString()
                            .contains("-sources"))
                    .filter(path -> !path.getFileName().toString()
                            .contains("-javadoc"))
                    .max(Comparator.comparingLong(path -> path.toFile().length()))
                    .orElseThrow(() -> new IllegalStateException(
                            "No executable taxonomy-app JAR found in " + target));
        }
    }

    private static String nodeExecutable(Path repository) {
        String configured = System.getProperty("taxonomy.node.executable");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("TAXONOMY_NODE_EXECUTABLE");
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (!path.isAbsolute()) {
                path = repository.resolve(path);
            }
            return path.normalize().toString();
        }
        Path pinned = repository.resolve(
                "taxonomy-build/target/frontend/node/node");
        return Files.isExecutable(pinned) ? pinned.toString() : "node";
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        throw new DeleteFailure(exception);
                    }
                });
            } catch (DeleteFailure failure) {
                throw failure.cause;
            }
        }
        Files.createDirectories(directory);
    }

    private static final class DeleteFailure extends RuntimeException {
        private final IOException cause;

        private DeleteFailure(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
