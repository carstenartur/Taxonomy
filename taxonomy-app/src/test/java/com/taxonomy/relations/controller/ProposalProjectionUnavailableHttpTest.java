package com.taxonomy.relations.controller;

import com.taxonomy.dto.RelationProposalDto;
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

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getFirst(
                RelationApiController.PROJECTION_STATE_HEADER))
                .isEqualTo("STALE");
        assertThat(response.getHeaders().getFirst(
                RelationApiController.PENDING_RECOVERY_HEADER))
                .isEqualTo("2");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG))
                .isEqualTo('"' + currentHead + '"');
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store");
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

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst(
                RelationApiController.PROJECTION_STATE_HEADER))
                .isEqualTo("BRANCH_MISSING");
        assertThat(response.getHeaders().containsKey(HttpHeaders.ETAG))
                .isFalse();
        assertThat(response.getHeaders().containsKey(
                RelationApiController.PENDING_RECOVERY_HEADER))
                .isFalse();
    }

    private static Map<String, String> validProposalBody() {
        return Map.of(
                "sourceCode", "BP",
                "relationType", "RELATED_TO",
                "limit", "3");
    }
}
