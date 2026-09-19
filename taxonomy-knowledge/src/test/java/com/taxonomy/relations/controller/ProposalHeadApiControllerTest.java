package com.taxonomy.relations.controller;

import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalHeadApiControllerTest {

    private static final String HEAD = "a".repeat(40);

    private RelationBranchProjectionReadinessService readinessService;
    private WorkspaceResolver workspaceResolver;
    private ProposalHeadApiController controller;

    @BeforeEach
    void setUp() {
        readinessService = mock(RelationBranchProjectionReadinessService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        controller = new ProposalHeadApiController(
                readinessService, workspaceResolver);
    }

    @Test
    void returnsStrongEtagFromExactSelectedContextWithoutProjectionRead() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(readinessService.readCurrentHead(context)).thenReturn(HEAD);

        var response = controller.readHead();

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG))
                .isEqualTo('"' + HEAD + '"');
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store");
        verify(readinessService).readCurrentHead(context);
    }

    @Test
    void missingSelectedBranchReturnsNotFoundWithoutInventingEtag() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "missing", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(readinessService.readCurrentHead(context)).thenReturn(null);

        var response = controller.readHead();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isNull();
        assertThat(response.getHeaders().getFirst(
                RelationApiController.PROJECTION_STATE_HEADER))
                .isEqualTo("BRANCH_MISSING");
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store");
    }
}
