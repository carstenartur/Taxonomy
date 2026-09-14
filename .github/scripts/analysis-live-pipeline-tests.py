from pathlib import Path
p=Path('taxonomy-app/src/test/java/com/taxonomy/analysis/service/LlmServiceBranchCoverageTest.java')
s=p.read_text();marker='    private LlmService service;'
assert s.count(marker)==1
s=s.replace(marker,marker+r'''

    @Test
    void cancellationBetweenRootsRetainsCompletedScoresWithoutAllocatingAnotherTree() {
        var registry = new AnalysisProgressRegistry(new org.springframework.core.env.StandardEnvironment());
        var scope = new com.taxonomy.workspace.service.WorkspaceContext("alice", "work-a", "draft", "repo-a");
        when(taxonomyService.getRootNodes()).thenReturn(new ArrayList<>(List.of(
                node("BP", null, "BP"), node("CP", null, "CP"))));
        when(taxonomyService.getChildrenOf("BP")).thenReturn(List.of());
        when(gateway.extractResponseText("first-body"))
                .thenReturn("{\"BP\":{\"score\":80,\"reason\":\"completed evidence\"}}");
        try (var run = registry.open(null, "alice", scope, null)) {
            when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenAnswer(invocation -> {
                registry.cancel(run.id(), "alice", scope);
                return "first-body";
            });
            AnalysisResult result = service.analyzeWithBudget("requirement");
            assertThat(result.getStatus()).isEqualTo("PARTIAL");
            assertThat(result.getErrorMessage()).startsWith("CANCELLED:");
            assertThat(result.getScores()).containsEntry("BP", 80).doesNotContainKey("CP");
            assertThat(result.getReasons()).containsEntry("BP", "completed evidence");
            assertThat(result.getTree()).isEmpty();
            verify(taxonomyService, never()).getFullTree();
            verify(gateway, org.mockito.Mockito.times(1)).sendHttpRequest(anyString(), anyString());
            run.finish(result.getStatus());
            assertThat(registry.snapshot(run.id(), "alice", scope).status()).isEqualTo("CANCELLED");
        }
        assertThat(AnalysisRunControl.active()).isFalse();
    }

    @Test
    void cancellationBetweenProductBatchesKeepsEarlierCategoryAndProductEvidence() {
        var registry = new AnalysisProgressRegistry(new org.springframework.core.env.StandardEnvironment());
        var scope = new com.taxonomy.workspace.service.WorkspaceContext("alice", "work-a", "draft", "repo-a");
        TaxonomyNode category = node("IP-C", "IP-F", "IP");
        TaxonomyNode first = node("IP-P1", "IP-F", "IP");
        TaxonomyNode second = node("IP-P2", "IP-F", "IP");
        when(catalogueOverlayService.isProduct("IP-P1")).thenReturn(true);
        when(catalogueOverlayService.isProduct("IP-P2")).thenReturn(true);
        ReflectionTestUtils.setField(service, "productBatchSize", 1);
        when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenReturn("category-body");
        when(gateway.extractResponseText("category-body")).thenReturn("{\"IP-C\":80}");
        when(gateway.extractResponseText("product-body"))
                .thenReturn("{\"IP-P1\":{\"score\":75,\"reason\":\"completed product\"}}");
        try (var run = registry.open(null, "alice", scope, null)) {
            when(gateway.sendHttpRequest("product prompt", "test-key")).thenAnswer(invocation -> {
                registry.cancel(run.id(), "alice", scope);
                return "product-body";
            });
            var stopped = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> service.analyzeSingleBatchDetailed("requirement", List.of(category, first, second), 100),
                    AnalysisStoppedException.class);
            assertThat(stopped).isNotNull();
            assertThat(stopped.partialScores()).containsKeys("IP-C", "IP-P1").doesNotContainKey("IP-P2");
            assertThat(stopped.partialScores()).containsEntry("IP-P1", 75);
            assertThat(stopped.partialReasons()).containsEntry("IP-P1", "completed product");
            verify(gateway, org.mockito.Mockito.times(1)).sendHttpRequest("product prompt", "test-key");
        }
    }
''')
p.write_text(s)
p=Path('taxonomy-app/src/test/java/com/taxonomy/analysis/controller/AnalysisProgressControllerTest.java')
assert not p.exists()
p.write_text(r'''package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisProgressControllerTest {
    @Test void statusIsVisibleBeforeCompletionWithoutSendingPromptsInEveryPoll() throws Exception {
        var registry=new AnalysisProgressRegistry(new StandardEnvironment());
        var scope=new WorkspaceContext("alice","work-a","draft","repo-a");
        var resolver=mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(scope);
        var mvc=MockMvcBuilders.standaloneSetup(new AnalysisProgressController(registry,resolver)).build();
        try(var run=registry.open(null,"alice",scope,null)) {
            AnalysisRunControl.call("MOCK","CP",()-> {
                var detail=new LlmCallDetail();
                detail.setScores(Map.of("CP",80));detail.setPrompt("private-prompt-marker");
                detail.setRawResponse("private-response-marker");return detail;
            });
            mvc.perform(get("/api/analysis-runs/"+run.id()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$.status").value("RUNNING"))
                    .andExpect(jsonPath("$.rawScores.CP").value(80))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-prompt-marker"))));
            mvc.perform(get("/api/analysis-runs/"+run.id()+"/calls/1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.prompt").value("private-prompt-marker"));
        }
    }
    @Test void anotherUserCannotPollReadDetailsOrCancelTheRun() throws Exception {
        var registry=new AnalysisProgressRegistry(new StandardEnvironment());
        var scope=new WorkspaceContext("alice","work-a","draft","repo-a");
        var resolver=mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("bob");
        when(resolver.resolveCurrentContext()).thenReturn(scope);
        var mvc=MockMvcBuilders.standaloneSetup(new AnalysisProgressController(registry,resolver)).build();
        try(var run=registry.open(null,"alice",scope,null)) {
            mvc.perform(get("/api/analysis-runs/"+run.id())).andExpect(status().isNotFound());
            mvc.perform(get("/api/analysis-runs/"+run.id()+"/calls/1")).andExpect(status().isNotFound());
            mvc.perform(post("/api/analysis-runs/"+run.id()+"/cancel")).andExpect(status().isNotFound());
            mvc.perform(get("/api/analysis-runs")).andExpect(status().isOk()).andExpect(content().json("[]"));
        }
    }
    @Test void aDifferentBranchCannotObserveOrCancelAnOtherwiseMatchingRun() throws Exception {
        var registry=new AnalysisProgressRegistry(new StandardEnvironment());
        var scope=new WorkspaceContext("alice","work-a","draft","repo-a");
        var resolver=mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice","work-a","other","repo-a"));
        var mvc=MockMvcBuilders.standaloneSetup(new AnalysisProgressController(registry,resolver)).build();
        try(var run=registry.open(null,"alice",scope,null)) {
            mvc.perform(get("/api/analysis-runs/"+run.id())).andExpect(status().isNotFound());
            mvc.perform(post("/api/analysis-runs/"+run.id()+"/cancel")).andExpect(status().isNotFound());
        }
    }
}
''')
print('Added 2 real LLM traversal cancellation cases and 3 HTTP isolation regressions')
