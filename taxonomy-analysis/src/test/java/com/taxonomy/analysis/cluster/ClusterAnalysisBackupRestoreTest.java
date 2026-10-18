package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.backup.*;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;

import static com.taxonomy.analysis.cluster.ClusterAnalysisBackupRecords.*;
import static com.taxonomy.analysis.cluster.ClusterAnalysisStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ClusterAnalysisBackupRestoreTest {
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP"), IP = TaxonomyShardRoot.of("IP");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void backupRetainsWaitingPredecessorHistoryWithoutResumingItOnRestore() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("waiting-source"); source.admitFrozen(original);
            new TransactionTemplate(source.transactions).executeWithoutResult(status -> source.em.createQuery(
                    "update ClusterAnalysisWork w set w.state='WAITING_PREDECESSOR' where w.operationId=:id and w.root='IP'")
                    .setParameter("id", original.operationId()).executeUpdate());
            var archive = exportedArchive(source);
            var waiting = archive.work().stream().filter(work -> work.root().equals("IP")).findFirst().orElseThrow();
            assertEquals("WAITING_PREDECESSOR", waiting.sourceState());
            assertEquals(0, waiting.attempts()); assertNull(waiting.startedAt()); assertNull(waiting.resultJson());
            var restored = targetContext("waiting-restored");

            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand());

            var snapshot = target.store.snapshot(restored);
            assertEquals(ClusterAnalysisState.CANCELLED, snapshot.state());
            assertEquals(List.of("STOPPED", "STOPPED"), snapshot.tasks().stream().map(ClusterAnalysisStore.TaskView::state).toList());
            assertTrue(snapshot.result().getRawScores().isEmpty());
            assertNull(snapshot.progress().nodes().taxonomies().getLast().rootPercent());
            assertStoppedObservation(target, restored, 0);
            assertNoOperationalState(target);
        }
    }

    @Test void waitingPredecessorCannotImportWorkerProgressBeforeAnyDelivery() {
        try (var db = new Database()) {
            var context = context("waiting-progress"); db.admit(context);
            var task = db.expectedTask(IP);
            var progress = new ClusterAnalysisProgress.Worker(UUID.randomUUID().toString(), 1, 1,
                    "SCORING", "IP", Map.of("IP", 0d), null);
            assertThrows(IllegalArgumentException.class, () -> ClusterAnalysisBackupRestorer.validateProgress(
                    progress, db.store.snapshot(context).revision(), 0, "WAITING_PREDECESSOR", task));
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "DETAILED, SHORT, false",
            "DETAILED, <null>, false",
            "<null>, STANDARD, false",
            "DETAILED, DETAILED, true",
            "<null>, <null>, true"
    }, nullValues = "<null>")
    void restorePreservesExactNullableReasonLengthBeforeWriting(AnalysisReasonLength captured,
                                                               AnalysisReasonLength requested,
                                                               boolean accepted) throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("reason-length-source");
            source.store.admit(original, command("requirement").withAnalysisReasonLength(captured), null,
                    Map.of(CP, "{\"root\":\"CP\"}", IP, "{\"root\":\"IP\"}"));
            source.store.accept(source.complete(source.task(CP), result("CP", 70)));
            source.store.start(source.task(IP));
            var archive = exportedArchive(source);
            var restored = targetContext("reason-length-restored");
            var requestedCommand = targetCommand().withAnalysisReasonLength(requested);
            var restorer = new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON);

            if (accepted) {
                restorer.restore(archive, restored, requestedCommand);
                var result = target.store.snapshot(restored).result();
                assertEquals(captured, target.store.command(restored).analysisReasonLength());
                assertEquals(captured, result.getAnalysisReasonLength(),
                        "Restored committed evidence must retain the recorded value, including legacy absence");
                assertEquals(Map.of("CP", 70d), result.getRawScores());
                assertEquals(ClusterAnalysisState.CANCELLED, target.store.snapshot(restored).state());
            } else {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> restorer.restore(archive, restored, requestedCommand));
                assertEquals("Restore cannot change the captured analysis request", failure.getMessage());
                assertRestoredClosureEmpty(target);
            }
            assertNoOperationalState(target);
        }
    }

    @Test void exportedLiveCoverageRemainsObservableAfterInterruptedRestoreWithoutAWorkerClaim() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("live-progress-source"); source.admitFrozen(original);
            var task = source.task(CP); var input = source.store.start(task);
            var worker = source.store.diagnostics(task, input.deliveryAttempt());
            var detail = new com.taxonomy.dto.LlmCallDetail(); detail.setScores(Map.of("CP", 70)); worker.assessment(detail);
            var archive = exportedArchive(source); var restored = targetContext("live-progress-restored");
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand());
            try (var observer = new DurableClusterAnalysisObservation(target.store, new ClusterAnalysisSignals(), AnalysisEventPublisher.NONE)) {
                var snapshot = observer.snapshot(restored.operationId(), "bob", targetCommand().workspaceContext()).orElseThrow().snapshot();
                assertNotNull(snapshot.nodeProgress()); assertEquals(1, snapshot.nodeProgress().assessed());
                assertEquals(2, snapshot.nodeProgress().open()); assertEquals("CANCELLED", snapshot.status());
                assertEquals("FINISHED", snapshot.phase()); assertTrue(snapshot.rawScores().isEmpty());
            }
            assertTrue(target.sent.isEmpty());
            var targetTask = archive(target, restored.operationId()).work().getFirst();
            var message = (AnalysisTaskMessage) new com.taxonomy.analysis.dag.json.AnalysisMessageCodec()
                    .decode(targetTask.messageJson().getBytes(StandardCharsets.UTF_8));
            assertFalse(target.store.start(message).executable());
            assertThrows(IllegalStateException.class, () -> target.store.diagnostics(message, input.deliveryAttempt()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision", "delivery", "foreignNode", "foreignTaskType"})
    void invalidArchivedProgressCannotClaimNewCoverageOrWorkerAuthority(String mismatch) throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("invalid-progress-source"); source.admitFrozen(original);
            var task = source.task(CP); var input = source.store.start(task);
            var worker = source.store.diagnostics(task, input.deliveryAttempt());
            var detail = new com.taxonomy.dto.LlmCallDetail(); detail.setScores(Map.of("CP", 70)); worker.assessment(detail);
            var archive = exportedArchive(source); var first = archive.work().getFirst();
            var progress = (tools.jackson.databind.node.ObjectNode) JSON.readTree(first.progressJson());
            switch (mismatch) {
                case "revision" -> progress.put("revision", archive.run().revision() + 1);
                case "delivery" -> progress.put("delivery", first.attempts() + 1);
                case "foreignNode" -> ((tools.jackson.databind.node.ObjectNode) progress.get("scores")).put("foreign-node", 90);
                case "foreignTaskType" -> progress.set("relations", JSON.valueToTree(new com.taxonomy.dto.RelationSearchProgress(
                        0, 0, 0, 0, 0, 0, 0, 24, 0, com.taxonomy.dto.RelationSearchProgress.Step.SOURCES, null, List.of())));
                default -> throw new AssertionError(mismatch);
            }
            var altered = new Work(first.sourceId(), first.run(), first.taskId(), first.taskType(), first.root(), first.ordinal(),
                    first.messageJson(), first.inputJson(), first.resultJson(), first.failureReason(), first.sourceState(),
                    first.settled(), first.attempts(), first.startedAt(), first.finishedAt(), JSON.writeValueAsString(progress));
            var work = new ArrayList<>(archive.work()); work.set(0, altered);
            var invalid = new Archive(archive.run(), work, archive.inputs(), archive.events(), archive.calls(), archive.attempts());
            assertThrows(IllegalArgumentException.class, () -> new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON)
                    .restore(invalid, targetContext("invalid-progress-target"), targetCommand()));
            long restoredRuns = new TransactionTemplate(target.transactions).execute(status -> target.em
                    .createQuery("select count(r) from ClusterAnalysisRun r", Long.class).getSingleResult());
            assertEquals(0L, restoredRuns);
            assertTrue(target.sent.isEmpty());
        }
    }

    @Test void diagnosticsExportRestoreRetainsBoundedDetailUnresolvedStartAndReplayWithoutResuming() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("diagnostic-source"); source.admit(original); var task = source.task(CP); source.store.start(task);
            var session = source.store.diagnostics(task); long call = session.started("Mock", "CP");
            session.prepared(call, "request".repeat(2000));
            var unresolved = new com.taxonomy.analysis.service.LlmTransportMeter.Attempt(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "CUSTOM_OPENAI", com.taxonomy.analysis.service.LlmTransportMeter.Source.HTTP, 0);
            session.started(unresolved);
            var replay = new com.taxonomy.analysis.service.LlmTransportMeter.Attempt(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "CUSTOM_OPENAI", com.taxonomy.analysis.service.LlmTransportMeter.Source.RECORDING_REPLAY, 0);
            session.started(replay); session.completed(replay, new com.taxonomy.analysis.service.LlmTransportMeter.Observation(replay.invocationId(), replay.provider(), replay.source(), 0, null,
                    com.taxonomy.analysis.service.LlmTransportMeter.Outcome.RESPONSE, 0, new com.taxonomy.analysis.service.LlmTransportMeter.Usage(null, null, null, null, null, false)));
            var exported = exportedArchive(source); assertEquals(1, exported.calls().size()); assertEquals(2, exported.attempts().size());
            var restored = targetContext("diagnostic-restored");
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(exported, restored, targetCommand());
            var detail = target.store.callDiagnostics().detail(restored, call);
            assertTrue(detail.truncated()); assertEquals(ClusterAnalysisDiagnostics.MAX_TEXT, detail.prompt().length()); assertEquals(14000, detail.promptLength());
            assertEquals(2, detail.attempts().size()); assertTrue(detail.attempts().stream().anyMatch(a -> a.source().equals("HTTP") && a.state().equals("STARTED")));
            assertTrue(detail.attempts().stream().anyMatch(a -> a.source().equals("RECORDING_REPLAY") && a.state().equals("COMPLETED") && a.usage().totalTokens() == null));
            assertNotEquals(unresolved.id(), detail.attempts().stream().filter(a -> a.source().equals("HTTP")).findFirst().orElseThrow().attemptId());
            assertTrue(detail.taskId().startsWith("diagnostic-restored:")); assertTrue(target.sent.isEmpty());
            assertEquals(ClusterAnalysisState.CANCELLED, target.store.snapshot(restored).state());
            assertEquals(2, archive(target, restored.operationId()).attempts().size());
        }
    }

    @Test void interruptedRestoreRetainsCommittedRootAndFrozenInputWithoutDispatching() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("source"); source.admit(original);
            source.store.accept(source.complete(source.task(CP), result("CP", 70)));
            source.store.start(source.task(IP));
            var archive = exportedArchive(source);
            var restored = targetContext("restored");
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand());
            var snapshot = target.store.snapshot(restored);
            assertEquals(ClusterAnalysisState.CANCELLED, snapshot.state());
            assertEquals(Map.of("CP", 70d), snapshot.result().getRawScores());
            assertNull(snapshot.result().getAnalysisCoverage(), "Legacy evidence must retain its original coverage contract");
            assertEquals("PARTIAL", snapshot.result().getStatus());
            assertTrue(snapshot.result().getWarnings().stream().anyMatch(w -> w.contains("RESTORE_INTERRUPTED")));
            var restoredArchive = archive(target, restored.operationId());
            assertEquals("RESTORE_INTERRUPTED", restoredArchive.work().stream()
                    .filter(work -> work.root().equals("IP")).findFirst().orElseThrow().failureReason());
            assertEquals(archive.run().revision() + 1, restoredArchive.run().revision());
            assertEquals("{\"root\":\"IP\"}", target.store.shard(restored, IP));
            assertTrue(snapshot.tasks().stream().noneMatch(w -> Set.of("QUEUED", "RUNNING").contains(w.state())));
            assertEquals(AnalysisProgressPhase.OPERATION_STOPPED, target.store.events(restored, 0, 100).getLast().phase());
            assertEquals(restored, target.store.authorize(restored.operationId(), "bob", targetCommand().workspaceContext()));
            assertEquals(List.of(restored), target.store.recent("bob", targetCommand().workspaceContext(), null, null));
            assertStoppedObservation(target, restored, 1);
            assertNull(new TransactionTemplate(target.transactions).execute(status -> target.em.find(ClusterAnalysisRun.class, "source")));
            assertNoOperationalState(target);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedRestoreRetainsModernCoverageAndUnassessedFrozenNodes(boolean emptyCommittedResult) throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("covered-source"); source.admitFrozen(original);
            var cp = coveredResult("CP", 70);
            source.store.accept(source.complete(source.task(CP), cp));
            if (emptyCommittedResult) {
                var failed = new AnalysisResult(Map.of(), List.of()); failed.setStatus("ERROR");
                source.complete(source.task(IP), failed); // Durable effect exists before coordinator settlement.
            } else source.store.start(source.task(IP));
            var archive = exportedArchive(source);
            var restored = targetContext("covered-restored");
            assertNotEquals(original.authority(), restored.authority());

            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand());

            var result = target.store.snapshot(restored).result();
            var coverage = result.getAnalysisCoverage();
            assertNotNull(coverage, "Interrupted archive restore must preserve committed modern assessment evidence");
            assertEquals(cp.getAnalysisCoverage().nodes().get("CP"), coverage.nodes().get("CP"));
            assertEquals(Set.of("CP", "IP", "fixture-ip-child"), coverage.nodes().keySet());
            for (String code : List.of("IP", "fixture-ip-child")) {
                var node = coverage.nodes().get(code);
                assertEquals(AnalysisCoverage.State.UNKNOWN, node.state());
                assertNull(node.score()); assertNull(node.effectiveRelevance());
                assertEquals("INTERRUPTED:ROOT_ANALYSIS_UNAVAILABLE", node.reason());
            }
            assertEquals(AnalysisCoverage.Descendants.UNASSESSED, coverage.nodes().get("IP").descendants());
            assertEquals(AnalysisCoverage.Descendants.COMPLETE, coverage.nodes().get("fixture-ip-child").descendants());
            assertEquals(1, coverage.assessedNodes()); assertEquals(2, coverage.unknownNodes());
            assertEquals(2, coverage.failedOrBlockedNodes()); assertTrue(coverage.hasOpenEvaluations());
            assertEquals(Map.of("CP", 70d), result.getRawScores()); assertEquals("PARTIAL", result.getStatus());
            assertExportable(result);
            assertEquals(source.store.shard(original, IP), target.store.shard(restored, IP),
                    "Frozen inputs retain the original source even when restore remaps the target authority");
            assertTrue(source.store.cancel(original));
            assertEquals(source.store.snapshot(original).result().getAnalysisCoverage(), coverage,
                    "Live cancellation and interrupted restore must agree on assessment evidence");
            assertStoppedObservation(target, restored, 1);
            assertNoOperationalState(target);
        }
    }

    @Test void interruptedRestoreDoesNotInventCoverageForMixedLegacyAndModernEvidence() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("mixed-source"); source.admit(original);
            source.store.accept(source.complete(source.task(CP), coveredResult("CP", 70)));
            source.complete(source.task(IP), result("IP", 30));
            var archive = exportedArchive(source); var restored = targetContext("mixed-restored");

            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand());

            var result = target.store.snapshot(restored).result();
            assertNull(result.getAnalysisCoverage(), "A modern subset cannot describe legacy scored evidence");
            assertEquals(Map.of("CP", 70d, "IP", 30d), result.getRawScores());
            assertEquals("PARTIAL", result.getStatus()); assertExportable(result);
            assertNoOperationalState(target);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository", "workspace", "branch", "commit", "root"})
    void interruptedRestoreRejectsForeignFrozenCoverageBeforeWriting(String mismatch) throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("guarded-coverage"); source.admitFrozen(original);
            source.store.accept(source.complete(source.task(CP), coveredResult("CP", 70)));
            var archive = exportedArchive(source);
            var input = archive.inputs().stream().filter(value -> value.root().equals("IP")).findFirst().orElseThrow();
            var frozen = JSON.readValue(input.inputJson(), RootCatalogueSnapshot.class);
            var identity = frozen.source();
            var foreign = new CatalogueSourceIdentity(
                    mismatch.equals("repository") ? "foreign-repository" : identity.repositoryId(),
                    mismatch.equals("workspace") ? "foreign-workspace" : identity.workspaceId(),
                    mismatch.equals("branch") ? "foreign-branch" : identity.branch(),
                    mismatch.equals("commit") ? "foreign-commit" : identity.sourceCommit());
            var changed = mismatch.equals("root")
                    ? JSON.readValue(source.store.shard(original, CP), RootCatalogueSnapshot.class)
                    : new RootCatalogueSnapshot(frozen.schemaVersion(), foreign, frozen.rootCode(), frozen.nodes(),
                            frozen.overlayMetadata(), frozen.catalogueProvenance());
            var corrupted = new Archive(archive.run(), archive.work(), archive.inputs().stream()
                    .map(value -> value == input ? new Input(value.sourceId(), value.run(), value.root(), JSON.writeValueAsString(changed)) : value)
                    .toList(), archive.events());

            var failure = assertThrows(IllegalStateException.class, () ->
                    new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON)
                            .restore(corrupted, targetContext("foreign-coverage"), targetCommand()));

            assertEquals("Frozen coverage source differs from admitted authority", failure.getMessage());
            assertRestoredClosureEmpty(target); assertNoOperationalState(target);
        }
    }

    @Test void interruptedRestoreRejectsDuplicateUnknownCoverageBeforeWriting() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("duplicate-coverage"); source.admitFrozen(original);
            var unknown = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                    AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:fixture");
            for (var root : List.of(CP, IP)) {
                var result = coveredResult(root.code(), 70);
                var nodes = new LinkedHashMap<>(result.getAnalysisCoverage().nodes());
                nodes.put("fixture-duplicate", unknown);
                result.setAnalysisCoverage(new AnalysisCoverage(nodes, 1, 1, 1));
                var completion = source.complete(source.task(root), result);
                if (root.equals(CP)) source.store.accept(completion);
            }
            var archive = exportedArchive(source);

            var failure = assertThrows(IllegalStateException.class, () ->
                    new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON)
                            .restore(archive, targetContext("duplicate-restored"), targetCommand()));

            assertEquals("Conflicting catalogue identities across root coverage", failure.getMessage());
            assertRestoredClosureEmpty(target); assertNoOperationalState(target);
        }
    }

    @Test void cancelledRunExportsAndRestoresCanonicalStoppedTasks() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("cancelled-source"); source.admit(original);
            source.store.accept(source.complete(source.task(CP), result("CP", 70)));
            source.store.start(source.task(IP));
            assertTrue(source.store.cancel(original));
            var archive = exportedArchive(source);
            assertEquals("CANCELLED", archive.run().sourceState());
            assertEquals(List.of("COMPLETED", "STOPPED"), archive.work().stream().map(Work::sourceState).toList());
            var restored = targetContext("restored-cancelled");
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand());
            var snapshot = target.store.snapshot(restored);
            assertEquals(ClusterAnalysisState.CANCELLED, snapshot.state());
            assertEquals(Map.of("CP", 70d), snapshot.result().getRawScores());
            assertEquals(source.store.snapshot(original).result().getWarnings(), snapshot.result().getWarnings());
            assertEquals(List.of("COMPLETED", "STOPPED"), snapshot.tasks().stream().map(ClusterAnalysisStore.TaskView::state).toList());
            assertEquals(AnalysisProgressPhase.OPERATION_STOPPED, target.store.events(restored, 0, 100).getLast().phase());
            assertStoppedObservation(target, restored, 1);
            assertTerminalProvenance(archive, archive(target, restored.operationId()));
            assertNoOperationalState(target);
        }
    }

    @Test void cooperativeStopExportsAndRestoresOriginalFailureProvenance() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("time-limited-source"); source.admit(original);
            source.store.start(source.task(CP));
            var task = source.task(CP);
            var stopped = new AnalysisMessageFactory(original, Clock.systemUTC())
                    .completed(task, AnalysisTaskOutcome.STOPPED, null, 0, "TIME_LIMIT");
            source.completions.commit(new PreparedAnalysisCompletion<>(stopped,
                    () -> source.store.persistFailure(task, "TIME_LIMIT")));
            assertTrue(source.store.accept(stopped));
            var archive = exportedArchive(source);
            assertEquals("PARTIAL", archive.run().sourceState());
            assertEquals(Arrays.asList("TIME_LIMIT", null), archive.work().stream().map(Work::failureReason).toList());
            assertEquals(List.of("FAILED", "STOPPED"), archive.work().stream().map(Work::sourceState).toList());
            var restored = targetContext("restored-time-limited");
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand());
            var snapshot = target.store.snapshot(restored);
            assertEquals(ClusterAnalysisState.PARTIAL, snapshot.state());
            assertEquals("TIME_LIMIT: remaining analysis work was stopped", snapshot.result().getErrorMessage());
            assertFalse(snapshot.result().getWarnings().stream().anyMatch(warning -> warning.contains("RESTORE_INTERRUPTED")));
            assertTerminalProvenance(archive, archive(target, restored.operationId()));
            assertNoOperationalState(target);
        }
    }

    @Test void terminalResultRemainsTerminalAndUsesNewDatabaseKeys() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("finished"); source.admit(original);
            for (var root : List.of(CP, IP)) source.store.accept(source.complete(source.task(root), result(root.code(), 55)));
            var archive = archive(source, original.operationId()); var restored = targetContext("restored-finished");
            var helper = new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON);
            helper.restore(archive, restored, targetCommand());
            var snapshot = target.store.snapshot(restored);
            assertEquals(ClusterAnalysisState.COMPLETED, snapshot.state());
            assertEquals("SUCCESS", snapshot.result().getStatus());
            assertEquals(Map.of("CP", 55d, "IP", 55d), snapshot.result().getRawScores());
            assertFalse(snapshot.result().getWarnings().stream().anyMatch(w -> w.contains("RESTORE_INTERRUPTED")));
            assertTrue(snapshot.tasks().stream().allMatch(w -> w.taskId().startsWith("restored-finished:")));
            assertThrows(IllegalStateException.class, () -> helper.restore(archive, restored, targetCommand()));
            assertNoOperationalState(target);
        }
    }

    @Test void callerRollbackRemovesWholeRestoredClosure() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("rollback-source"); source.admit(original); var archive = archive(source, original.operationId());
            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(target.transactions).executeWithoutResult(status -> {
                new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, targetContext("rollback"), targetCommand());
                target.em.flush(); throw new IllegalStateException("rollback");
            }));
            new TransactionTemplate(target.transactions).executeWithoutResult(status -> {
                for (String entity : List.of("ClusterAnalysisRun", "ClusterAnalysisWork", "ClusterAnalysisInput", "ClusterAnalysisEvent"))
                    assertEquals(0L, target.em.createQuery("select count(r) from " + entity + " r", Long.class).getSingleResult());
            });
            assertNoOperationalState(target);
        }
    }

    @Test void restoredHistoryUsesRemappedProjectAndRequirementSelectors() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var base = context("project-source");
            var original = new AnalysisOperationContext(base.operationId(), base.authority(), RequirementReference.of(1L, 2L, "old-snapshot", "requirement"), base.correlationId());
            source.admit(original); var baseTarget = targetContext("project-restored");
            var restored = new AnalysisOperationContext(baseTarget.operationId(), baseTarget.authority(), RequirementReference.of(9L, 10L, "new-snapshot", "requirement"), baseTarget.correlationId());
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive(source, original.operationId()), restored, targetCommand());
            assertEquals(List.of(restored), target.store.recent("bob", targetCommand().workspaceContext(), 9L, 10L));
            assertTrue(target.store.recent("bob", targetCommand().workspaceContext(), 1L, 2L).isEmpty());
            assertNoOperationalState(target);
        }
    }

    @Test void rejectsCrossRunEvidenceAndChangedRequirementBeforeWriting() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("guarded"); source.admit(original); var archive = archive(source, original.operationId());
            var input = archive.inputs().getFirst();
            var foreign = new Input(input.sourceId(), new SourceRecordId("analysis.cluster-run", "other"), input.root(), input.inputJson());
            var corrupted = new Archive(archive.run(), archive.work(), List.of(foreign), archive.events());
            var helper = new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON);
            assertThrows(IllegalArgumentException.class, () -> helper.restore(corrupted, targetContext("bad-input"), targetCommand()));
            var changed = new AnalyzeRequirementCommand("changed", false, 20, "MOCK", "bob", targetCommand().workspaceContext(), null, targetCommand().analysisScope());
            assertThrows(IllegalArgumentException.class, () -> helper.restore(archive, targetContext("bad-text"), changed));
            assertThrows(IllegalArgumentException.class, () -> helper.restore(archive, original, command("requirement")));
            Long count = new TransactionTemplate(target.transactions).execute(status -> target.em.createQuery("select count(r) from ClusterAnalysisRun r", Long.class).getSingleResult());
            assertEquals(0L, count);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void interruptedExtensionRestoreKeepsCanonicalNodeEvidenceAndOnlyCurrentSearchAccounting(boolean previousAcceptedRelations) throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var captured = captureRelationsExtension(source, previousAcceptedRelations);
            var retained = captured.command().retainedAnalysis();
            var offered = JSON.readValue(JSON.writeValueAsString(retained), AnalysisResult.class);
            var reverseReasons = new LinkedHashMap<String, String>();
            var reasonEntries = new ArrayList<>(retained.getReasons().entrySet()); Collections.reverse(reasonEntries);
            reasonEntries.forEach(entry -> reverseReasons.put(entry.getKey(), entry.getValue()));
            offered.setReasons(reverseReasons); // The same authority may be reconstructed with a different map order.
            assertNotEquals(new ArrayList<>(retained.getReasons().keySet()), new ArrayList<>(offered.getReasons().keySet()));
            var requested = remappedExtensionCommand(captured.command(), offered);
            var restored = targetContext("restored-extension");
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(captured.archive(), restored, requested);
            var snapshot = target.store.snapshot(restored); var result = snapshot.result();
            assertEquals(retained.getWarnings(), result.getWarnings().stream().filter(warning -> !warning.startsWith("RESTORE_INTERRUPTED:")).toList(),
                    "Relations work and interrupted restoration cannot discard original node warnings");
            assertEquals(retained.getDiscrepancies(), result.getDiscrepancies());
            assertEquals(retained.getProductCoverageGaps(), result.getProductCoverageGaps());
            assertEquals(retained.getRawScores(), result.getRawScores()); assertEquals(retained.getScores(), result.getScores());
            assertEquals(retained.getReasons(), result.getReasons()); assertEquals(retained.getAnalysisCoverage(), result.getAnalysisCoverage());
            assertEquals(JSON.valueToTree(retained.getTree()), JSON.valueToTree(result.getTree()));
            assertEquals(retained.getScoreSemanticsFingerprintSha256(), result.getScoreSemanticsFingerprintSha256());
            var report = result.getRelationSearchReport(); assertNotNull(report);
            assertEquals(2, report.totalCalls(), "Earlier accepted exchanges cannot overwrite the current plan's call accounting");
            assertEquals(1, report.progress().completedSearches()); assertEquals(1, report.progress().pendingSearches());
            assertEquals(0, report.progress().unresolvedSearches());
            assertEquals(previousAcceptedRelations ? 1 : 0, report.result().edges().size());
            assertEquals(previousAcceptedRelations ? 1 : 0, report.progress().verifiedRelations());
            assertEquals(report.progress(), snapshot.progress().relations(), "Reloaded telemetry must expose the retained evidence and current work accounting");
            assertFalse(report.isSearchExhausted()); assertEquals("PARTIAL", result.getStatus());
            if (previousAcceptedRelations) {
                assertEquals("SUCCESS", retained.getArchitectureView().getAnalysisStatus());
                assertEquals(7, retained.getArchitectureView().getRelationSearchReport().totalCalls());
                var view = result.getArchitectureView(); assertNotNull(view);
                assertAll("Restored architecture view reflects the current interrupted relation work",
                        () -> assertEquals(report, view.getRelationSearchReport()),
                        () -> assertEquals(result.getStatus(), view.getAnalysisStatus()));
            }
            assertEquals(ClusterAnalysisState.CANCELLED, snapshot.state());
            assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.startsWith("RESTORE_INTERRUPTED:")));
            assertEquals(captured.archive().inputs().stream().map(Input::inputJson).sorted().toList(),
                    archive(target, restored.operationId()).inputs().stream().map(Input::inputJson).sorted().toList(),
                    "Historical frozen inputs retain their original source identity");
            assertNull(target.store.command(restored).continuationBinding());
            for (var work : archive(target, restored.operationId()).work()) {
                var task = (AnalysisTaskMessage) new com.taxonomy.analysis.dag.json.AnalysisMessageCodec()
                        .decode(work.messageJson().getBytes(StandardCharsets.UTF_8));
                assertFalse(target.store.start(task).executable(), "Restored source and relation tasks are historical evidence only");
            }
            assertEquals(List.of(restored), target.store.recent("bob", requested.workspaceContext(), null, null));
            assertThrows(SecurityException.class, () -> target.store.authorize(restored.operationId(), "alice", requested.workspaceContext()));
            assertNoOperationalState(target);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"drop", "scores", "reasons", "tree", "coverage", "warnings", "discrepancies", "gaps"})
    void restoreRejectsDroppedOrAlteredRetainedNodeAuthorityBeforeWriting(String alteration) throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var captured = captureRelationsExtension(source, false);
            var changed = JSON.readValue(JSON.writeValueAsString(captured.command().retainedAnalysis()), AnalysisResult.class);
            switch (alteration) {
                case "drop" -> changed = null;
                case "scores" -> changed.setRawScores(Map.of("CP", 99d, "IP", 0d));
                case "reasons" -> changed.setReasons(Map.of("CP", "Changed explanation"));
                case "tree" -> changed.getTree().getFirst().setNameEn("Changed catalogue label");
                case "coverage" -> changed.setAnalysisCoverage(null);
                case "warnings" -> changed.setWarnings(List.of());
                case "discrepancies" -> changed.setDiscrepancies(List.of());
                case "gaps" -> changed.setProductCoverageGaps(List.of());
                default -> throw new AssertionError(alteration);
            }
            var requested = remappedExtensionCommand(captured.command(), changed);
            var failure = assertThrows(IllegalArgumentException.class, () -> new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON)
                    .restore(captured.archive(), targetContext("altered-extension"), requested));
            assertEquals("Restore cannot change the captured analysis request", failure.getMessage());
            assertRestoredClosureEmpty(target); assertNoOperationalState(target);
        }
    }

    private record ExtensionCapture(Archive archive, AnalyzeRequirementCommand command) { }
    private static ExtensionCapture captureRelationsExtension(Database db, boolean previousAcceptedRelations) throws Exception {
        var proxy = new org.springframework.aop.framework.ProxyFactory(new com.taxonomy.analysis.recovery.AnalysisContinuationStore(db.em, JSON));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(db.transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var saved = (com.taxonomy.analysis.recovery.AnalysisContinuationStore) proxy.getProxy();
        var store = new ClusterAnalysisStore(db.em, db.transactions, JSON, db.dispatch, saved);
        var context = context("source-extension"); var base = command("requirement");
        var roots = TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(root -> DurableClusterAnalysisExecutionTest.root(
                DurableClusterAnalysisExecutionTest.source(context), root.code())).toList();
        var tree = roots.stream().map(root -> {
            var node = root.nodes().getFirst(); var dto = new com.taxonomy.dto.TaxonomyNodeDto();
            dto.setCode(node.code()); dto.setTaxonomyRoot(node.taxonomyRoot()); dto.setNameEn(node.nameEn());
            dto.setLevel(node.level()); dto.setSortOrder(node.sortOrder()); dto.setAnalysisRole(node.metadata().analysisRole());
            dto.setSecondaryClassificationCodes(node.metadata().secondaryClassificationCodes());
            dto.setClassificationConfidence(node.metadata().confidence()); dto.setClassificationReviewRequired(node.metadata().reviewRequired());
            return dto;
        }).toList();
        var request = new com.taxonomy.dto.AnalysisRequest("requirement"); request.setProvider("MOCK"); request.setMaxArchitectureNodes(20);
        request.setAnalysisScope(base.analysisScope()); request.setContinuationId(UUID.randomUUID().toString());
        var sourceClaim = saved.begin(request, base.workspaceContext(), "a".repeat(64), tree);
        var original = new AnalysisResult(Map.of("CP", 77.125, "IP", 0d), tree); original.setStatus("PARTIAL"); original.setProvider("MOCK");
        original.setReasons(Map.of("CP", "Retained original explanation — ä.", "IP", "Retained zero-score explanation."));
        original.setWarnings(List.of("NODE_WARNING: retain the original assessment note"));
        original.setDiscrepancies(List.of(new com.taxonomy.dto.TaxonomyDiscrepancy("CP", 77.125, 88.75)));
        original.setProductCoverageGaps(List.of(new com.taxonomy.dto.ProductCoverageGap("IP", "Original product family", 42d,
                List.of("catalogued-candidate"), "Original product coverage gap")));
        saved.complete(sourceClaim, original, tree);
        var sourceSnapshot = saved.read(request.getContinuationId(), base.workspaceContext());
        var extension = JSON.readValue(JSON.writeValueAsString(request), com.taxonomy.dto.AnalysisRequest.class);
        extension.setContinuationId(UUID.randomUUID().toString()); extension.setRelationSourceContinuationId(request.getContinuationId());
        extension.setRelationSourceVersion(sourceSnapshot.recovery().version());
        extension.setAnalysisScope(new com.taxonomy.dto.AnalysisScope(base.analysisScope().taxonomyRoots(), com.taxonomy.dto.AnalysisMode.FULL));
        var claim = saved.begin(extension, base.workspaceContext(), "b".repeat(64), tree);
        var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice", base.workspaceContext(), null,
                extension.getAnalysisScope(), null, new com.taxonomy.analysis.recovery.AnalysisContinuationBinding(claim.id(), claim.token(),
                "b".repeat(64), "c".repeat(64), "d".repeat(64))).withRetainedAnalysis(claim.retainedAnalysis());
        var targets = new LinkedHashMap<TaxonomyShardRoot, String>(); roots.forEach(root -> targets.put(TaxonomyShardRoot.of(root.rootCode()), JSON.writeValueAsString(root)));
        var sources = new LinkedHashMap<TaxonomyShardRoot, String>();
        targets.forEach((root, value) -> { if (extension.getAnalysisScope().selects(root.code())) sources.put(root, value); });
        store.admit(context, command, null, sources, targets);
        assertEquals(command.retainedAnalysis().getWarnings(), store.rootResults(context).getWarnings(), "The live aggregate retains the canonical notes");
        var preparation = (RelationAnalysisTask) db.sent.getFirst(); var input = store.start(preparation);
        var worker = store.diagnostics(preparation, input.deliveryAttempt());
        worker.relations(new com.taxonomy.dto.RelationSearchProgress(1, 1, 2, 0, 0, 2, 1, 10, 0,
                com.taxonomy.dto.RelationSearchProgress.Step.SOURCES,
                new com.taxonomy.dto.RelationSearchProgress.Current("CP", "", "", null, 0, List.of("CP")),
                List.of(new com.taxonomy.dto.RelationSearchProgress.Taxonomy("CR", 1, 0, 0, 1),
                        new com.taxonomy.dto.RelationSearchProgress.Taxonomy("IP", 1, 0, 0, 1))));
        var node = new com.taxonomy.dto.RelationSearchModel.Node("CP", "CP", "Capability", "", false);
        var contribution = new com.taxonomy.dto.RelationSearchModel.Contribution(node, "requirement", "requirement", "");
        var source = new com.taxonomy.dto.RelationSearchModel.SourceAssessment(node, List.of(contribution), "", "");
        var target = new com.taxonomy.dto.RelationSearchModel.Node("CR", "CR", "Frozen CR", "", true);
        var plan = new RelationSearchDistribution.Plan(1, context.operationId() + ":relations", context.requirement().textSha256(),
                preparation.prerequisiteTasks().stream().map(AnalysisTaskId::value).toList(),
                new com.taxonomy.analysis.relations.RequirementRelationSearch.Options(new com.taxonomy.dto.RelationSearchModel.Limits(10, 8, 10, 128), 16),
                List.of(node), List.of(target, new com.taxonomy.dto.RelationSearchModel.Node("IP", "IP", "Frozen IP", "", true)), List.of(source),
                List.of(new RelationSearchDistribution.Item(0, 0, 0, "REALIZES", com.taxonomy.dto.RelationSearchModel.Direction.OUTGOING, TaxonomyShardRoot.of("CR"), 1),
                        new RelationSearchDistribution.Item(1, 0, 0, "REQUIRES", com.taxonomy.dto.RelationSearchModel.Direction.OUTGOING, IP, 1)),
                1, 0, List.of(), "", true);
        var prepared = ClusterRelationCoordinatorTest.completion(context, preparation);
        db.completions.commit(new PreparedAnalysisCompletion<>(prepared, () -> store.persistRelationPlan(preparation, plan))); store.accept(prepared);
        var task = (RelationAnalysisTask) db.sent.stream().filter(value -> value.taskId().relationWorkOrdinal().orElse(-1) == 0).findFirst().orElseThrow();
        var grant = JSON.readValue(store.start(task).taskInput(), RelationSearchDistribution.Work.class);
        var effect = new RelationSearchDistribution.WorkResult(grant,
                new com.taxonomy.dto.RelationSearchModel.Result(List.of(), List.of(), List.of(), 1, 1, 10), 1, "");
        var completed = ClusterRelationCoordinatorTest.completion(context, task);
        db.completions.commit(new PreparedAnalysisCompletion<>(completed, () -> store.persistRelationResult(task, effect))); store.accept(completed);
        var archive = exportedArchive(db);
        if (previousAcceptedRelations) {
            var retained = JSON.readValue(JSON.writeValueAsString(command.retainedAnalysis()), AnalysisResult.class);
            var decision = new com.taxonomy.dto.RelationSearchModel.Decision("CR", com.taxonomy.dto.RelationSearchModel.Outcome.VERIFIED,
                    "requirement", "requirement", com.taxonomy.dto.RelationSearchModel.Necessity.REQUIRED, "", "", "Earlier verified relation", "");
            var edge = new com.taxonomy.dto.RelationSearchModel.Edge(contribution, target, "REALIZES",
                    com.taxonomy.dto.RelationSearchModel.Direction.OUTGOING, decision);
            retained.setRelationSearchReport(new com.taxonomy.dto.RelationSearchReport(2, context.requirement().textSha256(), "prior-attempt",
                    List.of(source), new com.taxonomy.dto.RelationSearchModel.Result(List.of(edge), List.of(), List.of(), 7, 7, 70),
                    7, 10, 70, List.of(), "CALL_BUDGET"));
            var view = new com.taxonomy.dto.RequirementArchitectureView();
            view.setRelationSearchReport(retained.getRelationSearchReport()); view.setAnalysisStatus("SUCCESS");
            retained.setArchitectureView(view);
            command = command.withRetainedAnalysis(retained);
            var run = archive.run();
            archive = new Archive(new Run(run.sourceId(), run.principalScope(), run.context(), JSON.writeValueAsString(command), run.viewJson(),
                    run.resultJson(), run.relationPlanJson(), run.sourceState(), run.resumePolicy(), run.totalRoots(), run.completedRoots(),
                    run.revision(), run.createdAt(), run.updatedAt()), archive.work(), archive.inputs(), archive.events(), archive.calls(), archive.attempts());
        }
        return new ExtensionCapture(archive, command);
    }
    private static AnalyzeRequirementCommand remappedExtensionCommand(AnalyzeRequirementCommand captured, AnalysisResult retained) {
        var target = targetCommand();
        return new AnalyzeRequirementCommand(captured.businessText(), captured.includeArchitectureView(), captured.maxArchitectureNodes(),
                captured.provider(), target.username(), target.workspaceContext(), captured.provenance(), captured.analysisScope(),
                captured.providerBinding(), null, captured.analysisReasonLength()).withRetainedAnalysis(retained);
    }

    @Test void interruptedRelationRestoreRetainsCommittedSearchEvidenceAndRemapsItsReferences() throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("source-relations"); ClusterRelationCoordinatorTest.admit(source, original);
            for (var root : List.of(CP, IP)) source.store.accept(source.complete(source.task(root), result(root.code(), 45)));
            var preparation = (RelationAnalysisTask) source.sent.getLast();
            var plan = ClusterRelationCoordinatorTest.plan(original, preparation);
            var prepared = ClusterRelationCoordinatorTest.completion(original, preparation);
            source.completions.commit(new PreparedAnalysisCompletion<>(prepared, () -> source.store.persistRelationPlan(preparation, plan)));
            source.store.accept(prepared);
            var task = (RelationAnalysisTask) source.sent.get(3);
            var grant = JSON.readValue(source.store.start(task).taskInput(), RelationSearchDistribution.Work.class);
            var evidence = new RelationSearchDistribution.WorkResult(grant,
                    new com.taxonomy.dto.RelationSearchModel.Result(List.of(), List.of(), List.of(), 1, 1, 10), 1, "");
            var completed = ClusterRelationCoordinatorTest.completion(original, task);
            source.completions.commit(new PreparedAnalysisCompletion<>(completed, () -> source.store.persistRelationResult(task, evidence)));
            source.store.accept(completed);
            var archive = archive(source, original.operationId()); var restored = targetContext("restored-relations");
            var targetCommand = targetCommand();
            targetCommand = new AnalyzeRequirementCommand(targetCommand.businessText(), false, 20, "MOCK", "bob", targetCommand.workspaceContext(), null,
                    JSON.readValue(archive.run().commandJson(), AnalyzeRequirementCommand.class).analysisScope());
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand);
            var result = target.store.snapshot(restored).result();
            assertEquals(Map.of("CP", 45d, "IP", 45d), result.getRawScores());
            assertEquals(2, result.getRelationSearchReport().totalCalls());
            assertFalse(result.getRelationSearchReport().isSearchExhausted());
            assertFalse(result.getRelationSearchReport().result().unfinished().isEmpty());
            assertEquals("restored-relations:relations", target.store.relationPlan(restored).preparationId());
            assertTrue(target.store.relationPlan(restored).sourceResultIds().stream().allMatch(id -> id.startsWith("restored-relations:")));
            assertNoOperationalState(target);
        }
    }

    @Test void interruptedScoreOnlyPreparationRetainsCommittedProvisionalEvidenceWithoutAPlan() throws Exception {
        restoreScoreOnlyPreparation(false);
    }

    @Test void terminalScoreOnlyPreparationExportsAndRestoresWithoutAPlan() throws Exception {
        restoreScoreOnlyPreparation(true);
    }

    private static void restoreScoreOnlyPreparation(boolean settled) throws Exception {
        try (var source = new Database(); var target = new Database()) {
            var original = context("score-only-source"); ClusterRelationCoordinatorTest.admit(source, original);
            for (var root : List.of(CP, IP)) source.store.accept(source.complete(source.task(root), result(root.code(), 45)));
            var preparation = (RelationAnalysisTask) source.sent.getLast();
            var hypothesis = new com.taxonomy.dto.RelationHypothesisDto("CP", "Capability", "IP", "Information product",
                    "SUPPORTS", 0.72, "Committed score-only evidence");
            var completed = ClusterRelationCoordinatorTest.completion(original, preparation);
            source.completions.commit(new PreparedAnalysisCompletion<>(completed,
                    () -> source.store.persistScoreOnlyRelations(preparation, List.of(hypothesis))));
            if (settled) source.store.accept(completed);
            assertEquals(3, source.sent.size(), "Score-only preparation must not dispatch target work");
            var archive = exportedArchive(source);
            assertNull(archive.run().relationPlanJson());
            var archivedPreparation = archive.work().stream().filter(work -> work.taskType().equals(AnalysisTaskType.RELATION_ANALYSIS.name())).findFirst().orElseThrow();
            var restored = targetContext("score-only-restored");
            var originalTarget = targetCommand();
            var command = new AnalyzeRequirementCommand(originalTarget.businessText(), false, 20, "MOCK", "bob", originalTarget.workspaceContext(), null,
                    JSON.readValue(archive.run().commandJson(), AnalyzeRequirementCommand.class).analysisScope());
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, command);
            var snapshot = target.store.snapshot(restored);
            assertEquals(settled ? ClusterAnalysisState.COMPLETED : ClusterAnalysisState.CANCELLED, snapshot.state());
            assertEquals(Map.of("CP", 45d, "IP", 45d), snapshot.result().getRawScores());
            assertNull(snapshot.result().getRelationSearchReport());
            assertEquals(JSON.writeValueAsString(List.of(hypothesis)), JSON.writeValueAsString(snapshot.result().getProvisionalRelations()));
            assertEquals(!settled, snapshot.result().getWarnings().stream().anyMatch(warning -> warning.contains("RESTORE_INTERRUPTED")));
            String restoredPreparation = new TransactionTemplate(target.transactions).execute(status -> target.em.createQuery(
                            "select w.resultJson from ClusterAnalysisWork w where w.operationId=:id and w.taskType=:type", String.class)
                    .setParameter("id", restored.operationId()).setParameter("type", AnalysisTaskType.RELATION_ANALYSIS.name()).getSingleResult());
            assertEquals(archivedPreparation.resultJson(), restoredPreparation, "Score-only evidence has no operation references to remap");
            assertEquals(3, snapshot.tasks().size());
            assertNoOperationalState(target);
        }
    }

    static Archive archive(Database database, String id) {
        return new TransactionTemplate(database.transactions).execute(status -> {
            var r = database.em.find(ClusterAnalysisRun.class, id); var reference = new SourceRecordId("analysis.cluster-run", id);
            var run = new Run(reference, r.username, JSON.readValue(r.contextJson, AnalysisOperationContext.class), r.commandJson,
                    r.viewJson, r.resultJson, r.relationPlanJson, r.state.name(), ResumePolicy.REVIEW_REQUIRED,
                    r.totalRoots, r.completedRoots, r.revision, r.createdAt, r.updatedAt);
            var work = database.em.createQuery("select w from ClusterAnalysisWork w where w.operationId=:id order by w.ordinal", ClusterAnalysisWork.class).setParameter("id", id).getResultList().stream()
                    .map(w -> new Work(new SourceRecordId("analysis.cluster-work", w.id), reference, w.taskId, w.taskType, w.root, w.ordinal,
                            w.messageJson, w.inputJson, w.resultJson, w.failureReason, w.state, w.settled, w.attempts, w.startedAt, w.finishedAt, w.progressJson)).toList();
            var inputs = database.em.createQuery("select i from ClusterAnalysisInput i where i.operationId=:id", ClusterAnalysisInput.class).setParameter("id", id).getResultList().stream()
                    .map(i -> new Input(new SourceRecordId("analysis.cluster-input", i.id), reference, i.root, i.inputJson)).toList();
            var events = database.em.createQuery("select e from ClusterAnalysisEvent e where e.operationId=:id order by e.revision", ClusterAnalysisEvent.class).setParameter("id", id).getResultList().stream()
                    .map(e -> new Event(new SourceRecordId("analysis.cluster-event", e.id), reference, e.revision, e.eventJson)).toList();
            var calls = database.em.createQuery("select c from ClusterAnalysisCall c where c.operationId=:id order by c.callNumber", ClusterAnalysisCall.class).setParameter("id", id).getResultList().stream()
                    .map(c -> new Call(new SourceRecordId("analysis.cluster-call", c.id), reference, c.taskId, c.callNumber, c.executionId, c.logicalOrdinal, c.deliveryAttempt,
                            c.provider, c.node, c.state, c.startedAt, c.durationMillis, c.eventRevision, c.prompt, c.response, c.error, c.promptLength, c.responseLength)).toList();
            var attempts = database.em.createQuery("select a from ClusterAnalysisAttempt a where a.operationId=:id order by a.id", ClusterAnalysisAttempt.class).setParameter("id", id).getResultList().stream()
                    .map(a -> new Attempt(new SourceRecordId("analysis.cluster-attempt", a.id), reference, new SourceRecordId("analysis.cluster-call", a.callId), a.id, a.invocationId,
                            a.provider, a.source, a.retryIndex, a.startedAt, a.observationJson)).toList();
            return new Archive(run, work, inputs, events, calls, attempts);
        });
    }
    private static void assertTerminalProvenance(Archive original, Archive restored) {
        assertEquals(original.run().sourceState(), restored.run().sourceState());
        assertEquals(original.run().resultJson(), restored.run().resultJson());
        assertEquals(original.run().revision(), restored.run().revision(), "A terminal restore must not append an interruption event");
        assertEquals(original.work().stream().map(Work::failureReason).toList(),
                restored.work().stream().map(Work::failureReason).toList(), "Terminal task failure provenance must remain unchanged");
        assertEquals(original.work().stream().map(Work::sourceState).toList(), restored.work().stream().map(Work::sourceState).toList());
        assertEquals(original.work().stream().map(Work::finishedAt).toList(), restored.work().stream().map(Work::finishedAt).toList());
    }
    private static AnalysisOperationContext targetContext(String id) {
        return new AnalysisOperationContext(id, new AnalysisSourceAuthority("restored-repository", "restored-workspace", "restored-branch", "restored-commit"), RequirementReference.adHoc("requirement"), id);
    }
    private static void assertStoppedObservation(Database target, AnalysisOperationContext restored, int completedRoots) {
        try (var observer = new DurableClusterAnalysisObservation(target.store, new ClusterAnalysisSignals(), AnalysisEventPublisher.NONE, Runnable::run)) {
            var scope = targetCommand().workspaceContext();
            var view = observer.snapshot(restored.operationId(), "bob", scope).orElseThrow();
            assertEquals("CANCELLED", view.snapshot().status());
            assertEquals(completedRoots, view.cluster().completedRoots());
            assertTrue(view.cluster().tasks().stream().allMatch(task -> task.queued() == 0 && task.running() == 0));
            var recent = observer.recent("bob", scope, null, null);
            assertEquals(List.of(restored.operationId()), recent.stream().map(item -> item.snapshot().operationId()).toList());
            assertEquals(view.cluster(), recent.getFirst().cluster());
            assertEquals("PARTIAL", observer.result(restored.operationId(), "bob", scope).getStatus());
        }
    }
    @Test void restorePreservesExactProviderBindingAndRejectsUnavailableArtifactBeforeWrites() throws Exception {
        try (var source=new Database(); var target=new Database()) {
            var original=context("bound-source");var c=command("requirement");
            var binding=new com.taxonomy.extension.api.plugin.PluginInvocation(
                    new com.taxonomy.extension.api.plugin.PluginIdentity("example.provider","1.0.0","a".repeat(64)),"b".repeat(64));
            var bound=new AnalyzeRequirementCommand(c.businessText(),c.includeArchitectureView(),c.maxArchitectureNodes(),c.provider(),c.username(),c.workspaceContext(),c.provenance(),c.analysisScope(),binding);
            source.store.admit(original,bound,null,Map.of(CP,"{\"root\":\"CP\"}",IP,"{\"root\":\"IP\"}"));
            var archive=exportedArchive(source);var destination=targetContext("bound-restored");var t=targetCommand();
            var restoredCommand=new AnalyzeRequirementCommand(t.businessText(),t.includeArchitectureView(),t.maxArchitectureNodes(),t.provider(),t.username(),t.workspaceContext(),t.provenance(),t.analysisScope(),binding)
                    .withContinuationBinding(new com.taxonomy.analysis.recovery.AnalysisContinuationBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "c".repeat(64), "d".repeat(64), "e".repeat(64)));
            assertThrows(IllegalArgumentException.class,()->new ClusterAnalysisBackupRestorer(target.em,target.transactions,JSON).restore(archive,destination,restoredCommand));
            assertNull(new TransactionTemplate(target.transactions).execute(status->target.em.find(ClusterAnalysisRun.class,destination.operationId())));
            new ClusterAnalysisBackupRestorer(target.em,target.transactions,JSON,binding::equals).restore(archive,destination,restoredCommand);
            var stored=new TransactionTemplate(target.transactions).execute(status->target.em.find(ClusterAnalysisRun.class,destination.operationId()).commandJson);
            assertEquals(binding,JSON.readValue(stored,AnalyzeRequirementCommand.class).providerBinding());
            assertNull(JSON.readValue(stored,AnalyzeRequirementCommand.class).continuationBinding());
            assertNoOperationalState(target);
        }
    }

    private static Archive exportedArchive(Database database) throws Exception {
        var now = Instant.now();
        var request = new BackupRequest(BackupProfile.REPOSITORY_HISTORY, new BackupScope.Workspace("repo", "workspace"),
                new BackupTime.History(), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE);
        var authorized = new AuthorizedBackupRequest(request, PrincipalId.create(), "test-decision", now, EnumSet.allOf(BackupCapability.class));
        var snapshot = new SnapshotContext(BackupId.create(), authorized, now, now, 1,
                Map.of(new BackupRepositoryKey("repo", "workspace"), new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of())), Map.of());
        var datasets = new HashMap<String, String>();
        new ClusterAnalysisBackupExport(new PortableRows(database.bean.getDataSource()), "alice").write(snapshot, (path, input) -> {
            String contents = new String(input.readAllBytes(), StandardCharsets.UTF_8); datasets.put(path, contents);
            return new BackupEntry(path, contents.getBytes(StandardCharsets.UTF_8).length, RequirementReference.sha256(contents));
        }, (owner, authority, businessText, terminal) -> true);
        return new Archive(exportedRecords(datasets, "cluster-runs", Run.class).getFirst(),
                exportedRecords(datasets, "cluster-work", Work.class), exportedRecords(datasets, "cluster-inputs", Input.class),
                exportedRecords(datasets, "cluster-events", Event.class), exportedRecords(datasets, "cluster-calls", Call.class), exportedRecords(datasets, "cluster-attempts", Attempt.class));
    }
    private static <T> List<T> exportedRecords(Map<String, String> datasets, String name, Class<T> type) {
        return datasets.get("data/analysis/" + name + ".ndjson").lines().skip(1).map(line -> JSON.readValue(line, type)).toList();
    }
    private static AnalyzeRequirementCommand targetCommand() {
        return new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "bob", new WorkspaceContext("bob", "restored-workspace", "restored-branch", "restored-repository"), null, command("requirement").analysisScope());
    }
    private static AnalysisResult coveredResult(String code, int score) {
        var result = result(code, score);
        result.setAnalysisCoverage(AnalysisCoverage.derive(result.getTree(), result.getRawScores(), result.getScores(), Map.of()));
        return result;
    }
    private static void assertExportable(AnalysisResult result) {
        var saved = new SavedAnalysis(); saved.setVersion(result.getAnalysisCoverage() == null ? 2 : 3);
        saved.setScores(result.getScores()); saved.setRawScores(result.getRawScores());
        saved.setAnalysisCoverage(result.getAnalysisCoverage()); saved.setAnalysisStatus(result.getStatus());
        assertDoesNotThrow(saved::validateCoverageEvidence);
    }
    private static void assertRestoredClosureEmpty(Database target) {
        new TransactionTemplate(target.transactions).executeWithoutResult(status -> {
            for (String entity : List.of("ClusterAnalysisRun", "ClusterAnalysisWork", "ClusterAnalysisInput", "ClusterAnalysisEvent"))
                assertEquals(0L, target.em.createQuery("select count(r) from " + entity + " r", Long.class).getSingleResult());
        });
    }
    private static void assertNoOperationalState(Database target) {
        assertTrue(target.sent.isEmpty());
        new TransactionTemplate(target.transactions).executeWithoutResult(status -> {
            for (String entity : List.of("AnalysisDispatchIntent", "AnalysisTaskCompletionRecord"))
                assertEquals(0L, target.em.createQuery("select count(r) from " + entity + " r", Long.class).getSingleResult());
        });
    }
}
