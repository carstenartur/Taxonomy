package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.CatalogueIndexInitializer;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.pojo.massindexing.MassIndexingMonitor;
import org.hibernate.search.mapper.pojo.work.IndexingPlanSynchronizationStrategy;
import org.hibernate.search.util.common.SearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Validates and repairs catalogue text indexes, and refreshes their optional
 * vectors in bounded batches without taking existing full-text search offline.
 *
 * <p>Keeping nodes and relations explicit is intentional: node semantic search
 * is the user-facing readiness boundary and must not wait behind relation or
 * unrelated indexed entity types selected through an {@code Object.class}
 * scope.</p>
 */
@Service
public class LocalEmbeddingIndexRebuilder implements CatalogueIndexInitializer {
    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingIndexRebuilder.class);

    private final EntityManagerFactory entityManagerFactory;
    private final int loaderThreads;
    private final int batchSize;

    @Autowired
    private CatalogueRuntimePolicy catalogueRuntimePolicy = CatalogueRuntimePolicy.fullCatalogue();

    public LocalEmbeddingIndexRebuilder(
            EntityManagerFactory entityManagerFactory,
            @Value("${embedding.index.loader-threads:2}") int loaderThreads,
            @Value("${embedding.index.batch-size:16}") int batchSize) {
        this.entityManagerFactory = entityManagerFactory;
        this.loaderThreads = Math.max(1, loaderThreads);
        this.batchSize = Math.max(1, batchSize);
    }

    public void rebuildNodeIndex() throws InterruptedException {
        rebuildNodeIndex((processed, total) -> { });
    }

    public void rebuildRelationIndex() throws InterruptedException {
        rebuildRelationIndex((processed, total) -> { });
    }

    public void rebuildNodeIndex(BiConsumer<Long, Long> progress) throws InterruptedException {
        rebuild(TaxonomyNode.class, "select count(n) from TaxonomyNode n", progress);
    }

    public void rebuildRelationIndex(BiConsumer<Long, Long> progress) throws InterruptedException {
        rebuild(TaxonomyRelation.class, "select count(r) from TaxonomyRelation r", progress);
    }

    public void refreshNodeEmbeddings(BiConsumer<Long, Long> progress) throws InterruptedException {
        refreshEmbeddings(TaxonomyNode.class, "select n.id from TaxonomyNode n order by n.id",
                "select n from TaxonomyNode n where n.id in :ids", progress);
    }

    public void refreshRelationEmbeddings(BiConsumer<Long, Long> progress) throws InterruptedException {
        refreshEmbeddings(TaxonomyRelation.class, "select r.id from TaxonomyRelation r order by r.id",
                "select r from TaxonomyRelation r join fetch r.sourceNode join fetch r.targetNode where r.id in :ids",
                progress);
    }

    /**
     * Replace existing documents in bounded batches while preserving the complete
     * full-text index. A mass indexer purges first (and its non-purge mode adds
     * duplicate Lucene documents), which would invalidate full-text readiness
     * during this optional vector phase.
     */
    private void refreshEmbeddings(Class<?> type, String idQuery, String entityQuery,
            BiConsumer<Long, Long> progress) throws InterruptedException {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        List<Long> identifiers;
        try (var manager = entityManagerFactory.createEntityManager()) {
            identifiers = manager.createQuery(idQuery, Long.class).getResultList();
        }
        long total = identifiers.size();
        progress.accept(0L, total);
        if (identifiers.isEmpty()) return;
        var nextBatch = new AtomicInteger();
        var completed = new AtomicLong();
        var tasks = new ArrayList<Future<?>>();
        // Same loader/connection budget as mass indexing; the joining startup
        // worker holds no EntityManager or connection of its own.
        try (var workers = Executors.newFixedThreadPool(Math.min(loaderThreads, identifiers.size()),
                Thread.ofPlatform().daemon(true).name("embedding-refresh-", 0).factory())) {
            for (int worker = 0; worker < Math.min(loaderThreads, identifiers.size()); worker++) {
                tasks.add(workers.submit(() -> {
                    int offset;
                    while (!Thread.currentThread().isInterrupted()
                            && (offset = nextBatch.getAndAdd(batchSize)) < identifiers.size()) {
                        var batchIds = identifiers.subList(offset, Math.min(offset + batchSize, identifiers.size()));
                        // PostgreSQL needs a transaction to materialize the catalogue LOBs.
                        // Close it before inference so the worker releases its JDBC connection.
                        var snapshots = entityManagerFactory.callInTransaction(manager ->
                                manager.createQuery(entityQuery, type)
                                        .setParameter("ids", batchIds)
                                        .getResultList());
                        var vectors = new HashMap<String, EmbeddingBridgeSupport.PreparedEmbedding>();
                        var endpointIds = new TreeSet<Long>();
                        for (Object snapshot : snapshots) {
                            var vector = EmbeddingBridgeSupport.prepare(snapshot);
                            vectors.put(vector.contentKey(), vector);
                            if (snapshot instanceof TaxonomyRelation relation) {
                                endpointIds.add(relation.getSourceNode().getId());
                                endpointIds.add(relation.getTargetNode().getId());
                            }
                        }
                        if (Thread.currentThread().isInterrupted()) break;
                        applyPreparedEmbeddings(type, batchIds, endpointIds, vectors);
                        synchronized (completed) {
                            progress.accept(completed.addAndGet(batchIds.size()), total);
                        }
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("Embedding refresh worker was interrupted");
                    }
                }));
            }
            try {
                for (Future<?> task : tasks) task.get();
            } catch (InterruptedException interruption) {
                tasks.forEach(task -> task.cancel(true));
                workers.shutdownNow();
                throw interruption;
            } catch (ExecutionException failure) {
                tasks.forEach(task -> task.cancel(true));
                workers.shutdownNow();
                throw new IllegalStateException("Embedding refresh failed", failure.getCause());
            }
        }
    }

    /**
     * Keep slow inference outside transactions, then serialize the short document
     * replacement with ordinary ORM writes. A fresh fingerprint rejects edits or
     * deletions that committed during inference instead of restoring old content.
     */
    private void applyPreparedEmbeddings(Class<?> type, List<Long> identifiers, Set<Long> endpointIds,
            Map<String, EmbeddingBridgeSupport.PreparedEmbedding> vectors) {
        try (var manager = entityManagerFactory.createEntityManager()) {
            var transaction = manager.getTransaction();
            transaction.begin();
            try {
                Set<Long> lockedEndpoints = new HashSet<>();
                if (type == TaxonomyRelation.class && !endpointIds.isEmpty()) {
                    // Shared endpoints are locked in a stable order across workers.
                    var endpoints = manager.createQuery(
                                    "select n from TaxonomyNode n where n.id in :ids order by n.id", TaxonomyNode.class)
                            .setParameter("ids", endpointIds).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                    for (TaxonomyNode endpoint : endpoints) {
                        manager.refresh(endpoint);
                        lockedEndpoints.add(endpoint.getId());
                    }
                }
                String entityName = type == TaxonomyNode.class ? "TaxonomyNode" : "TaxonomyRelation";
                var current = manager.createQuery("select row from " + entityName
                                + " row where row.id in :ids order by row.id", type)
                        .setParameter("ids", identifiers).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                var search = Search.session(manager);
                search.indexingPlanSynchronizationStrategy(IndexingPlanSynchronizationStrategy.sync());
                search.indexingPlanFilter(filter -> filter.exclude(type == TaxonomyNode.class
                        ? TaxonomyRelation.class : TaxonomyNode.class));
                for (Object entity : current) {
                    // Some MVCC databases can return pre-wait values from SELECT FOR UPDATE.
                    // Reread after the row lock is held, before checking its content identity.
                    manager.refresh(entity);
                    if (entity instanceof TaxonomyRelation relation
                            && (!lockedEndpoints.contains(relation.getSourceNode().getId())
                            || !lockedEndpoints.contains(relation.getTargetNode().getId()))) continue;
                    if (vectors.containsKey(EmbeddingBridgeSupport.contentKey(entity))) {
                        search.indexingPlan().addOrUpdate(entity);
                    }
                }
                // Synchronous backend completion precedes lock release at commit.
                EmbeddingBridgeSupport.withPrepared(vectors, () -> search.indexingPlan().execute());
                transaction.commit();
            } catch (RuntimeException | Error failure) {
                if (transaction.isActive()) transaction.rollback();
                throw failure;
            }
        }
    }

    private void rebuild(Class<?> type, String countQuery, BiConsumer<Long, Long> progress)
            throws InterruptedException {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        long total;
        try (var manager = entityManagerFactory.createEntityManager()) {
            total = manager.createQuery(countQuery, Long.class).getSingleResult();
        }
        // Do not hold a JDBC connection while the bounded mass indexer borrows its own.
        var completed = new AtomicLong();
        progress.accept(0L, total);
        Search.mapping(entityManagerFactory)
                .scope(type)
                .massIndexer()
                .typesToIndexInParallel(1)
                .threadsToLoadObjects(loaderThreads)
                .batchSizeToLoadObjects(batchSize)
                .monitor(new MassIndexingMonitor() {
                    @Override public void documentsAdded(long count) {
                        synchronized (completed) {
                            progress.accept(completed.addAndGet(count), total);
                        }
                    }
                    @Override public void documentsBuilt(long count) { }
                    @Override public void entitiesLoaded(long count) { }
                    @Override public void indexingCompleted() { }
                })
                .startAndWait();
    }

    /** Full-text readiness is verified separately from optional semantic readiness. */
    @Override
    public void ensureCatalogueIndexes(AppInitializationStateService state) throws InterruptedException {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        Search.mapping(entityManagerFactory).scope(java.util.List.of(TaxonomyNode.class, TaxonomyRelation.class))
                .workspace().refresh();
        state.updateProgress(AppInitializationStateService.State.BUILDING_INDEX, "CHECKING_NODE_INDEX",
                "Checking taxonomy-node search index", 0, -1);
        if (!isNodeIndexCurrent()) {
            rebuildNodeIndex((processed, total) -> state.updateProgress(
                    AppInitializationStateService.State.BUILDING_INDEX, "INDEXING_NODES",
                    "Building taxonomy-node search index", processed, total));
            if (!isNodeIndexCurrent()) throw new IllegalStateException("Node search index validation failed");
        }
        state.updateProgress(AppInitializationStateService.State.BUILDING_INDEX, "CHECKING_RELATION_INDEX",
                "Checking taxonomy-relation search index", 0, -1);
        if (!isRelationIndexCurrent()) {
            rebuildRelationIndex((processed, total) -> state.updateProgress(
                    AppInitializationStateService.State.BUILDING_INDEX, "INDEXING_RELATIONS",
                    "Building taxonomy-relation search index", processed, total));
            if (!isRelationIndexCurrent()) throw new IllegalStateException("Relation search index validation failed");
        }
    }

    public boolean isNodeIndexCurrent() {
        return contentMatches(TaxonomyNode.class, CatalogueIndexFingerprint.NODE_ROWS,
                CatalogueIndexFingerprint::nodeRow, null);
    }

    public boolean isRelationIndexCurrent() {
        return contentMatches(TaxonomyRelation.class, CatalogueIndexFingerprint.RELATION_ROWS,
                CatalogueIndexFingerprint::relationRow, null);
    }

    public boolean isNodeEmbeddingIndexCurrent(String modelKey) {
        return modelKey != null && !modelKey.isBlank() && contentMatches(TaxonomyNode.class,
                CatalogueIndexFingerprint.NODE_ROWS, CatalogueIndexFingerprint::nodeRow, modelKey);
    }

    public boolean isRelationEmbeddingIndexCurrent(String modelKey) {
        return modelKey != null && !modelKey.isBlank() && contentMatches(TaxonomyRelation.class,
                CatalogueIndexFingerprint.RELATION_ROWS, CatalogueIndexFingerprint::relationRow, modelKey);
    }

    private boolean contentMatches(Class<?> type, String rowQuery,
            Function<Object[], String> fingerprint, String modelKey) {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        try (var manager = entityManagerFactory.createEntityManager()) {
            Set<String> expected = entityManagerFactory.callInTransaction(
                    rows -> databaseFingerprints(rows, rowQuery, fingerprint));
            if (type == TaxonomyNode.class && expected.isEmpty()) return false;
            var search = Search.session(manager).search(type);
            long documentCount = search.where(f -> f.matchAll()).fetchTotalHitCount();
            if (documentCount != expected.size()) return false;
            Set<String> remaining = new HashSet<>(expected);
            try (var documents = search.select(f -> f.field(CatalogueIndexFingerprint.FIELD, String.class))
                    .where(f -> modelKey == null ? f.matchAll() : f.match().field("embeddingModel").matching(modelKey))
                    .scroll(256)) {
                for (var page = documents.next(); page.hasHits(); page = documents.next()) {
                    for (String fingerprintValue : page.hits()) {
                        if (!remaining.remove(fingerprintValue)) return false;
                    }
                }
            }
            return remaining.isEmpty()
                    // A concurrent catalogue/tenant mutation must not validate an older
                    // projection merely because its row count happens to be unchanged.
                    && expected.equals(entityManagerFactory.callInTransaction(
                            rows -> databaseFingerprints(rows, rowQuery, fingerprint)));
        } catch (SearchException failure) {
            log.warn("Catalogue search validation requires rebuilding {} (code=INDEX_VALIDATION_FAILED)",
                    type.getSimpleName());
            return false;
        }
    }

    private Set<String> databaseFingerprints(EntityManager manager, String query,
            Function<Object[], String> fingerprint) {
        Set<String> values = new HashSet<>();
        long after = 0;
        while (true) {
            var page = manager.createQuery(query, Object[].class).setParameter("after", after)
                    .setMaxResults(256).getResultList();
            if (page.isEmpty()) return values;
            for (Object[] row : page) values.add(fingerprint.apply(row));
            after = ((Number) page.getLast()[0]).longValue();
        }
    }

    /** A successful mass-indexer run can still contain documents whose embedding bridge failed. */
    public void verifyNodeEmbeddingCoverage(String modelKey) {
        verifyEmbeddingCoverage(TaxonomyNode.class, "select count(n) from TaxonomyNode n", modelKey);
        if (!isNodeEmbeddingIndexCurrent(modelKey)) throw new IllegalStateException("Node embedding projection is stale");
    }

    public void verifyRelationEmbeddingCoverage(String modelKey) {
        verifyEmbeddingCoverage(TaxonomyRelation.class, "select count(r) from TaxonomyRelation r", modelKey);
        if (!isRelationEmbeddingIndexCurrent(modelKey)) throw new IllegalStateException("Relation embedding projection is stale");
    }

    private void verifyEmbeddingCoverage(Class<?> entityType, String countQuery, String modelKey) {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        if (modelKey == null || modelKey.isBlank()) {
            throw new IllegalArgumentException("Embedding coverage requires the active model identity");
        }
        // A concurrent ordinary write may have won with write-sync: committed
        // documents must become reader-visible before deciding semantic readiness.
        Search.mapping(entityManagerFactory).scope(entityType).workspace().refresh();
        try (var manager = entityManagerFactory.createEntityManager()) {
            long expected = manager.createQuery(countQuery, Long.class).getSingleResult();
            long searchable = Search.session(manager).search(entityType)
                    .where(f -> f.match().field("embeddingModel").matching(modelKey))
                    .fetchTotalHitCount();
            if (searchable != expected) {
                throw new IllegalStateException("Embedding index coverage is incomplete for "
                        + entityType.getSimpleName() + ": expected=" + expected + ", searchable=" + searchable);
            }
        }
    }
}
