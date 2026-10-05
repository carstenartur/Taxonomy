package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.backup.*;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
            assertEquals(Map.of("CP", 70), snapshot.result().getRawScores());
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
            assertEquals(Map.of("CP", 70), result.getRawScores()); assertEquals("PARTIAL", result.getStatus());
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
            assertEquals(Map.of("CP", 70, "IP", 30), result.getRawScores());
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
            assertEquals(Map.of("CP", 70), snapshot.result().getRawScores());
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
            source.store.start(source.task(IP));
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
            assertEquals(Map.of("CP", 55, "IP", 55), snapshot.result().getRawScores());
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
            assertEquals(Map.of("CP", 45, "IP", 45), result.getRawScores());
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
            assertEquals(Map.of("CP", 45, "IP", 45), snapshot.result().getRawScores());
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
                            w.messageJson, w.inputJson, w.resultJson, w.failureReason, w.state, w.settled, w.attempts, w.startedAt, w.finishedAt)).toList();
            var inputs = database.em.createQuery("select i from ClusterAnalysisInput i where i.operationId=:id", ClusterAnalysisInput.class).setParameter("id", id).getResultList().stream()
                    .map(i -> new Input(new SourceRecordId("analysis.cluster-input", i.id), reference, i.root, i.inputJson)).toList();
            var events = database.em.createQuery("select e from ClusterAnalysisEvent e where e.operationId=:id order by e.revision", ClusterAnalysisEvent.class).setParameter("id", id).getResultList().stream()
                    .map(e -> new Event(new SourceRecordId("analysis.cluster-event", e.id), reference, e.revision, e.eventJson)).toList();
            return new Archive(run, work, inputs, events);
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
                exportedRecords(datasets, "cluster-events", Event.class));
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
