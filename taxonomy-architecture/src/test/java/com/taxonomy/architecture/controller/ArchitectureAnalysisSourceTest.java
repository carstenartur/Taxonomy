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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$").isMap());
        }
        verify(resolver, times(requests.size())).resolveCurrentRepositoryContextForRead();
    }
}
