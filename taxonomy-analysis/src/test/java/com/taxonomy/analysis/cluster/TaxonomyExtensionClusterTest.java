package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.recovery.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Durable local-source → distributed-target replay, with the real worker-session adapter. */
class TaxonomyExtensionClusterTest {
    @Test void acceptedAnswersReplayAcrossWorkerRestartAndOnlySelectedRootsAcquireAttempts() {
        try (var fixture = new Fixture()) {
            var task = fixture.db.task(TaxonomyShardRoot.of("CP"));
            var input = fixture.cluster.start(task);
            // Simulate another process loading only persisted input and journal rows.
            var restarted = new ClusterAnalysisStore(fixture.db.em, fixture.db.transactions, new ObjectMapper(), fixture.db.dispatch, fixture.saved);
            var providerCalls = new AtomicInteger();
            try (var session = restarted.checkpointSession(input, task, Fixture.POLICY, Fixture.POLICY)) {
                var accepted = AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "MOCK", List.of("CP"), "original CP prompt",
                        () -> { providerCalls.incrementAndGet(); return detail("CP", 1); });
                assertThat(accepted.getScores()).containsExactlyEntriesOf(Map.of("CP", 77.125));
                assertThat(accepted.getReasons()).containsEntry("CP", "Accepted CP\nexact explanation");
                var open = AnalysisCheckpointSession.evaluate("CATEGORY", "MOCK", List.of("CP"), "unvisited question in retained root",
                        () -> { providerCalls.incrementAndGet(); return detail("CP", 1); });
                assertThat(open.getScores()).isEmpty();
                assertThat(providerCalls).hasValue(0);
            }
            var result = new AnalysisResult(Map.of("CP", 77.125), fixture.tree);
            result.setReasons(Map.of("CP", "Accepted CP\nexact explanation")); result.setStatus("SUCCESS");
            restarted.accept(fixture.db.complete(task, result));
            var next = fixture.db.task(TaxonomyShardRoot.of("IP"));
            var nextInput = restarted.start(next);
            try (var session = restarted.checkpointSession(nextInput, next, Fixture.POLICY, Fixture.POLICY)) {
                var fresh = AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "MOCK", List.of("IP"), "new IP prompt",
                        () -> { providerCalls.incrementAndGet(); return detail("IP", 31.25); });
                assertThat(fresh.getScores()).containsEntry("IP", 31.25);
            }
            assertThat(providerCalls).hasValue(1);
            restarted.cancel(fixture.context);
            var canonical = fixture.saved.read(fixture.target.getContinuationId(), fixture.owner()).result();
            assertThat(canonical.getRawScores()).containsExactlyInAnyOrderEntriesOf(Map.of("CP", 77.125, "IP", 31.25));
            assertThat(canonical.getReasons()).containsEntry("CP", "Accepted CP\nexact explanation");
            assertThat(canonical.getStatus()).isEqualTo("CANCELLED");
        }
    }

    @Test void cancellationBeforeAnyReplayedRootResultStillKeepsAcceptedSource() {
        try (var fixture = new Fixture()) {
            assertThat(fixture.cluster.cancel(fixture.context)).isTrue();
            var result = fixture.saved.read(fixture.target.getContinuationId(), fixture.owner()).result();
            assertThat(result.getRawScores()).containsExactlyEntriesOf(Map.of("CP", 77.125));
            assertThat(result.getReasons()).containsExactlyEntriesOf(Map.of("CP", "Accepted CP\nexact explanation"));
            assertThat(result.getAnalysisCoverage().nodes().get("IP").state()).isEqualTo(AnalysisCoverage.State.UNKNOWN);
            assertThat(result.getAnalysisScope().taxonomyRoots()).containsExactlyInAnyOrder("CP", "IP");
        }
    }

    private static LlmCallDetail detail(String code, double score) {
        var detail = new LlmCallDetail(); detail.setScores(Map.of(code, score));
        detail.setReasons(Map.of(code, "Accepted " + code + "\nexact explanation"));
        detail.setProvider("MOCK"); detail.setRawResponse("original provider response"); return detail;
    }

    private static final class Fixture implements AutoCloseable {
        static final String POLICY = "c".repeat(64);
        final ClusterAnalysisStoreTest.Database db = new ClusterAnalysisStoreTest.Database();
        final ObjectMapper mapper = new ObjectMapper();
        final AnalysisContinuationStore saved;
        final ClusterAnalysisStore cluster;
        final AnalysisOperationContext context = ClusterAnalysisStoreTest.context("taxonomy-extension-" + UUID.randomUUID());
        final List<TaxonomyNodeDto> tree;
        final AnalysisRequest target;

        Fixture() {
            var proxy = new ProxyFactory(new AnalysisContinuationStore(db.em, mapper)); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(db.transactions, new AnnotationTransactionAttributeSource()));
            saved = (AnalysisContinuationStore) proxy.getProxy();
            cluster = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var roots = TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(root ->
                    DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code())).toList();
            tree = roots.stream().map(root -> {
                var node = root.nodes().getFirst(); var dto = new TaxonomyNodeDto();
                dto.setCode(node.code()); dto.setTaxonomyRoot(node.taxonomyRoot()); dto.setNameEn(node.nameEn());
                dto.setLevel(node.level()); dto.setSortOrder(node.sortOrder()); dto.setAnalysisRole(node.metadata().analysisRole());
                dto.setSecondaryClassificationCodes(node.metadata().secondaryClassificationCodes());
                dto.setClassificationConfidence(node.metadata().confidence()); dto.setClassificationReviewRequired(node.metadata().reviewRequired());
                return dto;
            }).toList();
            var source = new AnalysisRequest("requirement"); source.setProvider("MOCK"); source.setResumable(true);
            source.setMaxArchitectureNodes(20); source.setContinuationId(UUID.randomUUID().toString());
            source.setAnalysisScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY));
            var claim = saved.begin(source, owner(), "a".repeat(64), tree);
            try (var session = new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
                public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) { return saved.prepare(claim, question); }
                public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) { saved.finish(claim, question, state, detail); }
                public void checkActive() { }
            })) {
                AnalysisCheckpointSession.evaluate("ROOT_RELEVANCE", "MOCK", List.of("CP"), "original CP prompt", () -> detail("CP", 77.125));
            }
            var sourceResult = new AnalysisResult(Map.of("CP", 77.125), tree);
            sourceResult.setReasons(Map.of("CP", "Accepted CP\nexact explanation")); sourceResult.setStatus("SUCCESS");
            saved.complete(claim, sourceResult, tree);
            target = mapper.readValue(mapper.writeValueAsString(source), AnalysisRequest.class);
            target.setContinuationId(UUID.randomUUID().toString()); target.setTaxonomySourceContinuationId(source.getContinuationId());
            target.setTaxonomySourceVersion(saved.read(source.getContinuationId(), owner()).recovery().version());
            target.setTaxonomyRequestedScope(new AnalysisScope(Set.of("IP"), AnalysisMode.TAXONOMIES_ONLY));
            target.setAnalysisScope(new AnalysisScope(Set.of("CP", "IP"), AnalysisMode.TAXONOMIES_ONLY));
            var admitted = saved.begin(target, owner(), "b".repeat(64), tree);
            assertThat(admitted.retainedAnalysis()).isNull();
            var binding = new AnalysisContinuationBinding(admitted.id(), admitted.token(), "b".repeat(64), POLICY, POLICY);
            var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice", owner(), null,
                    target.getAnalysisScope(), null, binding);
            var sources = new LinkedHashMap<TaxonomyShardRoot, String>();
            roots.forEach(root -> { if (target.getAnalysisScope().selects(root.rootCode()))
                sources.put(TaxonomyShardRoot.of(root.rootCode()), mapper.writeValueAsString(root)); });
            cluster.admit(context, command, null, sources, Map.of());
        }
        com.taxonomy.workspace.service.WorkspaceContext owner() { return ClusterAnalysisStoreTest.command("requirement").workspaceContext(); }
        public void close() { db.close(); }
    }
}
