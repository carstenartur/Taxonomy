package com.taxonomy.build;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
 * Browser-level acceptance test for the complete template-backed decision-report workflow.
 *
 * <p>Testcontainers starts the packaged Taxonomy application with deterministic mock scoring.
 * Playwright then follows the regular first-user path: it reviews and dismisses onboarding,
 * enters a civil hospital requirement, performs the analysis, verifies the architecture graph,
 * exports the production decision report and renders the downloaded DOCX for document QA.</p>
 */
@EnabledIfSystemProperty(named = "documentTemplateE2E", matches = "true")
class DocumentTemplateReportDownloadIT {

    private static final String APP_RUNTIME_IMAGE =
            "eclipse-temurin@sha256:"
                    + "d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13";
    private static final String REPORT_NAME =
            "taxonomy-decision-rationale-report.docx";
    private static final String TEMPLATE_ID_PROPERTY = "Taxonomy.Template.Id";
    private static final String TEMPLATE_COMMIT_PROPERTY = "Taxonomy.Template.Commit";
    private static final String TEMPLATE_SHA256_PROPERTY =
            "Taxonomy.Template.PackageSha256";

    @Test
    void playwrightEvaluatesHospitalRequirementAndDownloadsDecisionReport()
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
        Path onboardingScreenshot =
                output.resolve("hospital-requirement-onboarding.png");
        Path analysisScreenshot =
                output.resolve("hospital-requirement-analysis.png");
        Path graphScreenshot =
                output.resolve("hospital-requirement-architecture-graph.png");
        Path downloadedReport = output.resolve(REPORT_NAME);
        Path evidence = output.resolve("report-download-evidence.json");

        assertFile(managementScreenshot, 1_000);
        assertFile(onboardingScreenshot, 10_000);
        assertFile(analysisScreenshot, 10_000);
        assertFile(graphScreenshot, 10_000);
        assertFile(downloadedReport, 10_000);
        assertFile(evidence, 500);
        assertEvidence(evidence);

        if (Boolean.parseBoolean(
                System.getenv().getOrDefault(
                        "TAXONOMY_RENDER_DOCX_PREVIEW", "false"))) {
            assertFile(output.resolve(
                    "decision-rationale-template-test-report.png"), 10_000);
            assertFile(output.resolve(
                    "taxonomy-decision-rationale-report.pdf"), 10_000);
            assertFile(output.resolve(
                    "taxonomy-decision-rationale-report.txt"), 1_000);
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
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError(
                    "Playwright hospital-report acceptance timed out; see " + log);
        }
        String outputText = Files.exists(log)
                ? Files.readString(log, StandardCharsets.UTF_8)
                : "";
        assertEquals(0, process.exitValue(),
                "Playwright hospital-report acceptance failed:\n" + outputText);
    }

    private static void assertEvidence(Path evidence) throws IOException {
        String json = Files.readString(evidence, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 2"),
                "Evidence must use the evaluated-report schema");
        assertTrue(json.contains("\"requirement\": \"Ein kommunales Krankenhaus"),
                "Evidence must identify the hospital requirement");
        assertTrue(json.contains("\"stepCount\": 3"),
                "Evidence must cover the three-step first-user onboarding");
        assertTrue(json.contains("\"dismissInitiallyFocused\": true"),
                "Evidence must prove keyboard focus starts on the onboarding action");
        assertTrue(json.contains("\"horizontalOverflow\": false"),
                "Onboarding must not require horizontal scrolling");
        assertTrue(json.contains("\"impactGraphRendered\": true"),
                "Evidence must prove that the architecture graph was rendered");
        assertTrue(json.contains("\"completenessPercent\": 100"),
                "Evidence must prove a complete deterministic evaluation");
        assertFalse(json.contains("\"chapterCount\": 0"),
                "Evidence must contain at least one decision chapter");
        assertFalse(json.contains("\"leadingLeafCount\": 0"),
                "Evidence must contain at least one leading leaf");
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
            String visibleDocumentParts = readVisibleDocumentParts(zip, document);
            assertTrue(document.contains("Krankenhaus"),
                    "Downloaded report must contain the hospital requirement");
            assertTrue(document.contains("Patienten")
                            || document.contains("Kommunikations"),
                    "Downloaded report must contain substantive requirement text");
            assertTrue(visibleDocumentParts.contains("decision-rationale-report"),
                    "Visible report metadata must identify the source template");
            assertFalse(visibleDocumentParts.contains("{{taxonomy.template."),
                    "Template provenance tokens must be fully resolved");
            assertFalse(visibleDocumentParts.contains("Taxonomy template test report"),
                    "Production report must not contain the synthetic preview title");
            assertFalse(visibleDocumentParts.contains(
                            "no architecture decision was evaluated"),
                    "Production report must not contain the preview-only warning");

            long embeddedImages = zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith("word/media/"))
                    .count();
            assertTrue(embeddedImages > 0,
                    "Evaluated report must embed at least one decision diagram");
        }
        assertTemplateProvenanceProperties(docx);
    }

    private static void assertTemplateProvenanceProperties(Path docx)
            throws IOException {
        try (var input = Files.newInputStream(docx);
             XWPFDocument document = new XWPFDocument(input)) {
            var properties = document.getProperties().getCustomProperties();
            var templateId = properties.getProperty(TEMPLATE_ID_PROPERTY);
            var templateCommit = properties.getProperty(TEMPLATE_COMMIT_PROPERTY);
            var templateSha256 = properties.getProperty(TEMPLATE_SHA256_PROPERTY);

            assertNotNull(templateId,
                    "DOCX must contain the machine-readable template ID");
            assertNotNull(templateCommit,
                    "DOCX must contain the full machine-readable template commit");
            assertNotNull(templateSha256,
                    "DOCX must contain the machine-readable template package checksum");
            assertEquals("decision-rationale-report", templateId.getLpwstr());
            assertTrue(templateCommit.getLpwstr().matches("[0-9a-f]{40}"),
                    "Template provenance must use the full Git commit ID");
            assertTrue(templateSha256.getLpwstr().matches("[0-9a-f]{64}"),
                    "Template provenance must use a SHA-256 package checksum");
        }
    }

    private static String readVisibleDocumentParts(
            ZipFile zip,
            String document) throws IOException {
        StringBuilder visible = new StringBuilder(document);
        for (String name : zip.stream()
                .map(ZipEntry::getName)
                .filter(DocumentTemplateReportDownloadIT::isHeaderOrFooter)
                .sorted()
                .toList()) {
            visible.append('\n').append(readEntry(zip, name));
        }
        return visible.toString();
    }

    private static boolean isHeaderOrFooter(String name) {
        return name.matches("word/(?:header|footer)\\d+\\.xml");
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
        try (Stream<Path> candidates = Files.list(target)) {
            return candidates
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
