package com.taxonomy.shared.features;

import java.io.IOException;
import java.util.*;

/** Supported startup packages and their actual Maven prerequisites. No feature is hot-unloaded. */
public final class FeatureAssembly {
    private FeatureAssembly() { }
    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "templates", Set.of(), "architecture", Set.of(), "reporting", Set.of("templates"),
            "analysis", Set.of("architecture"), "portfolio", Set.of("analysis", "architecture", "reporting"),
            "interop", Set.of());
    private static final Map<String, String> MARKERS = Map.of(
            "templates", "com/taxonomy/templates/DocumentTemplateService.class",
            "architecture", "com/taxonomy/architecture/service/RequirementArchitectureViewService.class",
            "reporting", "com/taxonomy/reporting/config/ReportingAutoConfiguration.class",
            "analysis", "com/taxonomy/analysis/service/LlmService.class",
            "portfolio", "com/taxonomy/portfolio/service/ProjectPortfolioService.class",
            "interop", "com/taxonomy/interop/controller/IntegrationController.class");

    public static FeatureSet validate(Set<String> installed) {
        var copy = new TreeSet<>(Objects.requireNonNull(installed, "installed"));
        for (String feature : copy) {
            if (!REQUIRED.containsKey(feature)) throw new IllegalArgumentException("Unknown startup feature: " + feature);
            for (String dependency : new TreeSet<>(REQUIRED.get(feature))) {
                if (!copy.contains(dependency)) throw new IllegalArgumentException(feature + " requires " + dependency);
            }
        }
        return new FeatureSet(copy);
    }
    public static FeatureSet discover(ClassLoader loader) {
        return discover(loader, FeatureAssembly.class.getPackage().getImplementationVersion());
    }
    static FeatureSet discover(ClassLoader loader, String hostVersion) {
        var installed = new HashSet<String>();
        var locations = new HashMap<String, java.net.URL>();
        for (var marker : MARKERS.entrySet()) {
            try {
                var resources = Collections.list(loader.getResources(marker.getValue()));
                if (resources.size() > 1) throw new IllegalArgumentException("Duplicate startup feature: " + marker.getKey());
                if (!resources.isEmpty()) { installed.add(marker.getKey()); locations.put(marker.getKey(), resources.getFirst()); }
            } catch (IOException failure) { throw new IllegalStateException("Cannot inventory startup feature " + marker.getKey(), failure); }
        }
        FeatureSet result = validate(installed);
        var artifacts = new TreeMap<String, com.taxonomy.extension.api.plugin.PluginIdentity>();
        {
            for (var feature : locations.entrySet()) {
                try {
                    var connection = feature.getValue().openConnection();
                    connection.setUseCaches(false);
                    if (!(connection instanceof java.net.JarURLConnection)) {
                        if (hostVersion != null) throw new IllegalArgumentException("Packaged host requires a packaged startup feature: " + feature.getKey());
                        continue; // Exploded development classes have no distributable artifact identity.
                    }
                    var jar = (java.net.JarURLConnection) connection;
                    String version;
                    try (var archive = jar.getJarFile()) {
                        var manifest = archive.getManifest();
                        version = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
                    }
                    if (hostVersion != null && !hostVersion.equals(version))
                        throw new IllegalArgumentException("Incompatible startup feature version: " + feature.getKey()
                                + " requires host " + hostVersion);
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (var input = new java.security.DigestInputStream(jar.getJarFileURL().openStream(), digest)) {
                        input.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    artifacts.put(feature.getKey(), new com.taxonomy.extension.api.plugin.PluginIdentity(
                            "taxonomy.feature." + feature.getKey(), version == null ? "0.0.0-dev" : version,
                            java.util.HexFormat.of().formatHex(digest.digest())));
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible);
                } catch (IOException failure) { throw new IllegalStateException("Cannot verify startup feature " + feature.getKey(), failure); }
            }
        }
        return new FeatureSet(result.installed(), artifacts);
    }
    public record FeatureSet(Set<String> installed, Map<String, com.taxonomy.extension.api.plugin.PluginIdentity> artifacts) {
        public FeatureSet(Set<String> installed) { this(installed, Map.of()); }
        public FeatureSet { installed = Collections.unmodifiableSet(new TreeSet<>(installed)); artifacts = Map.copyOf(artifacts); }
        public boolean has(String id) { return installed.contains(id); }
    }
}
