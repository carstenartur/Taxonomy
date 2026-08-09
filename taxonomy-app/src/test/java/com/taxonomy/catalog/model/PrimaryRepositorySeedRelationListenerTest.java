package com.taxonomy.catalog.model;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrimaryRepositorySeedRelationListenerTest {

    private SystemRepositoryService repositoryService;
    private PrimaryRepositorySeedRelationListener listener;

    @BeforeEach
    void setUp() {
        repositoryService = mock(SystemRepositoryService.class);
        listener = new PrimaryRepositorySeedRelationListener(repositoryService);
    }

    @Test
    void excelSeedIsBoundToThePrimaryRepository() {
        SystemRepository primary = new SystemRepository();
        primary.setRepositoryId("primary-repo");
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
        verify(repositoryService, never()).getPrimaryRepository();
    }

    @Test
    void interactiveOrUnknownWritesWithoutRepositoryFailClosed() {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setProvenance("manual");

        assertThatIllegalStateException().isThrownBy(() ->
                listener.bindBuiltInSeedToPrimaryRepository(relation))
                .withMessageContaining("repositoryId is required");
        verify(repositoryService, never()).getPrimaryRepository();
    }
}
