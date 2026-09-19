package com.taxonomy.catalog.repository;

import com.taxonomy.catalog.model.TaxonomyRelation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaxonomyRelationRepositoryCompatibilityTest {

    @Test
    void noArgumentDeleteAllDelegatesOnlyThePrimaryScopedFindAllResult() {
        TaxonomyRelationRepository repository =
                mock(TaxonomyRelationRepository.class, CALLS_REAL_METHODS);
        List<TaxonomyRelation> primaryRelations =
                List.of(new TaxonomyRelation(), new TaxonomyRelation());
        when(repository.findAll()).thenReturn(primaryRelations);

        repository.deleteAll();

        verify(repository).findAll();
        verify(repository).deleteAll(primaryRelations);
    }

    @Test
    void noArgumentDeleteAllInBatchUsesTheSamePrimaryScopeBoundary() {
        TaxonomyRelationRepository repository =
                mock(TaxonomyRelationRepository.class, CALLS_REAL_METHODS);
        List<TaxonomyRelation> primaryRelations = List.of(new TaxonomyRelation());
        when(repository.findAll()).thenReturn(primaryRelations);

        repository.deleteAllInBatch();

        verify(repository).findAll();
        verify(repository).deleteAll(primaryRelations);
    }
}
