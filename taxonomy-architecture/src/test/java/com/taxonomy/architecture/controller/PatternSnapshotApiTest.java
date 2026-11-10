package com.taxonomy.architecture.controller;

import com.taxonomy.architecture.service.ArchitecturePatternService;
import com.taxonomy.dto.PatternDetectionView;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PatternSnapshotApiTest {
    @Test void getRequiresExplicitSavedSource() throws Exception {
        var service = mock(ArchitecturePatternService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PatternDetectionApiController(service, mock(ObligationRequestContext.class))).build();
        mvc.perform(get("/api/patterns/detect").param("nodeCode","anchor"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void postRequiresExplicitSavedSource() throws Exception {
        var service = mock(ArchitecturePatternService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PatternDetectionApiController(service, mock(ObligationRequestContext.class))).build();
        mvc.perform(post("/api/patterns/detect").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":{\"anchor\":80},\"minScore\":50}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void unassessedCoverageIsUnknown() { assertThat(new PatternDetectionView().getPatternCoverage()).isNull(); }

    @Test void authorizedScopeReturnsPatternSpecificProofAndUnavailableScopeFailsClosed() throws Exception {
        var resolver = mock(com.taxonomy.workspace.service.WorkspaceResolver.class);
        var context = com.taxonomy.workspace.service.RepositoryContext.centralRead("repo", "main", "user");
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(context);
        var service = mock(ArchitecturePatternService.class);
        var view = new PatternDetectionView();
        String commit = "a".repeat(40);
        view.setEvidence(new com.taxonomy.dto.PatternDetectionEvidence("repo", null, "__shared__", "main", commit,
                "CANONICAL_SNAPSHOT", "GIT_BRANCH_PROJECTION", "READY", "1.0.0", 50, 0, "NOT_EVALUATED_NO_ANCHORS"));
        when(service.detectForNode(context, commit, "anchor")).thenReturn(view);
        var mvc = MockMvcBuilders.standaloneSetup(new PatternDetectionApiController(service, new ObligationRequestContext(resolver)))
                .setControllerAdvice(new ObligationApiErrors()).build();
        mvc.perform(get("/api/patterns/detect").param("nodeCode","anchor").param("repositoryId","repo")
                .param("branch","main").param("commitId",commit).param("workspaceId",""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidence.commitId").value(commit))
                .andExpect(jsonPath("$.evidence.templateVersion").value("1.0.0"))
                .andExpect(jsonPath("$.evidence.effectiveMaxHops").doesNotExist())
                .andExpect(jsonPath("$.patternCoverage").value(org.hamcrest.Matchers.nullValue()));
        verify(service).detectForNode(context,commit,"anchor");
        mvc.perform(get("/api/patterns/detect").param("nodeCode","anchor").param("repositoryId","other")
                .param("branch","main").param("commitId",commit).param("workspaceId",""))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/patterns/detect").param("nodeCode","anchor").param("repositoryId","repo")
                .param("branch","main").param("commitId",commit))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/patterns/detect").param("nodeCode","anchor").param("repositoryId","repo")
                .param("branch","main").param("commitId","short").param("workspaceId",""))
                .andExpect(status().isBadRequest());
        when(service.detectForScores(eq(context),eq(commit),anyMap(),eq(50))).thenThrow(new IllegalStateException("STALE"));
        mvc.perform(post("/api/patterns/detect").param("repositoryId","repo").param("branch","main")
                .param("commitId",commit).param("workspaceId","").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":{\"anchor\":80},\"minScore\":50}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
        mvc.perform(get("/api/patterns/detect").param("nodeCode","anchor").param("repositoryId","repo")
                .param("branch","main").param("commitId",commit).param("workspaceId","private"))
                .andExpect(status().isConflict());
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }
}
