package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.*;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrozenClusterRelationComputationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CatalogueSourceJournal.Snapshot provenance() {
        var unknown = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
        return new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH,
                unknown, new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, "digest", 0), unknown);
    }

    @Test void preparationRanksSourcesByEffectiveRelevanceBeforeApplyingSourceLimit() {
        var fixture = new Fixture(); fixture.productScores();
        ReflectionTestUtils.setField(fixture.relations, "maxSources", 1);
        assertThat(fixture.store.roots.getRawScores().get("product")).isEqualTo(100);
        assertThat(fixture.store.roots.getScores().get("product")).isEqualTo(10);
        var task = ClusterRelationServiceTest.task(fixture.store.context, null);
        var prepared = (ClusterRelationComputation.Preparation) fixture.computation("all", "BP,BR,CP,CI,CO,CR,IP,UA")
                .compute(fixture.store.start(task), task, () -> false);
        assertThat(prepared.plan().sources()).extracting(source -> source.node().id()).containsExactly("process");
        assertThat(fixture.prompts.stream().filter(prompt -> prompt.contains("Extract ONLY")).toList()).hasSize(1);
        JsonNode input = JSON.readTree(fixture.prompts.getFirst().substring(fixture.prompts.getFirst().lastIndexOf("INPUT\n") + 6));
        assertThat(input.get("nodes").get(0).get("id").asString()).isEqualTo("process");
        assertRestored(fixture); verifyNoInteractions(fixture.repository);
    }

    @Test void disabledHierarchicalSearchUsesFrozenScoreOnlyHypothesesWithoutModelCalls() {
        var fixture = new Fixture(); fixture.productScores();
        fixture.store.roots.setRawScores(Map.of("process", 90, "family", 10, "product", 100, "evidence", 80));
        ReflectionTestUtils.setField(fixture.relations, "enabled", false);
        var task = ClusterRelationServiceTest.task(fixture.store.context, null);
        var result = fixture.computation("all", "BP,BR,CP,CI,CO,CR,IP,UA")
                .compute(fixture.store.start(task), task, () -> false);
        assertThat(fixture.prompts).isEmpty();
        assertThat(result).isInstanceOf(ClusterRelationComputation.ScoreOnly.class);
        var hypotheses = ((ClusterRelationComputation.ScoreOnly) result).hypotheses();
        assertThat(hypotheses).isNotEmpty().allSatisfy(hypothesis -> {
            assertThat(hypothesis.getSourceCode()).isNotEqualTo("product");
            assertThat(hypothesis.getTargetCode()).isNotEqualTo("product");
            assertThat(hypothesis.getConfidence()).isEqualTo(0.72);
        });
        assertThat(hypotheses).anySatisfy(hypothesis -> {
            assertThat(hypothesis.getSourceCode()).isEqualTo("process");
            assertThat(hypothesis.getTargetCode()).isEqualTo("evidence");
            assertThat(hypothesis.getRelationType()).isEqualTo("CONSUMES");
            assertThat(hypothesis.getSourceName()).isEqualTo("process");
            assertThat(hypothesis.getTargetName()).isEqualTo("evidence");
        });
        assertRestored(fixture); verifyNoInteractions(fixture.repository);
    }

    @Test void preparationReadsFrozenSourceCohortOnceAndTargetWorkerLoadsOnlyItsTarget() {
        var fixture = new Fixture();
        ReflectionTestUtils.setField(fixture.relations, "maxWorkItems", 4096);
        fixture.store.roots.setStatus("SUCCESS");
        var preparation = ClusterRelationServiceTest.task(fixture.store.context, null);
        var prepared = (ClusterRelationComputation.Preparation) fixture.computation("all", "BP,BR,CP,CI,CO,CR,IP,UA")
                .compute(fixture.store.start(preparation), preparation, () -> false);
        var plan = prepared.plan();
        assertThat(plan.options().limits().maxWorkItems()).isEqualTo(512);
        assertThat(plan.sources()).hasSize(1);
        assertThat(plan.sourceResultIds()).containsExactly("relations:subtaxonomy:BP");
        assertThat(fixture.store.reads).containsExactlyInAnyOrderElementsOf(TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(TaxonomyShardRoot::code).toList());
        assertThat(fixture.store.rootReads).isEqualTo(1);
        assertThat(fixture.prompts.stream().filter(p -> p.contains("Extract ONLY")).count()).isEqualTo(1);
        assertRestored(fixture);
        fixture.store.plan = plan;
        var work = RelationSearchDistribution.ready(plan, List.of()).stream()
                .filter(w -> w.targetRoot().code().equals("IP") && plan.items().get(w.ordinal()).type().equals("CONSUMES"))
                .findFirst().orElseThrow();
        fixture.store.input = JSON.writeValueAsString(work);
        fixture.store.reads.clear(); fixture.store.rootReads = 0;
        var task = ClusterRelationServiceTest.task(fixture.store.context, work);
        var evaluation = (ClusterRelationComputation.Evaluation) fixture.computation("worker", "IP")
                .compute(fixture.store.start(task), task, () -> false);
        assertThat(evaluation.result().result().edges()).hasSize(1);
        assertThat(fixture.store.reads).containsExactly("IP");
        assertThat(fixture.store.rootReads).isZero();
        assertThat(fixture.prompts.stream().filter(p -> p.contains("Extract ONLY")).count()).isEqualTo(1);
        assertRestored(fixture);
        verifyNoInteractions(fixture.repository);
    }

    @Test void partialSourceResultsStayExplicitInPreparedEvidence() {
        var fixture = new Fixture(); fixture.store.roots.setStatus("PARTIAL");
        fixture.store.roots.setWarnings(new ArrayList<>(List.of("IP: unexecuted root; no negative finding")));
        var task = ClusterRelationServiceTest.task(fixture.store.context, null);
        var prepared = (ClusterRelationComputation.Preparation) fixture.computation("all", "BP,BR,CP,CI,CO,CR,IP,UA")
                .compute(fixture.store.start(task), task, () -> false);
        assertThat(prepared.plan().warnings()).anyMatch(w -> w.startsWith("SOURCE_RESULTS_PARTIAL"));
        assertThat(prepared.plan().sources()).hasSize(1);
        assertThat(RelationSearchDistribution.combine(prepared.plan(), List.of()).isSearchExhausted()).isFalse();
    }

    @Test void foreignOrMissingFrozenCatalogueFailsBeforeProviderAndRestoresEveryScope() {
        var fixture = new Fixture();
        var task = ClusterRelationServiceTest.task(fixture.store.context, null);
        var snapshot = JSON.readValue(fixture.store.snapshots.get("IP"), RootCatalogueSnapshot.class);
        var foreign = new RootCatalogueSnapshot(snapshot.schemaVersion(),
                new CatalogueSourceIdentity("foreign", "workspace", "draft", "source"), snapshot.rootCode(), snapshot.nodes(), snapshot.overlayMetadata(), snapshot.catalogueProvenance());
        fixture.store.snapshots.put("IP", JSON.writeValueAsString(foreign));
        var computation = fixture.computation("all", "BP,BR,CP,CI,CO,CR,IP,UA");
        assertThatThrownBy(() -> computation.compute(fixture.store.start(task), task, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.prompts).isEmpty(); assertRestored(fixture);
        fixture.store.snapshots.remove("IP");
        assertThatThrownBy(() -> computation.compute(fixture.store.start(task), task, () -> false))
                .isInstanceOf(RuntimeException.class);
        assertThat(fixture.prompts).isEmpty(); assertRestored(fixture);
        verifyNoInteractions(fixture.repository);
    }

    @Test void mismatchedWorkOrdinalOrRequirementCannotReachModel() {
        var fixture = new Fixture();
        var task = ClusterRelationServiceTest.task(fixture.store.context, null);
        fixture.store.plan = ((ClusterRelationComputation.Preparation) fixture.computation("all", "BP,BR,CP,CI,CO,CR,IP,UA")
                .compute(fixture.store.start(task), task, () -> false)).plan();
        var work = RelationSearchDistribution.ready(fixture.store.plan, List.of()).getFirst();
        var forged = new RelationSearchDistribution.Work(work.preparationId(), work.ordinal() + 1, work.targetRoot(), work.maxCalls(), work.maxWorkItems());
        fixture.store.input = JSON.writeValueAsString(forged);
        var evaluation = ClusterRelationServiceTest.task(fixture.store.context, work);
        int before = fixture.prompts.size();
        var computation = fixture.computation("all", "BP,BR,CP,CI,CO,CR,IP,UA");
        assertThatThrownBy(() -> computation.compute(fixture.store.start(evaluation), evaluation, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.prompts).hasSize(before);
        var command = fixture.store.command;
        fixture.store.command = new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand("changed original", false,
                20, command.provider(), command.username(), command.workspaceContext(), command.provenance(), command.analysisScope());
        assertThatThrownBy(() -> computation.compute(fixture.store.start(task), task, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.prompts).hasSize(before); assertRestored(fixture);
    }

    @Test void cancellationAfterProviderResponseKeepsExplicitStoppedEvidenceAndClosesScopes() {
        var fixture = new Fixture();
        var cancelled = new AtomicBoolean(); fixture.afterResponse = () -> cancelled.set(true);
        var task = ClusterRelationServiceTest.task(fixture.store.context, null);
        var prepared = (ClusterRelationComputation.Preparation) fixture.computation("all", "BP,BR,CP,CI,CO,CR,IP,UA")
                .compute(fixture.store.start(task), task, cancelled::get);
        assertThat(prepared.plan().stopReason()).startsWith("CANCELLED");
        assertThat(prepared.plan().sources()).isEmpty();
        assertRestored(fixture);
    }

    private static void assertRestored(Fixture fixture) {
        assertThat(FrozenCatalogueContext.current()).isNull();
        assertThat(AnalysisRunControl.active()).isFalse();
        assertThat(fixture.providers.isMockMode()).isFalse();
        assertThat(fixture.providers.getActiveProvider()).isEqualTo(LlmProvider.OPENAI);
    }

    static final class Fixture {
        final ClusterRelationServiceTest.Store store = new ClusterRelationServiceTest.Store();
        final TaxonomyNodeRepository repository = mock(TaxonomyNodeRepository.class);
        final CatalogueOverlayService overlay = new CatalogueOverlayService(JSON, new DefaultResourceLoader(), false, "unused");
        final TaxonomyService taxonomy = new TaxonomyService(repository, null, new AppInitializationStateService());
        final LlmProviderConfig providers = new LlmProviderConfig(null);
        final List<String> prompts = new ArrayList<>();
        Runnable afterResponse = () -> { };
        final LlmService llm = new LlmService(providers, null, JSON, taxonomy, null, null, null) {
            @Override public String callLlmRaw(String prompt) {
                assertThat(providers.isMockMode()).isTrue();
                assertThat(AnalysisRunControl.currentOperationId()).isEqualTo(store.context.operationId());
                assertThat(FrozenCatalogueContext.current()).isNotNull();
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                prompts.add(prompt); String answer = answer(prompt); afterResponse.run(); return answer;
            }
        };
        final RequirementRelationSearchService relations = new RequirementRelationSearchService(taxonomy,
                new RelationCompatibilityMatrix(), llm, new AiPromptBudgetPolicy(new AiTargetCatalogService(providers)));
        Fixture() {
            ReflectionTestUtils.setField(providers, "llmProviderConfig", "OPENAI");
            ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
            var source = store.context.authority();
            var identity = new CatalogueSourceIdentity(source.repositoryId(), source.workspaceId(), source.branch(), source.sourceCommit());
            for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) {
                var nodes = new ArrayList<RootCatalogueSnapshot.Node>();
                nodes.add(node(root.code(), root.code(), null));
                if (root.code().equals("BP")) nodes.add(node("process", "BP", "BP"));
                if (root.code().equals("IP")) nodes.add(node("evidence", "IP", "IP"));
                store.snapshots.put(root.code(), JSON.writeValueAsString(new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, identity, root.code(), nodes,
                        new CatalogueOverlayService.OverlayMetadata(true, "fixture", "fixture", "v1", "digest", 1), provenance())));
            }
        }
        void productScores() {
            var ip = JSON.readValue(store.snapshots.get("IP"), RootCatalogueSnapshot.class);
            var nodes = new ArrayList<>(ip.nodes());
            nodes.add(node("family", "IP", "IP")); nodes.add(node("product", "IP", "family"));
            store.snapshots.put("IP", JSON.writeValueAsString(new RootCatalogueSnapshot(ip.schemaVersion(), ip.source(),
                    ip.rootCode(), nodes, ip.overlayMetadata(), ip.catalogueProvenance())));
            store.roots = new AnalysisResult(Map.of("process", 90, "family", 10, "product", 100), List.of());
            store.roots.setScoreSemanticsContext(Map.of("process", new AnalysisScoreSemantics.NodeContext("BP", "CATEGORY"),
                    "family", new AnalysisScoreSemantics.NodeContext("IP", "CATEGORY"),
                    "product", new AnalysisScoreSemantics.NodeContext("family", "PRODUCT"),
                    "evidence", new AnalysisScoreSemantics.NodeContext("IP", "CATEGORY")));
            store.roots.setStatus("SUCCESS");
        }
        FrozenClusterRelationComputation computation(String role, String roots) {
            return computation(store, role, roots);
        }
        FrozenClusterRelationComputation computation(ClusterRelationService.Store durableStore, String role, String roots) {
            var snapshots = new CatalogueSnapshotService(repository, overlay, new CatalogueRuntimePolicy(role, roots), mock(CatalogueSourceJournal.class));
            Supplier<AnalysisMemoryGuard> guards = () -> new AnalysisMemoryGuard(
                    new AnalysisMemoryGuard.Policy(80, 90, 1024 * 1024, 1000, 60000),
                    () -> new AnalysisMemoryGuard.Sample(1024, 100 * 1024 * 1024), System::currentTimeMillis);
            return new FrozenClusterRelationComputation(durableStore, snapshots, taxonomy, relations, providers, JSON, guards);
        }
        private static RootCatalogueSnapshot.Node node(String code, String root, String parent) {
            var node = new TaxonomyNode(); node.setCode(code); node.setTaxonomyRoot(root); node.setParentCode(parent);
            node.setNameEn(code); node.setDescriptionEn("Frozen " + code); node.setLevel(parent == null ? 0 : 1);
            return RootCatalogueSnapshot.Node.capture(node,
                    new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false);
        }
        private static String answer(String prompt) {
            JsonNode input = JSON.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
            String original = input.path("original").asString();
            if (input.has("nodes")) {
                var selections = new ArrayList<Map<String, Object>>();
                for (JsonNode node : input.get("nodes")) selections.add(Map.of("nodeId", node.get("id").asString(),
                        "outcome", "EXPLICIT", "contributions", List.of(Map.of("text", "requested evidence access", "quote", original, "condition", "")),
                        "rationale", "Exact scope", "question", ""));
                return JSON.writeValueAsString(Map.of("selections", selections));
            }
            Query query = JSON.treeToValue(input, Query.class);
            var decisions = new ArrayList<Decision>();
            for (Node node : query.candidates()) {
                Outcome outcome = !query.type().equals("CONSUMES") ? Outcome.REJECT
                        : query.phase() == Phase.VERIFY ? Outcome.VERIFIED : node.container() ? Outcome.DESCEND : Outcome.MATCH;
                decisions.add(new Decision(node.id(), outcome, "requested evidence", original, Necessity.REQUIRED,
                        "", "", "Original requires evidence", ""));
            }
            return JSON.writeValueAsString(Map.of("decisions", decisions));
        }
    }
}
