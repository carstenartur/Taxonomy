package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dispatch.*;
import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.architecture.pipeline.*;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.*;
import com.taxonomy.export.DiagramSelectionConfig;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production handlers, durable database records and persistent TCP delivery; no provider HTTP. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ArtemisProductionExecutionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEXT = "Provide a communication application that reads treatment records.";
    private static final Set<String> ALL_ROOTS = TaxonomyShardRoot.DEFAULT_ROOTS.stream()
            .map(TaxonomyShardRoot::code).collect(Collectors.toSet());
    private static final String SHARED = "same-frozen-node";
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final AnalysisDestinations destinations = new AnalysisDestinations(AnalysisDestinations.DEFAULT_PREFIX);
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();
    @TempDir Path data;
    private EmbeddedActiveMQ broker;
    private int port;

    @BeforeEach void startBroker() throws Exception {
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var configuration = new ConfigurationImpl();
        configuration.setPersistenceEnabled(true); configuration.setSecurityEnabled(false);
        configuration.setJournalType(JournalType.NIO);
        configuration.setJournalDirectory(data.resolve("journal").toString());
        configuration.setBindingsDirectory(data.resolve("bindings").toString());
        configuration.setLargeMessagesDirectory(data.resolve("large").toString());
        configuration.setPagingDirectory(data.resolve("paging").toString());
        configuration.addAcceptorConfiguration("tcp", "tcp://127.0.0.1:" + port);
        configuration.addAddressSetting("#", new AddressSettings().setMaxDeliveryAttempts(3).setRedeliveryDelay(0)
                .setDeadLetterAddress(SimpleString.of(destinations.deadLetter()))
                .setExpiryAddress(SimpleString.of(destinations.expiry())));
        for (String name : List.of(destinations.deadLetter(), destinations.expiry())) {
            configuration.addQueueConfiguration(QueueConfiguration.of(name).setRoutingType(RoutingType.ANYCAST));
        }
        broker = new EmbeddedActiveMQ(); broker.setConfiguration(configuration); broker.start();
    }

    @AfterEach void stop() throws Exception {
        while (!resources.isEmpty()) resources.pop().close();
        if (broker != null) broker.stop();
    }

    @Test void concurrentFrozenRootWorkersMatchLocalScoringAndPublishArchitectureBeforeTerminal() throws Exception {
        var first = fixture("capability-source", "CP", false);
        var second = fixture("product-source", "IP", false);
        var fixtures = List.of(first, second);
        var db = database();
        var engine = new Engine(Map.of());
        var simultaneousBindings = new CountDownLatch(2);
        var seen = new ConcurrentHashMap<String, String>();
        doAnswer(invocation -> {
            var frozen = Objects.requireNonNull(FrozenCatalogueContext.current());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(engine.providers.isMockMode()).isTrue();
            seen.put(frozen.source().repositoryId(), frozen.requireNode(SHARED).nameEn());
            simultaneousBindings.countDown();
            assertThat(simultaneousBindings.await(10, TimeUnit.SECONDS)).as("both root bindings overlap").isTrue();
            return invocation.callRealMethod();
        }).when(engine.taxonomy).getRootNodes();
        var observed = new ClusterAnalysisSignals();
        coordinator(db, observed, finalizer(db, engine));
        for (var fixture : fixtures) {
            var computation = new FrozenClusterAnalysisComputation(db.store, engine.snapshots("worker", fixture.root),
                    engine.llm, engine.providers, ArtemisProductionExecutionTest::guard, JSON);
            var service = new ClusterAnalysisService(db.store, computation, new ClusterAnalysisSignals());
            worker(db, new AnalysisTaskHandlers(service::prepare, null), fixture.root, true, false);
        }
        var execution = execution(db, engine, fixtures, observed);
        var observations = new ConcurrentHashMap<String, List<ClusterAnalysisStore.Snapshot>>();
        var executor = Executors.newFixedThreadPool(2);
        var results = new ArrayList<Future<AnalysisResult>>();
        try {
            for (var fixture : fixtures) {
                var progress = new CopyOnWriteArrayList<ClusterAnalysisStore.Snapshot>();
                observations.put(fixture.context.operationId(), progress);
                results.add(executor.submit(() -> execution.execute(fixture.context, fixture.command,
                        fixture.view, snapshot -> {
                            if (snapshot.state().terminal()) assertThat(snapshot.result().getArchitectureView()).isNotNull();
                            progress.add(snapshot);
                        })));
            }
            long localCalls = 0;
            for (int i = 0; i < fixtures.size(); i++) {
                var fixture = fixtures.get(i);
                var result = results.get(i).get(30, TimeUnit.SECONDS);
                var local = new Engine(Map.of());
                var expected = localScore(local, fixture);
                assertEquivalentScoring(result, expected);
                assertThat(result.getStatus()).isEqualTo("SUCCESS");
                assertThat(result.getProvider()).isEqualTo("Mock");
                assertThat(result.getViewContext()).isEqualTo(fixture.view);
                assertThat(result.getArchitectureView().getIncludedElements()).anySatisfy(element -> {
                    assertThat(element.getNodeCode()).isEqualTo(SHARED);
                    assertThat(element.getTitle()).isEqualTo(fixture.label + " child");
                });
                assertThat(db.store.command(fixture.context).provider()).isEqualTo("MOCK");
                var saved = db.store.snapshot(fixture.context);
                assertThat(saved.state()).isEqualTo(ClusterAnalysisState.COMPLETED);
                assertThat(JSON.writeValueAsString(saved.result().getArchitectureView()))
                        .isEqualTo(JSON.writeValueAsString(result.getArchitectureView()));
                assertThat(saved.tasks()).singleElement().satisfies(task -> {
                    assertThat(task.root()).isEqualTo(fixture.root);
                    assertThat(task.type()).isEqualTo("SUBTAXONOMY_ANALYSIS");
                });
                assertThat(observations.get(fixture.context.operationId())).anyMatch(snapshot -> snapshot.state().terminal());
                assertThat(db.ledger.find(db.sent.stream().filter(task ->
                        task.envelope().operationId().equals(fixture.context.operationId())).findFirst().orElseThrow())).isPresent();
                localCalls += (Long) local.llm.getDiagnostics().get("totalCalls");
                local.assertNoCurrentReadsOrExternalCalls();
            }
            assertThat(seen).containsExactlyInAnyOrderEntriesOf(Map.of(first.source.repositoryId(), first.label + " child",
                    second.source.repositoryId(), second.label + " child"));
            assertThat(engine.llm.getDiagnostics().get("totalCalls")).isEqualTo(localCalls);
            assertThat(localCalls).isEqualTo(4L);
            assertThat(engine.llm.getDiagnostics().get("failedCalls")).isEqualTo(0L);
            // MOCK's detailed calls intentionally send no prompt. Both paths must remain that way.
            verifyNoInteractions(engine.templates);
            engine.assertNoCurrentReadsOrExternalCalls();
        } finally {
            results.forEach(result -> result.cancel(true)); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void productionRelationWorkersMatchFrozenLocalPromptsEvidenceAndCallCount() throws Exception {
        var fixture = fixture("relation-source", "UA", true);
        var db = database();
        var engine = new Engine(Map.of("UA", 80, "UA-1574", 70));
        var prompts = traceRelationPrompts(engine);
        var observed = new ClusterAnalysisSignals();
        coordinator(db, observed, finalizer(db, engine));
        var roots = new FrozenClusterAnalysisComputation(db.store, engine.snapshots("worker", "UA"), engine.llm,
                engine.providers, ArtemisProductionExecutionTest::guard, JSON);
        worker(db, new AnalysisTaskHandlers(new ClusterAnalysisService(db.store, roots, new ClusterAnalysisSignals())::prepare,
                null), "UA", true, false);
        var relations = engine.relations();
        for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) {
            var computation = new FrozenClusterRelationComputation(db.store, engine.snapshots("worker", root.code()),
                    engine.taxonomy, relations, engine.providers, JSON, ArtemisProductionExecutionTest::guard);
            var service = new ClusterRelationService(db.store, computation, new ClusterAnalysisSignals());
            worker(db, new AnalysisTaskHandlers(null, service::prepare), root.code(), true, false);
        }
        var preparation = new FrozenClusterRelationComputation(db.store, engine.snapshots("all", String.join(",", ALL_ROOTS)),
                engine.taxonomy, relations, engine.providers, JSON, ArtemisProductionExecutionTest::guard);
        worker(db, new AnalysisTaskHandlers(null,
                new ClusterRelationService(db.store, preparation, new ClusterAnalysisSignals())::prepare), "UA", false, true);
        var execution = execution(db, engine, List.of(fixture), observed);
        var executor = Executors.newSingleThreadExecutor();
        var resultFuture = executor.submit(() -> execution.execute(fixture.context, fixture.command, fixture.view,
                snapshot -> { if (snapshot.state().terminal()) assertThat(snapshot.result().getArchitectureView()).isNotNull(); }));
        try {
            var result = resultFuture.get(45, TimeUnit.SECONDS);
            var local = new Engine(Map.of("UA", 80, "UA-1574", 70));
            var localPrompts = traceRelationPrompts(local);
            var expected = localScore(local, fixture);
            RelationSearchReport expectedRelations;
            try (var frozen = local.snapshots("all", String.join(",", ALL_ROOTS)).bind(fixture.source, ALL_ROOTS, fixture.roots);
                 var provider = local.providers.withRequestProvider("MOCK")) {
                expectedRelations = local.relations().search(TEXT, expected.getScores());
            }
            assertEquivalentScoring(result, expected);
            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getRelationSearchReport().isSearchExhausted()).isTrue();
            assertThat(result.getRelationSearchReport().result().edges()).hasSize(2)
                    .containsExactlyInAnyOrderElementsOf(expectedRelations.result().edges());
            assertThat(result.getRelationSearchReport().sources()).isEqualTo(expectedRelations.sources());
            assertThat(result.getRelationSearchReport().totalCalls()).isEqualTo(expectedRelations.totalCalls());
            assertThat(prompts).isNotEmpty().containsExactlyInAnyOrderElementsOf(localPrompts);
            assertThat(engine.llm.getDiagnostics().get("totalCalls")).isEqualTo(local.llm.getDiagnostics().get("totalCalls"));
            assertThat(result.getArchitectureView().getIncludedRelationships()).hasSize(2);
            assertThat(db.store.snapshot(fixture.context).result().getArchitectureView().getIncludedRelationships()).hasSize(2);
            assertThat(db.sent).anyMatch(task -> task instanceof RelationAnalysisTask relation && relation.taskId().relationWorkOrdinal().isPresent());
            for (var task : db.sent) assertThat(db.ledger.find(task)).as(task.taskId().value()).isPresent();
            engine.assertNoCurrentReadsOrExternalCalls(); local.assertNoCurrentReadsOrExternalCalls();
        } finally {
            resultFuture.cancel(true); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertEquivalentScoring(AnalysisResult actual, AnalysisResult expected) {
        assertThat(actual.getRawScores()).isEqualTo(expected.getRawScores());
        assertThat(actual.getEffectiveScores()).isEqualTo(expected.getEffectiveScores());
        assertThat(actual.getScoreDetails()).isEqualTo(expected.getScoreDetails());
        assertThat(actual.getScoreSemanticsFingerprintSha256()).isEqualTo(expected.getScoreSemanticsFingerprintSha256());
        assertThat(actual.getReasons()).isEqualTo(expected.getReasons());
        assertThat(JSON.writeValueAsString(actual.getTree())).isEqualTo(JSON.writeValueAsString(expected.getTree()));
    }

    private static AnalysisResult localScore(Engine engine, Fixture fixture) {
        try (var frozen = engine.snapshots("worker", fixture.root).bind(fixture.source, Set.of(fixture.root),
                fixture.roots.stream().filter(root -> root.rootCode().equals(fixture.root)).toList());
             var provider = engine.providers.withRequestProvider("MOCK")) {
            return engine.llm.analyzeWithBudget(TEXT, new AnalysisScope(Set.of(fixture.root), AnalysisMode.TAXONOMIES_ONLY));
        }
    }

    private static List<String> traceRelationPrompts(Engine engine) {
        var prompts = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            assertThat(engine.providers.isMockMode()).isTrue();
            assertThat(FrozenCatalogueContext.current()).isNotNull();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            prompts.add(invocation.getArgument(0)); return invocation.callRealMethod();
        }).when(engine.llm).callLlmRaw(anyString());
        return prompts;
    }

    private DurableClusterAnalysisExecution execution(Database db, Engine engine, List<Fixture> fixtures,
                                                       ClusterAnalysisSignals signals) {
        // Admission's scalar fixtures are already immutable; no live repository is consulted.
        var capture = mock(CatalogueSnapshotService.class);
        when(capture.captureRoots(any(), anySet())).thenAnswer(invocation -> {
            CatalogueSourceIdentity source = invocation.getArgument(0); Set<String> wanted = invocation.getArgument(1);
            return fixtures.stream().filter(fixture -> fixture.source.equals(source)).findFirst().orElseThrow()
                    .roots.stream().filter(root -> wanted.contains(root.rootCode())).toList();
        });
        var views = mock(WorkspaceViewContextReadPort.class);
        when(views.resolveWorkspaceBranch(anyString())).thenReturn("draft");
        when(views.getViewContext(anyString(), eq("draft"), any())).thenAnswer(invocation -> {
            WorkspaceContext context = invocation.getArgument(2);
            return fixtures.stream().filter(fixture -> fixture.source.repositoryId().equals(context.repositoryId()))
                    .findFirst().orElseThrow().view;
        });
        return new DurableClusterAnalysisExecution(db.store, capture, signals, engine.providers, JSON, views);
    }

    private FrozenClusterAnalysisFinalizer finalizer(Database db, Engine engine) {
        var projection = new RequirementArchitectureViewService(new ArchitectureViewPipeline(
                new ArchitecturePipelineStepRegistry(List.of()), new ArchitecturePipelineInvariantValidator()));
        return new FrozenClusterAnalysisFinalizer(db.store, engine.snapshots("all", String.join(",", ALL_ROOTS)), projection,
                () -> DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"), JSON);
    }

    private record Database(ClusterAnalysisStore store, JpaAnalysisTaskCompletionStore ledger, List<AnalysisTaskMessage> sent) { }
    private Database database() throws Exception {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:hsqldb:mem:production-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(ClusterAnalysisRun.class.getName(), ClusterAnalysisWork.class.getName(),
                ClusterAnalysisInput.class.getName(), ClusterAnalysisEvent.class.getName(), AnalysisDispatchIntent.class.getName(),
                AnalysisTaskCompletionRecord.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false"));
        factory.afterPropertiesSet(); resources.push(factory::destroy);
        var transactions = new JpaTransactionManager(factory.getObject());
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var sent = new CopyOnWriteArrayList<AnalysisTaskMessage>();
        var publisher = new ArtemisAnalysisTaskPublisher(connect(), destinations, codec); resources.push(publisher::close);
        var dispatch = new AnalysisDispatchService(new AnalysisDispatchStore(entityManager, transactions), task -> {
            sent.add(task); publisher.publish(task);
        }, 100, 1000);
        var store = new ClusterAnalysisStore(entityManager, transactions, JSON, dispatch);
        store.eventPublisher(publisher::progress);
        return new Database(store, new JpaAnalysisTaskCompletionStore(entityManager, transactions), sent);
    }

    private ArtemisAnalysisConnection connect() throws Exception {
        var settings = new ArtemisAnalysisSettings("tcp://127.0.0.1:" + port, false, 100, 2000);
        var connection = new ArtemisAnalysisConnection(ArtemisAnalysisTransportConfiguration.connectionFactory(settings, null, null));
        resources.push(connection); var ready = new CountDownLatch(1);
        connection.addListener(reconnected -> ready.countDown()); connection.start();
        assertThat(ready.await(10, TimeUnit.SECONDS)).as("TCP broker connection").isTrue(); return connection;
    }

    private void coordinator(Database db, ClusterAnalysisSignals signals, FrozenClusterAnalysisFinalizer finalizer) throws Exception {
        var coordinator = new ArtemisClusterCoordinator(connect(), destinations, codec, db.store, db.ledger, signals, true);
        coordinator.finalizer(finalizer); resources.push(coordinator); coordinator.start();
    }

    private void worker(Database db, AnalysisTaskHandlers handlers, String roots, boolean rootWork, boolean preparation) throws Exception {
        var worker = new ArtemisAnalysisWorker(connect(), destinations, AnalysisWorkerShards.parse(roots), 1, handlers, db.ledger, codec);
        resources.push(worker); worker.start(rootWork, preparation);
    }

    private static final class Engine {
        final TaxonomyNodeRepository repository = mock(TaxonomyNodeRepository.class);
        final CatalogueOverlayService overlay = new CatalogueOverlayService(JSON, new DefaultResourceLoader(), false, "unused");
        final TaxonomyService taxonomy = spy(new TaxonomyService(repository, null, new AppInitializationStateService()));
        final LocalEmbeddingService embedding = mock(LocalEmbeddingService.class);
        final LlmProviderConfig providers = new LlmProviderConfig(embedding);
        final LlmGatewayRegistry gateways = mock(LlmGatewayRegistry.class);
        final PromptTemplateService templates = mock(PromptTemplateService.class);
        final LlmService llm;
        Engine(Map<String, Integer> mockScores) throws java.io.IOException {
            ReflectionTestUtils.setField(providers, "llmProviderConfig", "OPENAI");
            ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
            ReflectionTestUtils.setField(taxonomy, "catalogueRuntimePolicy", new CatalogueRuntimePolicy("worker", String.join(",", ALL_ROOTS)));
            var saved = mock(SavedAnalysisService.class);
            if (!mockScores.isEmpty()) {
                var fixture = new SavedAnalysis(); fixture.setScores(mockScores);
                when(saved.loadFromClasspath(anyString())).thenReturn(fixture);
            }
            llm = spy(new LlmService(providers, gateways, JSON, taxonomy, templates, embedding, saved));
            ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
        }
        CatalogueSnapshotService snapshots(String role, String roots) {
            return new CatalogueSnapshotService(repository, overlay, new CatalogueRuntimePolicy(role, roots), mock(CatalogueSourceJournal.class));
        }
        RequirementRelationSearchService relations() {
            var service = new RequirementRelationSearchService(taxonomy, new RelationCompatibilityMatrix(), llm,
                    new AiPromptBudgetPolicy(new AiTargetCatalogService(providers)));
            ReflectionTestUtils.setField(service, "maxCalls", 200);
            ReflectionTestUtils.setField(service, "maxWorkItems", 128);
            return service;
        }
        void assertNoCurrentReadsOrExternalCalls() {
            assertThat(FrozenCatalogueContext.current()).isNull(); assertThat(AnalysisRunControl.active()).isFalse();
            assertThat(providers.isMockMode()).isFalse(); assertThat(providers.getActiveProvider()).isEqualTo(LlmProvider.OPENAI);
            verifyNoInteractions(repository, gateways, embedding);
        }
    }

    private record Fixture(String label, String root, CatalogueSourceIdentity source, AnalysisOperationContext context,
                           AnalyzeRequirementCommand command, ViewContext view, List<RootCatalogueSnapshot> roots) { }
    private static Fixture fixture(String label, String selected, boolean relations) {
        var source = new CatalogueSourceIdentity("repo-" + label, "ws-" + label, "draft", "commit-" + label);
        var context = new AnalysisOperationContext("op-" + label, new AnalysisSourceAuthority(source.repositoryId(), source.workspaceId(),
                source.branch(), source.sourceCommit()), RequirementReference.adHoc(TEXT), null);
        var command = new AnalyzeRequirementCommand(TEXT, true, 12, "MOCK", "alice",
                new WorkspaceContext("alice", source.workspaceId(), "draft", source.repositoryId()), null,
                new AnalysisScope(Set.of(selected), relations ? AnalysisMode.FULL : AnalysisMode.TAXONOMIES_ONLY));
        var view = new ViewContext(source.sourceCommit(), "draft", Instant.EPOCH, false, false, false);
        var unknown = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
        var provenance = new CatalogueSourceJournal.Snapshot(UUID.nameUUIDFromBytes(label.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                Instant.EPOCH, unknown, new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0), unknown);
        var roots = new ArrayList<RootCatalogueSnapshot>();
        for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) {
            var nodes = new ArrayList<RootCatalogueSnapshot.Node>();
            nodes.add(node(root.code(), root.code(), null, label + " " + root.code(), 0, "CATEGORY"));
            if (!relations && root.code().equals(selected)) nodes.add(node(SHARED, selected, selected, label + " child", 1,
                    selected.equals("IP") ? "PRODUCT" : "CATEGORY"));
            if (relations && root.code().equals("UA")) nodes.add(node("UA-1574", "UA", "UA", "Communication application", 1, "CATEGORY"));
            List<String> path = relations ? switch (root.code()) {
                case "CR" -> List.of("CR-1000", "CR-1011", "CR-1014", "CR-1005");
                case "IP" -> List.of("IP-1000", "IP-1049", "IP-1039", "IP-1136");
                default -> List.of();
            } : List.of();
            String parent = root.code(); int level = 1;
            for (String code : path) { nodes.add(node(code, root.code(), parent, "Frozen " + code, level++, "CATEGORY")); parent = code; }
            roots.add(new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, root.code(), nodes,
                    new CatalogueOverlayService.OverlayMetadata(false, "fixture", null, null, null, 0), provenance));
        }
        return new Fixture(label, selected, source, context, command, view, List.copyOf(roots));
    }

    private static RootCatalogueSnapshot.Node node(String code, String root, String parent, String title, int level, String role) {
        var node = new TaxonomyNode(); node.setCode(code); node.setTaxonomyRoot(root); node.setParentCode(parent);
        node.setNameEn(title); node.setDescriptionEn("Description of " + title); node.setLevel(level);
        return RootCatalogueSnapshot.Node.capture(node, new CatalogueOverlayService.NodeMetadata(role, List.of(), 1, false, null), false);
    }

    private static AnalysisMemoryGuard guard() {
        return new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 16 * 1024 * 1024, 5000, 120000),
                () -> new AnalysisMemoryGuard.Sample(1, 1024L * 1024 * 1024), System::currentTimeMillis);
    }
}
