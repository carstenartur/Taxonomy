package com.taxonomy.versioning.service;

import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.search.mapper.orm.Search;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/** Rebuilds only the Hibernate Search index owned by commit-history projections. */
@Service
public class CommitIndexSearchRebuilder {

    private final EntityManagerFactory entityManagerFactory;

    public CommitIndexSearchRebuilder(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    /**
     * Every persisted row must have exactly one document with the same scope and text.
     * A second database observation rejects source changes made during verification.
     * Older indexes without fingerprints are incomplete and are rebuilt once.
     */
    public boolean hasCompleteCoverage(long expectedCount) {
        Map<Long, String> expected = databaseFingerprints();
        if (expected.size() != expectedCount) return false;
        Map<Long, String> remaining = new HashMap<>(expected);
        try (var manager = entityManagerFactory.createEntityManager();
             var documents = Search.session(manager).search(ArchitectureCommitIndex.class)
                     .select(f -> f.composite(IndexedFingerprint::new,
                             f.id(Long.class), f.field("searchFingerprint", String.class)))
                     .where(f -> f.matchAll()).scroll(256)) {
            for (var page = documents.next(); page.hasHits(); page = documents.next()) {
                for (var document : page.hits()) {
                    String fingerprint = remaining.remove(document.id());
                    if (fingerprint == null || !fingerprint.equals(document.fingerprint())) return false;
                }
            }
        }
        return remaining.isEmpty() && expected.equals(databaseFingerprints());
    }

    private Map<Long, String> databaseFingerprints() {
        Map<Long, String> result = new HashMap<>();
        try (var manager = entityManagerFactory.createEntityManager()) {
            long after = 0;
            while (true) {
                var page = manager.createQuery("""
                        select row from ArchitectureCommitIndex row where row.id > :after order by row.id
                        """, ArchitectureCommitIndex.class).setParameter("after", after).setMaxResults(256).getResultList();
                if (page.isEmpty()) return result;
                for (var row : page) result.put(row.getId(), row.getSearchFingerprint());
                after = page.getLast().getId();
                manager.clear();
            }
        }
    }

    private record IndexedFingerprint(Long id, String fingerprint) { }

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
