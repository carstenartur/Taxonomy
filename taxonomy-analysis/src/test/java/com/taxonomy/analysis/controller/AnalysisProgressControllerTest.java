package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.analysis.cluster.ClusterAnalysisObservation;
import com.taxonomy.analysis.cluster.ClusterAnalysisView;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RequirementNodeAssessment;
import com.taxonomy.dto.RequirementNodeAssessment.*;
import com.taxonomy.dto.RootAssessment;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisProgressControllerTest {
    private static final String ID = "cb2a3d71-e849-4a50-9855-1f9cb8f81402";
    private static final WorkspaceContext SCOPE = new WorkspaceContext("alice", "work-a", "draft", "repo-a");

    private static ClusterAnalysisView clusterView(String status, long sequence) {
        var snapshot = new AnalysisProgressRegistry.Snapshot(ID, status, "SCORING", "CP", null,
                sequence, 1000, 2000, 2000, 3, false, Map.of("CP", 80d), List.of(), 0,
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
                detail.setScores(Map.of("CP",80d));detail.setPrompt("private-prompt-marker");
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
    @Test void progressPollCarriesScoredAndScorelessEvidenceWithoutInventingLegacyClaims() throws Exception {
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var resolver = resolver();
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisProgressController(registry, resolver)).build();
        try (var run = registry.open(ID, "alice", SCOPE, null)) {
            var cp = new LlmCallDetail();
            cp.setScores(Map.of("CP", 0d));
            cp.setNodeAssessments(Map.of("CP", List.of(supported("  The existing archive.  ", "Archive supports retention."))));
            cp.setPrompt("provider-prompt-marker");
            AnalysisRunControl.call("MOCK", "CP", () -> cp);

            var open = new RequirementNodeAssessment("", List.of(SubjectKind.PROCESS), List.of(RequirementKind.FUNCTIONAL),
                    ContributionType.DIRECT_SUBJECT, EvidenceStatus.UNRESOLVED, EvidenceBasis.UNSPECIFIED,
                    Necessity.UNDETERMINED, Scope.UNSPECIFIED, "", "Recovery is unspecified.", "Who requests recovery?");
            var ip = new LlmCallDetail();
            ip.setScores(Map.of());
            ip.setRootAssessments(Map.of("IP", new RootAssessment(RootAssessment.Status.UNRESOLVED,
                    null, open.rationale(), open.question())));
            ip.setError("ROOT_UNRESOLVED: " + open.question());
            ip.setNodeAssessments(Map.of("IP", List.of(open)));
            AnalysisRunControl.call("MOCK", "IP", () -> ip);

            var legacy = new LlmCallDetail();
            legacy.setScores(Map.of("BP", 90d));
            AnalysisRunControl.assessment(legacy);
            var failed = new LlmCallDetail();
            failed.setScores(Map.of("CR", 85d));
            failed.setNodeAssessments(Map.of("CR", cp.getNodeAssessments().get("CP")));
            failed.setError("Invalid provider response.");
            AnalysisRunControl.assessment(failed);

            String url = "/api/analysis-runs/" + run.id();
            mvc.perform(get(url))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"))
                    .andExpect(jsonPath("$.nodeAssessments.CP[0].requirementQuote").value("  The existing archive.  "))
                    .andExpect(jsonPath("$.nodeAssessments.CP[0].evidenceStatus").value("SUPPORTED"))
                    .andExpect(jsonPath("$.rawScores.CP").value(0))
                    .andExpect(jsonPath("$.nodeAssessments.IP[0].question").value("Who requests recovery?"))
                    .andExpect(jsonPath("$.rawScores.IP").doesNotExist())
                    .andExpect(jsonPath("$.rawScores.BP").value(90))
                    .andExpect(jsonPath("$.nodeAssessments.BP").doesNotExist())
                    .andExpect(jsonPath("$.nodeAssessments.CR").doesNotExist())
                    .andExpect(jsonPath("$.nodeAssessmentsTruncated").value(false))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("provider-prompt-marker"))));
            when(resolver.resolveCurrentUsername()).thenReturn("bob");
            mvc.perform(get(url)).andExpect(status().isNotFound());
        }
    }

    @Test void progressPreviewBoundsTheEncodedMapAndKeepsCompleteClaimsForEachDisplayedNode() throws Exception {
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var mapper = new tools.jackson.databind.ObjectMapper();
        var offered = new LinkedHashMap<String, List<RequirementNodeAssessment>>();
        var scores = new LinkedHashMap<String, Double>();
        String quote = "\"ä\\\t".repeat(1200) + "\nFINAL QUOTE";
        for (int i = 0; i < 64; i++) {
            var id = "CP-" + i;
            offered.put(id, List.of(supported(quote, "The archive supports the process."),
                    supported("Original text", "Additional interpretation for " + id)));
            scores.put(id, 0d);
        }
        var detail = new LlmCallDetail();
        detail.setScores(scores);
        detail.setNodeAssessments(offered);
        try (var run = registry.open(ID, "alice", SCOPE, null)) {
            AnalysisRunControl.assessment(detail);
            var mvc = MockMvcBuilders.standaloneSetup(new AnalysisProgressController(registry, resolver())).build();
            var response = mvc.perform(get("/api/analysis-runs/" + run.id())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.nodeAssessmentsTruncated").value(true)).andReturn().getResponse();
            var preview = mapper.readTree(response.getContentAsByteArray()).path("nodeAssessments");
            assertTrue(preview.size() > 0);
            assertTrue(preview.size() < offered.size());
            assertTrue(mapper.writeValueAsBytes(preview).length <= 128 * 1024);
            for (var entry : preview.properties()) {
                assertEquals(mapper.valueToTree(offered.get(entry.getKey())), entry.getValue());
            }
            assertEquals(64, detail.getNodeAssessments().size());
        }
    }

    private static RequirementNodeAssessment supported(String quote, String rationale) {
        return new RequirementNodeAssessment(quote, List.of(SubjectKind.SERVICE), List.of(RequirementKind.FUNCTIONAL),
                ContributionType.SUPPORTS, EvidenceStatus.SUPPORTED, EvidenceBasis.INFERRED,
                Necessity.CONTEXT, Scope.EXISTING_ENVIRONMENT, "", rationale, "");
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
