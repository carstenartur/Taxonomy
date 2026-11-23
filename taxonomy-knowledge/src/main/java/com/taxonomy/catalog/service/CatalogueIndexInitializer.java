package com.taxonomy.catalog.service;

/** Catalogue-owned contract for validating full-text indexes before readiness. */
@FunctionalInterface
public interface CatalogueIndexInitializer {

    /**
     * Validate and, when necessary, repair the catalogue indexes, reporting progress
     * through the catalogue's initialization state.
     *
     * @throws InterruptedException when initialization is interrupted
     */
    void ensureCatalogueIndexes(AppInitializationStateService state) throws InterruptedException;
}
