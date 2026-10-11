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
    private static final String OVERLAY_SHA256 = "a".repeat(64);

    private static CatalogueSourceJournal.Snapshot provenance() {
        var unknown = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
        return new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH,
                unknown, new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, OVERLAY_SHA256, 0), unknown);
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

    @Test void durablePreparationAndTargetWorkerBindPhysicalAttemptsToTheirOwnLogicalCalls() {
        var fixture = new Fixture();
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var context = fixture.store.context; var original = fixture.store.command;
            var command = new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand("requirement", false, 20, "CUSTOM_OPENAI", "alice", original.workspaceContext(), null,
                    new AnalysisScope(Set.of("BP"), AnalysisMode.FULL));
            var targets = new LinkedHashMap<TaxonomyShardRoot, String>();
            TaxonomyShardRoot.DEFAULT_ROOTS.forEach(root -> targets.put(root, fixture.store.snapshots.get(root.code())));
            db.store.admit(context, command, null, Map.of(TaxonomyShardRoot.of("BP"), fixture.store.snapshots.get("BP")), targets);
            fixture.store.roots.setStatus("SUCCESS");
            var root = db.task(TaxonomyShardRoot.of("BP")); db.store.start(root); db.store.accept(db.complete(root, fixture.store.roots));
            var task = (RelationAnalysisTask) db.sent.stream().filter(t -> t instanceof RelationAnalysisTask).findFirst().orElseThrow();
            String url = "https://relation.invalid/chat/completions";
            ReflectionTestUtils.setField(fixture.providers, "customLlmUrl", url); ReflectionTestUtils.setField(fixture.providers, "customLlmModel", "fixture");
            var http = new org.springframework.web.client.RestTemplate(); var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
            server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(url)).andRespond(request -> {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var calls = db.store.callDiagnostics().summaries(context).calls(); var current = calls.getLast();
                assertThat(db.store.callDiagnostics().detail(context, current.id()).attempts()).anySatisfy(a -> assertThat(a.state()).isEqualTo("STARTED"));
                var body = JSON.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                String prompt = body.path("messages").get(0).path("content").asString();
                String response = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", Fixture.answer(prompt))))));
                return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(response, org.springframework.http.MediaType.APPLICATION_JSON).createResponse(request);
            });
            AnalysisRuntimeSettings settings = (key, fallback) -> "llm.retry.max".equals(key) ? 0 : fallback;
            fixture.physicalGateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, url, "fixture", 0, http, JSON, new LlmResponseParser(JSON), settings, null, null);
            var prepared = (ClusterRelationComputation.Preparation) fixture.computation(db.store, "all", "BP,BR,CP,CI,CO,CR,IP,UA").compute(db.store.start(task), task, () -> false);
            new org.springframework.transaction.support.TransactionTemplate(db.transactions).executeWithoutResult(status -> db.store.persistRelationPlan(task, prepared.plan()));
            db.store.accept(new AnalysisMessageFactory(context, java.time.Clock.systemUTC()).completed(task, AnalysisTaskOutcome.COMPLETED, 0, null));
            var target = (RelationAnalysisTask) db.sent.stream().filter(t -> t instanceof RelationAnalysisTask relation && relation.taskId().relationWorkOrdinal().isPresent() && relation.targetRoots().equals(List.of(TaxonomyShardRoot.of("IP"))))
                    .findFirst().orElseThrow();
            fixture.computation(db.store, "worker", "IP").compute(db.store.start(target), target, () -> false);
            server.verify();
            var calls = db.store.callDiagnostics().summaries(context).calls(); assertThat(calls).hasSizeGreaterThan(1);
            var details = calls.stream().map(c -> db.store.callDiagnostics().detail(context, c.id())).toList();
            assertThat(details).anySatisfy(d -> assertThat(d.taskId()).isEqualTo(task.taskId().value()));
            assertThat(details).anySatisfy(d -> assertThat(d.taskId()).isEqualTo(target.taskId().value()));
            assertThat(details).allSatisfy(d -> assertThat(d.attempts()).singleElement().satisfies(a -> {
                assertThat(a.source()).isEqualTo("HTTP"); assertThat(a.state()).isEqualTo("COMPLETED"); assertThat(a.usage().totalTokens()).isNull();
            }));
            assertRestored(fixture); verifyNoInteractions(fixture.repository);
        }
    }

    @Test void aSavedRelationAnswerSurvivesAWorkerFactoryRestartWithoutAnotherPhysicalHttp() {
        var fixture=new Fixture();String database="jdbc:hsqldb:mem:continued-relations-"+UUID.randomUUID()+";hsqldb.tx=mvcc";
        String endpoint="https://continued-relations.invalid/chat/completions";
        ReflectionTestUtils.setField(fixture.providers,"customLlmUrl",endpoint);ReflectionTestUtils.setField(fixture.providers,"customLlmModel","fixture");
        var http=new org.springframework.web.client.RestTemplate();var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(endpoint)).andRespond(request -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();calls.incrementAndGet();
            String prompt=JSON.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString()).path("messages").get(0).path("content").asString();
            String response=JSON.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",Fixture.answer(prompt))))));
            return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(response,org.springframework.http.MediaType.APPLICATION_JSON).createResponse(request);
        });
        AnalysisRuntimeSettings settings=(key,fallback)->"llm.retry.max".equals(key)?0:fallback;
        fixture.physicalGateway=new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI,endpoint,"fixture",0,http,JSON,new LlmResponseParser(JSON),settings,null,null);
        var context=fixture.store.context;var original=fixture.store.command;
        var request=new AnalysisRequest("requirement");request.setProvider("CUSTOM_OPENAI");request.setMaxArchitectureNodes(20);
        request.setAnalysisScope(new AnalysisScope(Set.of("BP"),AnalysisMode.FULL));request.setResumable(true);request.setContinuationId(UUID.randomUUID().toString());
        var identity=DurableClusterAnalysisExecution.source(context);
        var roots=TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(root->JSON.readValue(fixture.store.snapshots.get(root.code()),RootCatalogueSnapshot.class)).toList();
        RelationAnalysisTask task;RelationSearchDistribution.Plan first;
        try(var db=new ClusterAnalysisStoreTest.Database(database,"create",sql->sql)) {
            var saved=continuationStore(db);
            var snapshots=new CatalogueSnapshotService(fixture.repository,fixture.overlay,new CatalogueRuntimePolicy("all","BP,BR,CP,CI,CO,CR,IP,UA"),mock(CatalogueSourceJournal.class));
            com.taxonomy.analysis.usecase.AnalyzeRequirementCommand command;
            try(var frozen=snapshots.bind(identity,TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(TaxonomyShardRoot::code).collect(java.util.stream.Collectors.toSet()),roots);
                var provider=fixture.providers.withRequestProvider("CUSTOM_OPENAI")) {
                String signature=com.taxonomy.analysis.recovery.AnalysisCheckpointSession.digest("relation-checkpoint-fixture");
                var claim=saved.begin(request,original.workspaceContext(),signature,fixture.taxonomy.getFullTree());
                command=new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand("requirement",false,20,"CUSTOM_OPENAI","alice",original.workspaceContext(),null,request.getAnalysisScope(),null)
                        .withContinuationBinding(new com.taxonomy.analysis.recovery.AnalysisContinuationBinding(claim.id(),claim.token(),signature,
                                fixture.llm.recoveryPolicyFingerprint("CUSTOM_OPENAI"),com.taxonomy.analysis.recovery.AnalysisCheckpointSession.digest(fixture.relations.recoveryPolicyFingerprint())));
            }
            var store=new ClusterAnalysisStore(db.em,db.transactions,JSON,db.dispatch,saved);
            var targets=new LinkedHashMap<TaxonomyShardRoot,String>();TaxonomyShardRoot.DEFAULT_ROOTS.forEach(root->targets.put(root,fixture.store.snapshots.get(root.code())));
            store.admit(context,command,null,Map.of(TaxonomyShardRoot.of("BP"),fixture.store.snapshots.get("BP")),targets);
            var root=db.task(TaxonomyShardRoot.of("BP"));store.start(root);fixture.store.roots.setStatus("SUCCESS");
            new org.springframework.transaction.support.TransactionTemplate(db.transactions).executeWithoutResult(status->store.persistResult(root,fixture.store.roots));
            store.accept(new AnalysisMessageFactory(context,java.time.Clock.systemUTC()).completed(root,AnalysisTaskOutcome.COMPLETED,80,1,null));
            task=(RelationAnalysisTask)db.sent.stream().filter(t->t instanceof RelationAnalysisTask).findFirst().orElseThrow();
            first=((ClusterRelationComputation.Preparation)fixture.computation(store,"all","BP,BR,CP,CI,CO,CR,IP,UA").compute(store.start(task),task,()->false)).plan();
            assertThat(calls).hasValue(1);assertThat(first.sources()).isNotEmpty();
            var recovery=saved.read(request.getContinuationId(),original.workspaceContext()).recovery();
            assertThat(recovery.completedCalls()).isEqualTo(1);assertThat(recovery.openQuestions()).isEmpty();
        }
        try(var db=new ClusterAnalysisStoreTest.Database(database,"validate",sql->sql)) {
            var store=new ClusterAnalysisStore(db.em,db.transactions,JSON,db.dispatch,continuationStore(db));
            var replay=((ClusterRelationComputation.Preparation)fixture.computation(store,"all","BP,BR,CP,CI,CO,CR,IP,UA").compute(store.start(task),task,()->false)).plan();
            assertThat(replay.sources()).isEqualTo(first.sources());assertThat(calls).hasValue(1);assertThat(fixture.prompts).hasSize(1);
            assertThat(com.taxonomy.analysis.recovery.AnalysisCheckpointSession.active()).isFalse();assertRestored(fixture);
        }
        server.verify();verifyNoInteractions(fixture.repository);
    }
    private static com.taxonomy.analysis.recovery.AnalysisContinuationStore continuationStore(ClusterAnalysisStoreTest.Database db) {
        var proxy=new org.springframework.aop.framework.ProxyFactory(new com.taxonomy.analysis.recovery.AnalysisContinuationStore(db.em,JSON));proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(db.transactions,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (com.taxonomy.analysis.recovery.AnalysisContinuationStore)proxy.getProxy();
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
        OpenAiCompatibleGateway physicalGateway;
        final LlmService llm = new LlmService(providers, null, JSON, taxonomy, mock(PromptTemplateService.class), null, null) {
            @Override public String callLlmRaw(String prompt) {
                if (physicalGateway != null) {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    prompts.add(prompt);
                    return physicalGateway.extractResponseText(physicalGateway.sendHttpRequest(prompt, "fixture"));
                }
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
                        new CatalogueOverlayService.OverlayMetadata(true, "fixture", "fixture", "v1", OVERLAY_SHA256, 1), provenance())));
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
