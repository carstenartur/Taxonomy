package com.taxonomy.analysis.controller;

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
