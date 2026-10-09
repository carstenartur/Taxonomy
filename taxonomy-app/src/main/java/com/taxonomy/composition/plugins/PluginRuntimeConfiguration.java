package com.taxonomy.composition.plugins;

import com.taxonomy.extension.api.plugin.PluginDescriptor;
import com.taxonomy.extension.runtime.*;
import com.taxonomy.shared.extension.TaxonomyExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit local artifact admission before any shared catalog facade becomes available. */
@Configuration(proxyBeanMethods = false)
public class PluginRuntimeConfiguration {
    @Bean(destroyMethod = "close") Installation pluginInstallation(ObjectProvider<TaxonomyExtension> builtins,
                                                                  Environment environment) throws IOException {
        var catalog = BuiltinCatalog.create(builtins.orderedStream().toList());
        Path directory = Path.of(environment.getProperty("taxonomy.plugins.directory", "plugins"));
        Path cache = Path.of(environment.getProperty("taxonomy.plugins.cache-directory",
                Path.of(System.getProperty("java.io.tmpdir"), "taxonomy-plugin-cache").toString()));
        var runtime = new Pf4jPluginRuntime(directory, cache, catalog);
        try {
            var validator = new PluginArtifactValidator();
            Map<String, Path> artifacts = new TreeMap<>();
            Map<String, PluginDescriptor> descriptors = new TreeMap<>();
            try (var paths = Files.list(directory)) {
                for (Path artifact : paths.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    // This read is only for ordering. install() copies and validates authoritative bytes again.
                    var descriptor = validator.validate(artifact);
                    if (artifacts.putIfAbsent(descriptor.identity().id(), artifact) != null)
                        throw new IllegalArgumentException("Duplicate local plugin ID: " + descriptor.identity().id());
                    descriptors.put(descriptor.identity().id(), descriptor);
                }
            }
            while (!artifacts.isEmpty()) {
                Set<String> available = new HashSet<>();
                catalog.snapshot().plugins().forEach(p -> available.add(p.identity().id()));
                String next = artifacts.keySet().stream().filter(id -> descriptors.get(id).requires().stream()
                                .allMatch(r -> available.contains(r.id()))).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Missing or cyclic local plugin dependencies: " + artifacts.keySet()));
                var identity = runtime.install(artifacts.remove(next));
                if (!identity.equals(descriptors.get(next).identity()))
                    throw new IllegalStateException("Operator plugin changed during startup: " + next);
                runtime.start(identity.id());
            }
            return new Installation(catalog, runtime);
        } catch (IOException | RuntimeException | Error failure) {
            try { runtime.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @Bean(destroyMethod = "") Pf4jPluginRuntime pluginRuntime(Installation installation) { return installation.runtime(); }
    record Installation(PluginCatalog catalog, Pf4jPluginRuntime runtime) implements AutoCloseable {
        @Override public void close() { runtime.close(); }
    }
}
