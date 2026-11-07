package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateWebDavLockManager.TemplateLock;

import java.io.IOException;
import java.time.Instant;

/** A resource operation and its lease bookkeeping share one cross-instance fence. */
interface DocumentTemplateWebDavLockStore {
    <T> T locked(String resource, Operation<T> operation) throws IOException;

    interface Lease {
        TemplateLock current();
        Instant now();
        void save(TemplateLock lock);
        void remove();
    }

    @FunctionalInterface
    interface Operation<T> {
        T execute(Lease lease) throws IOException;
    }
}
