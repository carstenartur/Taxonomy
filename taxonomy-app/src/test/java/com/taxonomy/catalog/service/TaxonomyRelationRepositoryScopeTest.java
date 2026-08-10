package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaxonomyRelationRepositoryScopeTest {

    private TaxonomyRelationRepository relationRepository;
    private TaxonomyNodeRepository nodeRepository;
    private SystemRepositoryService systemRepositoryService;
    private TaxonomyRelationService service;

    @BeforeEach
    void setUp() {
        relationRepository = mock(TaxonomyRelationRepository.class);
        nodeRepository = mock(TaxonomyNodeRepository.class);
        systemRepositoryService = mock(SystemRepositoryService.class);
        service = new TaxonomyRelationService(
                relationRepository, nodeRepository, systemRepositoryService);
    }

    @Test
    void centralReadUsesOnlyTheSelectedRepository() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "alice");
        when(relationRepository.findCentralByRepository("repo-a"))
                .thenReturn(List.of());

        assertThat(service.getAllRelationsInContext(context)).isEmpty();

        verify(relationRepository).findCentralByRepository("repo-a");
        verify(relationRepository, never()).findAll();
    }

    @Test
    void workspaceReadInheritsOnlyItsOwnRepositoryCentralBaseline() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(relationRepository.findVisibleByRepositoryAndWorkspaceAndNodeCode(
                        "repo-a", "workspace-a", "BP"))
                .thenReturn(List.of());

        assertThat(service.getRelationsForNodeInContext("BP", context)).isEmpty();

        verify(relationRepository).findVisibleByRepositoryAndWorkspaceAndNodeCode(
                "repo-a", "workspace-a", "BP");
        verify(relationRepository, never()).findVisibleByWorkspaceAndNodeCode(any(), any());
    }

    @Test
    void createPersistsRepositoryWorkspaceAndOwnerFromOneContext() {
        TaxonomyNode source = node("BP", "Business Process");
        TaxonomyNode target = node("CP", "Capability");
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(nodeRepository.findByCode("BP")).thenReturn(Optional.of(source));
        when(nodeRepository.findByCode("CP")).thenReturn(Optional.of(target));
        when(relationRepository.findVisibleByRepositoryAndWorkspaceAndSourceTargetType(
                        "repo-a", "workspace-a", "BP", "CP", RelationType.SUPPORTS))
                .thenReturn(List.of());
        when(relationRepository.save(any(TaxonomyRelation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createRelationInContext(
                "BP", "CP", RelationType.SUPPORTS, "description", "manual", context);

        ArgumentCaptor<TaxonomyRelation> captor =
                ArgumentCaptor.forClass(TaxonomyRelation.class);
        verify(relationRepository).save(captor.capture());
        TaxonomyRelation saved = captor.getValue();
        assertThat(saved.getRepositoryId()).isEqualTo("repo-a");
        assertThat(saved.getWorkspaceId()).isEqualTo("workspace-a");
        assertThat(saved.getWorkspaceScopeKey()).isEqualTo("workspace-a");
        assertThat(saved.getOwnerUsername()).isEqualTo("alice");
    }

    @Test
    void duplicateCheckCannotSeeAnEquivalentRelationFromAnotherRepository() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "alice");
        when(relationRepository.findCentralByRepositoryAndSourceTargetType(
                        "repo-a", "BP", "CP", RelationType.SUPPORTS))
                .thenReturn(List.of());

        assertThat(service.relationExistsVisibleInContext(
                "BP", "CP", RelationType.SUPPORTS, context)).isFalse();

        verify(relationRepository).findCentralByRepositoryAndSourceTargetType(
                "repo-a", "BP", "CP", RelationType.SUPPORTS);
        verify(relationRepository, never()).findSharedBySourceTargetType(any(), any(), any());
    }

    @Test
    void deleteRequiresExactRepositoryAndWorkspaceIdentity() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(relationRepository.findByIdInRepositoryWorkspace(
                        "repo-a", 42L, "workspace-a"))
                .thenReturn(Optional.empty());

        assertThatIllegalArgumentException().isThrownBy(() ->
                service.deleteRelationInContext(42L, context));

        verify(relationRepository).findByIdInRepositoryWorkspace(
                "repo-a", 42L, "workspace-a");
        verify(relationRepository, never()).delete(any());
    }

    @Test
    void compatibilityReadsResolveTheCatalogPrimaryInsteadOfGlobalData() {
        SystemRepository primary = new SystemRepository();
        primary.setRepositoryId("primary-repo");
        primary.setDefaultBranch("draft");
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(primary);
        when(relationRepository.findCentralByRepository("primary-repo"))
                .thenReturn(List.of());

        assertThat(service.getAllRelations((String) null)).isEmpty();

        verify(relationRepository).findCentralByRepository("primary-repo");
        verify(relationRepository, never()).findAll();
    }

    @Test
    void entityRejectsMissingRepositoryIdentityBeforePersistence() {
        TaxonomyRelation relation = new TaxonomyRelation();

        assertThatIllegalArgumentException().isThrownBy(() ->
                relation.setRepositoryId(" "));
    }

    private static TaxonomyNode node(String code, String name) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(name);
        return node;
    }
}
