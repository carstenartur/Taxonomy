package com.taxonomy.relations.controller;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadResult;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationProjectionUnavailableException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationApiControllerRepositoryScopeTest {

    private RelationProjectionReadService relationReadService;
    private WorkspaceResolver workspaceResolver;
    private RelationApiController controller;

    @BeforeEach
    void setUp() {
        relationReadService = mock(RelationProjectionReadService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        controller = new RelationApiController(
                relationReadService, workspaceResolver);
    }

    @Test
    void readsUseTheExactResolvedRepositoryContextAndExposeAuthority() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-b", "main", "alice");
        TaxonomyRelationDto relation = relation(
                17L, "BP", RelationType.SUPPORTS, "CP");
        ReadResult result = new ReadResult(
                ReadModel.PROJECTION,
                ReadinessState.READY,
                "a".repeat(40),
                List.of(relation));
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(relationReadService.readAll(context)).thenReturn(result);

        ResponseEntity<List<TaxonomyRelationDto>> response =
                controller.getRelations(null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(relation);
        assertThat(response.getHeaders().getETag())
                .isEqualTo("\"" + "a".repeat(40) + "\"");
        assertThat(response.getHeaders().getFirst(
                RelationApiController.READ_MODEL_HEADER))
                .isEqualTo("PROJECTION");
        assertThat(response.getHeaders().getFirst(
                RelationApiController.PROJECTION_STATE_HEADER))
                .isEqualTo("READY");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        verify(relationReadService).readAll(context);
    }

    @Test
    void typeAndNodeReadsUseTheSameProjectionBoundary() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        ReadResult result = new ReadResult(
                ReadModel.PROJECTION,
                ReadinessState.READY,
                "b".repeat(40),
                List.of());
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(relationReadService.readByType(
                context, RelationType.SUPPORTS)).thenReturn(result);
        when(relationReadService.readForNode(context, "BP"))
                .thenReturn(result);

        assertThat(controller.getRelations("supports").getStatusCode().value())
                .isEqualTo(200);
        assertThat(controller.getRelationsForNode("BP")
                .getStatusCode().value()).isEqualTo(200);

        verify(relationReadService).readByType(
                context, RelationType.SUPPORTS);
        verify(relationReadService).readForNode(context, "BP");
    }

    @Test
    void unsafeProjectionReturnsConflictAndActualPendingRecoveryCount() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(relationReadService.readAll(context)).thenThrow(
                new RelationProjectionUnavailableException(
                        context,
                        ReadinessState.STALE,
                        "c".repeat(40),
                        "d".repeat(40),
                        2));

        ResponseEntity<List<TaxonomyRelationDto>> response =
                controller.getRelations(null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getFirst(
                RelationApiController.PROJECTION_STATE_HEADER))
                .isEqualTo("STALE");
        assertThat(response.getHeaders().getFirst(
                RelationApiController.PENDING_RECOVERY_HEADER))
                .isEqualTo("2");
        assertThat(response.getHeaders().getETag())
                .isEqualTo("\"" + "c".repeat(40) + "\"");
    }

    @Test
    void unsafeProjectionWithoutPendingRecoveryOmitsRecoveryHeader() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(relationReadService.readAll(context)).thenThrow(
                new RelationProjectionUnavailableException(
                        context,
                        ReadinessState.CORRUPT,
                        "d".repeat(40),
                        "d".repeat(40),
                        0));

        ResponseEntity<List<TaxonomyRelationDto>> response =
                controller.getRelations(null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().containsKey(
                RelationApiController.PENDING_RECOVERY_HEADER)).isFalse();
    }

    @Test
    void countUsesTheExactSameReadResult() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "draft", "alice");
        ReadResult result = new ReadResult(
                ReadModel.LEGACY_FALLBACK,
                ReadinessState.NOT_BUILT,
                "e".repeat(40),
                List.of(
                        relation(1L, "BP", RelationType.SUPPORTS, "CP"),
                        relation(2L, "CP", RelationType.REALIZES, "CR")));
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
        when(relationReadService.readAll(context)).thenReturn(result);

        ResponseEntity<Map<String, Long>> response = controller.countRelations();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("count", 2L);
        assertThat(response.getHeaders().getFirst(
                RelationApiController.READ_MODEL_HEADER))
                .isEqualTo("LEGACY_FALLBACK");
    }

    @Test
    void dbFirstWriteRoutesAreExplicitlyGone() {
        assertThat(controller.createRelation(Map.of()).getStatusCode().value())
                .isEqualTo(410);
        assertThat(controller.deleteRelation(42L).getStatusCode().value())
                .isEqualTo(410);
    }

    private static TaxonomyRelationDto relation(
            Long id,
            String source,
            RelationType type,
            String target) {
        TaxonomyRelationDto relation = new TaxonomyRelationDto();
        relation.setId(id);
        relation.setSourceCode(source);
        relation.setTargetCode(target);
        relation.setRelationType(type.name());
        return relation;
    }
}
