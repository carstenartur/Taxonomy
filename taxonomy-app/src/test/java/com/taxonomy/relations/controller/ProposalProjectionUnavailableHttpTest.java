package com.taxonomy.relations.controller;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationProjectionUnavailableException;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.relations.service.RelationReviewService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProposalProjectionUnavailableHttpTest {

    private RelationProposalService proposalService;
    private WorkspaceResolver workspaceResolver;
    private ProposalApiController controller;

    @BeforeEach
    void setUp() {
        proposalService = mock(RelationProposalService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        controller = new ProposalApiController(
                proposalService,
                mock(RelationReviewService.class),
                workspaceResolver,
                mock(SystemRepositoryService.class),
                mock(RepositoryMembershipService.class));
    }

    @Test
    void staleProjectionReturnsConflictAndRecoveryMetadata() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        String currentHead = "a".repeat(40);
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(proposalService.proposeRelationsInContext(
                eq("BP"),
                eq(RelationType.RELATED_TO),
                anyInt(),
                eq(context)))
                .thenThrow(new RelationProjectionUnavailableException(
                        context,
                        ReadinessState.STALE,
                        currentHead,
                        "b".repeat(40),
                        2L));

        var response = controller.proposeRelations(validProposalBody());

        assertStaleResponse(response.getStatusCode().value(),
                response.getHeaders(), currentHead, "2");
    }

    @Test
    void missingSelectedBranchReturnsNotFoundWithoutInventingAnEtag() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "missing", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(proposalService.proposeRelationsInContext(
                eq("BP"),
                eq(RelationType.RELATED_TO),
                anyInt(),
                eq(context)))
                .thenThrow(new RelationProjectionUnavailableException(
                        context,
                        ReadinessState.BRANCH_MISSING,
                        null,
                        null,
                        0L));

        var response = controller.proposeRelations(validProposalBody());

        assertMissingBranchResponse(
                response.getStatusCode().value(), response.getHeaders());
    }

    @Test
    void staleProjectionBlocksHypothesisConversionWithRecoveryMetadata() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        String currentHead = "c".repeat(40);
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(proposalService.createFromHypothesisInContext(
                eq("BP"),
                eq("CP"),
                eq(RelationType.RELATED_TO),
                eq(0.82),
                eq("model evidence"),
                eq(context)))
                .thenThrow(new RelationProjectionUnavailableException(
                        context,
                        ReadinessState.STALE,
                        currentHead,
                        "d".repeat(40),
                        3L));

        var response = controller.createFromHypothesis(validHypothesisBody());

        assertStaleResponse(response.getStatusCode().value(),
                response.getHeaders(), currentHead, "3");
    }

    @Test
    void missingBranchBlocksHypothesisConversionWithoutInventingAnEtag() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "missing", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(proposalService.createFromHypothesisInContext(
                eq("BP"),
                eq("CP"),
                eq(RelationType.RELATED_TO),
                eq(0.82),
                eq("model evidence"),
                eq(context)))
                .thenThrow(new RelationProjectionUnavailableException(
                        context,
                        ReadinessState.BRANCH_MISSING,
                        null,
                        null,
                        0L));

        var response = controller.createFromHypothesis(validHypothesisBody());

        assertMissingBranchResponse(
                response.getStatusCode().value(), response.getHeaders());
    }

    private static void assertStaleResponse(
            int status,
            HttpHeaders headers,
            String currentHead,
            String pendingRecoveryCount) {
        assertThat(status).isEqualTo(409);
        assertThat(headers.getFirst(
                RelationApiController.PROJECTION_STATE_HEADER))
                .isEqualTo("STALE");
        assertThat(headers.getFirst(
                RelationApiController.PENDING_RECOVERY_HEADER))
                .isEqualTo(pendingRecoveryCount);
        assertThat(headers.getFirst(HttpHeaders.ETAG))
                .isEqualTo('"' + currentHead + '"');
        assertThat(headers.getCacheControl()).isEqualTo("no-store");
    }

    private static void assertMissingBranchResponse(
            int status,
            HttpHeaders headers) {
        assertThat(status).isEqualTo(404);
        assertThat(headers.getFirst(
                RelationApiController.PROJECTION_STATE_HEADER))
                .isEqualTo("BRANCH_MISSING");
        assertThat(headers.getFirst(HttpHeaders.ETAG)).isNull();
        assertThat(headers.getFirst(
                RelationApiController.PENDING_RECOVERY_HEADER))
                .isNull();
        assertThat(headers.getCacheControl()).isEqualTo("no-store");
    }

    private static Map<String, String> validProposalBody() {
        return Map.of(
                "sourceCode", "BP",
                "relationType", "RELATED_TO",
                "limit", "3");
    }

    private static Map<String, Object> validHypothesisBody() {
        return Map.of(
                "sourceCode", "BP",
                "targetCode", "CP",
                "relationType", "RELATED_TO",
                "confidence", 0.82,
                "rationale", "model evidence");
    }
}
