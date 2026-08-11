package com.taxonomy.architecture.service;

import com.taxonomy.architecture.model.ArchitectureCommitIndex;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.search.mapper.orm.Search;
import org.springframework.stereotype.Service;

/** Rebuilds only the Hibernate Search index owned by commit-history projections. */
@Service
public class CommitIndexSearchRebuilder {

    private final EntityManagerFactory entityManagerFactory;

    public CommitIndexSearchRebuilder(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    /**
     * Purge stale Lucene documents and reindex every currently persisted,
     * tenant-aware commit projection.
     */
    public void rebuildAll() throws InterruptedException {
        Search.mapping(entityManagerFactory)
                .scope(ArchitectureCommitIndex.class)
                .massIndexer()
                .purgeAllOnStart(true)
                .typesToIndexInParallel(1)
                .threadsToLoadObjects(1)
                .batchSizeToLoadObjects(25)
                .startAndWait();
    }
}
