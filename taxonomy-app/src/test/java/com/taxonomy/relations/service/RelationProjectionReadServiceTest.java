package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationProjectionUnavailableException;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationProjectionReadServiceTest {

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
    void readyProjectionIsTheOnlyNormalProductReadSource() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        RelationDecisionProjection projection = projection(
                17L,
                "BP-1",
                RelationType.SUPPORTS,
                "CP-2",
                "manual",
                "a".repeat(40));
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.READY,
                "a".repeat(40),
                "a".repeat(40),
                List.of(projection)));
        when(nodeRepository.findByCodeIn(any(Collection.class)))
                .thenReturn(List.of(
                        node("BP-1", "Business Process"),
                        node("CP-2", "Capability")));

        var result = service.readAll(context);

        assertThat(result.readModel()).isEqualTo(ReadModel.PROJECTION);
        assertThat(result.readinessState()).isEqualTo(ReadinessState.READY);
        assertThat(result.authoritativeCommitId()).isEqualTo("a".repeat(40));
        assertThat(result.relations()).singleElement().satisfies(relation -> {
            assertThat(relation.getId()).isEqualTo(17L);
            assertThat(relation.getSourceCode()).isEqualTo("BP-1");
            assertThat(relation.getSourceName()).isEqualTo("Business Process");
            assertThat(relation.getTargetCode()).isEqualTo("CP-2");
            assertThat(relation.getTargetName()).isEqualTo("Capability");
            assertThat(relation.getRelationType()).isEqualTo("SUPPORTS");
            assertThat(relation.getProvenance()).isEqualTo("manual");
        });
        verifyNoInteractions(legacyRelationService, repositoryService);
        verify(recoveryService, never()).pendingCount(context);
    }

    @Test
    void readyIdentitySnapshotAvoidsDtoAndNameMaterialization() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.READY,
                "a".repeat(40),
                "a".repeat(40),
                List.of(
                        projection(
                                1L, "BP", RelationType.SUPPORTS, "CP",
                                "manual", "a".repeat(40)),
                        projection(
                                2L, "CP", RelationType.REALIZES, "CR",
                                "manual", "a".repeat(40)))));

        var snapshot = service.readIdentitySnapshot(context);

        assertThat(snapshot.readModel()).isEqualTo(ReadModel.PROJECTION);
        assertThat(snapshot.readinessState()).isEqualTo(ReadinessState.READY);
        assertThat(snapshot.authoritativeCommitId()).isEqualTo("a".repeat(40));
        assertThat(snapshot.contains("BP", RelationType.SUPPORTS, "CP"))
                .isTrue();
        assertThat(snapshot.contains("BP", RelationType.SUPPORTS, "CR"))
                .isFalse();
        verifyNoInteractions(
                nodeRepository, legacyRelationService, repositoryService);
        verify(recoveryService, never()).pendingCount(context);
    }

    @Test
    void identitySnapshotsRemainBranchLocal() {
        RepositoryContext branchA = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        RepositoryContext branchB = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/b", "alice");
        when(readinessService.inspect(branchA)).thenReturn(new Readiness(
                ReadinessState.READY,
                "a".repeat(40),
                "a".repeat(40),
                List.of(projection(
                        1L, "BP", RelationType.SUPPORTS, "CP",
                        null, "a".repeat(40)))));
        when(readinessService.inspect(branchB)).thenReturn(new Readiness(
                ReadinessState.READY,
                "b".repeat(40),
                "b".repeat(40),
                List.of(projection(
                        2L, "BP", RelationType.SUPPORTS, "CR",
                        null, "b".repeat(40)))));

        var snapshotA = service.readIdentitySnapshot(branchA);
        var snapshotB = service.readIdentitySnapshot(branchB);

        assertThat(snapshotA.contains("BP", RelationType.SUPPORTS, "CP"))
                .isTrue();
        assertThat(snapshotA.contains("BP", RelationType.SUPPORTS, "CR"))
                .isFalse();
        assertThat(snapshotB.contains("BP", RelationType.SUPPORTS, "CP"))
                .isFalse();
        assertThat(snapshotB.contains("BP", RelationType.SUPPORTS, "CR"))
                .isTrue();
    }

    @Test
    void primaryDefaultBranchMayUseLegacyOnlyBeforeAnyBuildOrFailure() {
        RepositoryContext context = RepositoryContext.centralRead(
                "primary", "draft", "alice");
        TaxonomyRelationDto legacy = relation(
                8L, "BP", RelationType.SUPPORTS, "CP");
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.NOT_BUILT,
                "b".repeat(40),
                null,
                List.of()));
        when(recoveryService.pendingCount(context)).thenReturn(0L);
        when(repositoryService.getPrimaryRepository()).thenReturn(
                primary("primary", "draft"));
        when(legacyRelationService.getAllRelationsInContext(context))
                .thenReturn(List.of(legacy));

        var result = service.readAll(context);

        assertThat(result.readModel()).isEqualTo(ReadModel.LEGACY_FALLBACK);
        assertThat(result.readinessState()).isEqualTo(ReadinessState.NOT_BUILT);
        assertThat(result.authoritativeCommitId()).isEqualTo("b".repeat(40));
        assertThat(result.relations()).containsExactly(legacy);
    }

    @Test
    void identitySnapshotUsesTheSamePrimaryMigrationFallback() {
        RepositoryContext context = RepositoryContext.centralRead(
                "primary", "draft", "alice");
        when(readinessService.inspect(context)).thenReturn(notBuilt("b"));
        when(recoveryService.pendingCount(context)).thenReturn(0L);
        when(repositoryService.getPrimaryRepository()).thenReturn(
                primary("primary", "draft"));
        when(legacyRelationService.getAllRelationsInContext(context))
                .thenReturn(List.of(relation(
                        8L, "BP", RelationType.SUPPORTS, "CP")));

        var snapshot = service.readIdentitySnapshot(context);

        assertThat(snapshot.readModel()).isEqualTo(ReadModel.LEGACY_FALLBACK);
        assertThat(snapshot.readinessState()).isEqualTo(ReadinessState.NOT_BUILT);
        assertThat(snapshot.contains("BP", RelationType.SUPPORTS, "CP"))
                .isTrue();
        verifyNoInteractions(nodeRepository);
    }

    @Test
    void pendingGitProjectionBlocksTheMigrationFallback() {
        RepositoryContext context = RepositoryContext.centralRead(
                "primary", "draft", "alice");
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.NOT_BUILT,
                "c".repeat(40),
                null,
                List.of()));
        when(recoveryService.pendingCount(context)).thenReturn(1L);

        assertThatThrownBy(() -> service.readAll(context))
                .isInstanceOfSatisfying(
                        RelationProjectionUnavailableException.class,
                        error -> {
                            assertThat(error.getReadinessState())
                                    .isEqualTo(ReadinessState.NOT_BUILT);
                            assertThat(error.getPendingRecoveryCount())
                                    .isEqualTo(1L);
                        });
        verifyNoInteractions(legacyRelationService, repositoryService);
    }

    @Test
    void workspaceAndForkReadsNeverBorrowTheLegacyOverlay() {
        RepositoryContext workspace = RepositoryContext.workspace(
                "primary", "workspace-a", "feature/a", "alice");
        RepositoryContext fork = new RepositoryContext(
                "fork-a", null, "draft", "alice",
                com.taxonomy.workspace.service.RepositoryScope.FORK);
        when(readinessService.inspect(workspace)).thenReturn(notBuilt("d"));
        when(readinessService.inspect(fork)).thenReturn(notBuilt("e"));
        when(recoveryService.pendingCount(workspace)).thenReturn(0L);
        when(recoveryService.pendingCount(fork)).thenReturn(0L);

        assertThatThrownBy(() -> service.readAll(workspace))
                .isInstanceOf(RelationProjectionUnavailableException.class);
        assertThatThrownBy(() -> service.readAll(fork))
                .isInstanceOf(RelationProjectionUnavailableException.class);
        verifyNoInteractions(legacyRelationService, repositoryService);
    }

    @Test
    void staleOrCorruptPrimaryProjectionFailsClosed() {
        RepositoryContext context = RepositoryContext.centralRead(
                "primary", "draft", "alice");
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.STALE,
                "f".repeat(40),
                "0".repeat(40),
                List.of()));
        when(recoveryService.pendingCount(context)).thenReturn(0L);

        assertThatThrownBy(() -> service.readAll(context))
                .isInstanceOfSatisfying(
                        RelationProjectionUnavailableException.class,
                        error -> assertThat(error.getReadinessState())
                                .isEqualTo(ReadinessState.STALE));
        assertThatThrownBy(() -> service.readIdentitySnapshot(context))
                .isInstanceOfSatisfying(
                        RelationProjectionUnavailableException.class,
                        error -> assertThat(error.getReadinessState())
                                .isEqualTo(ReadinessState.STALE));
        verifyNoInteractions(legacyRelationService, repositoryService);
    }

    @Test
    void typeFilterRunsBeforeNameResolutionAndDtoAllocation() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        RelationDecisionProjection supports = projection(
                1L, "BP", RelationType.SUPPORTS, "CP", null,
                "1".repeat(40));
        RelationDecisionProjection realizes = projection(
                2L, "UNUSED-SOURCE", RelationType.REALIZES,
                "UNUSED-TARGET", null, "1".repeat(40));
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.READY,
                "1".repeat(40),
                "1".repeat(40),
                List.of(supports, realizes)));
        when(nodeRepository.findByCodeIn(any(Collection.class)))
                .thenReturn(List.of());

        var result = service.readByType(context, RelationType.SUPPORTS);

        assertThat(result.relations())
                .extracting(TaxonomyRelationDto::getId)
                .containsExactly(1L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> codes = ArgumentCaptor.forClass(
                Collection.class);
        verify(nodeRepository).findByCodeIn(codes.capture());
        assertThat(codes.getValue())
                .containsExactlyInAnyOrder("BP", "CP")
                .doesNotContain("UNUSED-SOURCE", "UNUSED-TARGET");
    }

    @Test
    void nodeFilterReturnsIncomingAndOutgoingRowsFromOneSnapshot() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        RelationDecisionProjection supports = projection(
                1L, "BP", RelationType.SUPPORTS, "CP", null,
                "1".repeat(40));
        RelationDecisionProjection realizes = projection(
                2L, "CP", RelationType.REALIZES, "CR", null,
                "1".repeat(40));
        RelationDecisionProjection unrelated = projection(
                3L, "APP", RelationType.DEPENDS_ON, "TECH", null,
                "1".repeat(40));
        when(readinessService.inspect(context)).thenReturn(new Readiness(
                ReadinessState.READY,
                "1".repeat(40),
                "1".repeat(40),
                List.of(supports, realizes, unrelated)));
        when(nodeRepository.findByCodeIn(any(Collection.class)))
                .thenReturn(List.of());

        assertThat(service.readForNode(context, "CP").relations())
                .extracting(TaxonomyRelationDto::getId)
                .containsExactly(1L, 2L);
    }

    private static Readiness notBuilt(String digit) {
        return new Readiness(
                ReadinessState.NOT_BUILT,
                digit.repeat(40),
                null,
                List.of());
    }

    private static RelationDecisionProjection projection(
            Long id,
            String source,
            RelationType type,
            String target,
            String provenance,
            String commit) {
        RelationDecisionProjection projection = new RelationDecisionProjection();
        projection.setId(id);
        projection.setRepositoryId("repo-a");
        projection.setWorkspaceId("workspace-a");
        projection.setBranch("feature/a");
        projection.setSourceCode(source);
        projection.setRelationType(type);
        projection.setTargetCode(target);
        projection.setRelationPresent(true);
        projection.setProvenance(provenance);
        projection.setAuthoritativeCommitId(commit);
        projection.setCausationId("rebuild:" + commit);
        return projection;
    }

    private static TaxonomyRelationDto relation(
            Long id,
            String source,
            RelationType type,
            String target) {
        TaxonomyRelationDto relation = new TaxonomyRelationDto();
        relation.setId(id);
        relation.setSourceCode(source);
        relation.setRelationType(type.name());
        relation.setTargetCode(target);
        return relation;
    }

    private static TaxonomyNode node(String code, String name) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(name);
        return node;
    }

    private static SystemRepository primary(String id, String branch) {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(id);
        repository.setDefaultBranch(branch);
        repository.setPrimaryRepo(true);
        return repository;
    }
}
