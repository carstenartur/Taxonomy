package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.shared.extension.TaxonomyExtension;
import org.pf4j.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

/** Private PF4J adapter. Host contracts, admission, publication and draining remain host-owned. */
public final class Pf4jPluginRuntime implements AutoCloseable {
    private final Path root;
    private final Path cache;
    private final PluginCatalog catalog;
    private final PluginArtifactValidator validator = new PluginArtifactValidator();
    private final Map<String, Installed> installed = new LinkedHashMap<>();
    private final DefaultPluginManager manager;
    private boolean closed;

    public Pf4jPluginRuntime(Path root, Path cache, PluginCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
        try {
            this.root = Files.createDirectories(root).toRealPath();
            Path parent = Files.createDirectories(cache).toRealPath();
            this.cache = Files.createTempDirectory(parent, "taxonomy-plugins-");
            if (Files.getFileStore(this.cache).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(this.cache, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot prepare local plugin directories", failure); }
        manager = new LocalPluginManager(this.cache, validator);
        manager.setSystemVersion(com.taxonomy.extension.api.plugin.PluginDescriptor.HOST_API_VERSION);
    }

    public synchronized PluginIdentity install(Path artifact) {
        ensureOpen();
        Path copy = null;
        String loaded = null;
        try {
            Path source = artifact.toAbsolutePath().normalize();
            if (!source.startsWith(root) || Files.isSymbolicLink(source)
                    || !source.toRealPath().startsWith(root) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !source.getFileName().toString().endsWith(".jar"))
                throw new IllegalArgumentException("Plugin artifact must be a local JAR inside the configured directory");
            // Every loader owns a private copy. Replacement of the operator file cannot affect it.
            copy = Files.createTempFile(cache, "admission-", ".jar");
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            var descriptor = validator.validate(copy);
            if (installed.containsKey(descriptor.identity().id()))
                throw new IllegalArgumentException("Duplicate plugin ID: " + descriptor.identity().id());
            catalog.validateDescriptor(descriptor);
            Path immutable = cache.resolve(descriptor.identity().artifactSha256() + ".jar");
            Files.move(copy, immutable); copy = immutable;
            if (Files.getFileStore(copy).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("r--------"));
            loaded = manager.loadPlugin(copy);
            if (!descriptor.identity().id().equals(loaded)) throw new IllegalStateException("Plugin loader identity mismatch");
            installed.put(loaded, new Installed(descriptor, copy));
            return descriptor.identity();
        } catch (IOException | RuntimeException failure) {
            if (loaded != null) manager.unloadPlugin(loaded);
            deleteCopy(copy, failure);
            if (failure instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("Cannot install local plugin artifact", failure);
        }
    }

    public synchronized void start(String pluginId) {
        ensureOpen();
        Installed plugin = requireInstalled(pluginId);
        if (plugin.started) throw new IllegalStateException("Plugin is already started: " + pluginId);
        try {
            catalog.validateDescriptor(plugin.descriptor);
            ClassLoader loader = manager.getPluginClassLoader(pluginId);
            List<TaxonomyExtension> contributions = new ArrayList<>();
            // Parent service providers must never be mistaken for entries in this artifact.
            for (var provider : ServiceLoader.load(TaxonomyPlugin.class, loader).stream().toList()) {
                if (provider.type().getClassLoader() != loader) continue;
                TaxonomyPlugin instance = provider.get(); plugin.instances.add(instance);
                for (TaxonomyExtension extension : List.copyOf(instance.extensions())) {
                    if (plugin.descriptor.mode() == PluginMode.DYNAMIC
                            && !(extension instanceof ReportRendererExtension)
                            && !(extension instanceof ExportFormatExtension))
                        throw new IllegalArgumentException("DYNAMIC plugins require an export or report renderer contract");
                    contributions.add(extension);
                }
            }
            if (plugin.instances.isEmpty()) throw new IllegalStateException("No local TaxonomyPlugin service provider");
            if (manager.startPlugin(pluginId) != PluginState.STARTED)
                throw new IllegalStateException("Plugin loader did not start: " + pluginId);
            catalog.publish(plugin.descriptor, contributions);
            plugin.started = true;
        } catch (RuntimeException | LinkageError | ServiceConfigurationError failure) {
            closeInstances(plugin, failure);
            manager.stopPlugin(pluginId); manager.unloadPlugin(pluginId);
            installed.remove(pluginId); deleteCopy(plugin.path, failure);
            throw new IllegalStateException("Plugin start rejected: " + pluginId, failure);
        }
    }

    public synchronized boolean stop(String pluginId, Duration timeout) throws InterruptedException {
        Installed plugin = requireInstalled(pluginId);
        if (!plugin.started) return true;
        catalog.beginDraining(plugin.descriptor.identity());
        if (!catalog.awaitReleased(plugin.descriptor.identity(), timeout)) return false;
        RuntimeException closeFailure = new IllegalStateException("Plugin resource cleanup failed: " + pluginId);
        closeInstances(plugin, closeFailure);
        manager.stopPlugin(pluginId);
        catalog.remove(plugin.descriptor.identity());
        plugin.started = false;
        if (closeFailure.getSuppressed().length != 0) throw closeFailure;
        return true;
    }

    public synchronized void unload(String pluginId) {
        Installed plugin = requireInstalled(pluginId);
        if (plugin.started) throw new IllegalStateException("Plugin must finish draining before unloading: " + pluginId);
        if (!manager.unloadPlugin(pluginId)) throw new IllegalStateException("Plugin loader could not unload: " + pluginId);
        installed.remove(pluginId);
        try { Files.deleteIfExists(plugin.path); }
        catch (IOException failure) { throw new IllegalStateException("Cannot remove private plugin copy", failure); }
    }

    public synchronized List<com.taxonomy.extension.api.plugin.PluginDescriptor> installed() {
        return installed.values().stream().map(p -> p.descriptor)
                .sorted(Comparator.comparing(p -> p.identity().id())).toList();
    }

    synchronized int openClassLoaders() { return manager.getPlugins().size(); }

    @Override public synchronized void close() {
        if (closed) return;
        List<String> remaining = new ArrayList<>(installed.keySet());
        // Dependents first, irrespective of installation order.
        while (!remaining.isEmpty()) {
            String id = remaining.stream().filter(candidate -> remaining.stream().noneMatch(other ->
                    !other.equals(candidate) && installed.get(other).descriptor.requires().stream()
                            .anyMatch(r -> r.id().equals(candidate)))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Plugin dependency cycle during shutdown"));
            try {
                if (!stop(id, Duration.ofSeconds(30))) throw new IllegalStateException("Plugin still draining: " + id);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Plugin shutdown interrupted", interrupted);
            }
            unload(id); remaining.remove(id);
        }
        try { Files.deleteIfExists(cache); }
        catch (IOException failure) { throw new IllegalStateException("Cannot remove private plugin directory", failure); }
        closed = true;
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Plugin runtime is closed"); }
    private Installed requireInstalled(String id) {
        Installed plugin = installed.get(id);
        if (plugin == null) throw new IllegalArgumentException("Plugin is not installed: " + id);
        return plugin;
    }
    private static void closeInstances(Installed plugin, Throwable failure) {
        for (int i = plugin.instances.size() - 1; i >= 0; i--) {
            try { plugin.instances.get(i).close(); } catch (RuntimeException close) { failure.addSuppressed(close); }
        }
        plugin.instances.clear();
    }
    private static void deleteCopy(Path path, Throwable failure) {
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }
    private static final class Installed {
        final com.taxonomy.extension.api.plugin.PluginDescriptor descriptor;
        final Path path;
        final List<TaxonomyPlugin> instances = new ArrayList<>();
        boolean started;
        Installed(com.taxonomy.extension.api.plugin.PluginDescriptor descriptor, Path path) {
            this.descriptor = descriptor; this.path = path;
        }
    }

    private static final class LocalPluginManager extends DefaultPluginManager {
        private final PluginArtifactValidator validator;
        LocalPluginManager(Path cache, PluginArtifactValidator validator) { super(cache); this.validator = validator; }
        @Override protected PluginDescriptorFinder createPluginDescriptorFinder() {
            return new PluginDescriptorFinder() {
                public boolean isApplicable(Path path) { return path.toString().endsWith(".jar"); }
                public org.pf4j.PluginDescriptor find(Path path) {
                    var descriptor = validator.validate(path);
                    // Dependencies are host capabilities, not access to another plugin's private classes.
                    // Catalog admission and drain validate them, including dependencies on built-ins.
                    return new DefaultPluginDescriptor(descriptor.identity().id(), "Taxonomy plugin",
                            Plugin.class.getName(), descriptor.identity().version(), descriptor.hostApiRange(), "", "");
                }
            };
        }
        @Override protected PluginLoader createPluginLoader() {
            return new JarPluginLoader(this) {
                @Override protected PluginClassLoader createPluginClassLoader(Path path, org.pf4j.PluginDescriptor descriptor) {
                    return new PluginClassLoader(pluginManager, descriptor, TaxonomyPlugin.class.getClassLoader(), ClassLoadingStrategy.PDA) {
                        @Override protected boolean shouldDelegateToParent(String name) {
                            return (name.startsWith("com.taxonomy.") && !name.startsWith("com.taxonomy.plugins."))
                                    || super.shouldDelegateToParent(name);
                        }
                    };
                }
            };
        }
    }
}
