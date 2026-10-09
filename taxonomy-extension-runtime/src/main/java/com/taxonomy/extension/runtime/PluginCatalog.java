package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.shared.extension.*;
import org.pf4j.DefaultVersionManager;

import java.time.Duration;
import java.util.*;

/**
 * One publication point for all built-in and installed contributions. Lifecycle mutations and
 * lease acquisition share a monitor; metadata readers observe one immutable volatile snapshot.
 */
public final class PluginCatalog implements ExtensionCatalog {
    public record Contribution(PluginDescriptor descriptor, List<? extends TaxonomyExtension> extensions) {
        public Contribution { Objects.requireNonNull(descriptor); extensions = List.copyOf(extensions); }
    }

    private final Map<String, Generation> generations = new LinkedHashMap<>();
    private volatile Publication current = new Publication(new CatalogSnapshot(0, List.of(), List.of()), Map.of());

    @Override public CatalogSnapshot snapshot() { return current.snapshot(); }

    /** Validate admission before creating a classloader. Publication repeats this atomically. */
    public synchronized void validateDescriptor(PluginDescriptor descriptor) {
        Map<String, Generation> candidate = new LinkedHashMap<>(generations);
        String id = descriptor.identity().id();
        if (candidate.putIfAbsent(id, new Generation(new Contribution(descriptor, List.of()))) != null)
            throw new IllegalArgumentException("Duplicate plugin ID: " + id);
        validateDependencies(candidate);
    }

    public void publish(PluginDescriptor descriptor, List<? extends TaxonomyExtension> extensions) {
        publishAll(List.of(new Contribution(descriptor, extensions)));
    }

    public void publishAll(List<Contribution> contributions) {
        // Read plugin-provided metadata before entering the host monitor. No plugin callback
        // executes while publishing or while accounting for an admitted invocation.
        List<Generation> additions = List.copyOf(contributions).stream().map(Generation::new).toList();
        synchronized (this) {
            Map<String, Generation> candidate = new LinkedHashMap<>(generations);
            for (Generation generation : additions) {
                String id = generation.descriptor.identity().id();
                if (candidate.putIfAbsent(id, generation) != null)
                    throw new IllegalStateException("Duplicate plugin ID: " + id);
            }
            validateDependencies(candidate);
            Publication publication = publication(candidate, current.snapshot().revision() + 1);
            // All validation succeeded. These two mutations are invisible to acquisitions until
            // the monitor is released; readers receive the complete old or new publication.
            generations.clear(); generations.putAll(candidate); current = publication;
        }
    }

    @Override public synchronized <T extends TaxonomyExtension> ExtensionLease<T> acquire(
            ExtensionKey key, Class<T> expectedType) {
        Objects.requireNonNull(expectedType, "expectedType");
        Entry entry = current.entries().get(Objects.requireNonNull(key, "key"));
        if (entry == null) throw new ExtensionUnavailableException(key);
        if (!expectedType.isInstance(entry.extension))
            throw new IllegalArgumentException("Extension contract mismatch: " + key);
        entry.owner.references++;
        return new Lease<>(entry.owner, expectedType.cast(entry.extension));
    }

    @Override public synchronized <T extends TaxonomyExtension> List<ExtensionLease<T>> acquireAll(
            ExtensionKind kind, Class<T> expectedType) {
        Objects.requireNonNull(kind); Objects.requireNonNull(expectedType);
        return current.entries().entrySet().stream()
                .filter(e -> e.getKey().kind() == kind && expectedType.isInstance(e.getValue().extension))
                .sorted(Map.Entry.comparingByKey()).map(e -> acquire(e.getKey(), expectedType)).toList();
    }

    public synchronized void beginDraining(PluginIdentity identity) {
        Generation generation = generation(identity);
        if (generation.draining) return;
        for (Generation dependent : generations.values()) {
            if (!dependent.draining && dependent.descriptor.requires().stream()
                    .anyMatch(r -> r.id().equals(identity.id()))) {
                throw new IllegalStateException("Plugin is required by " + dependent.descriptor.identity().id());
            }
        }
        generation.draining = true;
        current = publication(generations, current.snapshot().revision() + 1);
    }

    public synchronized boolean awaitReleased(PluginIdentity identity, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("Drain timeout must not be negative");
        Generation generation = generation(identity);
        if (!generation.draining) throw new IllegalStateException("Plugin is not draining");
        long budget;
        try { budget = timeout.toNanos(); } catch (ArithmeticException tooLarge) { budget = Long.MAX_VALUE; }
        long started = System.nanoTime();
        while (generation.references != 0) {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) return false;
            wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
        }
        return true;
    }

    public synchronized void remove(PluginIdentity identity) {
        Generation generation = generation(identity);
        if (!generation.draining || generation.references != 0)
            throw new IllegalStateException("Plugin must finish draining before removal: " + identity.id());
        generations.remove(identity.id());
        current = publication(generations, current.snapshot().revision() + 1);
    }

    public synchronized int activeLeases(PluginIdentity identity) { return generation(identity).references; }

    private Generation generation(PluginIdentity identity) {
        Generation generation = generations.get(identity.id());
        if (generation == null || !generation.descriptor.identity().equals(identity))
            throw new IllegalArgumentException("Plugin version is not installed: " + identity.id());
        return generation;
    }

    private static Publication publication(Map<String, Generation> all, long revision) {
        Map<ExtensionKey, Entry> entries = new TreeMap<>();
        List<PluginDescriptor> plugins = all.values().stream().filter(g -> !g.draining)
                .map(g -> g.descriptor).sorted(Comparator.comparing(p -> p.identity().id())).toList();
        for (Generation generation : all.values()) {
            if (generation.draining) continue;
            for (Entry entry : generation.entries) {
                if (entries.putIfAbsent(entry.metadata.key(), entry) != null)
                    throw new IllegalStateException("Duplicate extension ID for kind " + entry.metadata.key().kind()
                            + ": normalized ID '" + entry.metadata.key().id() + "'");
            }
        }
        return new Publication(new CatalogSnapshot(revision, plugins,
                entries.values().stream().map(e -> e.metadata).toList()), Map.copyOf(entries));
    }

    private static void validateDependencies(Map<String, Generation> candidate) {
        DefaultVersionManager versions = new DefaultVersionManager();
        for (Generation generation : candidate.values()) {
            if (generation.draining) continue;
            PluginDescriptor plugin = generation.descriptor;
            // Parsing the artifact version is required even when nobody currently requires it.
            versions.compareVersions(plugin.identity().version(), plugin.identity().version());
            if (!versions.checkVersionConstraint(PluginDescriptor.HOST_API_VERSION, plugin.hostApiRange()))
                throw new IllegalArgumentException("Host API range is incompatible: " + plugin.identity().id());
            for (PluginRequirement requirement : plugin.requires()) {
                Generation dependency = candidate.get(requirement.id());
                if (dependency == null || dependency.draining)
                    throw new IllegalArgumentException("Missing plugin dependency: " + requirement.id());
                if (!versions.checkVersionConstraint(dependency.descriptor.identity().version(), requirement.versionRange()))
                    throw new IllegalArgumentException("Incompatible plugin dependency version: " + requirement.id());
            }
        }
        Set<String> visited = new HashSet<>();
        for (Generation generation : candidate.values()) {
            if (!generation.draining) visit(generation.descriptor.identity().id(), candidate, visited, new HashSet<>());
        }
    }

    private static void visit(String id, Map<String, Generation> candidate, Set<String> visited, Set<String> path) {
        if (visited.contains(id)) return;
        if (!path.add(id)) throw new IllegalArgumentException("Plugin dependency cycle: " + id);
        for (PluginRequirement requirement : candidate.get(id).descriptor.requires())
            visit(requirement.id(), candidate, visited, path);
        path.remove(id); visited.add(id);
    }

    private static final class Generation {
        final PluginDescriptor descriptor;
        final List<Entry> entries;
        int references;
        boolean draining;
        Generation(Contribution contribution) {
            descriptor = contribution.descriptor();
            entries = contribution.extensions().stream().map(extension -> entry(extension, this)).toList();
        }
    }

    private static Entry entry(TaxonomyExtension extension, Generation owner) {
        Objects.requireNonNull(extension, "TaxonomyExtension must not be null");
        if (extension instanceof com.taxonomy.extension.api.importer.ImportProfileExtension importer
                && importer.descriptor() == null)
            throw new IllegalStateException("Import profile extension must declare a descriptor");
        if (extension instanceof com.taxonomy.extension.api.llm.LlmProviderExtension provider
                && provider.descriptor() == null)
            throw new IllegalStateException("LLM provider extension must declare a descriptor");
        if (extension instanceof ReportRendererExtension renderer && renderer.descriptor() == null)
            throw new IllegalStateException("Report renderer extension must declare a descriptor");
        if (extension instanceof com.taxonomy.extension.api.importer.ImportProfileExtension importer
                && (importer.descriptor().profileId() == null || importer.descriptor().profileId().isBlank()))
            throw new IllegalStateException("Import profile extension must declare a non-blank profile ID");
        ExtensionKind kind = extension.kind();
        if (kind == null) throw new IllegalStateException("Extension kind must not be null");
        if (extension.id() == null || extension.id().isBlank())
            throw new IllegalStateException("Extension must declare a non-blank ID");
        if (extension.displayName() == null || extension.displayName().isBlank())
            throw new IllegalStateException("Extension must declare a non-blank display name");
        if (extension.description() == null)
            throw new IllegalStateException("Extension must declare a non-null description");
        if (owner.descriptor.mode() == PluginMode.DYNAMIC && kind != ExtensionKind.EXPORT_FORMAT
                && kind != ExtensionKind.REPORT_RENDERER)
            throw new IllegalArgumentException("DYNAMIC plugins may contribute only export formats and report renderers");
        if (extension instanceof com.taxonomy.extension.api.llm.LlmProviderExtension provider) {
            try { new com.taxonomy.extension.api.llm.ProviderId(provider.descriptor().providerId()); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid LLM provider ID", invalid); }
        }
        ExtensionKey key = extension instanceof ReportRendererExtension renderer
                ? ExtensionKey.report(renderer.reportTypeId(), renderer.descriptor().id())
                : new ExtensionKey(kind, extension.id());
        if (extension instanceof ReportRendererExtension renderer)
            Objects.requireNonNull(renderer.reportModelType(), "report renderer model type must not be null");
        // Capture immutable values once. Later mutable plugin metadata cannot alter identity.
        ExtensionDescriptor metadata = new ExtensionDescriptor(extension.id(), extension.displayName(), extension.description(), kind);
        return new Entry(owner, extension, new ExtensionRegistration(key, owner.descriptor.identity(), metadata));
    }

    private record Entry(Generation owner, TaxonomyExtension extension, ExtensionRegistration metadata) { }
    private record Publication(CatalogSnapshot snapshot, Map<ExtensionKey, Entry> entries) { }

    private final class Lease<T extends TaxonomyExtension> implements ExtensionLease<T> {
        private final Generation generation;
        private final T extension;
        private boolean closed;
        Lease(Generation generation, T extension) { this.generation = generation; this.extension = extension; }
        @Override public T extension() {
            synchronized (PluginCatalog.this) {
                if (closed) throw new IllegalStateException("Extension lease is closed");
                return extension;
            }
        }
        @Override public PluginIdentity plugin() { return generation.descriptor.identity(); }
        @Override public void close() {
            synchronized (PluginCatalog.this) {
                if (!closed) { closed = true; generation.references--; PluginCatalog.this.notifyAll(); }
            }
        }
    }
}
