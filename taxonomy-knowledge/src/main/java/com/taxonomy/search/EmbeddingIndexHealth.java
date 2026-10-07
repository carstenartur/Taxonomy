package com.taxonomy.search;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Latches skipped vector writes without retaining document text or backend exceptions.
 * Recovery requires a restart and complete rebuild: an in-process reset could forget
 * a failed write whose transaction has not yet finished indexing.
 */
@Component
public class EmbeddingIndexHealth {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexHealth.class);
    private final AtomicBoolean nodeFailure = new AtomicBoolean();
    private final AtomicBoolean relationFailure = new AtomicBoolean();

    void recordFailure(Object entity) {
        if (entity instanceof TaxonomyNode && nodeFailure.compareAndSet(false, true)) {
            log.warn("Node vector write failed (code=NODE_VECTOR_WRITE_FAILED)");
        } else if (entity instanceof TaxonomyRelation && relationFailure.compareAndSet(false, true)) {
            log.warn("Relation vector write failed (code=RELATION_VECTOR_WRITE_FAILED)");
        }
    }

    boolean hasNodeFailure() { return nodeFailure.get(); }
    boolean hasRelationFailure() { return relationFailure.get(); }
}
