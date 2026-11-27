package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RequirementNodeAssessment;
import com.taxonomy.dto.RootAssessment;
import com.taxonomy.dto.RequirementNodeAssessment.*;
import com.taxonomy.dto.AnalysisResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequirementAssessmentClusterTest {
    @Test
    void runningProjectionRetainsCommittedRootEvidenceWithoutNumericScoreOrCatalogueDenominator() {
        var json = new ObjectMapper();
        var result = json.readValue("""
                {"nodeAssessments":{"BP":[{"requirementQuote":"","subjectKinds":["PROCESS"],
                 "requirementKinds":["FUNCTIONAL"],"contributionType":"DIRECT_SUBJECT","evidenceStatus":"UNRESOLVED",
                 "evidenceBasis":"UNSPECIFIED","necessity":"UNDETERMINED","scope":"UNSPECIFIED","condition":"",
                 "rationale":"Destination is unspecified.","question":"Which site receives the backup?"}]}}
                """, AnalysisResult.class);
        var task = new ClusterAnalysisWork();
        task.root = "BP";
        task.taskType = AnalysisTaskType.SUBTAXONOMY_ANALYSIS.name();
        task.resultJson = json.writeValueAsString(result);
        task.settled = true;

        var projection = ClusterAnalysisProgress.combine(null, ClusterAnalysisState.RUNNING,
                List.of(task), Map.of(), null, null, json);
        assertEquals(result.getNodeAssessments(), projection.nodeAssessments());
    }
    @Test
    void completedCallsAreDurableBeforeTheirShardsSettleIncludingScorelessClarifications() {
        var cp = TaxonomyShardRoot.of("CP");
        var ip = TaxonomyShardRoot.of("IP");
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var run = ClusterAnalysisStoreTest.context("semantic-worker-progress");
            db.admitFrozen(run);
            var first = db.task(cp);
            var input = db.store.start(first);
            var worker = db.store.diagnostics(first, input.deliveryAttempt());
            var claim = supported("requirement", "A capability supports this requirement.");
            var accepted = new LlmCallDetail();
            accepted.setScores(Map.of("CP", 70));
            accepted.setNodeAssessments(Map.of("CP", List.of(claim)));
            worker.completed(worker.started("Mock", "CP"), accepted, 1);

            var running = db.store.snapshot(run);
            assertEquals(ClusterAnalysisState.RUNNING, running.state());
            assertEquals(Map.of("CP", List.of(claim)), running.nodeAssessments());
            var firstResult = ClusterAnalysisStoreTest.result("CP", 70);
            firstResult.setNodeAssessments(accepted.getNodeAssessments());
            db.store.accept(db.complete(first, firstResult));

            var second = db.task(ip);
            var secondInput = db.store.start(second);
            var secondWorker = db.store.diagnostics(second, secondInput.deliveryAttempt());
            var clarification = open();
            var scoreless = new LlmCallDetail();
            scoreless.setScores(Map.of());
            scoreless.setRootAssessments(Map.of("IP", new RootAssessment(RootAssessment.Status.UNRESOLVED,
                    null, clarification.rationale(), clarification.question())));
            scoreless.setError("ROOT_UNRESOLVED: " + clarification.question());
            scoreless.setNodeAssessments(Map.of("IP", List.of(clarification)));
            secondWorker.completed(secondWorker.started("Mock", "IP"), scoreless, 1);

            var allClaims = Map.of("CP", List.of(claim), "IP", List.of(clarification));
            var beforeSettlement = db.store.snapshot(run);
            assertEquals(allClaims, beforeSettlement.nodeAssessments());
            assertEquals(0, beforeSettlement.progress().nodes().taxonomies().getLast().assessed());
            var secondResult = new AnalysisResult(Map.of(), List.of());
            secondResult.setStatus("PARTIAL");
            secondResult.setNodeAssessments(scoreless.getNodeAssessments());
            db.store.accept(db.complete(second, secondResult));
            assertEquals(allClaims, db.store.snapshot(run).result().getNodeAssessments());
        }
    }

    static RequirementNodeAssessment supported(String quote, String rationale) {
        return new RequirementNodeAssessment(quote, List.of(SubjectKind.SERVICE), List.of(RequirementKind.FUNCTIONAL),
                ContributionType.SUPPORTS, EvidenceStatus.SUPPORTED, EvidenceBasis.INFERRED,
                Necessity.REQUIRED, Scope.DELIVERY, "", rationale, "");
    }

    static RequirementNodeAssessment open() {
        return new RequirementNodeAssessment("", List.of(SubjectKind.PROCESS), List.of(RequirementKind.FUNCTIONAL),
                ContributionType.DIRECT_SUBJECT, EvidenceStatus.UNRESOLVED, EvidenceBasis.UNSPECIFIED,
                Necessity.UNDETERMINED, Scope.UNSPECIFIED, "", "Destination is unspecified.", "Which site receives the backup?");
    }

}
