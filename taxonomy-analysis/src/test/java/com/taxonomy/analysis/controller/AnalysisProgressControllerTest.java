package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.analysis.cluster.ClusterAnalysisObservation;
import com.taxonomy.analysis.cluster.ClusterAnalysisView;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisProgressControllerTest {
    private static final String ID = "cb2a3d71-e849-4a50-9855-1f9cb8f81402";
    private static final WorkspaceContext SCOPE = new WorkspaceContext("alice", "work-a", "draft", "repo-a");

    private static ClusterAnalysisView clusterView(String status, long sequence) {
        var snapshot = new AnalysisProgressRegistry.Snapshot(ID, status, "SCORING", "CP", null,
                sequence, 1000, 2000, 2000, 3, false, Map.of("CP", 80), List.of(), 0,
                null, "EXTERNAL_OR_UNKNOWN", "FILESYSTEM_OR_EXTERNAL", null, null, 1000,
                1000L, 0, 1000, null, null);
        return new ClusterAnalysisView(snapshot, ClusterAnalysisView.Transport.ARTEMIS,
                new ClusterAnalysisView.ObservationScope("work-a", "repo-a", "draft", "commit-a"),
                new ClusterAnalysisView.TaskSummary(1, 2, List.of(new ClusterAnalysisView.TaskCounts(
                        AnalysisTaskType.SUBTAXONOMY_ANALYSIS, TaxonomyShardRoot.of("CP"), 0, 0, 1, 0))));
    }

    private static WorkspaceResolver resolver() {
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(SCOPE);
        return resolver;
    }

    @Test void durableObservationWinsOverLocalStatusRecentAndCancellation() throws Exception {
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var cluster = mock(ClusterAnalysisObservation.class);
        when(cluster.snapshot(ID, "alice", SCOPE)).thenReturn(Optional.of(clusterView("RUNNING", 8)));
        when(cluster.cancel(ID, "alice", SCOPE)).thenReturn(Optional.of(clusterView("CANCELLED", 9)));
        when(cluster.recent("alice", SCOPE, null, null)).thenReturn(List.of(clusterView("RUNNING", 8)));
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisProgressController(registry, resolver(), cluster)).build();
        try (var ignored = registry.open(ID, "alice", SCOPE, null)) {
            mvc.perform(get("/api/analysis-runs/" + ID))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.operationId").value(ID)).andExpect(jsonPath("$.sequence").value(8))
                    .andExpect(jsonPath("$.transport").value("artemis"))
                    .andExpect(jsonPath("$.cluster.completedRoots").value(1))
                    .andExpect(jsonPath("$.cluster.tasks[0].taskType").value("SUBTAXONOMY_ANALYSIS"));
            mvc.perform(get("/api/analysis-runs"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].transport").value("artemis"));
            mvc.perform(post("/api/analysis-runs/" + ID + "/cancel"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            assertEquals("RUNNING", registry.snapshot(ID, "alice", SCOPE).status());
        }
    }

    @Test void hiddenDurableOperationCannotFallBackToLocalOrPendingAdmission() throws Exception {
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var cluster = mock(ClusterAnalysisObservation.class);
        when(cluster.snapshot(ID, "alice", SCOPE)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(cluster.cancel(ID, "alice", SCOPE)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(cluster.result(ID, "alice", SCOPE)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(cluster.events(ID, "alice", SCOPE, 0)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisProgressController(registry, resolver(), cluster)).build();
        try (var ignored = registry.open(ID, "alice", SCOPE, null)) {
            mvc.perform(get("/api/analysis-runs/" + ID).param("waitForRegistration", "true"))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/analysis-runs/" + ID + "/cancel")).andExpect(status().isNotFound());
            mvc.perform(get("/api/analysis-runs/" + ID + "/events")).andExpect(status().isNotFound());
            mvc.perform(get("/api/analysis-runs/" + ID + "/result")).andExpect(status().isNotFound());
            assertEquals("RUNNING", registry.snapshot(ID, "alice", SCOPE).status());
        }
    }

    @Test void reconnectUsesGreatestDurableCursorAndReturnsPersistedResultWithoutStartingWork() throws Exception {
        var cluster = mock(ClusterAnalysisObservation.class);
        var events = new SseEmitter();
        events.send(SseEmitter.event().id("10").name("snapshot").data(clusterView("COMPLETED", 10)));
        when(cluster.events(ID, "alice", SCOPE, 9)).thenReturn(events);
        var result = new AnalysisResult();
        result.setStatus("SUCCESS");
        when(cluster.result(ID, "alice", SCOPE)).thenReturn(result);
        when(cluster.request(ID, "alice", SCOPE)).thenReturn(new ClusterAnalysisObservation.RecoveryInput(
                ID, "Original requirement", "MOCK", com.taxonomy.dto.AnalysisScope.full(), clusterView("COMPLETED", 10).scope()));
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisProgressController(
                new AnalysisProgressRegistry(new StandardEnvironment()), resolver(), cluster)).build();
        mvc.perform(get("/api/analysis-runs/" + ID + "/events").param("afterSequence", "6")
                        .header("Last-Event-ID", "9"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id:10")));
        events.complete();
        mvc.perform(get("/api/analysis-runs/" + ID + "/result"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(get("/api/analysis-runs/" + ID + "/request"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.operationId").value(ID))
                .andExpect(jsonPath("$.businessText").value("Original requirement"))
                .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test void malformedReplayCursorsAreRejectedBeforeSubscription() throws Exception {
        var cluster = mock(ClusterAnalysisObservation.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisProgressController(
                new AnalysisProgressRegistry(new StandardEnvironment()), resolver(), cluster)).build();
        for (String cursor : List.of("-1", "invalid", "1e3", "9223372036854775808")) {
            mvc.perform(get("/api/analysis-runs/" + ID + "/events").header("Last-Event-ID", cursor))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/analysis-runs/" + ID + "/events").param("afterSequence", "-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cluster);
    }

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
