package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

/** Real SQL authority: extension input is retained server evidence, never browser scores. */
class RelationsExtensionTest {
    private static final WorkspaceContext OWNER = new WorkspaceContext("alice", "ws", "draft", "repo");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void completedNodeOnlyAssessmentSeedsAnIndependentRelationsContinuation() {
        try (var db = new Database()) {
            var source = db.source("COMPLETED", "SUCCESS");
            var request = db.extension(source);
            var claim = db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree));
            assertThat(claim.retainedAnalysis()).as("server-retained source evidence").isNotNull();
            assertThat(claim.retainedAnalysis().getRawScores()).containsExactlyEntriesOf(Map.of("CP", 71.125));
            assertThat(claim.retainedAnalysis().getReasons()).containsEntry("CP", "Original reasoning\nunchanged.");
            assertThat(claim.retainedAnalysis().getScores()).doesNotContainKey("IP");
            assertThat(db.tx(em -> db.store(em).read(source, OWNER)).recovery().state()).isEqualTo("COMPLETED");
            assertThat(db.tx(em -> db.store(em).read(source, OWNER)).recovery().version()).isEqualTo(request.getRelationSourceVersion());
            assertThatThrownBy(() -> db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
            var duplicate = MAPPER.readValue(MAPPER.writeValueAsString(request), AnalysisRequest.class);
            duplicate.setContinuationId(UUID.randomUUID().toString());
            assertThatThrownBy(() -> db.tx(em -> db.store(em).begin(duplicate, OWNER, "b".repeat(64), db.tree)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
            var result = claim.retainedAnalysis();
            result.setAnalysisScope(request.getAnalysisScope());
            result.setStatus("SUCCESS");
            db.tx(em -> db.store(em).complete(claim, result, db.tree));
            var replay = db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree));
            assertThat(replay.completedResult().getRawScores()).containsExactlyEntriesOf(result.getRawScores());
        }
    }

    @Test void foreignStaleRunningAndNonNodeOnlySourcesCannotAuthorizeAnExtension() {
        try (var db = new Database()) {
            var source = db.source("COMPLETED", "SUCCESS");
            var request = db.extension(source);
            request.setRelationSourceVersion(request.getRelationSourceVersion() + 1);
            assertThatThrownBy(() -> db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
            request.setRelationSourceVersion(request.getRelationSourceVersion() - 1);
            assertThatThrownBy(() -> db.tx(em -> db.store(em).begin(request,
                    new WorkspaceContext("bob", "ws", "draft", "repo"), "b".repeat(64), db.tree)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            db.tx(em -> { em.find(AnalysisContinuationRun.class, source).state = "RUNNING"; return null; });
            request.setRelationSourceVersion(db.tx(em -> db.store(em).read(source, OWNER)).recovery().version());
            assertThatThrownBy(() -> db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
            db.tx(em -> {
                var row = em.find(AnalysisContinuationRun.class, source); row.state = "COMPLETED";
                var changed = MAPPER.readValue(row.requestJson, AnalysisRequest.class); changed.setAnalysisScope(AnalysisScope.full());
                row.requestJson = MAPPER.writeValueAsString(changed); return null;
            });
            request.setRelationSourceVersion(db.tx(em -> db.store(em).read(source, OWNER)).recovery().version());
            assertThatThrownBy(() -> db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        }
    }

    @Test void retainedPartialNodeCoverageCannotBecomeCompleteWhenRelationsFinish() {
        try (var db = new Database()) {
            var source = db.source("COMPLETED_WITH_GAPS", "PARTIAL");
            var request = db.extension(source);
            var claim = db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree));
            var completed = db.tx(em -> db.store(em).complete(claim, claim.retainedAnalysis(), db.tree));
            assertThat(completed.getStatus()).isEqualTo("PARTIAL");
            assertThat(completed.getAnalysisCoverage().nodes().get("IP").state()).isEqualTo(AnalysisCoverage.State.UNKNOWN);
            assertThat(completed.getRawScores()).doesNotContainKey("IP");
            assertThat(completed.getWarnings()).contains("Original node warning");
        }
    }

    @Test void cancellationRetainsPartialRelationsAndCannotReplaceAcceptedNodeEvidence() {
        try (var db = new Database()) {
            var source = db.source("COMPLETED", "SUCCESS"); var request = db.extension(source);
            var claim = db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree));
            db.tx(em -> db.store(em).cancel(claim.id(), OWNER));
            var partial = claim.retainedAnalysis();
            var sourceNode = new RelationSearchModel.Node("CP", "CP", "Capability", "", false);
            var targetNode = new RelationSearchModel.Node("IP", "IP", "Information", "", false);
            var contribution = new RelationSearchModel.Contribution(sourceNode, "Original requirement", "Original requirement", "");
            var edge = new RelationSearchModel.Edge(contribution, targetNode, "PRODUCES", RelationSearchModel.Direction.OUTGOING,
                    new RelationSearchModel.Decision("IP", RelationSearchModel.Outcome.VERIFIED, "Original requirement", "Original requirement",
                            RelationSearchModel.Necessity.REQUIRED, "", "", "Accepted before cancellation", ""));
            var report = new RelationSearchReport(2, com.taxonomy.analysis.dag.RequirementReference.sha256("Original requirement"), "fixture",
                    List.of(new RelationSearchModel.SourceAssessment(sourceNode, List.of(contribution), "", "")),
                    new RelationSearchModel.Result(List.of(edge), List.of(new RelationSearchModel.Unfinished("CP", "PRODUCES",
                    RelationSearchModel.Direction.OUTGOING, List.of("IP"), "CANCELLED", "")), List.of(), 2, 1, 1),
                    2, 24, 1, List.of(), "CANCELLED: partial search");
            partial.setRelationSearchReport(report);
            partial.setRawScores(Map.of("CP", 99d)); // A late worker must never replace the cancelled source assessments.
            var cancelled = db.tx(em -> db.store(em).complete(claim, partial, db.tree));
            assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
            assertThat(cancelled.getRawScores()).containsExactlyEntriesOf(Map.of("CP", 71.125));
            assertThat(cancelled.getRelationSearchReport()).isEqualTo(report);
            assertThat(db.tx(em -> db.store(em).read(claim.id(), OWNER)).result().getRelationSearchReport()).isEqualTo(report);
        }
    }

    @Test void relationRetryRetainsScoresAndRequiresTheCurrentTargetRevision() {
        try (var db = new Database()) {
            var request = db.extension(db.source("COMPLETED", "SUCCESS"));
            var claim = db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree));
            var question = new AnalysisCheckpointSession.Question("R" + "a".repeat(63), "c".repeat(64), "MOCK", List.of("CP"), "relation prompt");
            db.tx(em -> db.store(em).prepare(claim, question));
            var failed = new LlmCallDetail(); failed.setError("relation fixture failure");
            db.tx(em -> { db.store(em).finish(claim, question, "FAILED", failed); return null; });
            claim.retainedAnalysis().getWarnings().add("RELATION_EVALUATION_FAILED: fixture pause");
            db.tx(em -> db.store(em).complete(claim, claim.retainedAnalysis(), db.tree));
            var paused = db.tx(em -> db.store(em).read(claim.id(), OWNER));
            request.setContinuationAction("RETRY"); request.setContinuationQuestion(question.key()); request.setContinuationVersion(paused.recovery().version() - 1);
            assertThatThrownBy(() -> db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
            request.setContinuationVersion(paused.recovery().version());
            var retry = db.tx(em -> db.store(em).begin(request, OWNER, "b".repeat(64), db.tree));
            assertThat(retry.retainedAnalysis().getRawScores()).containsExactlyEntriesOf(Map.of("CP", 71.125));
            assertThat(db.tx(em -> db.store(em).prepare(retry, question)).state()).isEqualTo("ATTEMPT");
            var accepted = new LlmCallDetail(); accepted.setRawResponse("accepted relation fixture");
            db.tx(em -> { db.store(em).finish(retry, question, "SUCCESS", accepted); return null; });
            var recovered = retry.retainedAnalysis(); recovered.setStatus("SUCCESS");
            var complete = db.tx(em -> db.store(em).complete(retry, recovered, db.tree));
            assertThat(complete.getStatus()).isEqualTo("SUCCESS");
            assertThat(complete.getRecovery().state()).isEqualTo("COMPLETED");
            assertThat(complete.getWarnings()).noneMatch(warning -> warning.startsWith("RELATION_EVALUATION_FAILED:"));
        }
    }

    private static final class Database implements AutoCloseable {
        final List<TaxonomyNodeDto> tree;
        final SessionFactory factory;
        Database() {
            var cp = new TaxonomyNodeDto(); cp.setCode("CP"); cp.setTaxonomyRoot("CP"); cp.setNameEn("Capabilities");
            var ip = new TaxonomyNodeDto(); ip.setCode("IP"); ip.setTaxonomyRoot("IP"); ip.setNameEn("Information products");
            tree = List.of(cp, ip);
            factory = new Configuration().addAnnotatedClass(AnalysisContinuationRun.class).addAnnotatedClass(AnalysisQuestionCheckpoint.class)
                    .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:relations-extension-" + UUID.randomUUID())
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.search.enabled", "false").buildSessionFactory();
        }
        String source(String state, String status) {
            return tx(em -> {
                var request = new AnalysisRequest("Original requirement"); request.setAnalysisScope(new AnalysisScope("SUCCESS".equals(status) ? Set.of("CP") : Set.of("CP", "IP"), AnalysisMode.TAXONOMIES_ONLY));
                request.setProvider("MOCK"); request.setMaxArchitectureNodes(20);
                var result = new AnalysisResult(Map.of("CP", 71.125), tree); result.setReasons(Map.of("CP", "Original reasoning\nunchanged."));
                result.setStatus(status); result.setProvider("MOCK"); result.setAnalysisScope(request.getAnalysisScope());
                if ("PARTIAL".equals(status)) result.getWarnings().add("Original node warning");
                result.setAnalysisCoverage(AnalysisCoverage.derive(request.getAnalysisScope().selectedTree(tree), result.getRawScores(), result.getScores(), Map.of("IP", "LEFT_OPEN:source"), null));
                var run = new AnalysisContinuationRun(); run.id = UUID.randomUUID().toString(); run.username = "alice"; run.workspaceId = "ws";
                run.branchName = "draft"; run.repositoryId = "repo"; run.inputHash = "a".repeat(64); run.state = state;
                run.requestJson = MAPPER.writeValueAsString(request); run.resultJson = MAPPER.writeValueAsString(result); em.persist(run); return run.id;
            });
        }
        AnalysisRequest extension(String source) {
            var snapshot = tx(em -> store(em).read(source, OWNER));
            var request = MAPPER.readValue(MAPPER.writeValueAsString(snapshot.request()), AnalysisRequest.class);
            request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString());
            request.setAnalysisScope(new AnalysisScope(request.getAnalysisScope().taxonomyRoots(), AnalysisMode.FULL));
            request.setRelationSourceContinuationId(source); request.setRelationSourceVersion(snapshot.recovery().version());
            return request;
        }
        <T> T tx(Function<EntityManager, T> action) {
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                try { T result = action.apply(em); em.getTransaction().commit(); return result; }
                catch (RuntimeException | Error failure) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw failure; }
            }
        }
        AnalysisContinuationStore store(EntityManager em) { return new AnalysisContinuationStore(em, MAPPER); }
        public void close() { factory.close(); }
    }
}
