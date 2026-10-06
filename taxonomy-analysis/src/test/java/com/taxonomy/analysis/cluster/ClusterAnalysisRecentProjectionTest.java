package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.taxonomy.analysis.cluster.ClusterAnalysisStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HSQL/Hibernate query budget and compatibility contract for the recovery list. */
class ClusterAnalysisRecentProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WorkspaceContext SCOPE = command("requirement").workspaceContext();

    @Test void fiftyRecentViewsUseTwoProjectedQueriesWithoutLoadingRunOrWorkEntities() {
        var statements = new ArrayList<String>();
        try (var db = new Database(sql -> { statements.add(sql); return sql; });
             var observer = observer(db)) {
            for (int index = 1; index <= 50; index++) {
                persist(db, "run-%02d".formatted(index), index, ClusterAnalysisState.COMPLETED,
                        SCOPE, RequirementReference.adHoc("requirement"), "{\"rawScores\":{\"CP\":75}}");
            }
            persist(db, "tie-b", 100, ClusterAnalysisState.COMPLETED, SCOPE,
                    RequirementReference.adHoc("requirement"), null);
            persist(db, "tie-a", 100, ClusterAnalysisState.COMPLETED, SCOPE,
                    RequirementReference.adHoc("requirement"), null);
            var statistics = db.bean.getObject().unwrap(SessionFactory.class).getStatistics();
            statistics.setStatisticsEnabled(true);
            statistics.clear();
            statements.clear();

            var recent = observer.recent("alice", SCOPE, null, null);

            assertEquals(50, recent.size());
            assertEquals(List.of("tie-a", "tie-b", "run-50"), ids(recent.subList(0, 3)));
            assertEquals("run-03", recent.getLast().snapshot().operationId());
            assertEquals(2, statistics.getPrepareStatementCount(),
                    "A full recovery page must not read each operation and its tasks separately");
            assertEquals(0, statistics.getEntityLoadCount(), "The recovery list needs projections, not durable payload entities");
            assertEquals(2, statements.size());
            var runSql = statements.getFirst().toLowerCase(java.util.Locale.ROOT);
            assertTrue(runSql.contains("analysis_cluster_run"));
            assertFalse(runSql.contains("command_json"));
            assertFalse(runSql.contains("view_json"));
            assertFalse(runSql.contains("relation_plan_json"));
            var workSql = statements.getLast().toLowerCase(java.util.Locale.ROOT);
            assertTrue(workSql.contains("analysis_cluster_work"));
            assertFalse(workSql.contains("message_json"));
            assertFalse(workSql.contains("input_json"));
            assertFalse(workSql.contains("result_json"));

            statistics.clear();
            assertEquals(1, observer.recent("alice", SCOPE, null, null).stream()
                    .filter(view -> view.snapshot().operationId().equals("tie-a")).count());
            assertEquals(2, statistics.getPrepareStatementCount(), "The budget applies on repeated reads too");
        }
    }

    @Test void recentPreservesExactOwnerRepositoryWorkspaceBranchAndRequirementFilters() {
        try (var db = new Database(); var observer = observer(db)) {
            persist(db, "project-one-requirement-two", 10, ClusterAnalysisState.PARTIAL, SCOPE,
                    RequirementReference.of(1L, 2L, "version-one", "requirement"), null);
            persist(db, "project-one-requirement-three", 20, ClusterAnalysisState.PARTIAL, SCOPE,
                    RequirementReference.of(1L, 3L, "version-two", "requirement"), null);
            persist(db, "project-two-requirement-two", 30, ClusterAnalysisState.PARTIAL, SCOPE,
                    RequirementReference.of(2L, 2L, "version-three", "requirement"), null);
            persist(db, "ad-hoc", 40, ClusterAnalysisState.PARTIAL, SCOPE,
                    RequirementReference.adHoc("requirement"), null);
            var foreignScopes = List.of(
                    new WorkspaceContext("bob", "workspace", "draft", "repo"),
                    new WorkspaceContext("alice", "workspace", "draft", "other-tenant-repository"),
                    new WorkspaceContext("alice", "other-workspace", "draft", "repo"),
                    new WorkspaceContext("alice", null, "draft", "repo"),
                    new WorkspaceContext("alice", "workspace", "other-branch", "repo"));
            for (int index = 0; index < foreignScopes.size(); index++) {
                persist(db, "foreign-" + index, 100, ClusterAnalysisState.PARTIAL, foreignScopes.get(index),
                        RequirementReference.of(1L, 2L, "foreign-version", "requirement"), null);
            }
            assertEquals(List.of("ad-hoc", "project-two-requirement-two", "project-one-requirement-three",
                    "project-one-requirement-two"), ids(observer.recent("alice", SCOPE, null, null)));
            assertEquals(List.of("project-one-requirement-three", "project-one-requirement-two"),
                    ids(observer.recent("alice", SCOPE, 1L, null)));
            assertEquals(List.of("project-two-requirement-two", "project-one-requirement-two"),
                    ids(observer.recent("alice", SCOPE, null, 2L)));
            var exact = observer.recent("alice", SCOPE, 1L, 2L);
            assertEquals(List.of("project-one-requirement-two"), ids(exact));
            assertEquals(new AnalysisProvenance(1L, 2L, "version-one", null), exact.getFirst().snapshot().provenance());
            assertNull(observer.recent("alice", SCOPE, null, null).getFirst().snapshot().provenance());
            assertTrue(observer.recent("bob", SCOPE, null, null).isEmpty());
            assertTrue(observer.recent("alice", SCOPE, 9L, null).isEmpty());
            for (int index = 0; index < foreignScopes.size(); index++) {
                var scope = foreignScopes.get(index);
                assertEquals(List.of("foreign-" + index), ids(observer.recent(scope.username(), scope, 1L, 2L)));
            }
            var statistics = db.bean.getObject().unwrap(SessionFactory.class).getStatistics();
            statistics.setStatisticsEnabled(true);
            statistics.clear();
            assertTrue(observer.recent("alice", SCOPE, 9L, 9L).isEmpty());
            assertEquals(1, statistics.getPrepareStatementCount(), "No task query is necessary when there are no matching runs");
        }
    }

    @Test void projectedViewsRetainEveryStateTaskSummaryRevisionAndTimeMeaning() {
        try (var db = new Database(); var observer = observer(db)) {
            for (var state : ClusterAnalysisState.values()) {
                persist(db, state.name(), 1000, state, SCOPE,
                        RequirementReference.of(7L, 8L, "requirement-version", "requirement"),
                        "{\"rawScores\":{\"IP\":125,\" CP \":-5},\"tree\":[],\"warnings\":[\"large optional evidence\"]}");
            }
            var recent = observer.recent("alice", SCOPE, null, null);
            assertEquals(7, recent.size());
            for (var view : recent) {
                var baseline = observer.snapshot(view.snapshot().operationId(), "alice", SCOPE).orElseThrow();
                assertEquivalent(baseline, view);
                var snapshot = view.snapshot();
                assertEquals(17, snapshot.sequence());
                assertEquals(1000, snapshot.startedAt());
                assertEquals(1100, snapshot.lastActivityAt());
                assertEquals(1010L, snapshot.executionStartedAt());
                assertEquals(10, snapshot.queueWaitMillis());
                assertEquals(Map.of("CP", 0, "IP", 100), snapshot.rawScores());
                assertEquals(2, snapshot.evaluatedNodes());
                assertEquals(new ClusterAnalysisView.ObservationScope("workspace", "repo", "draft", "source"), view.scope());
                assertEquals(List.of(
                        new ClusterAnalysisView.TaskCounts(AnalysisTaskType.SUBTAXONOMY_ANALYSIS, TaxonomyShardRoot.of("CP"), 1, 1, 1, 2),
                        new ClusterAnalysisView.TaskCounts(AnalysisTaskType.RELATION_ANALYSIS, null, 0, 0, 0, 0)), view.cluster().tasks());
            }
            var phases = new LinkedHashMap<String, String>();
            recent.forEach(view -> phases.put(view.snapshot().operationId(), view.snapshot().phase()));
            assertEquals(Map.of("QUEUED", "QUEUED", "RUNNING", "SCORING", "RELATIONS", "RELATIONS",
                    "FINALIZING", "FINALIZING", "COMPLETED", "FINISHED", "PARTIAL", "FINISHED", "CANCELLED", "FINISHED"), phases);
        }
    }

    @Test void recentScorePreviewPreservesLegacyAliasPrecedenceCanonicalOrderAndTruncation() {
        try (var db = new Database(); var observer = observer(db)) {
            var documents = List.of(
                    "{\"scores\":{\"CP\":70}}",
                    "{\"scores\":{\"CP\":70},\"rawScores\":{\"IP\":80}}",
                    "{\"rawScores\":{\"IP\":80},\"scores\":{\"CP\":70}}",
                    "{\"rawScores\":{},\"scores\":{\"CP\":70}}",
                    "{\"scores\":{\"CP\":70},\"rawScores\":{}}",
                    "{\"rawScores\":null,\"scores\":{\"CP\":70}}",
                    "{\"scores\":{\"CP\":70},\"rawScores\":null}");
            for (int index = 0; index < documents.size(); index++) {
                persist(db, "legacy-" + index, index, ClusterAnalysisState.COMPLETED, SCOPE,
                        RequirementReference.adHoc("requirement"), documents.get(index));
            }
            var scores = new LinkedHashMap<String, Integer>();
            for (int index = 8192; index >= 0; index--) scores.put("node-%05d".formatted(index), index % 101);
            persist(db, "large-preview", 100, ClusterAnalysisState.COMPLETED, SCOPE,
                    RequirementReference.adHoc("requirement"), JSON.writeValueAsString(Map.of("rawScores", scores,
                            "warnings", List.of("optional result evidence ".repeat(1000)), "tree", List.of())));
            for (var view : observer.recent("alice", SCOPE, null, null)) {
                assertEquivalent(observer.snapshot(view.snapshot().operationId(), "alice", SCOPE).orElseThrow(), view);
            }
            var large = observer.recent("alice", SCOPE, null, null).getFirst().snapshot();
            assertEquals(8193, large.evaluatedNodes());
            assertEquals(8192, large.rawScores().size());
            assertTrue(large.scoresTruncated());
            assertTrue(large.rawScores().containsKey("node-00000"));
            assertFalse(large.rawScores().containsKey("node-08192"));
            var retained = db.store.recentSnapshots("alice", SCOPE, null, null).getFirst();
            assertNull(retained.snapshot().result(), "Recent must not retain the full score map or result graph");
            assertEquals(8193, retained.scorePreview().evaluatedNodes());
            assertEquals(8192, retained.scorePreview().rawScores().size());
            assertTrue(retained.scorePreview().scoresTruncated());
        }
    }

    @ParameterizedTest @MethodSource("corruptedContexts")
    void inconsistentOperationContextCannotExposeAnotherRunsAuthorityThroughRecent(AnalysisOperationContext corrupted) {
        try (var db = new Database(); var observer = observer(db)) {
            var requirement = RequirementReference.of(7L, 8L, "version", "requirement");
            persist(db, "damaged", 1000, ClusterAnalysisState.COMPLETED, SCOPE, requirement, null);
            new TransactionTemplate(db.transactions).executeWithoutResult(status ->
                    db.em.createQuery("update ClusterAnalysisRun r set r.contextJson=:context where r.id='damaged'")
                            .setParameter("context", JSON.writeValueAsString(corrupted)).executeUpdate());
            assertThrows(IllegalStateException.class, () -> observer.recent("alice", SCOPE, 7L, 8L), corrupted.toString());
        }
    }

    @Test void nullResultRetainsEmptyPreviewButCannotBypassStrictTrailingTokenValidation() {
        try (var db = new Database(); var observer = observer(db)) {
            persist(db, "null-result", 1000, ClusterAnalysisState.COMPLETED, SCOPE,
                    RequirementReference.adHoc("requirement"), "null");
            var view = observer.recent("alice", SCOPE, null, null).getFirst();
            assertEquals(0, view.snapshot().evaluatedNodes());
            assertTrue(view.snapshot().rawScores().isEmpty());
            assertFalse(view.snapshot().scoresTruncated());
            new TransactionTemplate(db.transactions).executeWithoutResult(status ->
                    db.em.createQuery("update ClusterAnalysisRun r set r.resultJson='null {}' where r.id='null-result'")
                            .executeUpdate());
            assertThrows(IllegalArgumentException.class, () -> observer.recent("alice", SCOPE, null, null));
        }
    }

    private static List<AnalysisOperationContext> corruptedContexts() {
        var original = context("damaged");
        var requirement = RequirementReference.of(7L, 8L, "version", "requirement");
        return List.of(
                new AnalysisOperationContext("missing-run", original.authority(), requirement, "damaged"),
                new AnalysisOperationContext("damaged", new AnalysisSourceAuthority("foreign-repository", "workspace", "draft", "source"), requirement, "damaged"),
                new AnalysisOperationContext("damaged", new AnalysisSourceAuthority("repo", "foreign-workspace", "draft", "source"), requirement, "damaged"),
                new AnalysisOperationContext("damaged", new AnalysisSourceAuthority("repo", "workspace", "foreign-branch", "source"), requirement, "damaged"),
                new AnalysisOperationContext("damaged", original.authority(), RequirementReference.of(9L, 8L, "version", "requirement"), "damaged"),
                new AnalysisOperationContext("damaged", original.authority(), RequirementReference.of(7L, 9L, "version", "requirement"), "damaged"));
    }

    private static DurableClusterAnalysisObservation observer(Database db) {
        return new DurableClusterAnalysisObservation(db.store, new ClusterAnalysisSignals(), AnalysisEventPublisher.NONE, Runnable::run);
    }

    private static List<String> ids(List<ClusterAnalysisView> views) {
        return views.stream().map(view -> view.snapshot().operationId()).toList();
    }

    private static void assertEquivalent(ClusterAnalysisView baseline, ClusterAnalysisView projected) {
        ObjectNode expected = JSON.valueToTree(baseline), actual = JSON.valueToTree(projected);
        expected.remove("serverTime"); actual.remove("serverTime");
        // These two durations intentionally advance with the server clock for active runs.
        if (baseline.snapshot().finishedAt() == null) {
            expected.remove("elapsedMillis"); actual.remove("elapsedMillis");
            expected.remove("executionMillis"); actual.remove("executionMillis");
            assertEquals(projected.snapshot().serverTime() - projected.snapshot().startedAt(), projected.snapshot().elapsedMillis());
            assertEquals(projected.snapshot().serverTime() - projected.snapshot().executionStartedAt(), projected.snapshot().executionMillis());
        }
        assertEquals(expected, actual, baseline.snapshot().operationId());
    }

    private static void persist(Database db, String id, long createdAt, ClusterAnalysisState state,
                                WorkspaceContext scope, RequirementReference requirement, String resultJson) {
        new TransactionTemplate(db.transactions).executeWithoutResult(status -> {
            var context = new AnalysisOperationContext(id, new AnalysisSourceAuthority(scope.repositoryId(),
                    scope.workspaceId(), scope.currentBranch(), "source"), requirement, id);
            var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", scope.username(),
                    scope, null, command("requirement").analysisScope());
            var run = new ClusterAnalysisRun();
            run.id = id; run.username = scope.username(); run.scopeKey = ClusterAnalysisStore.scopeKey(scope);
            run.projectId = requirement.projectId(); run.requirementId = requirement.requirementId();
            run.contextJson = JSON.writeValueAsString(context); run.commandJson = JSON.writeValueAsString(command);
            run.state = state; run.revision = 17; run.createdAt = createdAt; run.updatedAt = createdAt + 100;
            run.totalRoots = 1; run.completedRoots = 1; run.resultJson = resultJson;
            run.viewJson = "{\"unneeded\":\"" + "view ".repeat(1000) + "\"}";
            run.relationPlanJson = "{\"unneeded\":\"" + "plan ".repeat(1000) + "\"}";
            db.em.persist(run);
            var taskStates = List.of("QUEUED", "RUNNING", "COMPLETED", "PARTIAL", "FAILED", "STOPPED");
            for (int index = 0; index < taskStates.size(); index++) {
                var task = new ClusterAnalysisWork(); task.id = java.util.UUID.randomUUID().toString();
                task.operationId = id; task.taskId = id + "-task-" + index; task.ordinal = index;
                task.taskType = index == 5 ? "RELATION_ANALYSIS" : "SUBTAXONOMY_ANALYSIS";
                task.root = index == 5 ? null : "CP"; task.state = taskStates.get(index);
                task.startedAt = index == 0 ? null : createdAt + 10; task.finishedAt = index > 1 ? createdAt + 90 : null;
                task.messageJson = "{\"unneeded\":\"" + "message ".repeat(1000) + "\"}";
                task.inputJson = "{\"unneeded\":\"" + "input ".repeat(1000) + "\"}";
                task.resultJson = "{\"unneeded\":\"" + "result ".repeat(1000) + "\"}";
                db.em.persist(task);
            }
        });
    }
}
