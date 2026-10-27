package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dispatch.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ClusterAnalysisStoreTest {
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP"), IP = TaxonomyShardRoot.of("IP");

    @Test void dispatchesOneRootAtATimeInCatalogueOrderAfterDurableSettlement() {
        try (var db = new Database()) {
            var context = context("ordered-roots");
            var ci = TaxonomyShardRoot.of("CI");
            var roots = Map.of(IP, "{}", CP, "{}", ci, "{}");
            var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice",
                    command("requirement").workspaceContext(), null,
                    new AnalysisScope(Set.of("CI", "CP", "IP"), AnalysisMode.TAXONOMIES_ONLY));
            db.store.admit(context, command, null, roots);

            assertEquals(List.of(ci), db.sent.stream().map(AnalysisTaskMessage::routingRoot).toList());
            var first = db.task(ci);
            assertTrue(db.store.start(first).executable());
            var completion = db.complete(first, result("CI", 0));
            assertEquals(1, db.sent.size(), "Preparing a result must not release the next root");
            assertTrue(db.store.accept(completion));
            assertFalse(db.store.accept(completion), "Redelivery must not release another root");
            assertEquals(List.of(ci, CP), db.sent.stream().map(AnalysisTaskMessage::routingRoot).toList());

            var failed = new AnalysisResult(Map.of(), List.of()); failed.setStatus("ERROR");
            assertTrue(db.store.accept(db.complete(db.task(CP), failed)));
            assertEquals(List.of(ci, CP, IP), db.sent.stream().map(AnalysisTaskMessage::routingRoot).toList());
            assertTrue(db.store.accept(db.complete(db.task(IP), result("IP", 60))));
            assertEquals(ClusterAnalysisState.PARTIAL, db.store.snapshot(context).state());
            assertFalse(db.store.snapshot(context).result().getRawScores().containsKey("CP"));
        }
    }

    @Test void cancellationOrCooperativeStopDoesNotDispatchWaitingRoots() {
        for (boolean cancel : List.of(false, true)) try (var db = new Database()) {
            var context = context(cancel ? "ordered-cancel" : "ordered-stop"); db.admit(context);
            assertEquals(1, db.sent.size());
            var task = db.task(CP); db.store.start(task);
            if (cancel) {
                assertTrue(db.store.cancel(context));
                assertFalse(db.store.accept(db.complete(task, result("CP", 50))));
            } else {
                var completion = new AnalysisMessageFactory(context, Clock.systemUTC())
                        .completed(task, AnalysisTaskOutcome.STOPPED, 50d, 1, "TIME_LIMIT");
                db.completions.commit(new PreparedAnalysisCompletion<>(completion,
                        () -> db.store.persistResult(task, result("CP", 50))));
                assertTrue(db.store.accept(completion));
                assertFalse(db.store.accept(completion));
            }
            assertEquals(1, db.sent.size(), "Stopping an operation must retain undispatched roots");
            assertTrue(db.store.snapshot(context).state().terminal());
        }
    }

    @Test void stoppedOrCancelledWorkingViewsKeepTheirExactAdmittedSourceWithoutAContinuation() {
        String source = "  Frozen cooperative-stop input\nÜnicode: Österreich.  ";
        for (boolean cancel : List.of(false, true)) try (var db = new Database()) {
            var base = context(cancel ? "source-cancel" : "source-stop");
            var context = new AnalysisOperationContext(base.operationId(), base.authority(), RequirementReference.adHoc(source), base.correlationId());
            var original = command(source);
            var command = new AnalyzeRequirementCommand(source, true, 20, original.provider(), original.username(),
                    original.workspaceContext(), original.provenance(), original.analysisScope());
            var targets = new LinkedHashMap<TaxonomyShardRoot, String>();
            var mapper = new ObjectMapper();
            for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) targets.put(root, mapper.writeValueAsString(
                    DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code())));
            db.store.admit(context, command, null, Map.of(CP, targets.get(CP), IP, targets.get(IP)), targets);
            var task = db.task(CP); db.store.start(task);
            if (cancel) assertTrue(db.store.cancel(context));
            else {
                var completion = new AnalysisMessageFactory(context, Clock.systemUTC())
                        .completed(task, AnalysisTaskOutcome.STOPPED, 50d, 1, "TIME_LIMIT");
                db.completions.commit(new PreparedAnalysisCompletion<>(completion,
                        () -> db.store.persistResult(task, result("CP", 50))));
                assertTrue(db.store.accept(completion));
            }
            var restored = db.store.snapshot(context).result();
            assertNotNull(restored.getArchitectureView());
            assertEquals(source, restored.getArchitectureView().getOriginalRequirementText());
            assertTrue(db.store.snapshot(context).state().terminal());
            assertEquals(1, db.sent.size(), "No new provider task is dispatched after the cooperative stop");
        }
    }

    @Test void earlyDeliveryCannotStartALaterRootOrConsumeItsAttempt() {
        try (var db = new Database()) {
            var context = context("early-root"); db.admit(context);
            var future = (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(context, Clock.systemUTC())
                    .task(AnalysisTaskGraph.plan(context.operationId(), List.of(CP, IP), false).tasks().getLast());
            assertThrows(AnalysisTaskDeferredException.class, () -> db.store.start(future));
            assertEquals(0, db.store.snapshot(context).tasks().getLast().attempts());
            assertEquals("WAITING_PREDECESSOR", db.store.snapshot(context).tasks().getLast().state());
            assertTrue(db.store.accept(db.complete(db.task(CP), result("CP", 70))));
            assertTrue(db.store.start(future).executable());
            assertEquals(1, db.store.snapshot(context).tasks().getLast().attempts());
        }
    }

    @Test void earlyFailureNoticeCannotSettleAWaitingRootOrRecordACompletion() {
        try (var db = new Database()) {
            var context = context("early-failure"); db.admit(context);
            var future = db.expectedTask(IP);
            var failed = new AnalysisMessageFactory(context, Clock.systemUTC())
                    .completed(future, AnalysisTaskOutcome.FAILED, null, 0, null);
            assertThrows(AnalysisTaskDeferredException.class, () -> db.completions.commit(
                    new PreparedAnalysisCompletion<>(failed, () -> db.store.persistFailure(future, "BROKER_DEAD_LETTER"))));
            assertTrue(db.completions.find(future).isEmpty());
            assertEquals(0, db.store.snapshot(context).completedRoots());
            assertTrue(db.store.accept(db.complete(db.task(CP), result("CP", 70))));
            assertTrue(db.store.start(db.task(IP)).executable());
        }
    }

    @Test void previouslyAdmittedQueuedRootsRetainTheirExecutionPolicy() {
        try (var db = new Database()) {
            var context = context("legacy-queued"); db.admit(context);
            var later = db.expectedTask(IP);
            new TransactionTemplate(db.transactions).executeWithoutResult(status ->
                    emulatedLegacyQueue(db, context));
            assertTrue(db.store.start(db.task(CP)).executable());
            assertTrue(db.store.start(later).executable(), "A previously dispatched legacy root must not enter a broker retry loop");
        }
    }

    private static void emulatedLegacyQueue(Database db, AnalysisOperationContext context) {
        db.em.createQuery("update ClusterAnalysisWork w set w.state='QUEUED' where w.operationId=:run and w.root='IP'")
                .setParameter("run", context.operationId()).executeUpdate();
    }

    @Test void acceptsOnlyExactInputsAndDispatchesAfterCommit() {
        try (var db = new Database()) {
            var context = context("admission");
            db.admit(context);
            assertEquals(1, db.sent.size());
            assertEquals(2, db.store.snapshot(context).totalRoots());
            assertEquals(0, db.store.snapshot(context).completedRoots());
            assertEquals("QUEUED", db.store.snapshot(context).state().name());
            assertEquals("{\"root\":\"CP\"}", db.store.shard(context, CP));
            assertThrows(IllegalStateException.class, () -> db.store.shard(context, TaxonomyShardRoot.of("UA")));
            var foreign = new AnalysisOperationContext(context.operationId(),
                    new AnalysisSourceAuthority("other", "workspace", "draft", "source"), context.requirement(), context.correlationId());
            assertThrows(IllegalStateException.class, () -> db.store.snapshot(foreign));
            assertThrows(IllegalStateException.class, () -> db.store.admit(context, command("changed"), null,
                    Map.of(CP, "{}", IP, "{}")));
        }
    }

    @Test void legacyCommandWithoutLanguageCanBeReadmittedButCannotAcquireANewLanguage() {
        try (var db = new Database()) {
            var context = context("legacy-language-admission");
            db.admit(context);
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> {
                var run = db.em.find(ClusterAnalysisRun.class, context.operationId());
                var mapper = new ObjectMapper();
                var command = (tools.jackson.databind.node.ObjectNode) mapper.readTree(run.commandJson);
                command.remove("analysisLanguage");
                run.commandJson = mapper.writeValueAsString(command);
            });

            db.admit(context);

            assertEquals(1, db.sent.size(), "An idempotent legacy admission must not duplicate work");
            assertNull(db.store.start(db.task(CP)).command().analysisLanguage());
            assertThrows(IllegalStateException.class, () -> db.store.admit(context,
                    command("requirement").withAnalysisLanguage(AnalysisLanguage.ENGLISH), null,
                    Map.of(CP, "{\"root\":\"CP\"}", IP, "{\"root\":\"IP\"}")));
        }
    }

    @Test void rejectsEveryAdmissionIdentityMismatchWithoutDurableOrPublishedEffects() {
        try (var db = new Database()) {
            var context = context("rejected-admission");
            var workspace = command("requirement").workspaceContext();
            var progress = new ArrayList<AnalysisProgressEvent>();
            db.store.eventPublisher(progress::add);
            record InvalidInput(String name, String text, String username, WorkspaceContext workspace) { }
            var invalidInputs = List.of(
                    new InvalidInput("missing text", null, "alice", workspace),
                    new InvalidInput("changed text", "changed", "alice", workspace),
                    new InvalidInput("missing owner", "requirement", null, workspace),
                    new InvalidInput("blank owner", "requirement", " ", workspace),
                    new InvalidInput("different owner", "requirement", "bob", workspace),
                    new InvalidInput("different workspace owner", "requirement", "alice",
                            new WorkspaceContext("bob", "workspace", "draft", "repo")),
                    new InvalidInput("different repository", "requirement", "alice",
                            new WorkspaceContext("alice", "workspace", "draft", "other")),
                    new InvalidInput("different workspace", "requirement", "alice",
                            new WorkspaceContext("alice", "other", "draft", "repo")),
                    new InvalidInput("central scope substitution", "requirement", "alice",
                            new WorkspaceContext("alice", null, "draft", "repo")),
                    new InvalidInput("different branch", "requirement", "alice",
                            new WorkspaceContext("alice", "workspace", "other", "repo")),
                    new InvalidInput("missing branch", "requirement", "alice",
                            new WorkspaceContext("alice", "workspace", null, "repo")));
            for (var input : invalidInputs) {
                var command = new AnalyzeRequirementCommand(input.text(), false, 20, "MOCK", input.username(),
                        input.workspace(), null, new AnalysisScope(Set.of("CP", "IP"), AnalysisMode.TAXONOMIES_ONLY));
                assertThrows(IllegalStateException.class,
                        () -> db.store.admit(context, command, null, Map.of(CP, "{}", IP, "{}")), input.name());
            }
            assertTrue(db.sent.isEmpty());
            assertTrue(progress.isEmpty());
            for (String entity : List.of("ClusterAnalysisRun", "ClusterAnalysisWork", "ClusterAnalysisInput",
                    "ClusterAnalysisEvent", "AnalysisDispatchIntent")) {
                assertEquals(0L, db.em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult(),
                        "Rejected admission must not write " + entity);
            }
        }
    }

    @Test void exactAdmissionReplayPreservesEvidenceAndRejectsChangedSourceIdentity() {
        try (var db = new Database()) {
            var context = context("replayed-admission");
            var progress = new ArrayList<AnalysisProgressEvent>();
            db.store.eventPublisher(progress::add);
            db.admit(context);
            var before = db.store.snapshot(context);
            var events = db.store.events(context, 0, 100);

            db.admit(context);
            var changedSource = new AnalysisOperationContext(context.operationId(),
                    new AnalysisSourceAuthority("repo", "workspace", "draft", "other-source"),
                    context.requirement(), context.correlationId());
            assertThrows(IllegalStateException.class, () -> db.admit(changedSource));

            assertEquals(before, db.store.snapshot(context));
            assertEquals(events, db.store.events(context, 0, 100));
            assertEquals(1, db.sent.size());
            assertEquals(1, progress.size());
        }
    }

    @Test void legacyReverseAndDuplicateCompletionsAggregateExactlyOnce() {
        try (var db = new Database()) {
            var context = context("reverse"); db.admit(context);
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> emulatedLegacyQueue(db, context));
            var cp = db.task(CP); var ip = db.expectedTask(IP);
            assertEquals("requirement", db.store.start(cp).command().businessText());
            db.store.start(ip);
            assertEquals(2, db.store.snapshot(context).runningTasks());
            // Old concurrently admitted runs can already have durable effects in reverse order.
            var cpCompletion = db.complete(cp, result("CP", 50));
            var ipCompletion = db.complete(ip, result("IP", 70));
            assertEquals(0, db.store.snapshot(context).completedRoots(), "Only the coordinator settles progress");
            assertTrue(db.store.accept(ipCompletion));
            long revision = db.store.snapshot(context).revision();
            assertFalse(db.store.accept(ipCompletion));
            assertEquals(revision, db.store.snapshot(context).revision());
            assertEquals(1, db.store.snapshot(context).completedRoots());
            assertTrue(db.store.accept(cpCompletion));
            var snapshot = db.store.snapshot(context);
            assertEquals(ClusterAnalysisState.COMPLETED, snapshot.state());
            assertEquals(Map.of("CP", 50d, "IP", 70d), snapshot.result().getRawScores());
            assertEquals(List.of("CP", "IP"), snapshot.result().getTree().stream().map(TaxonomyNodeDto::getCode).toList());
            assertEquals("SUCCESS", snapshot.result().getStatus());
            assertEquals(snapshot.revision(), db.store.events(context, 0, 100).getLast().sequence());
        }
    }

    @Test void acceptsFractionalV3CompletionForPersistedLegacyV1Task() {
        try (var db = new Database()) {
            var context = context("legacy-v1-completion");
            db.admit(context);
            var currentTask = db.task(CP);
            db.store.start(currentTask);
            var taskEnvelope = currentTask.envelope();
            var legacy = new SubtaxonomyAnalysisTask(new AnalysisEnvelope(1,
                    AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK, taskEnvelope.operationId(),
                    taskEnvelope.taskId(), taskEnvelope.taskType(), taskEnvelope.authority(),
                    taskEnvelope.requirement(), taskEnvelope.roots(), 1, null, taskEnvelope.correlationId(),
                    taskEnvelope.createdAt(), taskEnvelope.deadline()), currentTask.root());
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> {
                var work = db.em.createQuery(
                                "select w from ClusterAnalysisWork w where w.operationId=:operation and w.taskId=:task",
                                ClusterAnalysisWork.class)
                        .setParameter("operation", context.operationId())
                        .setParameter("task", legacy.taskId().value()).getSingleResult();
                work.messageJson = new String(new com.taxonomy.analysis.dag.json.AnalysisMessageCodec().encode(legacy),
                        java.nio.charset.StandardCharsets.UTF_8);
            });
            var node = new TaxonomyNodeDto();
            node.setCode("CP"); node.setTaxonomyRoot("CP");
            var result = new AnalysisResult(Map.of("CP", 0.5d), List.of(node));
            result.setStatus("SUCCESS");
            var completion = new AnalysisMessageFactory(context, Clock.systemUTC())
                    .completed(legacy, AnalysisTaskOutcome.COMPLETED, 0.5d, 1, null);
            db.completions.commit(new PreparedAnalysisCompletion<>(completion,
                    () -> db.store.persistResult(legacy, result)));

            assertTrue(db.store.accept(completion));
            assertEquals(1, db.store.snapshot(context).completedRoots());
            assertTrue(db.store.accept(db.complete(db.task(IP), result("IP", 70))));
            assertEquals(Map.of("CP", 0.5d, "IP", 70d), db.store.snapshot(context).result().getRawScores());
        }
    }

    @Test void cancellationWinsOverLateProviderResultAndSurvivesNewStore() {
        try (var db = new Database()) {
            var context = context("cancel"); db.admit(context);
            var task = db.task(CP); db.store.start(task);
            assertTrue(db.store.cancel(context));
            long revision = db.store.snapshot(context).revision();
            assertFalse(db.store.cancel(context));
            var completion = db.complete(task, result("CP", 99));
            assertFalse(db.store.accept(completion));
            assertEquals(ClusterAnalysisState.CANCELLED, db.store.snapshot(context).state());
            assertEquals(revision, db.store.snapshot(context).revision());
            assertTrue(db.store.snapshot(context).result().getRawScores().isEmpty());
            assertFalse(db.store.start(db.expectedTask(IP)).executable());
            var restarted = new ClusterAnalysisStore(db.em, db.transactions, new ObjectMapper(), db.dispatch);
            assertEquals(ClusterAnalysisState.CANCELLED, restarted.snapshot(context).state());
        }
    }

    @Test void unknownOrForeignOrUnpersistedCompletionNeverCounts() {
        try (var db = new Database()) {
            var context = context("forged"); db.admit(context);
            var task = db.task(CP);
            var messages = new AnalysisMessageFactory(context, Clock.systemUTC());
            var completion = messages.completed(task, AnalysisTaskOutcome.COMPLETED, 50, 1, null);
            assertThrows(IllegalStateException.class, () -> db.store.accept(completion));
            assertEquals(0, db.store.snapshot(context).completedRoots());
            var foreign = new AnalysisOperationContext(context.operationId(),
                    new AnalysisSourceAuthority("repo", "workspace", "another-branch", "source"), context.requirement(), context.correlationId());
            var other = (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(foreign, Clock.systemUTC())
                    .task(AnalysisTaskGraph.plan(context.operationId(), List.of(CP), false).tasks().getFirst());
            assertThrows(IllegalStateException.class, () -> db.store.start(other));
        }
    }

    @Test void missingAndFailedRootsRemainExplicitRatherThanZeros() {
        try (var db = new Database()) {
            var context = context("partial"); db.admit(context);
            var partial = result("CP", 80); partial.setStatus("PARTIAL"); partial.setErrorMessage("provider unavailable");
            db.store.accept(db.complete(db.task(CP), partial));
            assertFalse(db.store.snapshot(context).state().terminal());
            var failed = new AnalysisResult(Map.of(), List.of()); failed.setStatus("ERROR");
            failed.setErrorMessage("evaluation failed");
            db.store.accept(db.complete(db.task(IP), failed));
            var result = db.store.snapshot(context).result();
            assertEquals(ClusterAnalysisState.PARTIAL, db.store.snapshot(context).state());
            assertEquals(Map.of("CP", 80d), result.getRawScores());
            assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("IP")));
            assertFalse(result.getRawScores().containsKey("IP"));
        }
    }

    @Test void aggregationPreservesIndependentAssessmentAndDescendantCoverageAcrossRoots() {
        try (var db = new Database()) {
            var context = context("coverage-aggregation"); db.admit(context);
            var cp = result("CP", 80);
            var assessed = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.RELEVANT, 80, 80,
                    AnalysisCoverage.Descendants.PARTIAL, null);
            var unresolved = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                    AnalysisCoverage.Descendants.UNASSESSED, "INTERRUPTED:TIMEOUT");
            cp.setAnalysisCoverage(new AnalysisCoverage(Map.of("CP", assessed, "fixture-child", unresolved), 1, 1, 1));
            cp.setStatus("PARTIAL");
            cp.setErrorMessage("TIMEOUT");
            var ip = result("IP", 0);
            var excluded = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.NOT_RELEVANT, 0, 0,
                    AnalysisCoverage.Descendants.COMPLETE, null);
            ip.setAnalysisCoverage(new AnalysisCoverage(Map.of("IP", excluded), 1, 0, 0));

            db.store.accept(db.complete(db.task(CP), cp));
            db.store.accept(db.complete(db.task(IP), ip));

            var coverage = db.store.snapshot(context).result().getAnalysisCoverage();
            assertNotNull(coverage, "Cluster aggregation must retain the workers' assessment evidence");
            assertEquals(Map.of("CP", assessed, "fixture-child", unresolved, "IP", excluded), coverage.nodes());
            assertEquals(2, coverage.assessedNodes());
            assertEquals(1, coverage.unknownNodes());
            assertEquals(1, coverage.failedOrBlockedNodes());
            assertTrue(coverage.hasOpenEvaluations());
            var restarted = new ClusterAnalysisStore(db.em, db.transactions, new ObjectMapper(), db.dispatch);
            assertEquals(coverage, restarted.snapshot(context).result().getAnalysisCoverage());
        }
    }

    @Test void cancellationRetainsCoverageOfCommittedRootEvidence() {
        try (var db = new Database()) {
            var context = context("coverage-cancellation"); db.admitFrozen(context);
            var cp = result("CP", 75);
            var coverage = AnalysisCoverage.derive(cp.getTree(), cp.getRawScores(), cp.getScores(), Map.of());
            cp.setAnalysisCoverage(coverage);
            db.store.accept(db.complete(db.task(CP), cp));
            db.store.cancel(context);

            var combined = db.store.snapshot(context).result().getAnalysisCoverage();
            assertEquals(coverage.nodes().get("CP"), combined.nodes().get("CP"));
            assertNotNull(combined.nodes().get("IP"), "The unexecuted admitted root must remain visible");
            assertNotNull(combined.nodes().get("fixture-ip-child"), "Frozen descendants must remain visible");
            assertEquals(AnalysisCoverage.State.UNKNOWN, combined.nodes().get("IP").state());
            assertEquals(AnalysisCoverage.State.UNKNOWN, combined.nodes().get("fixture-ip-child").state());
            assertNull(combined.nodes().get("IP").score());
            assertTrue(combined.hasOpenEvaluations());
            assertEquals(2, combined.unknownNodes());
            assertFalse(db.store.snapshot(context).result().getRawScores().containsKey("IP"));
        }
    }

    @Test void failedRootWithoutEvidenceRemainsOpenBesideCompleteCoverage() {
        try (var db = new Database()) {
            var context = context("coverage-failure"); db.admitFrozen(context);
            var cp = result("CP", 75);
            cp.setAnalysisCoverage(AnalysisCoverage.derive(cp.getTree(), cp.getRawScores(), cp.getScores(), Map.of()));
            db.store.accept(db.complete(db.task(CP), cp));
            var failed = new AnalysisResult(Map.of(), List.of()); failed.setStatus("ERROR");
            db.store.accept(db.complete(db.task(IP), failed));
            var combined = db.store.snapshot(context).result();
            assertEquals("PARTIAL", combined.getStatus());
            assertTrue(combined.getAnalysisCoverage().hasOpenEvaluations());
            assertEquals(AnalysisCoverage.State.UNKNOWN, combined.getAnalysisCoverage().nodes().get("IP").state());
            assertFalse(combined.getRawScores().containsKey("IP"));
            assertExportable(combined);
        }
    }

    @Test void mixedLegacyAndCurrentResultsRemainExportableWithoutInventingCoverage() {
        try (var db = new Database()) {
            var context = context("coverage-mixed"); db.admit(context);
            var cp = result("CP", 75);
            var nodes = new LinkedHashMap<>(AnalysisCoverage.derive(cp.getTree(), cp.getRawScores(), cp.getScores(), Map.of()).nodes());
            nodes.put("fixture-open", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                    AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:fixture"));
            cp.setAnalysisCoverage(new AnalysisCoverage(nodes, 1, 1, 1));
            cp.setStatus("PARTIAL");
            db.store.accept(db.complete(db.task(CP), cp));
            db.store.accept(db.complete(db.task(IP), result("IP", 30)));
            var combined = db.store.snapshot(context).result();
            assertExportable(combined);
            assertNull(combined.getAnalysisCoverage(), "Legacy evidence cannot be promoted to complete version 3 coverage");
            assertEquals("PARTIAL", combined.getStatus());
            assertEquals(Map.of("CP", 75d, "IP", 30d), combined.getRawScores());
        }
    }

    @Test void allLegacyResultsRetainAbsentCoverage() {
        try (var db = new Database()) {
            var context = context("coverage-legacy"); db.admit(context);
            db.store.accept(db.complete(db.task(CP), result("CP", 75)));
            db.store.accept(db.complete(db.task(IP), result("IP", 30)));
            var combined = db.store.snapshot(context).result();
            assertNull(combined.getAnalysisCoverage());
            assertExportable(combined);
        }
    }

    @Test void duplicateUnknownIdentitiesAcrossRootCoverageAreRejected() {
        try (var db = new Database()) {
            var context = context("coverage-duplicate"); db.admit(context);
            var unknown = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                    AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:fixture");
            var cp = result("CP", 75); var ip = result("IP", 30);
            for (var result : List.of(cp, ip)) {
                var nodes = new LinkedHashMap<>(AnalysisCoverage.derive(result.getTree(), result.getRawScores(), result.getScores(), Map.of()).nodes());
                nodes.put("fixture-duplicate", unknown);
                result.setAnalysisCoverage(new AnalysisCoverage(nodes, 1, 1, 1));
            }
            db.store.accept(db.complete(db.task(CP), cp));
            var completion = db.complete(db.task(IP), ip);
            assertThrows(IllegalStateException.class, () -> db.store.accept(completion));
            assertFalse(db.store.snapshot(context).state().terminal());
        }
    }

    private static void assertExportable(AnalysisResult result) {
        var saved = new SavedAnalysis();
        saved.setVersion(result.getAnalysisCoverage() == null ? 2 : 3);
        saved.setScores(result.getScores()); saved.setRawScores(result.getRawScores());
        saved.setAnalysisCoverage(result.getAnalysisCoverage()); saved.setAnalysisStatus(result.getStatus());
        assertDoesNotThrow(saved::validateCoverageEvidence);
    }

    @Test void concurrentDeliveriesAndResultRollbackPreserveOneDurableEffect() throws Exception {
        try (var db = new Database(); var pool = Executors.newFixedThreadPool(2)) {
            var context = context("duplicate"); db.admit(context);
            var task = db.task(CP); db.store.start(task);
            var completion = new AnalysisMessageFactory(context, Clock.systemUTC())
                    .completed(task, AnalysisTaskOutcome.COMPLETED, 40, 1, null);
            var result = result("CP", 40);
            assertThrows(IllegalStateException.class, () -> db.completions.commit(new PreparedAnalysisCompletion<>(completion, () -> {
                db.store.persistResult(task, result); throw new IllegalStateException("crash");
            })));
            assertThrows(IllegalStateException.class, () -> db.store.accept(completion));
            var start = new CountDownLatch(1);
            var prepared = new PreparedAnalysisCompletion<>(completion, () -> db.store.persistResult(task, result));
            Callable<AnalysisCompletionMessage> call = () -> { start.await(); return db.completions.commit(prepared); };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertTrue(db.store.accept(completion)); assertFalse(db.store.accept(completion));
            assertEquals(1, db.store.snapshot(context).completedRoots());
        }
    }

    static AnalysisOperationContext context(String id) {
        return new AnalysisOperationContext(id, new AnalysisSourceAuthority("repo", "workspace", "draft", "source"),
                RequirementReference.adHoc("requirement"), id);
    }
    static AnalyzeRequirementCommand command(String text) {
        return new AnalyzeRequirementCommand(text, false, 20, "MOCK", "alice",
                new WorkspaceContext("alice", "workspace", "draft", "repo"), null,
                new AnalysisScope(Set.of("CP", "IP"), AnalysisMode.TAXONOMIES_ONLY));
    }
    static AnalysisResult result(String code, int score) {
        var node = new TaxonomyNodeDto(); node.setCode(code); node.setTaxonomyRoot(code);
        var result = new AnalysisResult(Map.of(code, score), List.of(node));
        result.setStatus("SUCCESS"); result.setProvider("MOCK"); return result;
    }

    static final class Database implements AutoCloseable {
        private final String ownedDatabaseUrl;
        final LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        final jakarta.persistence.EntityManager em;
        final JpaTransactionManager transactions;
        final List<AnalysisTaskMessage> sent = new CopyOnWriteArrayList<>();
        final AnalysisDispatchService dispatch;
        final ClusterAnalysisStore store;
        final JpaAnalysisTaskCompletionStore completions;
        Database() {
            this(sql -> sql);
        }
        Database(org.hibernate.resource.jdbc.spi.StatementInspector inspector) {
            this("jdbc:hsqldb:mem:cluster-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "create-drop", inspector, true);
        }
        Database(String url, String schema, org.hibernate.resource.jdbc.spi.StatementInspector inspector) {
            this(url, schema, inspector, false);
        }
        private Database(String url, String schema, org.hibernate.resource.jdbc.spi.StatementInspector inspector,
                         boolean ownsDatabase) {
            ownedDatabaseUrl = ownsDatabase ? url : null;
            bean.setDataSource(new DriverManagerDataSource(url, "sa", ""));
            bean.setManagedTypes(PersistenceManagedTypes.of(ClusterAnalysisRun.class.getName(), ClusterAnalysisWork.class.getName(),
                    ClusterAnalysisInput.class.getName(), ClusterAnalysisEvent.class.getName(), ClusterAnalysisCall.class.getName(), ClusterAnalysisAttempt.class.getName(),
                    AnalysisDispatchIntent.class.getName(), AnalysisTaskCompletionRecord.class.getName(),
                    com.taxonomy.analysis.recovery.AnalysisContinuationRun.class.getName(), com.taxonomy.analysis.recovery.AnalysisQuestionCheckpoint.class.getName()));
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", schema, "hibernate.search.enabled", "false",
                    "hibernate.session_factory.statement_inspector", inspector));
            bean.afterPropertiesSet();
            em = SharedEntityManagerCreator.createSharedEntityManager(bean.getObject());
            transactions = new JpaTransactionManager(bean.getObject());
            dispatch = new AnalysisDispatchService(new AnalysisDispatchStore(em, transactions), task -> {
                var committed = new TransactionTemplate(transactions);
                committed.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertNotNull(committed.execute(status -> em.find(ClusterAnalysisRun.class, task.envelope().operationId())),
                        "Publication must observe committed operation authority");
                sent.add(task);
            }, 20, 100);
            store = new ClusterAnalysisStore(em, transactions, new ObjectMapper(), dispatch);
            completions = new JpaAnalysisTaskCompletionStore(em, transactions);
        }
        void admit(AnalysisOperationContext context) {
            store.admit(context, command("requirement"), null, Map.of(CP, "{\"root\":\"CP\"}", IP, "{\"root\":\"IP\"}"));
        }
        void admitFrozen(AnalysisOperationContext context) {
            var source = DurableClusterAnalysisExecutionTest.source(context);
            var cp = DurableClusterAnalysisExecutionTest.root(source, "CP");
            var ip = DurableClusterAnalysisExecutionTest.root(source, "IP");
            var child = new com.taxonomy.catalog.model.TaxonomyNode();
            child.setCode("fixture-ip-child"); child.setTaxonomyRoot("IP"); child.setParentCode("IP");
            child.setLevel(1); child.setNameEn("Unexecuted fixture child");
            var nodes = new ArrayList<>(ip.nodes());
            nodes.add(com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.Node.capture(child, ip.nodes().getFirst().metadata(), false));
            ip = new com.taxonomy.catalog.snapshot.RootCatalogueSnapshot(ip.schemaVersion(), source, "IP", nodes,
                    ip.overlayMetadata(), ip.catalogueProvenance());
            var mapper = new ObjectMapper();
            store.admit(context, command("requirement"), null,
                    Map.of(CP, mapper.writeValueAsString(cp), IP, mapper.writeValueAsString(ip)));
        }
        SubtaxonomyAnalysisTask task(TaxonomyShardRoot root) {
            return sent.stream().filter(t -> t.routingRoot().equals(root)).map(SubtaxonomyAnalysisTask.class::cast).findFirst().orElseThrow();
        }
        SubtaxonomyAnalysisTask task(AnalysisOperationContext context, TaxonomyShardRoot root) {
            return sent.stream().filter(t -> t.envelope().operationId().equals(context.operationId()) && t.routingRoot().equals(root))
                    .map(SubtaxonomyAnalysisTask.class::cast).findFirst().orElseThrow();
        }
        /** Reads the admitted contract for deliberate early/legacy-delivery tests; it does not publish it. */
        SubtaxonomyAnalysisTask expectedTask(TaxonomyShardRoot root) {
            String message = em.createQuery("select w.messageJson from ClusterAnalysisWork w where w.root=:root", String.class)
                    .setParameter("root", root.code()).getSingleResult();
            return (SubtaxonomyAnalysisTask) new com.taxonomy.analysis.dag.json.AnalysisMessageCodec()
                    .decode(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        AnalysisCompletionMessage complete(SubtaxonomyAnalysisTask task, AnalysisResult result) {
            var e = task.envelope();
            var factory = new AnalysisMessageFactory(new AnalysisOperationContext(e.operationId(), e.authority(), e.requirement(), e.correlationId()), Clock.systemUTC());
            var outcome = "SUCCESS".equals(result.getStatus()) ? AnalysisTaskOutcome.COMPLETED : AnalysisTaskOutcome.PARTIAL;
            var completion = factory.completed(task, outcome, result.getRawScores().get(task.root().code()), result.getRawScores().size(), null);
            return completions.commit(new PreparedAnalysisCompletion<>(completion, () -> store.persistResult(task, result)));
        }
        @Override public void close() {
            try {
                bean.destroy();
            } finally {
                // Closing the EMF leaves HSQL memory databases registered. Caller-supplied
                // URLs belong to restart tests; only release this fixture's private UUID DB.
                if (ownedDatabaseUrl != null) {
                    try (var connection = DriverManager.getConnection(ownedDatabaseUrl, "sa", "");
                         var statement = connection.createStatement()) {
                        statement.execute("SHUTDOWN");
                    } catch (SQLException failure) {
                        throw new AssertionError("Could not release the owned test database", failure);
                    }
                }
            }
        }
    }
}
