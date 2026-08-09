package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.search.mapper.orm.Search;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs bounded Hibernate Search mass-indexing phases for the two entity types
 * that own local embedding vectors.
 *
 * <p>Keeping nodes and relations explicit is intentional: node semantic search
 * is the user-facing readiness boundary and must not wait behind relation or
 * unrelated indexed entity types selected through an {@code Object.class}
 * scope.</p>
 */
@Service
public class LocalEmbeddingIndexRebuilder {

    private final EntityManagerFactory entityManagerFactory;
    private final int loaderThreads;
    private final int batchSize;

    public LocalEmbeddingIndexRebuilder(
            EntityManagerFactory entityManagerFactory,
            @Value("${embedding.index.loader-threads:2}") int loaderThreads,
            @Value("${embedding.index.batch-size:16}") int batchSize) {
        this.entityManagerFactory = entityManagerFactory;
        this.loaderThreads = Math.max(1, loaderThreads);
        this.batchSize = Math.max(1, batchSize);
    }

    public void rebuildNodeIndex() throws InterruptedException {
        Search.mapping(entityManagerFactory)
                .scope(TaxonomyNode.class)
                .massIndexer()
                .typesToIndexInParallel(1)
                .threadsToLoadObjects(loaderThreads)
                .batchSizeToLoadObjects(batchSize)
                .startAndWait();
    }

    public void rebuildRelationIndex() throws InterruptedException {
        Search.mapping(entityManagerFactory)
                .scope(TaxonomyRelation.class)
                .massIndexer()
                .typesToIndexInParallel(1)
                .threadsToLoadObjects(loaderThreads)
                .batchSizeToLoadObjects(batchSize)
                .startAndWait();
    }
}
