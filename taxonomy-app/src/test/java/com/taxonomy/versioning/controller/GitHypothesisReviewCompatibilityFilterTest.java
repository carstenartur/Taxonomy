package com.taxonomy.versioning.controller;

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
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
                mock(RepositoryMembershipService.class));
        context = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "draft", "alice");
    }

    @Test
    void productiveLegacyRouteReturnsTheAuthoritativeCommitAndEtag()
            throws Exception {
        arrangeSuccessfulReview(ReviewAction.ACCEPT, HEAD_A);

        MockHttpServletRequest request = request(
                "/api/dsl/hypotheses/42/accept");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ETAG))
                .isEqualTo('"' + HEAD_B + '"');
        assertNoStoreJson(response);
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
                .isEqualTo("legacy-hypothesis-accept-42-" + HEAD_A)
                .hasSizeLessThanOrEqualTo(
                        GitHypothesisReviewCompatibilityFilter
                                .MAX_IDEMPOTENCY_KEY_LENGTH);

        InOrder order = inOrder(hypothesisService, readinessService);
        order.verify(hypothesisService).requireReviewable(
                42L, context, ReviewAction.ACCEPT);
        order.verify(readinessService).inspect(context);
    }

    @Test
    void suppliedExpectedHeadAndIdempotencyKeyArePreserved() throws Exception {
        arrangeSuccessfulReview(ReviewAction.REJECT, HEAD_A);

        MockHttpServletRequest request = request(
                "/api/dsl/hypotheses/42/reject");
        request.addHeader(HttpHeaders.IF_MATCH, '"' + HEAD_A + '"');
        request.addHeader("Idempotency-Key", "review-42.reject:v1");
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
                .isEqualTo("review-42.reject:v1");
    }

    @Test
    void contextPathRouteUsesTheSameBoundedContract() throws Exception {
        arrangeSuccessfulReview(ReviewAction.ACCEPT, HEAD_A);

        MockHttpServletRequest request = request(
                "/taxonomy/api/dsl/hypotheses/42/accept");
        request.setContextPath("/taxonomy");
        request.addHeader("Idempotency-Key", "context-path-review-42");
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
                eq(ReviewAction.ACCEPT));
        assertThat(metadataCaptor.getValue().causationId())
                .isEqualTo("context-path-review-42");
    }

    @Test
    void oversizedIdempotencyKeyFailsBeforeRepositoryResolution()
            throws Exception {
        assertInvalidIdempotencyKey("x".repeat(
                GitHypothesisReviewCompatibilityFilter
                        .MAX_IDEMPOTENCY_KEY_LENGTH + 1));
    }

    @Test
    void controlCharacterInIdempotencyKeyFailsBeforeRepositoryResolution()
            throws Exception {
        assertInvalidIdempotencyKey("review-\u0001-key");
    }

    @Test
    void formatCharacterInIdempotencyKeyFailsBeforeRepositoryResolution()
            throws Exception {
        assertInvalidIdempotencyKey("review-\u200b-key");
    }

    @Test
    void whitespaceInIdempotencyKeyFailsBeforeRepositoryResolution()
            throws Exception {
        assertInvalidIdempotencyKey("review 42");
    }

    @Test
    void tenantVisibilityFailureIsSanitizedAndPrecedesBranchInspection()
            throws Exception {
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        org.mockito.Mockito.doThrow(new IllegalArgumentException(
                        "Hypothesis not found: 42"))
                .when(hypothesisService)
                .requireReviewable(42L, context, ReviewAction.ACCEPT);

        MockHttpServletRequest request = request(
                "/api/dsl/hypotheses/42/accept");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(404);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"HYPOTHESIS_NOT_FOUND\"")
                .contains("Hypothesis is not available for review.")
                .doesNotContain("Hypothesis not found: 42")
                .doesNotContain("repo-a", "workspace-a1", "alice");
        verify(readinessService, never()).inspect(any());
        verify(hypothesisService, never()).review(
                any(), any(), any(), any(), any());
    }

    @Test
    void knownTerminalLifecycleFailureUsesStableSafePayload()
            throws Exception {
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "Hypothesis 42 cannot be accepted from ACCEPTED"))
                .when(hypothesisService)
                .requireReviewable(42L, context, ReviewAction.ACCEPT);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request("/api/dsl/hypotheses/42/accept"),
                response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(400);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"HYPOTHESIS_NOT_REVIEWABLE\"")
                .contains("not in a state that permits this review action")
                .doesNotContain("cannot be accepted from ACCEPTED")
                .doesNotContain("42");
        verify(readinessService, never()).inspect(any());
    }

    @Test
    void arbitraryExceptionTextIsNeverReflectedToTheClient()
            throws Exception {
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "repository=secret-repo workspace=tenant-a path=/tmp/private"))
                .when(hypothesisService)
                .requireReviewable(42L, context, ReviewAction.REJECT);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request("/api/dsl/hypotheses/42/reject"),
                response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(400);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"REVIEW_OPERATION_REJECTED\"")
                .contains("cannot be completed in the current state")
                .doesNotContain("secret-repo", "tenant-a", "/tmp/private");
        verify(readinessService, never()).inspect(any());
    }

    @Test
    void malformedIfMatchUsesAStableSafePreconditionError()
            throws Exception {
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(readinessService.inspect(context)).thenReturn(ready(HEAD_A));

        MockHttpServletRequest request = request(
                "/api/dsl/hypotheses/42/accept");
        request.addHeader(HttpHeaders.IF_MATCH, "not-a-git-etag");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(400);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INVALID_PRECONDITION\"")
                .contains("strong quoted full Git commit ID")
                .doesNotContain("not-a-git-etag");
        verify(hypothesisService, never()).review(
                any(), any(), any(), any(), any());
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

    private void assertInvalidIdempotencyKey(String value) throws Exception {
        MockHttpServletRequest request = request(
                "/api/dsl/hypotheses/42/accept");
        request.addHeader("Idempotency-Key", value);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(400);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INVALID_IDEMPOTENCY_KEY\"")
                .contains("visible ASCII characters without whitespace")
                .doesNotContain(value);
        verify(workspaceResolver, never()).resolveCurrentRepositoryContext();
        verifyNoInteractions(readinessService);
        verifyNoInteractions(hypothesisService);
    }

    private void arrangeSuccessfulReview(
            ReviewAction action,
            String expectedHead) throws Exception {
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(readinessService.inspect(context)).thenReturn(ready(HEAD_A));
        when(hypothesisService.review(
                eq(42L),
                eq(context),
                eq(expectedHead),
                any(CommandMetadata.class),
                eq(action)))
                .thenReturn(reviewResult());
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }

    private static void assertNoStoreJson(
            MockHttpServletResponse response) {
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
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
