package com.taxonomy.versioning.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.HypothesisReviewPendingException;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.PendingPhase;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewAction;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisService;
import com.taxonomy.versioning.service.HypothesisReviewStateStore.HypothesisReviewConflictException;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GitHypothesisReviewCompatibilityFilterErrorContractTest {

    private static final String HEAD_A = "a".repeat(40);
    private static final String HEAD_B = "b".repeat(40);

    private GitAuthoritativeHypothesisService hypothesisService;
    private RelationBranchProjectionReadinessService readinessService;
    private WorkspaceResolver workspaceResolver;
    private SystemRepositoryService repositoryService;
    private RepositoryMembershipService membershipService;
    private GitHypothesisReviewCompatibilityFilter filter;
    private RepositoryContext context;

    @BeforeEach
    void setUp() {
        hypothesisService = mock(GitAuthoritativeHypothesisService.class);
        readinessService = mock(RelationBranchProjectionReadinessService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        repositoryService = mock(SystemRepositoryService.class);
        membershipService = mock(RepositoryMembershipService.class);
        filter = new GitHypothesisReviewCompatibilityFilter(
                hypothesisService,
                readinessService,
                workspaceResolver,
                repositoryService,
                membershipService);
        context = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "draft", "alice");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void outOfRangeHypothesisIdFailsBeforeContextResolution() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request("/api/dsl/hypotheses/999999999999999999999/accept"),
                response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(400);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INVALID_HYPOTHESIS_ID\"")
                .contains("outside the supported range")
                .doesNotContain("999999999999999999999");
        verifyNoInteractions(
                hypothesisService,
                readinessService,
                workspaceResolver,
                repositoryService,
                membershipService);
    }

    @Test
    void centralReaderWithoutMaintenancePermissionIsForbidden() throws Exception {
        RepositoryContext selected = RepositoryContext.centralRead(
                "repo-a", "main", "alice");
        SystemRepository repository = mock(SystemRepository.class);
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(selected);
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "alice")).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request("/api/dsl/hypotheses/42/accept"),
                response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"status\":\"FORBIDDEN\"")
                .doesNotContain("repo-a", "alice");
        verifyNoInteractions(hypothesisService, readinessService);
    }

    @Test
    void branchHeadConflictReturnsStablePreconditionPayloadAndCurrentEtag()
            throws Exception {
        arrangeReady();
        BranchHeadConflictException conflict =
                mock(BranchHeadConflictException.class);
        when(conflict.getExpectedHeadCommit()).thenReturn(HEAD_A);
        when(conflict.getActualHeadCommit()).thenReturn(HEAD_B);
        when(hypothesisService.review(
                eq(42L),
                eq(context),
                eq(HEAD_A),
                any(CommandMetadata.class),
                eq(ReviewAction.REJECT)))
                .thenThrow(conflict);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request("/api/dsl/hypotheses/42/reject"),
                response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(412);
        assertThat(response.getHeader(HttpHeaders.ETAG))
                .isEqualTo('"' + HEAD_B + '"');
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"projectionStatus\":\"PRECONDITION_FAILED\"")
                .contains("\"expectedHeadCommit\":\"" + HEAD_A + "\"")
                .contains("\"actualHeadCommit\":\"" + HEAD_B + "\"");
    }

    @Test
    void pendingProjectionReturnsAcceptedRecoveryEvidence() throws Exception {
        arrangeReady();
        CommandResult authority = authority();
        HypothesisReviewPendingException pending =
                mock(HypothesisReviewPendingException.class);
        when(pending.getHypothesisId()).thenReturn(42L);
        when(pending.getIntendedStatus()).thenReturn(HypothesisStatus.ACCEPTED);
        when(pending.getAuthority()).thenReturn(authority);
        when(pending.getPhase()).thenReturn(PendingPhase.PROJECTION);
        when(hypothesisService.review(
                eq(42L),
                eq(context),
                eq(HEAD_A),
                any(CommandMetadata.class),
                eq(ReviewAction.ACCEPT)))
                .thenThrow(pending);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request("/api/dsl/hypotheses/42/accept"),
                response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeader(HttpHeaders.ETAG))
                .isEqualTo('"' + HEAD_B + '"');
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"status\":\"ACCEPTED\"")
                .contains("\"projectionStatus\":\"PENDING_RECOVERY\"")
                .contains("\"pendingPhase\":\"PROJECTION\"")
                .contains("\"authoritativeCommitId\":\"" + HEAD_B + "\"");
    }

    @Test
    void bookkeepingConflictReturnsStableConflictEvidence() throws Exception {
        arrangeReady();
        when(hypothesisService.review(
                eq(42L),
                eq(context),
                eq(HEAD_A),
                any(CommandMetadata.class),
                eq(ReviewAction.ACCEPT)))
                .thenThrow(new HypothesisReviewConflictException(
                        42L,
                        HypothesisStatus.PROPOSED,
                        HypothesisStatus.REJECTED));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request("/api/dsl/hypotheses/42/accept"),
                response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(409);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"hypothesisId\":42")
                .contains("\"expectedStatus\":\"PROPOSED\"")
                .contains("\"actualStatus\":\"REJECTED\"")
                .contains("\"projectionStatus\":\"BOOKKEEPING_CONFLICT\"")
                .doesNotContain("changed from");
    }

    @Test
    void gitIoFailureUsesNonCacheableServiceUnavailablePayload()
            throws Exception {
        arrangeReady();
        when(hypothesisService.review(
                eq(42L),
                eq(context),
                eq(HEAD_A),
                any(CommandMetadata.class),
                eq(ReviewAction.REVERT)))
                .thenThrow(new IOException("/private/repository/path"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request("/api/dsl/hypotheses/42/revert"),
                response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(503);
        assertNoStoreJson(response);
        assertThat(response.getContentAsString())
                .contains("\"status\":\"GIT_UNAVAILABLE\"")
                .doesNotContain("/private/repository/path");
    }

    private void arrangeReady() throws Exception {
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(readinessService.inspect(context)).thenReturn(ready(HEAD_A));
    }

    private CommandResult authority() {
        return new CommandResult(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                RepositoryScope.WORKSPACE,
                HEAD_A,
                HEAD_B,
                ChangeKind.ADDED,
                true,
                "cause-42");
    }

    private static Readiness ready(String head) {
        return new Readiness(ReadinessState.READY, head, head, List.of());
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
}
