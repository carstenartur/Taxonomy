package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

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
            var archive = archive(source, original.operationId());
            var restored = targetContext("restored");
            new ClusterAnalysisBackupRestorer(target.em, target.transactions, JSON).restore(archive, restored, targetCommand());
            var snapshot = target.store.snapshot(restored);
            assertEquals(ClusterAnalysisState.CANCELLED, snapshot.state());
            assertEquals(Map.of("CP", 70), snapshot.result().getRawScores());
            assertEquals("PARTIAL", snapshot.result().getStatus());
            assertTrue(snapshot.result().getWarnings().stream().anyMatch(w -> w.contains("RESTORE_INTERRUPTED")));
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
    private static void assertNoOperationalState(Database target) {
        assertTrue(target.sent.isEmpty());
        new TransactionTemplate(target.transactions).executeWithoutResult(status -> {
            for (String entity : List.of("AnalysisDispatchIntent", "AnalysisTaskCompletionRecord"))
                assertEquals(0L, target.em.createQuery("select count(r) from " + entity + " r", Long.class).getSingleResult());
        });
    }
}
