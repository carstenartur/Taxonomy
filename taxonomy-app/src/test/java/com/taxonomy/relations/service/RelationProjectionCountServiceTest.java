package com.taxonomy.relations.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationProjectionCountServiceTest {

    private RelationBranchProjectionReadinessService readinessService;
    private RelationProjectionRecoveryService recoveryService;
    private TaxonomyRelationService legacyRelationService;
    private TaxonomyNodeRepository nodeRepository;
    private SystemRepositoryService repositoryService;
    private RelationProjectionReadService service;

    @BeforeEach
    void setUp() {
        readinessService = mock(RelationBranchProjectionReadinessService.class);
        recoveryService = mock(RelationProjectionRecoveryService.class);
        legacyRelationService = mock(TaxonomyRelationService.class);
        nodeRepository = mock(TaxonomyNodeRepository.class);
        repositoryService = mock(SystemRepositoryService.class);
        service = new RelationProjectionReadService(
                readinessService,
                recoveryService,
                legacyRelationService,
                nodeRepository,
                repositoryService);
    }

    @Test
    void readyCountUsesProjectionRowsWithoutDtoOrNameMaterialization() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.READY,
                "a".repeat(40),
                "a".repeat(40),
                List.of(projection(1L), projection(2L))));

        var result = service.count(context);

        assertThat(result.readModel()).isEqualTo(ReadModel.PROJECTION);
        assertThat(result.readinessState()).isEqualTo(ReadinessState.READY);
        assertThat(result.authoritativeCommitId()).isEqualTo("a".repeat(40));
        assertThat(result.count()).isEqualTo(2L);
        verify(recoveryService, never()).pendingCount(context);
        verifyNoInteractions(
                legacyRelationService, nodeRepository, repositoryService);
    }

    @Test
    void migrationFallbackUsesRepositoryCountWithoutLoadingDtos() {
        RepositoryContext context = RepositoryContext.centralRead(
                "primary", "draft", "alice");
        SystemRepository primary = new SystemRepository();
        primary.setRepositoryId("primary");
        primary.setDefaultBranch("draft");
        primary.setPrimaryRepo(true);
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.NOT_BUILT,
                "b".repeat(40),
                null,
                List.of()));
        when(recoveryService.pendingCount(context)).thenReturn(0L);
        when(repositoryService.getPrimaryRepository()).thenReturn(primary);
        when(legacyRelationService.countRelationsInContext(context))
                .thenReturn(37L);

        var result = service.count(context);

        assertThat(result.readModel()).isEqualTo(ReadModel.LEGACY_FALLBACK);
        assertThat(result.count()).isEqualTo(37L);
        verify(legacyRelationService).countRelationsInContext(context);
        verifyNoInteractions(nodeRepository);
    }

    private static RelationDecisionProjection projection(Long id) {
        RelationDecisionProjection projection = new RelationDecisionProjection();
        projection.setId(id);
        projection.setRepositoryId("repo-a");
        projection.setWorkspaceId("workspace-a");
        projection.setBranch("feature/a");
        projection.setSourceCode("BP-" + id);
        projection.setRelationType(RelationType.SUPPORTS);
        projection.setTargetCode("CP-" + id);
        projection.setRelationPresent(true);
        projection.setAuthoritativeCommitId("a".repeat(40));
        projection.setCausationId("rebuild:" + id);
        return projection;
    }
}
