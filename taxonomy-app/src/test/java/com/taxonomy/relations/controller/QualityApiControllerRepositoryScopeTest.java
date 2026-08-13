package com.taxonomy.relations.controller;

import com.taxonomy.dto.RelationQualityMetrics;
import com.taxonomy.dto.TopRejectedProposal;
import com.taxonomy.relations.service.RelationQualityService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class QualityApiControllerRepositoryScopeTest {

    private RelationQualityService qualityService;
    private WorkspaceResolver workspaceResolver;
    private QualityApiController controller;

    @BeforeEach
    void setUp() {
        qualityService = mock(RelationQualityService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        controller = new QualityApiController(qualityService, workspaceResolver);
    }

    @Test
    void dashboardUsesOneExactResolvedContextAndDisablesCaching() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        RelationQualityMetrics metrics = new RelationQualityMetrics(
                2, 1, 1, 0, 0.5, 0.8, 0.6, List.of(), List.of());
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(qualityService.calculateMetrics(context)).thenReturn(metrics);

        var response = controller.getMetrics();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(metrics);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(workspaceResolver).resolveCurrentRepositoryContext();
        verify(qualityService).calculateMetrics(context);
        verifyNoMoreInteractions(workspaceResolver, qualityService);
    }

    @Test
    void topRejectedRoutesLimitAndContextTogether() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "bob");
        TopRejectedProposal proposal = new TopRejectedProposal(
                "A", "Node A", "B", "Node B",
                "RELATED_TO", 0.9, "evidence");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(qualityService.topRejected(4, context))
                .thenReturn(List.of(proposal));

        var response = controller.getTopRejected(4);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(proposal);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(workspaceResolver).resolveCurrentRepositoryContext();
        verify(qualityService).topRejected(4, context);
        verifyNoMoreInteractions(workspaceResolver, qualityService);
    }
}
