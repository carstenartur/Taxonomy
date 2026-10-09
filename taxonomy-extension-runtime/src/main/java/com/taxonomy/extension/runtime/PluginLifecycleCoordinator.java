package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.*;
import java.nio.file.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** Single-process lifecycle. Only operator-installed local IDs can select executable bytes. */
public final class PluginLifecycleCoordinator {
    private final Pf4jPluginRuntime runtime;
    private final Path directory;
    private final boolean enabled;
    private final boolean clustered;
    private final PluginArtifactValidator validator = new PluginArtifactValidator();

    public PluginLifecycleCoordinator(Pf4jPluginRuntime runtime, Path directory, boolean enabled, boolean clustered) {
        this.runtime = Objects.requireNonNull(runtime);
        this.directory = Objects.requireNonNull(directory);
        this.enabled = enabled; this.clustered = clustered;
    }

    public synchronized PluginIdentity activate(String id) {
        requireManagement(id);
        var existing = findInstalled(id);
        if (existing != null) {
            requireDynamic(existing);
            if (runtime.isDraining(id)) throw new PluginOperationException("DRAINING");
            if (runtime.isStarted(id)) return existing.identity();
        } else {
            Path artifact = localArtifact(id);
            try {
                var candidate = validator.validate(artifact);
                requireDynamic(candidate);
                runtime.install(artifact, candidate.identity());
                existing = findInstalled(id);
                // install reads an immutable copy; never trust the earlier ordering/lookup read.
                if (existing == null || !existing.identity().equals(candidate.identity())) {
                    if (existing != null) runtime.unload(id);
                    throw new PluginOperationException("ARTIFACT_CHANGED");
                }
                requireDynamic(existing);
            } catch (PluginOperationException rejected) { throw rejected; }
            catch (RuntimeException rejected) { throw new PluginOperationException("ADMISSION_REJECTED"); }
        }
        try { runtime.start(id); return existing.identity(); }
        catch (RuntimeException rejected) { throw new PluginOperationException("START_REJECTED"); }
    }

    public synchronized PluginOperationResult deactivate(String id, Duration timeout) {
        PluginIdentity identity = null;
        try {
            requireManagement(id);
            if (timeout == null || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
                throw new PluginOperationException("INVALID_TIMEOUT");
            var plugin = findInstalled(id);
            if (plugin == null) throw new PluginOperationException("NOT_INSTALLED");
            identity = plugin.identity(); requireDynamic(plugin);
            if (!runtime.stop(id, timeout))
                return new PluginOperationResult(id, identity, PluginOperationResult.Status.DRAINING, "INVOCATIONS_ACTIVE");
            runtime.unload(id);
            return new PluginOperationResult(id, identity, PluginOperationResult.Status.STOPPED, "STOPPED");
        } catch (PluginOperationException rejected) {
            return new PluginOperationResult(id, identity, PluginOperationResult.Status.REJECTED, rejected.code());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new PluginOperationResult(id, identity, PluginOperationResult.Status.DRAINING, "INTERRUPTED");
        } catch (RuntimeException failure) {
            // A failing close must not retain a stopped classloader. A still-active dependent
            // or a draining invocation must keep its loader and resources intact.
            if (findInstalled(id) != null && !runtime.isStarted(id)) {
                try { runtime.unload(id); } catch (RuntimeException ignored) { /* reported as cleanup failure */ }
                return new PluginOperationResult(id, identity, PluginOperationResult.Status.REJECTED, "CLEANUP_FAILED");
            }
            return new PluginOperationResult(id, identity, PluginOperationResult.Status.REJECTED, "DEPENDENTS_ACTIVE");
        }
    }

    public boolean enabled() { return enabled; }
    public boolean clustered() { return clustered; }
    public synchronized List<InstalledPlugin> installed() {
        return runtime.installed().stream().map(p -> new InstalledPlugin(p,
                runtime.isDraining(p.identity().id()) ? "DRAINING" : runtime.isStarted(p.identity().id()) ? "ACTIVE" : "INSTALLED"))
                .toList();
    }
    public record InstalledPlugin(PluginDescriptor descriptor, String state) { }

    private void requireManagement(String id) {
        if (!enabled) throw new PluginOperationException("DYNAMIC_DISABLED");
        if (clustered) throw new PluginOperationException("CLUSTER_UNSUPPORTED");
        if (id == null || !id.matches("[a-z][a-z0-9.-]{0,127}")) throw new PluginOperationException("INVALID_ID");
    }
    private static void requireDynamic(PluginDescriptor descriptor) {
        if (descriptor.mode() != PluginMode.DYNAMIC) throw new PluginOperationException("STARTUP_ONLY");
    }
    private PluginDescriptor findInstalled(String id) {
        return runtime.installed().stream().filter(p -> p.identity().id().equals(id)).findFirst().orElse(null);
    }
    private Path localArtifact(String id) {
        var matches = new ArrayList<Path>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new PluginOperationException("ADMISSION_REJECTED");
                if (validator.validate(path).identity().id().equals(id)) matches.add(path);
            }
        } catch (IOException | IllegalArgumentException invalid) { throw new PluginOperationException("ADMISSION_REJECTED"); }
        if (matches.size() != 1) throw new PluginOperationException(matches.isEmpty() ? "NOT_INSTALLED" : "DUPLICATE_ID");
        return matches.getFirst();
    }
}
