package com.taxonomy.templates;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, commit-keyed cache for deterministic DOTX projections and validation results.
 *
 * <p>A Git commit is immutable, so changing a template naturally changes the key. The
 * synchronized single-flight boundary also prevents parallel WebDAV, health and export
 * requests from repeatedly packing the same large OOXML tree.</p>
 */
@Component
public final class DocumentTemplateMaterializationCache {

    static final int DEFAULT_MAX_ENTRIES = 64;
    static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024;
    private static final String BUILDER_VERSION = "dotx-v1";

    private final int maxEntries;
    private final long maxBytes;
    private final LinkedHashMap<Key, byte[]> packed =
            new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<Key, Boolean> validated =
            new LinkedHashMap<>(16, 0.75f, true);
    private long currentBytes;

    private final Counter packHits;
    private final Counter packMisses;
    private final Counter validationHits;
    private final Counter validationMisses;
    private final Counter evictions;
    private final Timer packTimer;

    public DocumentTemplateMaterializationCache(
            ObjectProvider<MeterRegistry> registries) {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES,
                registries == null ? null : registries.getIfAvailable());
    }

    DocumentTemplateMaterializationCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES, null);
    }

    DocumentTemplateMaterializationCache(
            int maxEntries,
            long maxBytes,
            MeterRegistry registry) {
        if (maxEntries < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("Template cache limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        packHits = counter(registry, "taxonomy.document.template.cache.pack.hits");
        packMisses = counter(registry, "taxonomy.document.template.cache.pack.misses");
        validationHits = counter(registry,
                "taxonomy.document.template.cache.validation.hits");
        validationMisses = counter(registry,
                "taxonomy.document.template.cache.validation.misses");
        evictions = counter(registry, "taxonomy.document.template.cache.evictions");
        packTimer = registry == null ? null : Timer.builder(
                "taxonomy.document.template.materialization")
                .description("Time spent producing deterministic DOTX bytes")
                .register(registry);
        if (registry != null) {
            registry.gauge("taxonomy.document.template.cache.entries", packed,
                    Map::size);
            registry.gauge("taxonomy.document.template.cache.bytes", this,
                    cache -> cache.sizeBytes());
        }
    }

    public synchronized byte[] packed(
            String templateId,
            String commitId,
            IoSupplier<byte[]> materializer) throws IOException {
        Key key = key(templateId, commitId);
        byte[] present = packed.get(key);
        if (present != null) {
            increment(packHits);
            return present.clone();
        }
        increment(packMisses);
        byte[] produced;
        if (packTimer == null) {
            produced = materializer.get();
        } else {
            try {
                produced = packTimer.recordCallable(materializer::get);
            } catch (IOException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IOException("Could not materialize DOTX template", exception);
            }
        }
        Objects.requireNonNull(produced, "materialized DOTX");
        if (produced.length <= maxBytes) {
            packed.put(key, produced.clone());
            currentBytes += produced.length;
            trim();
        }
        return produced.clone();
    }

    public synchronized void validateOnce(
            String templateId,
            String commitId,
            CheckedRunnable validation) throws IOException {
        Key key = key(templateId, commitId);
        if (Boolean.TRUE.equals(validated.get(key))) {
            increment(validationHits);
            return;
        }
        increment(validationMisses);
        validation.run();
        validated.put(key, Boolean.TRUE);
        while (validated.size() > maxEntries) {
            Key eldest = validated.keySet().iterator().next();
            validated.remove(eldest);
        }
    }

    synchronized int entries() {
        return packed.size();
    }

    synchronized long sizeBytes() {
        return currentBytes;
    }

    private void trim() {
        while (packed.size() > maxEntries || currentBytes > maxBytes) {
            Map.Entry<Key, byte[]> eldest = packed.entrySet().iterator().next();
            packed.remove(eldest.getKey());
            validated.remove(eldest.getKey());
            currentBytes -= eldest.getValue().length;
            increment(evictions);
        }
    }

    private static Key key(String templateId, String commitId) {
        return new Key(
                Objects.requireNonNull(templateId, "templateId"),
                Objects.requireNonNull(commitId, "commitId"),
                BUILDER_VERSION);
    }

    private static Counter counter(MeterRegistry registry, String name) {
        return registry == null ? null : Counter.builder(name).register(registry);
    }

    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private record Key(String templateId, String commitId, String builderVersion) {
    }

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws IOException;
    }
}
