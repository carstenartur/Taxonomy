package com.taxonomy.backup;

/** Composition of module-owned projections that remove embedded history from stand documents. */
@FunctionalInterface
public interface BackupDocumentProjector {
    String currentState(String document);
}
