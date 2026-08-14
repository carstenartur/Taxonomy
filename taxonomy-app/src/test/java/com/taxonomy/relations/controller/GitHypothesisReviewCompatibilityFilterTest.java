package com.taxonomy.relations.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewAction;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewResult;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHypothesisReviewCompatibilityFilterTest {

    private static final String HEAD_A = "a".repeat(40);
    private static final String HEAD_B = "b".repeat(40);

    private GitAuthoritativeHypothesisService hypothesisService;
    private RelationBranchProjectionReadinessService readinessService;
    private WorkspaceResolver workspaceResolver;
    private GitHypothesisReviewCompatibilityFilter filter;
    private RepositoryContext context;

    @BeforeEach
    void setUp() {
        hypothesisService = mock(GitAuthoritativeHypothesisService.class);
        readinessService = mock(RelationBranchProjectionReadinessService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        filter = new GitHypothesisReviewCompatibilityFilter(
                hypothesisService,
                readinessService,
                workspaceResolver,
                mock(SystemRepositoryService.class),
                mock(RepositoryMembershipService.class),
                new ObjectMapper());
        context = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "draft", "alice");
    }

    @Test
    void productiveLegacyRouteReturnsTheAuthoritativeCommitAndEtag()
            throws Exception {
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(readinessService.inspect(context)).thenReturn(ready(HEAD_A));
        when(hypothesisService.review(
                eq(42L),
                eq(context),
                eq(HEAD_A),
                any(CommandMetadata.class),
                eq(ReviewAction.ACCEPT)))
                .thenReturn(reviewResult());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/dsl/hypotheses/42/accept");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ETAG))
                .isEqualTo('"' + HEAD_B + '"');
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .contains("\"authoritativeCommitId\":\"" + HEAD_B + "\"")
                .contains("\"projectionStatus\":\"PROJECTED\"")
                .contains("\"status\":\"ACCEPTED\"");
        verify(chain, never()).doFilter(any(), any());

        ArgumentCaptor<CommandMetadata> metadataCaptor =
                ArgumentCaptor.forClass(CommandMetadata.class);
        verify(hypothesisService).review(
                eq(42L),
                eq(context),
                eq(HEAD_A),
                metadataCaptor.capture(),
                eq(ReviewAction.ACCEPT));
        assertThat(metadataCaptor.getValue().causationId())
                .isEqualTo("legacy-hypothesis-accept-42-" + HEAD_A);
    }

    @Test
    void suppliedExpectedHeadAndIdempotencyKeyArePreserved() throws Exception {
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(readinessService.inspect(context)).thenReturn(ready(HEAD_B));
        when(hypothesisService.review(
                eq(42L),
                eq(context),
                eq(HEAD_A),
                any(CommandMetadata.class),
                eq(ReviewAction.REJECT)))
                .thenReturn(reviewResult());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/dsl/hypotheses/42/reject");
        request.addHeader(HttpHeaders.IF_MATCH, '"' + HEAD_A + '"');
        request.addHeader("Idempotency-Key", "review-42-reject");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(200);
        ArgumentCaptor<CommandMetadata> metadataCaptor =
                ArgumentCaptor.forClass(CommandMetadata.class);
        verify(hypothesisService).review(
                eq(42L),
                eq(context),
                eq(HEAD_A),
                metadataCaptor.capture(),
                eq(ReviewAction.REJECT));
        assertThat(metadataCaptor.getValue().causationId())
                .isEqualTo("review-42-reject");
    }

    @Test
    void unrelatedDslRequestContinuesThroughTheMvcChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/dsl/hypotheses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(hypothesisService, never()).review(
                any(), any(), any(), any(), any());
    }

    private static Readiness ready(String head) {
        return new Readiness(ReadinessState.READY, head, head, List.of());
    }

    private ReviewResult reviewResult() {
        RelationHypothesis hypothesis = new RelationHypothesis();
        hypothesis.setId(42L);
        hypothesis.setRepositoryId(context.repositoryId());
        hypothesis.setWorkspaceId(context.workspaceId());
        hypothesis.setSourceNodeId("CR");
        hypothesis.setTargetNodeId("CO");
        hypothesis.setRelationType(RelationType.REALIZES);
        hypothesis.setStatus(HypothesisStatus.ACCEPTED);
        hypothesis.setConfidence(0.82);

        CommandResult authority = new CommandResult(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                context.scope(),
                HEAD_A,
                HEAD_B,
                ChangeKind.ADDED,
                true,
                "cause-42");
        MutationResult mutation = new MutationResult(
                authority,
                new ProjectionResult(
                        ProjectionOutcome.CREATED,
                        HEAD_B,
                        true));
        return new ReviewResult(
                42L,
                ReviewAction.ACCEPT,
                hypothesis,
                mutation);
    }
}
