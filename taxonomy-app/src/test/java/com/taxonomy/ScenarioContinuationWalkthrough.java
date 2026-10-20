package com.taxonomy;

import tools.jackson.databind.JsonNode;
import com.taxonomy.acceptance.ScenarioJsonFingerprint;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses the same explicit, bounded continuation action exposed to the project client. */
final class ScenarioContinuationWalkthrough {
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "PARTIAL", "FAILED", "CANCELLED");
    static final int MAX_CONTINUATIONS_PER_PASS = 128;
    private record PassIdentity(long requirementId, long versionId, String provider, String reasonLength) { }

    static JsonNode finishIndependentBudgetWork(ScenarioArchitectureAcceptanceTest app, long projectId,
                                               String operationPath, ScenarioBrowserWalkthrough browser) throws Exception {
        var history = new ArrayList<Map<String, Object>>();
        var journals = new LinkedHashMap<String, String>();
        JsonNode operation = awaitTerminal(app, operationPath);
        Map<String, PassIdentity> originalPasses = passIdentities(operation);
        var itemCycles = new HashMap<String, Integer>();
        var browserPasses = new HashSet<String>();
        var originalSnapshots = new HashMap<String, ScenarioSnapshotObservation>();
        // Bind the very first saved evidence to its fixture/revision before a
        // continuation can fail or the parent can terminate this application.
        for (JsonNode job : operation.path("jobs")) for (JsonNode item : job.path("items")) {
            String id = item.path("snapshotId").asText();
            JsonNode initial = app.get("/api/projects/" + projectId + "/snapshots/" + id);
            originalSnapshots.put(job.path("id").asText() + ":" + item.path("id").asText(), ScenarioSnapshotObservation.observe(id, initial));
            if (id.equals(operation.path("selectedSnapshotId").asText()))
                app.checkpointSnapshot(projectId, item.path("requirementId").asLong(), initial);
        }
        // Each independently assessed pass has its own bounded driver. This does
        // not change the provider's per-cycle resource policy or add a pass.
        for (int cycle = 0; cycle <= MAX_CONTINUATIONS_PER_PASS * originalPasses.size(); cycle++) {
            if (browser != null) browser.observeOperation(operation.path("operationId").asText());
            assertThat(passIdentities(operation)).as("Continuation retains the original passes and their admission")
                    .isEqualTo(originalPasses);
            app.save("operation.json", operation);
            app.save("playback-coverage.json", app.json.valueToTree(app.playback.relationCoverage()));
            app.save("continuation-history.json", app.json.valueToTree(history));
            assertThat(operation.path("jobs")).hasSize(2);
            JsonNode selectedJob = null, selectedItem = null, permission = null;
            int selectedCycles = Integer.MAX_VALUE;
            for (JsonNode job : operation.path("jobs")) for (JsonNode item : job.path("items")) {
                if (!item.path("status").asText().equals("PARTIAL")) continue;
                String candidateKey = job.path("id").asText() + ":" + item.path("id").asText();
                int completedCycles = itemCycles.getOrDefault(candidateKey, 0);
                if (completedCycles >= selectedCycles) continue;
                String path = itemPath(projectId, job, item);
                JsonNode eligibility = app.get(path + "/continuation");
                if (eligibility.path("available").asBoolean()) {
                    selectedJob = job; selectedItem = item; permission = eligibility;
                    selectedCycles = completedCycles;
                }
            }
            if (permission == null) {
                if (browser != null) assertThat(browserPasses)
                        .as("Both original passes were continued through their actual UI action")
                        .containsExactlyInAnyOrderElementsOf(originalPasses.keySet());
                return operation;
            }
            String itemKey = selectedJob.path("id").asText() + ":" + selectedItem.path("id").asText();
            int itemCycle = itemCycles.merge(itemKey, 1, Integer::sum);
            assertThat(itemCycle).as("Bounded continuation driver for original pass %s", itemKey)
                    .isLessThanOrEqualTo(MAX_CONTINUATIONS_PER_PASS);
            String journal = permission.path("continuationId").asText();
            assertThat(journal).isNotBlank();
            String previousJournal = journals.putIfAbsent(itemKey, journal);
            assertThat(previousJournal == null || previousJournal.equals(journal)).isTrue();
            assertThat(new HashSet<>(journals.values())).hasSize(journals.size());
            String oldSnapshotId = selectedItem.path("snapshotId").asText();
            assertThat(permission.path("snapshotId").asText()).isEqualTo(oldSnapshotId);
            String oldPath = "/api/projects/" + projectId + "/snapshots/" + oldSnapshotId;
            // Retain the complete first-read fingerprint and scalar progress for each
            // original pass. The full snapshot is already durably checkpointed; another
            // pre-action GET would silently replace the original observed evidence.
            var observed = originalSnapshots.get(itemKey);
            if (observed != null) assertThat(observed.id()).isEqualTo(oldSnapshotId);
            var before = observed == null ? ScenarioSnapshotObservation.observe(oldSnapshotId, app.get(oldPath)) : observed;
            int callsBefore = app.playback.callCount();
            String path = itemPath(projectId, selectedJob, selectedItem);
            String jobId = selectedJob.path("id").asText();
            long itemId = selectedItem.path("id").asLong();
            assertThat(selectedItem.path("attempt").isIntegralNumber()).isTrue();
            int previousAttempt = selectedItem.path("attempt").asInt();
            // Exercise the real button independently for both jobs. Further
            // cycles use the same explicit HTTP action and all the same guards;
            // they do not need another full browser reload to verify persistence.
            boolean throughBrowser = browser != null && itemCycle == 1;
            if (throughBrowser) {
                browser.continuePass(jobId, itemId);
                assertThat(browserPasses.add(itemKey)).isTrue();
            }
            else app.post(path + "/continue", Map.of("snapshotId", oldSnapshotId,
                    "continuationVersion", permission.path("continuationVersion").asLong()), 202);
            // A browser click may return before its POST arrives. The old PARTIAL
            // response is terminal too, so require the new persisted attempt.
            operation = awaitTerminal(app, operationPath,
                    value -> findItem(value, jobId, itemId).path("attempt").asInt() > previousAttempt);
            app.save("operation.json", operation);
            JsonNode completedItem = findItem(operation, jobId, itemId);
            assertThat(completedItem.path("attempt").asInt()).as("One action admits exactly one new attempt")
                    .isEqualTo(previousAttempt + 1);
            assertThat(completedItem.path("status").asText())
                    .as("Continuation attempt must finish with usable evidence: %s", completedItem)
                    .isIn("SUCCESS", "PARTIAL");
            String nextId = completedItem.path("snapshotId").asText();
            assertThat(nextId).isNotBlank().isNotEqualTo(oldSnapshotId);
            JsonNode after = app.get("/api/projects/" + projectId + "/snapshots/" + nextId);
            app.checkpointSnapshot(projectId, selectedItem.path("requirementId").asLong(), after);
            assertThat(ScenarioJsonFingerprint.sha256(app.get(oldPath)))
                    .as("Continuation must preserve its immutable input snapshot").isEqualTo(before.sha256());
            assertThat(after.at("/analysis/recovery/state").asText())
                    .as("Continuation stopped outside an execution budget: %s", after.at("/analysis/warnings"))
                    .isIn("STOPPED", "COMPLETED", "COMPLETED_WITH_GAPS");
            assertThat(after.at("/analysis/recovery/id").asText()).isEqualTo(journal);
            assertThat(after.at("/analysis/recovery/version").asLong())
                    .isGreaterThan(permission.path("continuationVersion").asLong());
            JsonNode progressAfter = after.at("/analysis/relationSearchReport/progress");
            int newCalls = app.playback.callCount() - callsBefore;
            boolean advances = newCalls > 0
                    || progressAfter.path("assessedSources").asInt() > before.assessedSources()
                    || progressAfter.path("completedSearches").asInt() > before.completedSearches()
                    || !after.at("/analysis/recovery/state").equals(before.recoveryState());
            assertThat(advances).as("Each explicit cycle must advance the saved work instead of restarting it; stop reason: %s",
                    after.at("/analysis/relationSearchReport/stopReason").asText()).isTrue();
            assertThat(after.at("/analysis/relationSearchReport/maxCalls").asInt()).isEqualTo(24);
            assertThat(after.at("/analysis/relationSearchReport/totalCalls").asInt()).isBetween(0, 24);
            assertThat(newCalls).as("Each continuation stays within the original actual-provider-call envelope").isBetween(0, 24);
            assertThat(newCalls).as("The durable budget reports actual new relation calls, separately from scheduler grants")
                    .isEqualTo(after.at("/analysis/relationSearchReport/budget/newCalls").asInt());
            var entry = new LinkedHashMap<String, Object>();
            entry.put("cycle", cycle + 1); entry.put("jobId", selectedJob.path("id").asText());
            entry.put("itemId", selectedItem.path("id").asLong()); entry.put("continuationId", journal);
            entry.put("itemCycle", itemCycle); entry.put("trigger", throughBrowser ? "BROWSER" : "HTTP");
            entry.put("beforeSnapshotId", oldSnapshotId); entry.put("afterSnapshotId", nextId);
            entry.put("beforeAttempt", previousAttempt); entry.put("afterAttempt", completedItem.path("attempt").asInt());
            entry.put("beforeRevision", permission.path("continuationVersion").asLong());
            entry.put("afterRevision", after.at("/analysis/recovery/version").asLong());
            entry.put("newProviderCalls", newCalls); entry.put("progress", progressAfter);
            entry.put("recoveryState", after.at("/analysis/recovery/state").asText());
            history.add(entry);
            originalSnapshots.put(itemKey, ScenarioSnapshotObservation.observe(nextId, after));
        }
        app.save("continuation-history.json", app.json.valueToTree(history));
        throw new AssertionError("The declared scenario did not finish its independent budget work within the bounded driver");
    }

    private static Map<String, PassIdentity> passIdentities(JsonNode operation) {
        assertThat(operation.path("jobs")).hasSize(2);
        var result = new LinkedHashMap<String, PassIdentity>();
        for (JsonNode job : operation.path("jobs")) {
            assertThat(job.path("id").asText()).isNotBlank();
            assertThat(job.path("provider").asText()).isNotBlank();
            assertThat(job.path("analysisReasonLength").asText()).isNotBlank();
            assertThat(job.path("items")).hasSize(1);
            for (JsonNode item : job.path("items")) {
                assertThat(item.path("id").isIntegralNumber()).isTrue();
                assertThat(item.path("requirementId").isIntegralNumber()).isTrue();
                assertThat(item.path("requirementVersionId").isIntegralNumber()).isTrue();
                String key = job.path("id").asText() + ":" + item.path("id").asText();
                assertThat(result.putIfAbsent(key, new PassIdentity(item.path("requirementId").asLong(),
                        item.path("requirementVersionId").asLong(), job.path("provider").asText(),
                        job.path("analysisReasonLength").asText()))).as("Unique original pass identity").isNull();
            }
        }
        return Map.copyOf(result);
    }

    private static JsonNode awaitTerminal(ScenarioArchitectureAcceptanceTest app, String path) throws Exception {
        return awaitTerminal(app, path, operation -> true);
    }

    private static JsonNode awaitTerminal(ScenarioArchitectureAcceptanceTest app, String path,
                                          java.util.function.Predicate<JsonNode> awaitedAttempt) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        JsonNode operation;
        do {
            // Every concurrent status reader must succeed; no hidden HTTP retry.
            var readers = java.util.stream.IntStream.range(0, 3).mapToObj(index ->
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try { return app.get(path); }
                        catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                    })).toList();
            operation = null;
            for (var reader : readers) operation = reader.join();
            if (TERMINAL.contains(operation.path("status").asText()) && awaitedAttempt.test(operation)) return operation;
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Scenario operation did not reach a terminal state: " + operation);
    }

    private static String itemPath(long projectId, JsonNode job, JsonNode item) {
        return "/api/projects/" + projectId + "/analysis-jobs/" + job.path("id").asText() + "/items/" + item.path("id").asLong();
    }
    private static JsonNode findItem(JsonNode operation, String jobId, long itemId) {
        for (JsonNode job : operation.path("jobs")) if (jobId.equals(job.path("id").asText()))
            for (JsonNode item : job.path("items")) if (itemId == item.path("id").asLong()) return item;
        throw new AssertionError("Continuation replaced the original verification job or item");
    }
}
