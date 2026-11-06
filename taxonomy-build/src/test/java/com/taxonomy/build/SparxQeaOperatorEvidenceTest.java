package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Tests evidence binding only; these header-shaped byte fixtures are not EA repositories. */
class SparxQeaOperatorEvidenceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FIRST = "dcb1ed2d-e301-4106-b6eb-c5af8cc54701", SECOND = "dcb1ed2d-e301-4106-b6eb-c5af8cc54702";
    @TempDir Path temporary;

    @Test void localFilesNeverBecomeAnExecutedEaProductTest() throws Exception {
        Path manifest = fixture(false);
        var result = SparxQeaOperatorEvidence.validate(manifest);
        assertEquals("FILE_EVIDENCE_VALIDATED", result.path("status").asText());
        assertEquals("NOT_EXECUTED", result.path("realProductStatus").asText());
        assertEquals("NOT_PROVIDED", result.path("operatorEvidenceStatus").asText());
    }

    @Test void completeOperatorEvidenceIsLabelledAsExternalEvidenceNotHarnessExecution() throws Exception {
        var result = SparxQeaOperatorEvidence.validate(fixture(true));
        assertEquals("OPERATOR_EVIDENCE_VALIDATED", result.path("status").asText());
        assertEquals("NOT_EXECUTED", result.path("realProductStatus").asText());
        assertEquals("17.2 operator-recorded build", result.path("operatorReportedProductVersion").asText());
    }

    @Test void changedQeaBytesInvalidateAnOtherwisePassingReport() throws Exception {
        Path manifest = fixture(true);
        Files.writeString(temporary.resolve("outputs/" + FIRST + ".qea"), "changed after review", StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> SparxQeaOperatorEvidence.validate(manifest));
    }

    @Test void changedTemplateInvalidatesTheRecordedExportBasis() throws Exception {
        Path manifest = fixture(false);
        Files.writeString(temporary.resolve("template.qea"), "changed template", StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> SparxQeaOperatorEvidence.validate(manifest));
    }

    @Test void missingEaResultCannotCertifyAllDownloadedOperations() throws Exception {
        Path manifest = fixture(true), ea = temporary.resolve("ea-evidence.json");
        var report = (ObjectNode) JSON.readTree(Files.readString(ea));
        ((tools.jackson.databind.node.ArrayNode) report.path("results")).remove(1);
        Files.writeString(ea, JSON.writeValueAsString(report));
        assertThrows(IllegalArgumentException.class, () -> SparxQeaOperatorEvidence.validate(manifest));
    }

    @Test void changedEaLogAndFailedEaChecksFailClosed() throws Exception {
        Path manifest = fixture(true), log = temporary.resolve("ea-run.log");
        String original = Files.readString(log);
        Files.writeString(log, original + "later change");
        assertThrows(IllegalArgumentException.class, () -> SparxQeaOperatorEvidence.validate(manifest));
        Files.writeString(log, original);
        Path ea = temporary.resolve("ea-evidence.json");
        var report = (ObjectNode) JSON.readTree(Files.readString(ea));
        ((ObjectNode) report.path("results").get(0)).put("result", "FAILED");
        Files.writeString(ea, JSON.writeValueAsString(report));
        assertThrows(IllegalArgumentException.class, () -> SparxQeaOperatorEvidence.validate(manifest));
    }

    private Path fixture(boolean eaEvidence) throws Exception {
        Path output = Files.createDirectory(temporary.resolve("outputs"));
        Path template = Files.write(temporary.resolve("template.qea"), sqliteHeader("template"));
        var files = new ArrayList<Map<String, Object>>();
        for (var entry : Map.of(FIRST, "NEW_PROJECT", SECOND, "UPDATE_COPY").entrySet()) {
            Path file = Files.write(output.resolve(entry.getKey() + ".qea"), sqliteHeader(entry.getValue()));
            files.add(Map.of("operationId", entry.getKey(), "mode", entry.getValue(), "sha256", PackagedPluginSupport.sha256(file), "bytes", Files.size(file)));
        }
        Files.writeString(output.resolve("export-evidence.json"), JSON.writeValueAsString(Map.of(
                "schemaVersion", 1, "harnessStatus", "PASSED", "realProductStatus", "NOT_EXECUTED", "hostSha256", "a".repeat(64),
                "pluginSha256", "b".repeat(64), "templateSha256", PackagedPluginSupport.sha256(template), "files", files)));
        var manifest = new LinkedHashMap<String, Object>(Map.of("schemaVersion", 1, "template", "template.qea",
                "exports", "outputs/export-evidence.json", "outputs", "outputs"));
        if (eaEvidence) {
            Path log = Files.writeString(temporary.resolve("ea-run.log"), "Operator-provided evidence fixture, no EA was run.\n");
            var results = files.stream().map(file -> Map.of("operationId", file.get("operationId"), "outputSha256", file.get("sha256"),
                    "result", "PASSED", "checked", List.of("OPEN_IN_EA", "CLOSE_REOPEN", "SUPPORTED_SEMANTICS"), "knownLosses", List.of())).toList();
            Files.writeString(temporary.resolve("ea-evidence.json"), JSON.writeValueAsString(Map.of("schemaVersion", 1,
                    "product", "Sparx Enterprise Architect", "version", "17.2 operator-recorded build", "operator", "test fixture",
                    "executionDate", "2026-11-06T12:00:00Z", "log", "ea-run.log", "logSha256", PackagedPluginSupport.sha256(log), "results", results)));
            manifest.put("eaEvidence", "ea-evidence.json");
        }
        return Files.writeString(temporary.resolve("manifest.json"), JSON.writeValueAsString(manifest));
    }

    private static byte[] sqliteHeader(String content) { return ("SQLite format 3\0" + content).getBytes(StandardCharsets.UTF_8); }
}
