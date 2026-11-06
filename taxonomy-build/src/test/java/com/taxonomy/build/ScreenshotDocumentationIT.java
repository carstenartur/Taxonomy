package com.taxonomy.build;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Final owner in the screenshots profile, after application and document-report ITs. */
@EnabledIfSystemProperty(named = "generateScreenshots", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScreenshotDocumentationIT {
    private static Path output() {
        String configured = System.getenv("TAXONOMY_DOCS_IMAGE_DIR");
        if (configured == null || configured.isBlank()) configured = System.getProperty("taxonomy.docs.image.dir");
        return configured == null || configured.isBlank()
                ? ExternalDriverSupport.root().resolve("target/generated-screenshots") : Path.of(configured);
    }

    @Test @Order(1)
    void capturesExistingProductionExportComponent() throws Exception {
        Files.createDirectories(output());
        ExternalDriverSupport.run("decision-export-browser.mjs", List.of(),
                "documentation-decision-export.log", 3,
                Map.of("TAXONOMY_DOC_SCREENSHOTS", output().toAbsolutePath().toString()));
    }

    @Test @Order(2)
    void everyActiveGuideImageWasGeneratedInThisRun() throws Exception {
        Path root = ExternalDriverSupport.root();
        Path manifest = output().resolve("documentation-screenshots.json");
        // Never leave a successful manifest behind when this verification fails.
        Files.deleteIfExists(manifest);
        Instant startedAt = Instant.parse(System.getProperty("taxonomy.docs.startedAt"));
        var captures = DocumentationCaptureInventory.verify(root, output(), startedAt);
        var names = captures.stream().map(DocumentationCaptureInventory.Capture::file).toList();
        for (String requiredOwnerOutput : List.of("14-navbar-settings.png",
                "71-copilot-architecture-workbench.png", "72-complete-copilot-run-result.png")) {
            assertTrue(names.contains(requiredOwnerOutput), "Missing capture owner: " + requiredOwnerOutput);
        }
        var metadata = new LinkedHashMap<String,Object>();
        metadata.put("schemaVersion", 1);
        metadata.put("capturedAt", Instant.now().toString());
        metadata.put("runStartedAt", startedAt.toString());
        metadata.put("sourceCommit", git("rev-parse", "HEAD"));
        metadata.put("sourceTree", git("rev-parse", "HEAD^{tree}"));
        metadata.put("sourceDirty", !git("status", "--porcelain", "--untracked-files=normal").isBlank());
        metadata.put("entryPoint", "./mvnw -B verify -Pscreenshots");
        metadata.put("applicationData", "isolated deterministic mock/demo data; no external LLM calls");
        metadata.put("componentExamples", "decision-export-{dialog,options,mobile}-{de,en}.png use production components with authored example input");
        metadata.put("captures", captures);
        Files.writeString(manifest, JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                .writeValueAsString(metadata) + "\n");
    }

    private static String git(String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        return ExternalDriverSupport.command(command, "documentation-source-" + arguments[0] + ".log", 1).strip();
    }
}
