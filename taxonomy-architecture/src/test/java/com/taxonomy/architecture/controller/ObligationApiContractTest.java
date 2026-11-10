package com.taxonomy.architecture.controller;

import com.taxonomy.workspace.controller.ExplicitRepositoryReadSelection;

import com.taxonomy.architecture.service.*;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ObligationApiContractTest {
    @Test void profileDiscoveryPublishesVersionAndAlternativeCardinality() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GapAnalysisApiController(mock(ArchitectureGapService.class), mock(ExplicitRepositoryReadSelection.class)))
                .setControllerAdvice(new ObligationApiErrors()).build();
        mvc.perform(get("/api/gap/profiles")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("compatibility"))
                .andExpect(jsonPath("$[0].rules").isEmpty())
                .andExpect(jsonPath("$[1].version").value("1.0.0"))
                .andExpect(jsonPath("$[1].rules[0].minCount").value(1))
                .andExpect(jsonPath("$[1].rules[0].targetRoots", org.hamcrest.Matchers.containsInAnyOrder("BP", "BR")));
    }
    @Test void scopedRequestReturnsJudgmentsWithoutLegacyMissingRelationFields() throws Exception {
        var context = RepositoryContext.centralRead("repo", "main", "user");
        String commit = "a".repeat(40);
        var contexts = mock(ExplicitRepositoryReadSelection.class);
        when(contexts.resolve(eq("repo"), eq("main"), eq(commit), any())).thenReturn(context);
        var gaps = mock(ArchitectureGapService.class);
        var view = ArchitectureGapService.compatibilityAssessment("Fixture");
        view.setJudgments(List.of(new ObligationJudgment("support", "service", "CR", "SUPPORTS", Set.of("BP", "BR"),
                1, null, 0, ObligationProfile.Severity.WARNING, ObligationJudgment.Status.INCONCLUSIVE, "Incomplete")));
        when(gaps.analyze(context, commit, "service-support", "1.0.0", EvidenceCompleteness.INCOMPLETE, "Fixture")).thenReturn(view);
        var mvc = MockMvcBuilders.standaloneSetup(new GapAnalysisApiController(gaps, contexts)).setControllerAdvice(new ObligationApiErrors()).build();
        mvc.perform(post("/api/gap/analyze").param("repositoryId", "repo").param("branch", "main").param("commitId", commit)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"profileId":"service-support","profileVersion":"1.0.0","evidenceCompleteness":"INCOMPLETE","businessText":"Fixture"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.judgments[0].status").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.totalGaps").value(0)).andExpect(jsonPath("$.missingRelations").doesNotExist());
    }
    @Test void unavailableEvidenceNeverReturnsAnEmptySuccessfulAssessment() throws Exception {
        var contexts = mock(ExplicitRepositoryReadSelection.class);
        var context = RepositoryContext.centralRead("repo", "main", "user");
        when(contexts.resolve(anyString(), anyString(), anyString(), any())).thenReturn(context);
        var gaps = mock(ArchitectureGapService.class);
        when(gaps.analyze(any(), anyString(), anyString(), anyString(), any(), any())).thenThrow(new IllegalStateException("stale"));
        var mvc = MockMvcBuilders.standaloneSetup(new GapAnalysisApiController(gaps, contexts)).setControllerAdvice(new ObligationApiErrors()).build();
        mvc.perform(post("/api/gap/analyze").param("repositoryId", "repo").param("branch", "main").param("commitId", "a".repeat(40))
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"profileId":"compatibility","profileVersion":"1.0.0","evidenceCompleteness":"COMPLETE"}
                """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
    }
}
