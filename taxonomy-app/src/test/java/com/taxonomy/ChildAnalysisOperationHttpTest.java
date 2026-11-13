package com.taxonomy;

import com.taxonomy.analysis.controller.AnalysisApiController;
import com.taxonomy.analysis.operation.AnalysisNodeReceipts;
import com.taxonomy.analysis.service.AnalysisFollowUpProviders;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.composition.analysis.*;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChildAnalysisOperationHttpTest {
    static final String NODE = "/api/analyze-node?parentCode=CP&businessText=Need&parentScore=100";
    static final String KEY = "X-Analysis-Operation-Id";

    @Test void followsOriginalReceiptAcrossReplicaThenReplaysNativeLeafOffline() throws Exception {
        try (var f = new Fixture()) {
            var first = f.mvc().perform(get(NODE)).andExpect(status().isOk()).andExpect(jsonPath("$.prompt").value(""))
                    .andExpect(jsonPath("$.rawResponse").value("")).andExpect(jsonPath("$.parentBinding").doesNotExist())
                    .andReturn().getResponse();
            String id = first.getHeader(KEY);
            assertNotNull(id);
            assertEquals(id, f.json.readTree(first.getContentAsString()).path("analysisOperationId").asString());
            when(f.providers.getActiveProviderId()).thenReturn(new ProviderId("OPENAI"));
            var replica = f.mvc();
            replica.perform(get(NODE)).andExpect(status().isOk()).andExpect(header().string(KEY,id))
                    .andExpect(content().json(first.getContentAsString()));
            String body = f.json.writeValueAsString(Map.of("operationId", id, "nodeCode","CP-1","businessText","Need"));
            when(f.leaves.require(any(), anyList())).thenReturn(new JustifyLeafResult("CP-1","Original explanation"));
            var leaf = replica.perform(post("/api/justify-leaf").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.justification").value("Original explanation"))
                    .andReturn().getResponse();
            assertNotEquals(id,leaf.getHeader(KEY));
            when(f.llm.interactiveDispatchAvailable(false)).thenReturn(false);
            f.mvc().perform(post("/api/justify-leaf").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(header().string(KEY,leaf.getHeader(KEY)))
                    .andExpect(content().json(leaf.getContentAsString()));
            verify(f.leaves).require(any(),anyList());
            verify(f.nodes).analyze(any(),anyList());
            replica.perform(get(leaf.getHeader("Content-Location"))).andExpect(status().isOk()).andExpect(content().json(leaf.getContentAsString()));
            when(f.llm.interactiveSource(anyList(),anyBoolean())).thenReturn("c".repeat(64));
            replica.perform(get(leaf.getHeader("Content-Location"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_SOURCE"));
            f.actor.set(PrincipalId.create());
            replica.perform(post("/api/justify-leaf").contentType("application/json").content(body)).andExpect(status().isNotFound());
            replica.perform(get("/api/analysis-adapters/"+UUID.randomUUID())).andExpect(status().isNotFound());
        }
    }

    @Test void physicalChildFailuresStayFailedDuringReadbackWithoutPublishingProviderDiagnostics() throws Exception {
        try (var f = new Fixture()) {
            var mvc = f.mvc();
            String sensitive = "private provider payload";
            when(f.nodes.analyze(any(), anyList())).thenReturn(new AnalyzeNodeChildrenResult(
                    Map.of(), Map.of(), sensitive, sensitive, "OPENAI", 1, sensitive));
            when(f.leaves.require(any(), anyList())).thenThrow(new IllegalStateException(sensitive));
            var leaf = post("/api/justify-leaf").contentType("application/json")
                    .content(f.json.writeValueAsString(Map.of("nodeCode", "CP-1", "businessText", "Need")));
            for (var command : java.util.List.of(get(NODE), leaf)) {
                var failure = mvc.perform(command).andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.state").value("FAILED")).andReturn().getResponse();
                assertFalse(failure.getContentAsString().contains(sensitive));
                mvc.perform(get(failure.getHeader("Content-Location"))).andExpect(status().isUnprocessableEntity())
                        .andExpect(content().json(failure.getContentAsString()));
            }
            verify(f.nodes).analyze(any(), anyList());
            verify(f.leaves).require(any(), anyList());
        }
    }

    @Test void duplicateNodeReceivesCanonicalPendingStateAndCancellationFencesPhysicalCompletion() throws Exception {
        try (var f = new Fixture(); var executor = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1); var finished = new CountDownLatch(1);
            when(f.nodes.analyze(any(),anyList())).thenAnswer(call -> {
                entered.countDown(); assertTrue(finished.await(15,TimeUnit.SECONDS)); return f.node();
            });
            var mvc = f.mvc();
            var running = executor.submit(() -> {
                f.login();
                try { return mvc.perform(get(NODE).header("Idempotency-Key","rapid")).andReturn().getResponse().getStatus(); }
                finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var accepted = mvc.perform(get(NODE).header("Idempotency-Key","rapid"))
                        .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("RUNNING"))
                        .andExpect(header().string("Retry-After","2")).andReturn().getResponse();
                String location = accepted.getHeader("Content-Location");
                assertNotNull(location);
                mvc.perform(post(location+"/cancel")).andExpect(status().isConflict()).andExpect(jsonPath("$.state").value("CANCELLED"));
                finished.countDown();
                assertEquals(409,running.get(10,TimeUnit.SECONDS));
                verify(f.nodes).analyze(any(),anyList());
            } finally { finished.countDown(); }
        }
    }

    static final class Fixture implements AutoCloseable {
        final ClusterAnalysisDatabaseContract db = ClusterAnalysisDatabaseContract.hsql();
        final ObjectMapper json = new ObjectMapper();
        final WorkspaceResolver workspace = mock(WorkspaceResolver.class);
        final WorkspaceDslReadPort git = mock(WorkspaceDslReadPort.class);
        final PrincipalIdentityService identities = mock(PrincipalIdentityService.class);
        final AtomicReference<PrincipalId> actor = new AtomicReference<>(PrincipalId.create());
        final LlmService llm = mock(LlmService.class);
        final LlmProviderConfig providers = mock(LlmProviderConfig.class);
        final AnalyzeNodeChildrenUseCase nodes = mock(AnalyzeNodeChildrenUseCase.class);
        final JustifyLeafUseCase leaves = mock(JustifyLeafUseCase.class);
        final TaxonomyService taxonomy = mock(TaxonomyService.class);

        Fixture() throws Exception {
            when(workspace.resolveCurrentUsername()).thenReturn("alice");
            when(workspace.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice","ws","draft","repo"));
            when(workspace.resolveCurrentRepositoryContextForRead()).thenReturn(RepositoryContext.workspace("repo","ws","draft","alice"));
            var handle = mock(WorkspaceDslReadPort.RepositoryRead.class);
            when(git.openRead(any())).thenReturn(handle);
            when(handle.currentHead()).thenReturn("a".repeat(40));
            when(identities.requireCurrent(any())).thenAnswer(call -> actor.get());
            when(taxonomy.isInitialized()).thenReturn(true);
            var candidates = List.of(new TaxonomyNode());
            when(taxonomy.getChildrenOf("CP")).thenReturn(candidates);
            when(taxonomy.getPathToRoot("CP-1")).thenReturn(candidates);
            when(llm.interactiveSource(anyList(),anyBoolean())).thenReturn("b".repeat(64));
            when(llm.interactiveDispatchAvailable(anyBoolean())).thenReturn(true);
            when(providers.getActiveProviderId()).thenReturn(new ProviderId("GEMINI"));
            when(nodes.analyze(any(),anyList())).thenReturn(node());
            login();
        }

        AnalyzeNodeChildrenResult node() {
            return new AnalyzeNodeChildrenResult(Map.of("CP-1",87d),Map.of("CP-1","reason"),"private prompt","private wire response","Gemini",10,null);
        }

        MockMvc mvc() {
            var gateway = db.adapterGateway(workspace,git,identities);
            var operations = new ScopedAnalysisChildOperations(gateway,json);
            var pins = new AnalysisFollowUpProviders(providers);
            ReflectionTestUtils.setField(pins,"nodeReceipts",operations);
            var child = new AnalysisChildAssessment(taxonomy,llm,pins,nodes,leaves);
            ReflectionTestUtils.setField(child,"receipts",operations);
            var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
            beans.addBean("assessments",child);
            var sourceAuthority = new ChildAnalysisSourceAuthority(workspace,beans.getBeanProvider(AnalysisChildAssessment.class),
                    beans.getBeanProvider(com.taxonomy.composition.architecture.ArchitectureFocusSuggestionService.class),
                    beans.getBeanProvider(com.taxonomy.composition.architecture.ArchitectureSourceFocusService.class),json);
            beans.addBean("sourceAuthority",sourceAuthority);
            ReflectionTestUtils.invokeMethod(gateway,"childSources",beans.getBeanProvider(com.taxonomy.analysis.operation.AnalysisChildSourceAuthority.class));
            var controller = new AnalysisApiController(taxonomy,null,json,null,null,nodes,leaves,null,
                    mock(RepositoryStateService.class),workspace,null);
            ReflectionTestUtils.setField(controller,"childAssessments",child);
            return MockMvcBuilders.standaloneSetup(controller,new AnalysisAdapterObservationController(gateway))
                    .setControllerAdvice(new ChildAnalysisResponseAdvice()).build();
        }
        void login() { SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice","unused","ROLE_USER")); }
        public void close() { SecurityContextHolder.clearContext(); db.close(); }
    }
}
