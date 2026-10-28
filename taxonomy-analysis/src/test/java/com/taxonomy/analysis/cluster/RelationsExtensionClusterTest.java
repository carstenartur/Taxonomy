package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.recovery.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.taxonomy.dto.RelationSearchModel.*;

class RelationsExtensionClusterTest {
    @Test void retryCancellationRetainsAcceptedRelationsBeforeAnyTargetResultReplays() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var extension = extension(db);
            var saved = extension.saved();
            var request = extension.request();
            var owner = ClusterAnalysisStoreTest.command("requirement").workspaceContext();
            var claim = saved.begin(request, owner, "b".repeat(64), extension.tree());
            var oldPlan = plan("previous-relations", List.of("previous-source-result"));
            var completed = RelationSearchDistribution.ready(oldPlan, List.of()).getLast();
            var contribution = oldPlan.sources().getFirst().contributions().getFirst();
            var edge = new Edge(contribution, new Node("IP", "IP", "Information product", "", false), "REQUIRES", Direction.OUTGOING,
                    new Decision("IP", Outcome.VERIFIED, "requirement", "requirement", Necessity.REQUIRED, "", "", "Accepted before retry", ""));
            var accepted = new RelationSearchDistribution.WorkResult(completed,
                    new Result(List.of(edge), List.of(), List.of(), 1, 1, 1), 1, "");
            var previous = RelationSearchDistribution.combine(oldPlan, List.of(accepted));
            var success = new AnalysisCheckpointSession.Question("R" + "1".repeat(63), "1".repeat(64), "MOCK", List.of("IP"), "accepted target prompt");
            saved.prepare(claim, success); saved.finish(claim, success, "SUCCESS", new LlmCallDetail());
            var failed = new AnalysisCheckpointSession.Question("R" + "2".repeat(63), "2".repeat(64), "MOCK", List.of("CR"), "failed target prompt");
            saved.prepare(claim, failed); saved.finish(claim, failed, "FAILED", new LlmCallDetail());
            var partial = claim.retainedAnalysis(); partial.setRelationSearchReport(previous); partial.setStatus("PARTIAL");
            saved.complete(claim, partial, extension.tree());
            var paused = saved.read(claim.id(), owner);
            assertThat(paused.recovery().state()).isEqualTo("PAUSED");
            request.setContinuationAction("RETRY"); request.setContinuationQuestion(failed.key());
            request.setContinuationVersion(paused.recovery().version());
            var retry = saved.begin(request, owner, "b".repeat(64), extension.tree());
            assertThat(retry.retainedAnalysis().getRelationSearchReport().result().edges()).containsExactly(edge);
            var binding = new AnalysisContinuationBinding(retry.id(), retry.token(), "b".repeat(64), "c".repeat(64), "d".repeat(64));
            var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice", owner, null,
                    request.getAnalysisScope(), null, binding).withRetainedAnalysis(retry.retainedAnalysis());
            extension.store().admit(extension.context(), command, null, extension.sources(), extension.all());
            var preparation = (RelationAnalysisTask) db.sent.getFirst();
            var currentPlan = plan(extension.context().operationId() + ":relations",
                    preparation.prerequisiteTasks().stream().map(AnalysisTaskId::value).toList());
            var completion = new AnalysisMessageFactory(extension.context(), Clock.systemUTC()).completed(preparation, AnalysisTaskOutcome.COMPLETED, 0, null);
            db.completions.commit(new PreparedAnalysisCompletion<>(completion, () -> extension.store().persistRelationPlan(preparation, currentPlan)));
            extension.store().accept(completion);
            var live = extension.store().snapshot(extension.context());
            assertThat(extension.store().cancel(extension.context())).isTrue();
            var cancelled = extension.store().snapshot(extension.context());
            var canonical = saved.read(retry.id(), owner).result();
            org.assertj.core.api.SoftAssertions.assertSoftly(check -> {
                check.assertThat(cancelled.result().getRelationSearchReport().result().edges()).containsExactly(edge);
                check.assertThat(canonical.getRelationSearchReport().result().edges()).containsExactly(edge);
                check.assertThat(live.progress().relations().verifiedRelations()).isEqualTo(1);
                check.assertThat(cancelled.progress().relations().verifiedRelations()).isEqualTo(1);
                check.assertThat(cancelled.result().getRawScores()).containsExactlyEntriesOf(retry.retainedAnalysis().getRawScores());
                for (var progress : List.of(live.progress().relations(), cancelled.progress().relations(), canonical.getRelationSearchReport().progress())) {
                    // The accepted IP search remains complete while new scheduler
                    // grants await replay; only the failed CR search remains open.
                    check.assertThat(progress.completedSearches()).isEqualTo(1);
                    check.assertThat(progress.pendingSearches()).isEqualTo(1);
                    check.assertThat(progress.taxonomies()).contains(
                            new RelationSearchProgress.Taxonomy("IP", 1, 1, 0, 0),
                            new RelationSearchProgress.Taxonomy("CR", 1, 0, 0, 1));
                    check.assertThat(progress.calls()).isEqualTo(1);
                }
                check.assertThat(canonical.getRelationSearchReport().result().calls()).isZero();
                check.assertThat(canonical.getRelationSearchReport().result().visitedBatches()).isZero();
                check.assertThat(canonical.getRecovery().completedCalls()).isEqualTo(1);
                check.assertThat(db.sent).hasSize(3).allMatch(task -> task instanceof RelationAnalysisTask);
                check.assertThat(cancelled.tasks()).allMatch(task -> task.attempts() == 0);
            });
        }
    }

    private record Extension(ClusterAnalysisStore store, AnalysisContinuationStore saved, AnalysisRequest request,
            AnalysisOperationContext context, List<TaxonomyNodeDto> tree, Map<TaxonomyShardRoot, String> sources,
            Map<TaxonomyShardRoot, String> all) { }

    private static Extension extension(ClusterAnalysisStoreTest.Database db) {
        var mapper = new ObjectMapper();
        var proxy = new ProxyFactory(new AnalysisContinuationStore(db.em, mapper)); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(db.transactions, new AnnotationTransactionAttributeSource()));
        var saved = (AnalysisContinuationStore) proxy.getProxy();
        var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
        var context = ClusterAnalysisStoreTest.context("relations-extension-retry");
        var original = ClusterAnalysisStoreTest.command("requirement");
        var roots = TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(root ->
                DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code())).toList();
        var tree = roots.stream().map(root -> {
            var n = root.nodes().getFirst(); var dto = new TaxonomyNodeDto();
            dto.setCode(n.code()); dto.setTaxonomyRoot(n.taxonomyRoot()); dto.setNameEn(n.nameEn());
            dto.setLevel(n.level()); dto.setSortOrder(n.sortOrder()); dto.setAnalysisRole(n.metadata().analysisRole());
            dto.setSecondaryClassificationCodes(n.metadata().secondaryClassificationCodes());
            dto.setClassificationConfidence(n.metadata().confidence()); dto.setClassificationReviewRequired(n.metadata().reviewRequired());
            return dto;
        }).toList();
        var source = new AnalysisRequest("requirement"); source.setProvider("MOCK"); source.setMaxArchitectureNodes(20);
        source.setAnalysisScope(original.analysisScope()); source.setContinuationId(UUID.randomUUID().toString());
        var sourceClaim = saved.begin(source, original.workspaceContext(), "a".repeat(64), tree);
        var evidence = new AnalysisResult(Map.of("CP", 77.125, "IP", 0d), tree); evidence.setStatus("SUCCESS"); evidence.setProvider("MOCK");
        saved.complete(sourceClaim, evidence, tree);
        var snapshot = saved.read(source.getContinuationId(), original.workspaceContext());
        var request = mapper.readValue(mapper.writeValueAsString(source), AnalysisRequest.class);
        request.setContinuationId(UUID.randomUUID().toString()); request.setRelationSourceContinuationId(source.getContinuationId());
        request.setRelationSourceVersion(snapshot.recovery().version());
        request.setAnalysisScope(new AnalysisScope(original.analysisScope().taxonomyRoots(), AnalysisMode.FULL));
        var all = new LinkedHashMap<TaxonomyShardRoot, String>(); roots.forEach(root -> all.put(TaxonomyShardRoot.of(root.rootCode()), mapper.writeValueAsString(root)));
        var sources = new LinkedHashMap<TaxonomyShardRoot, String>();
        all.forEach((root, json) -> { if (request.getAnalysisScope().selects(root.code())) sources.put(root, json); });
        return new Extension(store, saved, request, context, tree, sources, all);
    }

    private static RelationSearchDistribution.Plan plan(String id, List<String> sourceResults) {
        var source = new Node("CP", "CP", "Capability", "", false);
        var contribution = new Contribution(source, "requirement", "requirement", "");
        return new RelationSearchDistribution.Plan(1, id, RequirementReference.sha256("requirement"), sourceResults,
                new RequirementRelationSearch.Options(new Limits(10, 8, 10, 128), 16), List.of(source),
                List.of(new Node("CR", "CR", "Core service", "", false), new Node("IP", "IP", "Information product", "", false)),
                List.of(new SourceAssessment(source, List.of(contribution), "", "")),
                List.of(new RelationSearchDistribution.Item(0, 0, 0, "REALIZES", Direction.OUTGOING, TaxonomyShardRoot.of("CR"), 1),
                        new RelationSearchDistribution.Item(1, 0, 0, "REQUIRES", Direction.OUTGOING, TaxonomyShardRoot.of("IP"), 1)),
                1, 0, List.of(), "", true);
    }

    @Test void retainedSourceTasksAreSettledWithoutDispatchAndOnlyRelationsRun() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var mapper = new ObjectMapper();
            var proxy = new ProxyFactory(new AnalysisContinuationStore(db.em, mapper)); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(db.transactions, new AnnotationTransactionAttributeSource()));
            var saved = (AnalysisContinuationStore) proxy.getProxy();
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var context = ClusterAnalysisStoreTest.context("relations-extension");
            var original = ClusterAnalysisStoreTest.command("requirement");
            var roots = TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(root ->
                    DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code())).toList();
            var tree = roots.stream().map(root -> {
                var n = root.nodes().getFirst(); var dto = new TaxonomyNodeDto();
                dto.setCode(n.code()); dto.setTaxonomyRoot(n.taxonomyRoot()); dto.setNameEn(n.nameEn());
                dto.setLevel(n.level()); dto.setSortOrder(n.sortOrder()); dto.setAnalysisRole(n.metadata().analysisRole());
                dto.setSecondaryClassificationCodes(n.metadata().secondaryClassificationCodes());
                dto.setClassificationConfidence(n.metadata().confidence()); dto.setClassificationReviewRequired(n.metadata().reviewRequired());
                return dto;
            }).toList();
            var source = new AnalysisRequest("requirement"); source.setProvider("MOCK"); source.setMaxArchitectureNodes(20);
            source.setAnalysisScope(original.analysisScope()); source.setContinuationId(UUID.randomUUID().toString());
            var sourceClaim = saved.begin(source, original.workspaceContext(), "a".repeat(64), tree);
            var evidence = new AnalysisResult(Map.of("CP", 77.125, "IP", 0d), tree); evidence.setStatus("SUCCESS");
            evidence.setProvider("MOCK"); evidence.setReasons(Map.of("CP", "Keep the original explanation."));
            saved.complete(sourceClaim, evidence, tree);
            var sourceSnapshot = saved.read(source.getContinuationId(), original.workspaceContext());
            var request = mapper.readValue(mapper.writeValueAsString(source), AnalysisRequest.class);
            request.setContinuationId(UUID.randomUUID().toString()); request.setRelationSourceContinuationId(source.getContinuationId());
            request.setRelationSourceVersion(sourceSnapshot.recovery().version());
            request.setAnalysisScope(new AnalysisScope(original.analysisScope().taxonomyRoots(), AnalysisMode.FULL));
            var claim = saved.begin(request, original.workspaceContext(), "b".repeat(64), tree);
            claim.retainedAnalysis().setCopilot(new AnalysisCopilotResult(1, "COMPLETED", null, null, null,
                    List.of("Reports from the retained source")));
            var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice", original.workspaceContext(), null,
                    request.getAnalysisScope(), null, new AnalysisContinuationBinding(claim.id(), claim.token(), "b".repeat(64),
                    "c".repeat(64), "d".repeat(64))).withRetainedAnalysis(claim.retainedAnalysis());
            var all = new LinkedHashMap<TaxonomyShardRoot, String>(); roots.forEach(root -> all.put(TaxonomyShardRoot.of(root.rootCode()), mapper.writeValueAsString(root)));
            var sources = new LinkedHashMap<TaxonomyShardRoot, String>();
            all.forEach((root, json) -> { if (command.analysisScope().selects(root.code())) sources.put(root, json); });
            store.admit(context, command, null, sources, all);
            assertThat(db.sent).hasSize(1).allMatch(task -> task instanceof RelationAnalysisTask);
            assertThat(store.snapshot(context).completedRoots()).isEqualTo(sources.size());
            assertThat(store.snapshot(context).totalRoots()).isEqualTo(sources.size());
            assertThat(store.snapshot(context).state()).isEqualTo(ClusterAnalysisState.RELATIONS);
            assertThat(store.snapshot(context).tasks().stream().filter(work -> work.type().equals("SUBTAXONOMY_ANALYSIS")))
                    .allMatch(work -> work.state().equals("COMPLETED"));
            assertThat(store.rootResults(context).getRawScores()).containsExactlyEntriesOf(evidence.getRawScores());
            assertThat(store.rootResults(context).getReasons()).containsExactlyEntriesOf(evidence.getReasons());
            assertThat(store.rootResults(context).getCopilot())
                    .as("Aggregation for new relation work invalidates inherited completion reports").isNull();
            assertThat(claim.retainedAnalysis().getCopilot()).isNotNull();
            assertThat(store.snapshot(context).tasks().stream().filter(task -> task.type().equals("SUBTAXONOMY_ANALYSIS")))
                    .allMatch(task -> task.attempts() == 0);
            var task = (RelationAnalysisTask) db.sent.getFirst();
            var completion = new AnalysisMessageFactory(context, Clock.systemUTC()).completed(task, AnalysisTaskOutcome.COMPLETED, 0, null);
            var tx = new org.springframework.transaction.support.TransactionTemplate(db.transactions);
            tx.executeWithoutResult(status -> store.persistScoreOnlyRelations(task, List.of()));
            store.accept(completion);
            assertThat(store.snapshot(context).state()).isEqualTo(ClusterAnalysisState.COMPLETED);
            assertThat(store.snapshot(context).result().getRawScores()).containsExactlyEntriesOf(evidence.getRawScores());
            assertThat(saved.read(source.getContinuationId(), original.workspaceContext()).recovery().state()).isEqualTo("COMPLETED");
        }
    }
}
