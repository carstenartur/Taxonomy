package com.taxonomy.catalog.model;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrimaryRepositorySeedRelationListenerTest {

    private ObjectProvider<SystemRepositoryService> repositoryServiceProvider;
    private SystemRepositoryService repositoryService;
    private PrimaryRepositorySeedRelationListener listener;

    @BeforeEach
    void setUp() {
        repositoryServiceProvider = mock(ObjectProvider.class);
        repositoryService = mock(SystemRepositoryService.class);
        listener = new PrimaryRepositorySeedRelationListener(repositoryServiceProvider);
    }

    @Test
    void constructingListenerDoesNotResolveJpaBackedRepositoryService() {
        verify(repositoryServiceProvider, never()).getIfAvailable();
    }

    @Test
    void excelSeedIsBoundToThePrimaryRepository() {
        SystemRepository primary = new SystemRepository();
        primary.setRepositoryId("primary-repo");
        when(repositoryServiceProvider.getIfAvailable()).thenReturn(repositoryService);
        when(repositoryService.getPrimaryRepository()).thenReturn(primary);
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setProvenance("excel");

        listener.bindBuiltInSeedToPrimaryRepository(relation);

        assertThat(relation.getRepositoryId()).isEqualTo("primary-repo");
    }

    @Test
    void csvSeedIsBoundToThePrimaryRepository() {
        SystemRepository primary = new SystemRepository();
        primary.setRepositoryId("primary-repo");
        when(repositoryServiceProvider.getIfAvailable()).thenReturn(repositoryService);
        when(repositoryService.getPrimaryRepository()).thenReturn(primary);
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setProvenance("csv-framework:NAF");

        listener.bindBuiltInSeedToPrimaryRepository(relation);

        assertThat(relation.getRepositoryId()).isEqualTo("primary-repo");
    }

    @Test
    void explicitRepositoryIdentityIsNeverRewritten() {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setRepositoryId("repo-b");
        relation.setProvenance("excel");

        listener.bindBuiltInSeedToPrimaryRepository(relation);

        assertThat(relation.getRepositoryId()).isEqualTo("repo-b");
        verify(repositoryServiceProvider, never()).getIfAvailable();
    }

    @Test
    void interactiveOrUnknownWritesWithoutRepositoryFailClosed() {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setProvenance("manual");

        assertThatIllegalStateException().isThrownBy(() ->
                listener.bindBuiltInSeedToPrimaryRepository(relation))
                .withMessageContaining("repositoryId is required");
        verify(repositoryServiceProvider, never()).getIfAvailable();
    }

    @Test
    void unavailableRepositoryServiceFailsClosedAtSeedPersistenceTime() {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setProvenance("excel");
        when(repositoryServiceProvider.getIfAvailable()).thenReturn(null);

        assertThatIllegalStateException().isThrownBy(() ->
                listener.bindBuiltInSeedToPrimaryRepository(relation))
                .withMessageContaining("SystemRepositoryService is not available");
    }
}
