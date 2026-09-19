package com.taxonomy.catalog.api;

import com.taxonomy.catalog.model.TaxonomyNode;
import java.util.Optional;

/** Read-only catalogue lookup used by analysis and portfolio validation. */
@FunctionalInterface
public interface TaxonomyNodeLookup {
    Optional<TaxonomyNode> findByCode(String code);
}
