package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.taxonomy.acceptance.DirectedRelationMetrics.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioRelationQualityTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void writesExplicitlyAuthoredReportsFromTheSavedSnapshot() throws Exception {
        inputs();
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(Verdict.PASS, result.verdict());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals("AUTHORED_PLAYBACK_NOT_LIVE_MODEL", report.path("evidenceKind").stringValue());
        assertEquals(7, report.at("/evaluation/matchedRequired").asInt());
        assertEquals("a".repeat(64), report.path("taxonomyFingerprint").stringValue());
        assertEquals("b".repeat(64), report.path("promptFingerprint").stringValue());
        assertEquals(136, report.path("relationCalls").asInt());
        assertEquals(256, report.path("relationCallBudget").asInt());
        assertTrue(Files.readString(directory.resolve("relation-quality.html")).contains("Not a live-model benchmark"));
        assertTrue(Files.readString(directory.resolve("relation-quality.csv")).contains("PASS"));
        byte[] before = Files.readAllBytes(directory.resolve("relation-quality.json"));
        ScenarioRelationQuality.write(directory);
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("relation-quality.json")));
    }

    @Test void unfinishedSearchCannotPassDespiteSuccessfulTopLevelStatus() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport/result")).putArray("unfinished").addObject();
        saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(RunState.PARTIAL, result.state());
        assertEquals(Verdict.INCONCLUSIVE, result.verdict());
        assertNull(result.recall());
        assertThrows(AssertionError.class, () -> ScenarioRelationQuality.verify(directory));
    }

    @Test void missingStatusIsRejectedAndRemovesEarlierSuccessReports() throws Exception {
        var snapshot = inputs();
        ScenarioRelationQuality.write(directory);
        ((ObjectNode) snapshot.path("analysis")).remove("status");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
        assertFalse(Files.exists(directory.resolve("relation-quality.json")));
        assertFalse(Files.exists(directory.resolve("relation-quality.html")));
        assertFalse(Files.exists(directory.resolve("relation-quality.csv")));
    }

    @Test void missingCompletenessEvidenceIsNotInterpretedAsAnEmptySearch() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport/result")).remove("unfinished");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void foreignSnapshotAndUnversionedCaseAreRejected() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.path("summary")).put("id", "another-snapshot");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
        inputs();
        var run = (ObjectNode) json.readTree(Files.readString(directory.resolve("run.json")));
        run.put("scenario", "another-case");
        Files.writeString(directory.resolve("run.json"), run.toString());
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void referenceErrorsAreWrittenBeforeVerificationFails() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/architectureView/includedRelationships/0")).put("relationType", "PRODUCES");
        saveSnapshot(snapshot);
        assertThrows(AssertionError.class, () -> ScenarioRelationQuality.verify(directory));
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals("FAIL", report.at("/evaluation/verdict").stringValue());
        assertEquals(1, report.at("/evaluation/falsePositives").size());
        assertEquals(1, report.at("/evaluation/notObservedRequired").size());
    }

    private ObjectNode inputs() throws Exception {
        var snapshot = json.createObjectNode();
        snapshot.putObject("summary").put("id", "snapshot-1").put("status", "SUCCESS")
                .put("taxonomyFingerprint", "a".repeat(64)).put("promptFingerprint", "b".repeat(64));
        var analysis = snapshot.putObject("analysis").put("status", "SUCCESS");
        var search = analysis.putObject("relationSearchReport").put("totalCalls", 136).put("maxCalls", 256)
                .put("durationMillis", 100).put("stopReason", "");
        search.putArray("warnings");
        search.putObject("result").putArray("unfinished");
        var relations = analysis.putObject("architectureView").putArray("includedRelationships");
        // Hand-authored projection for serializer/contract tests, not an application or model-quality test.
        for (String signature : List.of("UA-1580:CONSUMES:IP-1116", "CI-1052:SUPPORTS:BP-1017",
                "BP-1017:CONSUMES:IP-1116", "CI-1052:FULFILLS:CP-1041", "UA-1580:USES:CI-1052",
                "CI-1052:PRODUCES:IP-1116", "UA-1580:USES:CR-1097")) {
            String[] parts = signature.split(":");
            relations.addObject().put("sourceCode", parts[0]).put("relationType", parts[1]).put("targetCode", parts[2]);
        }
        saveSnapshot(snapshot);
        var run = json.createObjectNode().put("scenario", "flood-information-v1")
                .put("fixtureSha256", "c".repeat(64)).put("snapshotId", "snapshot-1")
                .put("sourceRevision", "d".repeat(40)).put("llmCalls", 350);
        Files.writeString(directory.resolve("run.json"), run.toString());
        return snapshot;
    }

    private void saveSnapshot(ObjectNode snapshot) throws Exception {
        Files.writeString(directory.resolve("snapshot.json"), snapshot.toString());
    }
}
