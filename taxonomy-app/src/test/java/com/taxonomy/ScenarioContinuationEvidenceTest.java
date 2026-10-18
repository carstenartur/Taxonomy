package com.taxonomy;

import com.taxonomy.acceptance.ScenarioLlmPlayback;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The driver must compare against the first observed immutable evidence, including between cycles. */
class ScenarioContinuationEvidenceTest {
    @Test
    void retainsTheOriginallyObservedSnapshotAcrossConsecutiveContinuations() throws Exception {
        var app = new Application(false);
        var result = ScenarioContinuationWalkthrough.finishIndependentBudgetWork(app, 1, "/operation", null);
        assertThat(result.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(app.saved.get("continuation-history.json")).hasSize(3);
        assertThat(app.saved).doesNotContainKey("llm-calls.json");
        assertThat(app.reads.get("/api/projects/1/snapshots/second-1"))
                .as("One real read after creation and one after using it as immutable input")
                .isEqualTo(2);
        assertThat(app.reads.get("/api/projects/1/snapshots/second-0")).isEqualTo(2);
        assertThat(app.reads.get("/api/projects/1/snapshots/first-0")).isEqualTo(2);
        assertThat(app.attempts).containsEntry("first", 1).containsEntry("second", 2);
        assertThat(app.continuedPasses)
                .as("Both original passes advance before another continuation of the same pass")
                .containsExactly("first", "second", "second");
    }

    @Test
    void detectsAStoredSnapshotChangedAfterItsOriginalReadAndBeforeTheNextContinue() {
        var app = new Application(true);
        assertThatThrownBy(() -> ScenarioContinuationWalkthrough.finishIndependentBudgetWork(app, 1, "/operation", null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Continuation must preserve its immutable input snapshot");
        assertThat(app.mutated).isTrue();
    }

    @Test
    void checkpointsTheInitialSnapshotBeforeAnyContinuationCanFail() throws Exception {
        var app = new Application(false) {
            @Override synchronized JsonNode post(String path, Object body, int status) {
                throw new IllegalStateException("interrupted continuation");
            }
        };
        assertThatThrownBy(() -> ScenarioContinuationWalkthrough.finishIndependentBudgetWork(app, 1, "/operation", null))
                .isInstanceOf(IllegalStateException.class).hasMessage("interrupted continuation");
        assertThat(app.saved).containsKeys("snapshot.json", "run.json");
        assertThat(app.saved.get("run.json").path("snapshotId").asText())
                .isEqualTo(app.saved.get("snapshot.json").at("/summary/id").asText());
        assertThat(app.saved.get("run.json").path("scenario").asText()).isEqualTo("flood-information-v1");
        assertThat(app.saved.get("run.json").path("fixtureSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(app.saved.get("run.json").path("sourceRevision").asText()).isNotBlank();
        assertThat(app.saved).containsKey("checkpoint.json");
        assertThat(app.saved.get("checkpoint.json").path("run")).isEqualTo(app.saved.get("run.json"));
        assertThat(app.saved.get("checkpoint.json").path("snapshot")).isEqualTo(app.saved.get("snapshot.json"));
        assertThat(app.saved).doesNotContainKey("llm-calls.json");
    }

    @Test
    void interruptedPairReplacementRecoversMatchingManifestWithoutChangingRunIdentity(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var manifest = json.createObjectNode().put("scenario", "flood-information-v1")
                .put("runId", "current-run").put("ciRun", "current-ci-run")
                .put("fixtureSha256", "a".repeat(64)).put("sourceRevision", "b".repeat(40))
                .put("projectId", 1).put("requirementId", 2).put("snapshotId", "previous-snapshot");
        java.nio.file.Files.writeString(directory.resolve("run.json"), manifest.toString());
        java.nio.file.Files.writeString(directory.resolve("snapshot.json"),
                "{\"summary\":{\"id\":\"foreign-uncommitted-snapshot\"}}");
        var snapshot = json.createObjectNode();
        snapshot.putObject("summary").put("id", "last-complete-snapshot").put("projectId", 1).put("requirementId", 2);
        var checkpoint = json.createObjectNode();
        checkpoint.put("schemaVersion", 1);
        checkpoint.set("run", manifest.deepCopy().put("snapshotId", "last-complete-snapshot"));
        checkpoint.set("snapshot", snapshot);
        checkpoint.put("snapshotSha256", ScenarioLlmPlayback.sha256(snapshot.toString()));
        java.nio.file.Files.writeString(directory.resolve("checkpoint.json"), checkpoint.toString());
        ScenarioAcceptanceProcess.recoverEvidence(directory, manifest);
        var recovered = json.readTree(directory.resolve("run.json").toFile());
        assertThat(recovered.path("snapshotId").asText()).isEqualTo("last-complete-snapshot");
        for (String field : List.of("scenario", "fixtureSha256", "sourceRevision", "projectId", "requirementId"))
            assertThat(recovered.path(field)).isEqualTo(manifest.path(field));
        assertThat(json.readTree(directory.resolve("snapshot.json").toFile())).isEqualTo(snapshot);
    }

    @Test
    void failureBeforeFirstCheckpointCannotResurrectAPreviousRun(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        for (String artifact : List.of("snapshot.json", "run.json", "checkpoint.json", "llm-calls.json", "llm-calls.jsonl"))
            java.nio.file.Files.writeString(directory.resolve(artifact), "stale previous run");
        ScenarioAcceptanceProcess.clearEvidence(directory);
        ScenarioAcceptanceProcess.recoverEvidence(directory, new tools.jackson.databind.ObjectMapper().createObjectNode());
        for (String artifact : List.of("snapshot.json", "run.json", "checkpoint.json", "llm-calls.json", "llm-calls.jsonl"))
            assertThat(directory.resolve(artifact)).doesNotExist();
    }

    @Test
    void corruptCheckpointCannotRebindConventionalArtifacts(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("run.json"), "previous mirror");
        java.nio.file.Files.writeString(directory.resolve("snapshot.json"), "previous mirror");
        java.nio.file.Files.writeString(directory.resolve("checkpoint.json"), """
                {"schemaVersion":1,"run":{"snapshotId":"bound","fixtureSha256":"%s","sourceRevision":"original-revision"},
                 "snapshot":{"summary":{"id":"bound"}},"snapshotSha256":"%s"}
                """.formatted("a".repeat(64), "b".repeat(64)));
        assertThatThrownBy(() -> ScenarioAcceptanceProcess.recoverEvidence(directory, new tools.jackson.databind.ObjectMapper().createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Checkpoint");
        assertThat(java.nio.file.Files.readString(directory.resolve("run.json"))).isEqualTo("previous mirror");
        assertThat(java.nio.file.Files.readString(directory.resolve("snapshot.json"))).isEqualTo("previous mirror");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"runId", "scenario", "fixtureSha256", "sourceRevision", "ciRun", "projectId", "requirementId"})
    void validSnapshotHashCannotAuthorizeAForeignOrMutatedManifest(String field,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var run = json.createObjectNode().put("runId", "current-run").put("scenario", "flood-information-v1")
                .put("fixtureSha256", "a".repeat(64)).put("sourceRevision", "original-revision").put("ciRun", "current-ci-run")
                .put("projectId", 1).put("requirementId", 2).put("snapshotId", "bound");
        var expected = run.deepCopy();
        if (field.endsWith("Id") && !field.equals("runId")) run.put(field, 999);
        else run.put(field, field.equals("fixtureSha256") ? "b".repeat(64) : "foreign");
        var snapshot = json.createObjectNode();
        snapshot.putObject("summary").put("id", "bound").put("projectId", 1).put("requirementId", 2);
        var checkpoint = json.createObjectNode().put("schemaVersion", 1)
                .put("snapshotSha256", ScenarioLlmPlayback.sha256(snapshot.toString()));
        checkpoint.set("run", run); checkpoint.set("snapshot", snapshot);
        java.nio.file.Files.writeString(directory.resolve("checkpoint.json"), checkpoint.toString());
        assertThatThrownBy(() -> ScenarioAcceptanceProcess.recoverEvidence(directory, expected))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Checkpoint");
        assertThat(directory.resolve("snapshot.json")).doesNotExist();
        assertThat(directory.resolve("run.json")).doesNotExist();
    }

    @Test void badCheckpointDoesNotPreventRecoveryOfEarlierAcceptedExchanges(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var call = new ScenarioLlmPlayback.Call("accepted", ScenarioLlmPlayback.sha256("prompt"),
                ScenarioLlmPlayback.sha256("response"), "prompt", "response");
        java.nio.file.Files.writeString(directory.resolve("llm-calls.jsonl"), json.writeValueAsString(call) + "\n");
        java.nio.file.Files.writeString(directory.resolve("checkpoint.json"), "{}");
        assertThatThrownBy(() -> ScenarioAcceptanceProcess.recoverEvidence(directory, json.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(json.readTree(directory.resolve("llm-calls.json").toFile())).isEqualTo(json.valueToTree(List.of(call)));
    }

    /** Deterministic two-pass server model; the production HTTP/authentication path has separate acceptance coverage. */
    private static class Application extends ScenarioArchitectureAcceptanceTest {
        final Map<String, Integer> attempts = new HashMap<>();
        final Map<String, JsonNode> snapshots = new HashMap<>();
        final Map<String, JsonNode> saved = new HashMap<>();
        final Map<String, Integer> reads = new HashMap<>();
        final java.util.ArrayList<String> continuedPasses = new java.util.ArrayList<>();
        final boolean changeBetweenReads;
        boolean mutated;

        Application(boolean changeBetweenReads) {
            this.changeBetweenReads = changeBetweenReads;
            playback = mock(ScenarioLlmPlayback.class);
            when(playback.calls()).thenReturn(List.of());
            when(playback.relationCoverage()).thenReturn(Map.of());
            when(playback.fixture()).thenReturn(json.valueToTree(Map.of("id", "flood-information-v1")));
            for (String job : List.of("first", "second")) {
                attempts.put(job, 0);
                snapshots.put(job + "-0", snapshot(job, 0));
            }
        }

        @Override synchronized JsonNode get(String path) {
            reads.merge(path, 1, Integer::sum);
            if (path.equals("/operation")) return operation();
            if (path.contains("/snapshots/")) return snapshots.get(path.substring(path.lastIndexOf('/') + 1)).deepCopy();
            String job = path.contains("/first/") ? "first" : "second";
            int cycle = attempts.get(job);
            if (changeBetweenReads && job.equals("second") && cycle == 1 && !mutated) {
                ((ObjectNode) snapshots.get("second-1")).put("originalEvidence", "illegally changed between cycles");
                mutated = true;
            }
            return json.valueToTree(Map.of("available", cycle < (job.equals("first") ? 1 : 2),
                    "continuationId", "journal-" + job, "continuationVersion", cycle + 10,
                    "snapshotId", job + "-" + cycle));
        }

        @Override synchronized JsonNode post(String path, Object body, int expectedStatus) {
            assertThat(expectedStatus).isEqualTo(202);
            String job = path.contains("/first/") ? "first" : "second";
            continuedPasses.add(job);
            int before = attempts.get(job);
            assertThat(json.valueToTree(body).path("snapshotId").asText()).isEqualTo(job + "-" + before);
            assertThat(json.valueToTree(body).path("continuationVersion").asInt()).isEqualTo(before + 10);
            int after = attempts.merge(job, 1, Integer::sum);
            snapshots.put(job + "-" + after, snapshot(job, after));
            return operation();
        }

        @Override void save(String file, JsonNode value) {
            saved.put(file, value.deepCopy());
        }

        private JsonNode operation() {
            var result = json.createObjectNode();
            result.put("status", "PARTIAL");
            result.put("selectedSnapshotId", "first-" + attempts.get("first"));
            result.put("requirementId", 1);
            var jobs = result.putArray("jobs");
            for (String job : List.of("first", "second")) {
                int cycle = attempts.get(job);
                var entry = jobs.addObject();
                entry.put("id", job); entry.put("provider", "CUSTOM_OPENAI");
                entry.put("analysisReasonLength", "standard");
                var item = entry.putArray("items").addObject();
                item.put("id", job.equals("first") ? 1 : 2);
                item.put("requirementId", 1); item.put("requirementVersionId", 1);
                item.put("status", "PARTIAL"); item.put("attempt", cycle + 1);
                item.put("snapshotId", job + "-" + cycle);
            }
            return result;
        }

        private JsonNode snapshot(String job, int cycle) {
            return json.valueToTree(Map.of("originalEvidence", "first saved evidence " + job + "-" + cycle,
                    "summary", Map.of("id", job + "-" + cycle, "projectId", 1, "requirementId", 1),
                    "analysis", Map.of("recovery", Map.of("id", "journal-" + job, "version", cycle + 10, "state", "STOPPED"),
                            "relationSearchReport", Map.of("maxCalls", 24, "totalCalls", 0,
                                    "progress", Map.of("assessedSources", 1, "completedSearches", cycle)))));
        }
    }
}
