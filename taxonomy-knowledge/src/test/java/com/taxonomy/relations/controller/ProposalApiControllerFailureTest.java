package com.taxonomy.relations.controller;

import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.ReadOnlyRepositoryContextException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProposalApiControllerFailureTest {
    private static final String PREVIOUS = "a".repeat(40);
    private static final String CURRENT = "b".repeat(40);
    private final RelationProposalService proposals = mock(RelationProposalService.class);
    private final GitAuthoritativeProposalReviewService reviews = mock(GitAuthoritativeProposalReviewService.class);
    private final RelationBranchProjectionReadinessService readiness = mock(RelationBranchProjectionReadinessService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ProposalApiController controller = new ProposalApiController(proposals, reviews, readiness,
            resolver, mock(SystemRepositoryService.class), mock(RepositoryMembershipService.class));
    private final RepositoryContext context = RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice");

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(context);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new ReadOnlyRepositoryContextException("read only"), 403),
                Arguments.of(new IllegalArgumentException("unknown proposal"), 400),
                Arguments.of(new IllegalStateException("already reviewed"), 409),
                Arguments.of(new IOException("Git unavailable"), 503));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void reviewEndpointsTranslateFailuresWithoutInventingAuthority(Exception error, int status) throws Exception {
        when(reviews.reject(eq(42L), eq(context), eq(PREVIOUS), any())).thenThrow(error);
        when(reviews.revert(eq(42L), eq(context), eq(PREVIOUS), any())).thenThrow(error);
        var rejected = controller.rejectProposal(42L, '"' + PREVIOUS + '"', " request-17 ");
        var reverted = controller.revertProposal(42L, '"' + PREVIOUS + '"', " request-18 ");
        for (var response : List.of(rejected, reverted)) {
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            assertThat(response.getHeaders().getETag()).isNull();
            assertThat(response.getBody()).isNull();
        }
        verifyNoInteractions(readiness);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictResponseReportsBothExpectedAndActualHeadWithoutCaching(boolean branchExists) throws Exception {
        String actual = branchExists ? CURRENT : null;
        when(reviews.accept(eq(42L), eq(context), eq(PREVIOUS), any()))
                .thenThrow(new BranchHeadConflictException("draft", PREVIOUS, actual, "Head changed"));
        var response = controller.acceptProposal(42L, '"' + PREVIOUS + '"', "request-17");
        assertThat(response.getStatusCode().value()).isEqualTo(412);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getETag()).isEqualTo(branchExists ? '"' + CURRENT + '"' : null);
        assertThat(response.getBody()).containsEntry("proposalId", 42L).containsEntry("action", "ACCEPT")
                .containsEntry("projectionStatus", "PRECONDITION_FAILED")
                .containsEntry("expectedHeadCommit", PREVIOUS).containsEntry("actualHeadCommit", actual);
    }

    @Test
    void bulkRejectContinuesAfterInvalidItemsButStopsOnUnavailableGit() throws Exception {
        when(readiness.readCurrentHead(context)).thenReturn(PREVIOUS);
        when(reviews.reject(eq(2L), eq(context), eq(PREVIOUS), any()))
                .thenThrow(new IllegalStateException("Already reviewed"));
        when(reviews.reject(eq(3L), eq(context), eq(PREVIOUS), any()))
                .thenThrow(new IOException("Storage unavailable"));
        var response = controller.bulkAction(Map.of("ids", Arrays.asList(null, 2L, 3L, 4L), "action", "reject"), "bulk-17");
        assertThat(response.getStatusCode().value()).isEqualTo(207);
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + PREVIOUS + '"');
        assertThat(response.getBody()).containsEntry("total", 4).containsEntry("processed", 3)
                .containsEntry("failed", 3).containsEntry("projected", 0).containsEntry("complete", false);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).extracting(item -> item.get("projectionStatus"))
                .containsExactly("INVALID_ID", "REVIEW_REJECTED", "GIT_UNAVAILABLE");
        assertThat(items.get(0)).doesNotContainKey("detail");
        assertThat(items.get(1)).containsEntry("detail", "Already reviewed");
        assertThat(items.get(2)).containsEntry("detail", "Storage unavailable");
        verify(reviews, never()).reject(eq(4L), any(), any(), any());
    }

    @Test
    void bulkConflictStopsAndReturnsTheNewHeadInsteadOfRetryingAgainstStaleAuthority() throws Exception {
        when(readiness.readCurrentHead(context)).thenReturn(PREVIOUS);
        when(reviews.accept(eq(2L), eq(context), eq(PREVIOUS), any()))
                .thenThrow(new BranchHeadConflictException("draft", PREVIOUS, CURRENT, "Head changed"));
        var response = controller.bulkAction(Map.of("ids", List.of(2L, 3L), "action", "accept"), "bulk-18");
        assertThat(response.getStatusCode().value()).isEqualTo(207);
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + CURRENT + '"');
        assertThat(response.getBody()).containsEntry("processed", 1).containsEntry("failed", 1)
                .containsEntry("complete", false).containsEntry("authoritativeCommitId", CURRENT);
        verify(reviews, never()).accept(eq(3L), any(), any(), any());
    }

    @Test
    void absentBranchAndMalformedSingleRequestKeyNeverExecuteAReview() {
        assertThat(controller.revertProposal(42L).getStatusCode().value()).isEqualTo(404);
        var response = controller.rejectProposal(42L, '"' + PREVIOUS + '"', "request\nforged");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(reviews);
    }

    @Test
    void hypothesisWithMissingOptionalFieldsUsesDefaultConfidenceAndPreservesContext() {
        var dto = new RelationProposalDto();
        when(proposals.createFromHypothesisInContext("BP", "CP", RelationType.SUPPORTS, 0.5, null, context))
                .thenReturn(dto);
        var response = controller.createFromHypothesis(Map.of("sourceCode", "BP", "targetCode", "CP", "relationType", "supports"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(dto);
        verify(proposals).createFromHypothesisInContext("BP", "CP", RelationType.SUPPORTS, 0.5, null, context);
    }
}
