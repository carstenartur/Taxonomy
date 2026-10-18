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
        assertThat(app.reads.get("/api/projects/1/snapshots/second-1"))
                .as("One real read after creation and one after using it as immutable input")
                .isEqualTo(2);
        assertThat(app.reads.get("/api/projects/1/snapshots/second-0")).isEqualTo(2);
        assertThat(app.reads.get("/api/projects/1/snapshots/first-0")).isEqualTo(2);
        assertThat(app.attempts).containsEntry("first", 1).containsEntry("second", 2);
    }

    @Test
    void detectsAStoredSnapshotChangedAfterItsOriginalReadAndBeforeTheNextContinue() {
        var app = new Application(true);
        assertThatThrownBy(() -> ScenarioContinuationWalkthrough.finishIndependentBudgetWork(app, 1, "/operation", null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Continuation must preserve its immutable input snapshot");
        assertThat(app.mutated).isTrue();
    }

    /** Deterministic two-pass server model; the production HTTP/authentication path has separate acceptance coverage. */
    private static final class Application extends ScenarioArchitectureAcceptanceTest {
        final Map<String, Integer> attempts = new HashMap<>();
        final Map<String, JsonNode> snapshots = new HashMap<>();
        final Map<String, JsonNode> saved = new HashMap<>();
        final Map<String, Integer> reads = new HashMap<>();
        final boolean changeBetweenReads;
        boolean mutated;

        Application(boolean changeBetweenReads) {
            this.changeBetweenReads = changeBetweenReads;
            playback = mock(ScenarioLlmPlayback.class);
            when(playback.calls()).thenReturn(List.of());
            when(playback.relationCoverage()).thenReturn(Map.of());
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
                    "analysis", Map.of("recovery", Map.of("id", "journal-" + job, "version", cycle + 10, "state", "STOPPED"),
                            "relationSearchReport", Map.of("maxCalls", 24, "totalCalls", 0,
                                    "progress", Map.of("assessedSources", 1, "completedSearches", cycle)))));
        }
    }
}
