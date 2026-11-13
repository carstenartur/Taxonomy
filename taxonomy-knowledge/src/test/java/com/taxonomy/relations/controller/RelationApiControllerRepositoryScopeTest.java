package com.taxonomy.relations.controller;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.CountResult;
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
        when(workspaceResolver.resolveUnambiguousRepositoryForRead())
                .thenReturn(context);
        when(relationReadService.readAll(context)).thenReturn(result);

        ResponseEntity<List<TaxonomyRelationDto>> response =
                controller.getRelations(null, null);

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
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Repository")).isEqualTo("repo-b");
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Branch")).isEqualTo("main");
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Workspace-Id")).isEmpty();
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
        when(workspaceResolver.resolveUnambiguousRepositoryForRead())
                .thenReturn(context);
        when(relationReadService.readByType(
                context, RelationType.SUPPORTS)).thenReturn(result);
        when(relationReadService.readForNode(context, "BP"))
                .thenReturn(result);

        assertThat(controller.getRelations("supports", null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(controller.getRelationsForNode("BP", null)
                .getStatusCode().value()).isEqualTo(200);

        verify(relationReadService).readByType(
                context, RelationType.SUPPORTS);
        verify(relationReadService).readForNode(context, "BP");
    }

    @Test
    void unsafeProjectionReturnsConflictAndActualPendingRecoveryCount() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "alice");
        when(workspaceResolver.resolveUnambiguousRepositoryForRead())
                .thenReturn(context);
        when(relationReadService.readAll(context)).thenThrow(
                new RelationProjectionUnavailableException(
                        context,
                        ReadinessState.STALE,
                        "c".repeat(40),
                        "d".repeat(40),
                        2));

        ResponseEntity<List<TaxonomyRelationDto>> response =
                controller.getRelations(null, null);

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
        when(workspaceResolver.resolveUnambiguousRepositoryForRead())
                .thenReturn(context);
        when(relationReadService.readAll(context)).thenThrow(
                new RelationProjectionUnavailableException(
                        context,
                        ReadinessState.CORRUPT,
                        "d".repeat(40),
                        "d".repeat(40),
                        0));

        ResponseEntity<List<TaxonomyRelationDto>> response =
                controller.getRelations(null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getFirst(
                RelationApiController.PENDING_RECOVERY_HEADER)).isNull();
    }

    @Test
    void countUsesDirectMetadataFromTheSameProvenReadSource() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "draft", "alice");
        CountResult result = new CountResult(
                ReadModel.LEGACY_FALLBACK,
                ReadinessState.NOT_BUILT,
                "e".repeat(40),
                2L);
        when(workspaceResolver.resolveUnambiguousRepositoryForRead())
                .thenReturn(context);
        when(relationReadService.count(context)).thenReturn(result);

        ResponseEntity<Map<String, Long>> response = controller.countRelations(null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("count", 2L);
        assertThat(response.getHeaders().getFirst(
                RelationApiController.READ_MODEL_HEADER))
                .isEqualTo("LEGACY_FALLBACK");
        assertThat(response.getHeaders().getETag())
                .isEqualTo("\"" + "e".repeat(40) + "\"");
        verify(relationReadService).count(context);
    }

    @Test
    void listNodeAndCountRejectPinsFromBeforeTheSelectedBranchMoved() {
        var context = RepositoryContext.workspace("repo", "A2", "draft", "alice");
        String head = "a".repeat(40);
        String old = "b".repeat(40);
        var result = new ReadResult(ReadModel.PROJECTION, ReadinessState.READY, head, List.of());
        when(workspaceResolver.resolveUnambiguousRepositoryForRead()).thenReturn(context);
        when(relationReadService.readAll(context)).thenReturn(result);
        when(relationReadService.readForNode(context, "BP")).thenReturn(result);
        when(relationReadService.count(context)).thenReturn(new CountResult(ReadModel.PROJECTION, ReadinessState.READY, head, 2));
        for (var response : List.of(controller.getRelations(null, old), controller.getRelationsForNode("BP", old), controller.countRelations(old))) {
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody()).isNull();
            assertThat(response.getHeaders().getFirst(RelationApiController.PROJECTION_STATE_HEADER)).isEqualTo("STALE");
            assertThat(response.getHeaders().getFirst("X-Taxonomy-Workspace-Id")).isEqualTo("A2");
            assertThat(response.getHeaders().getFirst("X-Taxonomy-Commit")).isEqualTo(head);
        }
        assertThat(controller.getRelations(null, "not-a-commit").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.getRelations(null, head).getStatusCode().value()).isEqualTo(200);
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
