package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.controller.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.architecture.service.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.*;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import com.taxonomy.relations.service.*;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production HTTP, continuation, SQL, use-case, traversal and gateway wiring with deterministic provider replies. */
class RelationsExtensionHttpTest {
    @Test void normalNodeOnlyHttpRequestExtendsRelationsWithoutAnotherScoringExchange() throws Exception {
        var mapper = new ObjectMapper();
        var bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(new DriverManagerDataSource("jdbc:hsqldb:mem:relations-http-" + UUID.randomUUID(), "sa", ""));
        bean.setManagedTypes(PersistenceManagedTypes.of(AnalysisContinuationRun.class.getName(), AnalysisQuestionCheckpoint.class.getName()));
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false")); bean.afterPropertiesSet();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var em = SharedEntityManagerCreator.createSharedEntityManager(bean.getObject());
            var transactions = new JpaTransactionManager(bean.getObject());
            var proxy = new ProxyFactory(new AnalysisContinuationStore(em, mapper)); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            var store = (AnalysisContinuationStore) proxy.getProxy();
            var nodes = mock(TaxonomyNodeRepository.class);
            var root = new TaxonomyNode(); root.setCode("CP"); root.setTaxonomyRoot("CP"); root.setNameEn("Capabilities"); root.setLevel(0);
            var child = new TaxonomyNode(); child.setCode("CP-1"); child.setTaxonomyRoot("CP"); child.setNameEn("Evidence capability");
            child.setParentCode("CP"); child.setLevel(1); root.getChildren().add(child);
            when(nodes.findByParentIsNullOrderByCodeAsc()).thenReturn(List.of(root));
            when(nodes.findByParentCodeOrderByNameEnAsc("CP")).thenReturn(List.of(child));
            when(nodes.findByParentCodeOrderByNameEnAsc("CP-1")).thenReturn(List.of());
            when(nodes.findByTaxonomyRootOrderByLevelAscNameEnAsc("CP")).thenReturn(List.of(root, child));
            when(nodes.findByCode("CP")).thenReturn(Optional.of(root)); when(nodes.findByCode("CP-1")).thenReturn(Optional.of(child));
            var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused");
            var initialized = new AppInitializationStateService(); initialized.update(AppInitializationStateService.State.READY, "fixture ready");
            var catalogue = new TaxonomyService(nodes, mock(TaxonomyRelationRepository.class), initialized);
            ReflectionTestUtils.setField(catalogue, "catalogueOverlayService", overlay);
            var journal = mock(CatalogueSourceJournal.class);
            var unknown = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
            when(journal.lockForCapture()).thenReturn(new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH,
                    unknown, new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0), unknown));
            var snapshots = new CatalogueSnapshotService(nodes, overlay, CatalogueRuntimePolicy.fullCatalogue(), journal);
            var embedding = mock(LocalEmbeddingService.class); var providers = new LlmProviderConfig(embedding);
            String endpoint = "https://fixture.invalid/chat/completions";
            ReflectionTestUtils.setField(providers, "customLlmUrl", endpoint); ReflectionTestUtils.setField(providers, "customLlmModel", "fixture");
            ReflectionTestUtils.setField(providers, "llmProviderConfig", "CUSTOM_OPENAI");
            var http = new RestTemplate(); var gatewayServer = MockRestServiceServer.bindTo(http).build();
            var scoringCalls = new AtomicInteger(); var relationCalls = new AtomicInteger();
            gatewayServer.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), request -> { }).andRespond(request -> {
                String sent = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                String answer;
                if (sent.contains("SCORE:")) {
                    scoringCalls.incrementAndGet(); String code = sent.contains("SCORE:ROOT") ? "CP" : "CP-1";
                    answer = mapper.writeValueAsString(Map.of(code, Map.of("score",71.125,"reason","Original explanation.\nRetain verbatim.")));
                } else {
                    relationCalls.incrementAndGet();
                    answer = "{\"selections\":[{\"nodeId\":\"CP-1\",\"outcome\":\"REJECT\",\"contributions\":[],\"rationale\":\"No explicit additional contribution in this fixture\",\"question\":\"\"}]}";
                }
                return withSuccess(mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",answer))))), MediaType.APPLICATION_JSON).createResponse(request);
            });
            AnalysisRuntimeSettings settings = (key, fallback) -> "llm.retry.max".equals(key) ? 0 : fallback;
            var gateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, endpoint, "fixture", 0, http, mapper, new LlmResponseParser(mapper), settings, null, null);
            var gateways = mock(LlmGatewayRegistry.class); when(gateways.getGatewayById(LlmProvider.CUSTOM_OPENAI.id())).thenReturn(gateway);
            var prompts = mock(PromptTemplateService.class);
            when(prompts.renderRootPrompt(anyString(), anyString(), anyString())).thenReturn("SCORE:ROOT");
            when(prompts.renderPrompt(anyString(), anyString(), anyString(), anyDouble(), anyString())).thenReturn("SCORE:CHILD");
            var llm = new LlmService(providers, gateways, mapper, catalogue, prompts, embedding, mock(SavedAnalysisService.class));
            ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
            var budget = mock(AiPromptBudgetPolicy.class); var rules = new RelationCompatibilityMatrix();
            var relations = new RequirementRelationSearchService(catalogue, rules, llm, budget);
            var views = mock(RepositoryStateService.class); var scope = new WorkspaceContext("alice", "ws", "draft", "repo");
            when(views.getViewContext("alice", "draft", scope)).thenReturn(new ViewContext("commit", "draft", null, false, false, false));
            var workspace = new UserWorkspace(); workspace.setUsername("alice"); var workspaces = mock(WorkspaceManager.class);
            when(workspaces.getWorkspaceById("ws")).thenReturn(workspace);
            var continuation = new AnalysisContinuationService(store, catalogue, llm, workspaces, mapper);
            ReflectionTestUtils.setField(continuation,"providers",providers);ReflectionTestUtils.setField(continuation,"views",views);ReflectionTestUtils.setField(continuation,"relations",relations);
            var metadata = mock(ArchitectureReportMetadataPort.class);
            when(metadata.resolve()).thenReturn(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
            var architecture = mock(RequirementArchitectureViewService.class);
            when(architecture.buildFromEvidence(any(), any(), anyInt(), any())).thenReturn(new RequirementArchitectureView());
            var usecase = new AnalyzeRequirementUseCase(llm,budget,architecture,new AnalysisRelationGenerator(rules,nodes),mock(HypothesisService.class),views,metadata);
            ReflectionTestUtils.setField(usecase,"requirementRelationSearch",relations);ReflectionTestUtils.setField(usecase,"catalogueSnapshots",snapshots);
            var resolver = mock(WorkspaceResolver.class);when(resolver.resolveCurrentUsername()).thenReturn("alice");when(resolver.resolveCurrentContext()).thenReturn(scope);
            var controller = new AnalysisApiController(catalogue,executor,mapper,usecase,mock(StreamRequirementAnalysisUseCase.class),mock(AnalyzeNodeChildrenUseCase.class),mock(JustifyLeafUseCase.class),new AnalysisSseEventMapper(),views,resolver,mock(MessageSource.class));
            ReflectionTestUtils.setField(controller,"continuationService",continuation);ReflectionTestUtils.setField(controller,"followUpProviders",new AnalysisFollowUpProviders(providers));
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            String sourceId = UUID.randomUUID().toString();
            String sourceBody = mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                    "businessText","Read evidence","provider","CUSTOM_OPENAI","resumable",true,"continuationId",sourceId,"maxArchitectureNodes",23,
                    "analysisScope",Map.of("taxonomyRoots",List.of("CP"),"mode","TAXONOMIES_ONLY")))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var source = mapper.readValue(sourceBody,AnalysisResult.class);
            assertThat(source.getStatus()).isEqualTo("SUCCESS");assertThat(scoringCalls).hasValue(2);assertThat(relationCalls).hasValue(0);
            ReflectionTestUtils.setField(providers,"llmProviderConfig","GEMINI");ReflectionTestUtils.setField(providers,"geminiApiKey","fixture-unused-key");
            String targetId = UUID.randomUUID().toString();
            String extension = mapper.writeValueAsString(Map.of("resumable",true,"continuationId",targetId,"relationSourceContinuationId",sourceId,
                    "relationSourceVersion",source.getRecovery().version(),"provider","GEMINI","maxArchitectureNodes",999));
            var response = mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content(extension)).andExpect(status().isOk()).andReturn().getResponse();
            var extended = mapper.readValue(response.getContentAsString(),AnalysisResult.class);
            assertThat(extended.getRawScores()).containsExactlyEntriesOf(source.getRawScores());assertThat(extended.getScores()).containsExactlyEntriesOf(source.getScores());
            assertThat(extended.getReasons()).containsExactlyEntriesOf(source.getReasons());
            assertThat(extended.getScoreSemanticsFingerprintSha256()).isEqualTo(source.getScoreSemanticsFingerprintSha256());
            assertThat(extended.getAnalysisProviderId()).isEqualTo("CUSTOM_OPENAI");assertThat(extended.getRelationSearchReport()).isNotNull();
            assertThat(scoringCalls).hasValue(2);assertThat(relationCalls).hasValue(1);
            mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content(extension)).andExpect(status().isOk());
            assertThat(scoringCalls).hasValue(2);assertThat(relationCalls).hasValue(1);
            assertThat(store.read(sourceId,scope).recovery().version()).isEqualTo(source.getRecovery().version());
            gatewayServer.verify();
        } finally { bean.destroy(); }
    }
}
