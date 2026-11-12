package com.taxonomy.catalog.snapshot;

import com.taxonomy.dsl.model.CatalogueDefinition;

import java.util.Optional;

/**
 * Explicit synchronous read binding. It is intentionally not inherited by executor threads:
 * each worker delivery must independently validate and bind its durable input.
 */
public final class FrozenCatalogueContext {
    private static final ThreadLocal<FrozenCatalogueView> CURRENT = new ThreadLocal<>();

    private FrozenCatalogueContext() {}

    public static FrozenCatalogueView current() { return CURRENT.get(); }

    /** Scoped metadata for consumers that need catalogue rules without access to node or embedding payloads. */
    public static Optional<CatalogueDefinition> definition() {
        return Optional.ofNullable(current()).map(FrozenCatalogueView::definition);
    }

    static Scope bind(FrozenCatalogueView view) {
        if (CURRENT.get() != null) throw new IllegalStateException("A catalogue snapshot is already bound");
        CURRENT.set(view);
        return new Scope(view);
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final FrozenCatalogueView view;
        private boolean closed;

        private Scope(FrozenCatalogueView view) { this.view = view; }

        @Override
        public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || CURRENT.get() != view) {
                throw new IllegalStateException("Catalogue snapshot scope must close on its owning thread");
            }
            CURRENT.remove();
            closed = true;
        }
    }
}
