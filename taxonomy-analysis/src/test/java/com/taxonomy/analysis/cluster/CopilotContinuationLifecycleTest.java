package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.analysis.recovery.AnalysisContinuationBinding;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotContinuationLifecycleTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void uncertainQuestionDowngradesAlreadyComputedReportsBeforeTheContinuationIsPersisted() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var tx = new TransactionTemplate(db.transactions);
            var saved = new AnalysisContinuationStore(db.em, mapper);
            var request = request();
            var workspace = ClusterAnalysisStoreTest.command("requirement").workspaceContext();
            var claim = tx.execute(status -> saved.begin(request, workspace,
                    AnalysisCheckpointSession.digest("copilot-question"), tree()));
            tx.execute(status -> saved.prepare(claim, new AnalysisCheckpointSession.Question(
                    "a".repeat(64), "b".repeat(64), "MOCK", List.of("CP"), "Uncertain fixture question")));
            var incoming = new AnalysisResult(Map.of("CP", 80d), tree());
            incoming.setStatus("SUCCESS");
            incoming.setCopilot(new AnalysisCopilotResult(1, "COMPLETED", new GapAnalysisView(),
                    new PatternDetectionView(), new ArchitectureRecommendation(), List.of("Earlier completed reports")));

            var canonical = tx.execute(status -> saved.complete(claim, incoming, tree()));

            assertThat(canonical.getStatus()).isEqualTo("PARTIAL");
            assertThat(canonical.getCopilot()).isNotNull();
            assertThat(canonical.getCopilot().state()).isEqualTo("SKIPPED_PARTIAL");
            assertThat(canonical.getCopilot().notes()).noneMatch(note -> note.contains("Earlier completed"));
            var restored = tx.execute(status -> saved.read(claim.id(), workspace)).result();
            assertThat(restored.getCopilot().state()).isEqualTo("SKIPPED_PARTIAL");
            assertThat(restored.getRawScores()).containsEntry("CP", 80d);
        }
    }

    @Test
    void boundCancellationStoresTheSameExplicitSkippedReportsInBothCanonicalJournals() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var tx = new TransactionTemplate(db.transactions);
            var saved = new AnalysisContinuationStore(db.em, mapper);
            var request = request();
            var original = ClusterAnalysisStoreTest.command("requirement");
            String signature = AnalysisCheckpointSession.digest("copilot-bound-cancel");
            var claim = tx.execute(status -> saved.begin(request, original.workspaceContext(), signature, tree()));
            var context = ClusterAnalysisStoreTest.context("copilot-bound-cancel");
            var command = new AnalyzeRequirementCommand("requirement", true, 20, "MOCK", "alice",
                    original.workspaceContext(), null, request.getAnalysisScope())
                    .withContinuationBinding(new AnalysisContinuationBinding(claim.id(), claim.token(),
                            signature, signature, signature));
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var targets = new LinkedHashMap<TaxonomyShardRoot, String>();
            for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) targets.put(root, mapper.writeValueAsString(
                    DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code())));
            var cp = TaxonomyShardRoot.of("CP");
            store.admit(context, command, null, Map.of(cp, targets.get(cp)), targets);

            assertThat(store.cancel(context)).isTrue();

            var cluster = store.snapshot(context).result();
            var continuation = tx.execute(status -> saved.read(claim.id(), original.workspaceContext())).result();
            assertThat(cluster.getRecovery().state()).isEqualTo("CANCELLED");
            assertThat(cluster.getCopilot()).isNotNull();
            assertThat(continuation.getCopilot()).isNotNull();
            assertThat(cluster.getCopilot().state()).isEqualTo("SKIPPED_PARTIAL");
            assertThat(continuation.getCopilot().state()).isEqualTo("SKIPPED_PARTIAL");
            assertThat(cluster.getRawScores()).isEqualTo(continuation.getRawScores());
        }
    }

    private static AnalysisRequest request() {
        var request = new AnalysisRequest("requirement");
        request.setProvider("MOCK");
        request.setResumable(true);
        request.setIncludeArchitectureView(true);
        request.setMaxArchitectureNodes(20);
        request.setContinuationId(UUID.randomUUID().toString());
        request.setAnalysisScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY));
        return request;
    }

    private static List<TaxonomyNodeDto> tree() {
        var context = ClusterAnalysisStoreTest.context("copilot-bound-cancel");
        return TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(root -> {
            var recorded = DurableClusterAnalysisExecutionTest.root(
                    DurableClusterAnalysisExecutionTest.source(context), root.code()).nodes().getFirst();
            var node = new TaxonomyNodeDto();
            node.setCode(recorded.code()); node.setTaxonomyRoot(recorded.taxonomyRoot());
            node.setNameEn(recorded.nameEn()); node.setLevel(recorded.level()); node.setSortOrder(recorded.sortOrder());
            node.setAnalysisRole(recorded.metadata().analysisRole());
            node.setSecondaryClassificationCodes(recorded.metadata().secondaryClassificationCodes());
            node.setClassificationConfidence(recorded.metadata().confidence());
            node.setClassificationReviewRequired(recorded.metadata().reviewRequired());
            return node;
        }).toList();
    }
}
