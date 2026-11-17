package com.taxonomy.architecture.controller;

import com.taxonomy.architecture.service.ArchitectureGapService;
import com.taxonomy.architecture.service.ArchitecturePatternService;
import com.taxonomy.architecture.service.ArchitectureRecommendationService;
import com.taxonomy.dto.ApqcCoverageResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.EvidenceCompleteness;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.workspace.controller.ExplicitRepositoryReadSelection;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationProjectionUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ArchitectureAnalysisSourceTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "__shared__"})
    void allScopedAnalysesReturnTheSameAuthorizedSourceThatProducedTheirBody(String workspace) throws Exception {
        String commit = "a".repeat(40);
        var context = workspace.isEmpty() ? RepositoryContext.centralRead("repo", "draft", "alice")
                : RepositoryContext.workspace("repo", workspace, "draft", "alice");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(context);
        var selection = new ExplicitRepositoryReadSelection(resolver);
        var gaps = mock(ArchitectureGapService.class);
        var patterns = mock(ArchitecturePatternService.class);
        var recommendations = mock(ArchitectureRecommendationService.class);
        when(gaps.analyzeApqcCoverage(context, commit)).thenReturn(new ApqcCoverageResult(
                "READY", 1, 0, 0d, List.of("A"), Map.of(), "repo", context.workspaceId(), "draft", commit, List.of()));
        when(gaps.analyze(context, commit, "compatibility", "1.0.0", EvidenceCompleteness.COMPLETE, null))
                .thenReturn(ArchitectureGapService.compatibilityAssessment("x"));
        when(patterns.detectForNode(context, commit, "A")).thenReturn(new PatternDetectionView());
        when(patterns.detectForScores(context, commit, Map.of(), 0)).thenReturn(new PatternDetectionView());
        when(recommendations.recommend(context, commit, "compatibility", "1.0.0", EvidenceCompleteness.COMPLETE, null, null, 0))
                .thenReturn(new ArchitectureRecommendation());
        var mvc = MockMvcBuilders.standaloneSetup(new GapAnalysisApiController(gaps, selection),
                new PatternDetectionApiController(patterns, selection), new RecommendationApiController(recommendations, selection)).build();
        String profile = """
                {"profileId":"compatibility","profileVersion":"1.0.0","evidenceCompleteness":"COMPLETE"}
                """;
        var requests = List.of(get("/api/gap/apqc-coverage"), post("/api/gap/analyze").content(profile),
                get("/api/patterns/detect").param("nodeCode", "A"), post("/api/patterns/detect").content("{\"minScore\":0}"),
                post("/api/recommend").content(profile.replace("}", ",\"minScore\":0}")));
        for (var request : requests) {
            mvc.perform(request.param("repositoryId", "repo").param("workspaceId", workspace).param("branch", "draft")
                            .param("commitId", commit.toUpperCase(Locale.ROOT)).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Taxonomy-Repository", "repo"))
                    .andExpect(header().string("X-Taxonomy-Workspace-Id", workspace))
                    .andExpect(header().string("X-Taxonomy-Branch", "draft"))
                    .andExpect(header().string("X-Taxonomy-Commit", commit))
                    .andExpect(header().string("X-Taxonomy-Source-State", "READY"))
                    .andExpect(header().exists("ETag"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$").isMap());
        }
        verify(resolver, times(requests.size())).resolveCurrentRepositoryContextForRead();
    }

    @Test
    void conditionalReadsValidateTheAuthorizedSourceAndResponse() throws Exception {
        var resolver = mock(WorkspaceResolver.class);
        var patterns = mock(ArchitecturePatternService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PatternDetectionApiController(patterns,
                new ExplicitRepositoryReadSelection(resolver))).build();
        String commit = "a".repeat(40);
        var privateContext = RepositoryContext.workspace("repo", "__shared__", "draft", "alice");
        var centralContext = RepositoryContext.centralRead("repo", "draft", "alice");
        var first = new PatternDetectionView();
        first.setNodeCode("A");
        var second = new PatternDetectionView();
        second.setNodeCode("B");
        when(patterns.detectForNode(any(), eq(commit), eq("A"))).thenReturn(first);
        when(patterns.detectForNode(any(), eq(commit), eq("B"))).thenReturn(second);
        var query = get("/api/patterns/detect").param("nodeCode", "A").param("repositoryId", "repo")
                .param("branch", "draft").param("commitId", commit);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(centralContext);
        String tag = mvc.perform(query.header("X-Taxonomy-Workspace-Id", "")).andExpect(status().isOk())
                .andExpect(header().exists("ETag")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(query.header("If-None-Match", tag)).andExpect(status().isNotModified());
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(privateContext);
        var privateRequest = get("/api/patterns/detect").param("repositoryId", "repo").param("workspaceId", "__shared__")
                .param("branch", "draft").param("commitId", commit);
        String privateTag = mvc.perform(privateRequest.param("nodeCode", "A").header("If-None-Match", tag))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        assertThat(privateTag).isNotNull().isNotEqualTo(tag);
        mvc.perform(get("/api/patterns/detect").param("nodeCode", "B").param("repositoryId", "repo")
                        .param("workspaceId", "__shared__").param("branch", "draft").param("commitId", commit)
                        .header("If-None-Match", privateTag))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodeCode").value("B"));
        when(resolver.resolveCurrentRepositoryContextForRead()).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN));
        mvc.perform(privateRequest.header("If-None-Match", privateTag)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"NOT_BUILT,MISSING,409", "BRANCH_MISSING,MISSING,404", "STALE,STALE,409", "CORRUPT,FAILED,409"})
    void unavailableProjectionPublishesOnlyBoundedStatesAndTheCurrentAuthorizedSource(ReadinessState projection,
            String state, int expectedStatus) throws Exception {
        var context = RepositoryContext.workspace("repo", "__shared__", "draft", "alice");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(context);
        var patterns = mock(ArchitecturePatternService.class);
        String requested = "a".repeat(40);
        String head = projection == ReadinessState.BRANCH_MISSING ? null : "b".repeat(40);
        when(patterns.detectForNode(context, requested, "A")).thenThrow(
                new RelationProjectionUnavailableException(context, projection, head, null, 2));
        var mvc = MockMvcBuilders.standaloneSetup(new PatternDetectionApiController(patterns, new ExplicitRepositoryReadSelection(resolver)))
                .setControllerAdvice(new ObligationApiErrors()).build();
        var result = mvc.perform(get("/api/patterns/detect").param("nodeCode", "A").param("repositoryId", "repo")
                        .param("workspaceId", "__shared__").param("branch", "draft").param("commitId", requested))
                .andExpect(status().is(expectedStatus)).andExpect(header().string("X-Taxonomy-Source-State", state))
                .andExpect(header().string("X-Taxonomy-Workspace-Id", "__shared__"))
                .andExpect(header().string("X-Taxonomy-Relation-Pending-Recovery", "2"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"));
        if (head == null) result.andExpect(header().doesNotExist("X-Taxonomy-Commit"));
        else result.andExpect(header().string("X-Taxonomy-Commit", head));
    }
}
