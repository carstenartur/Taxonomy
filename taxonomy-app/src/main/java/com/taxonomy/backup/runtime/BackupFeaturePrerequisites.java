package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;
import com.taxonomy.shared.features.FeatureAssembly;
import com.taxonomy.extension.api.plugin.PluginIdentity;
import java.util.*;

/** Code prerequisites are checked before capture or handing a verified archive to any writer. */
public final class BackupFeaturePrerequisites {
    public static final String PROTOCOL = "plugin-prerequisites-v1";
    private static final String PREFIX = "taxonomy-plugin-v1:";
    private final Set<String> installed;
    private final Map<String, PluginIdentity> artifacts;
    private final Set<String> dataFeatures;

    public BackupFeaturePrerequisites(FeatureAssembly.FeatureSet features) {
        this(features, new com.taxonomy.extension.api.plugin.CatalogSnapshot(0, List.of(), List.of()));
    }
    public BackupFeaturePrerequisites(FeatureAssembly.FeatureSet features,
                                     com.taxonomy.extension.api.plugin.CatalogSnapshot catalog) {
        installed = features.installed();
        var identities = new TreeMap<String, PluginIdentity>();
        features.artifacts().values().forEach(identity -> identities.put(identity.id(), identity));
        catalog.plugins().stream().filter(plugin -> plugin.mode() == com.taxonomy.extension.api.plugin.PluginMode.STARTUP).forEach(plugin -> {
            var previous=identities.putIfAbsent(plugin.identity().id(), plugin.identity());
            if (previous != null && !previous.equals(plugin.identity()))
                throw new IllegalArgumentException("Ambiguous backup artifact: " + plugin.identity().id());
        });
        artifacts = Map.copyOf(identities);
        try {
            var owners = new TreeSet<String>();
            for (var category : com.taxonomy.backup.runtime.BackupCoverageInventory.load().categories()) {
                if ((category.rule() == BackupStorageRule.PORTABLE_PRIMARY || category.rule() == BackupStorageRule.GIT_PRIMARY)
                        && Set.of("analysis", "architecture", "portfolio", "interop", "templates").contains(category.owner().value()))
                    owners.add(category.owner().value());
            }
            dataFeatures = Set.copyOf(owners);
        } catch (java.io.IOException unavailable) { throw new IllegalStateException("Backup ownership inventory is unavailable", unavailable); }
    }
    public static BackupFeaturePrerequisites discover() {
        return new BackupFeaturePrerequisites(FeatureAssembly.discover(Thread.currentThread().getContextClassLoader()));
    }
    public void requireCapture() {
        var missing = new TreeSet<>(dataFeatures); missing.removeAll(installed);
        // Without a data owner, absence of entities in JPA is not evidence that the database is empty.
        if (!missing.isEmpty()) throw new IllegalStateException("Required backup data features are unavailable: " + missing);
    }
    public List<String> dependencies() {
        return artifacts.values().stream().sorted(Comparator.comparing(PluginIdentity::id))
                .map(BackupFeaturePrerequisites::encode).toList();
    }
    public void requireRestore(BackupManifest manifest) {
        var available = artifacts.values().stream().collect(java.util.stream.Collectors.toMap(PluginIdentity::id, p -> p));
        for (var component : manifest.components()) {
            if (dataFeatures.contains(component.id().value()) && !installed.contains(component.id().value()))
                throw new IllegalArgumentException("Required restore feature is unavailable: " + component.id().value());
        }
        for (String requirement : manifest.dependencies()) {
            if (!requirement.startsWith(PREFIX)) continue;
            if (!manifest.requiredFeatures().contains(PROTOCOL)) throw new IllegalArgumentException("Plugin prerequisite protocol is missing");
            var required = decode(requirement);
            if (!required.equals(available.get(required.id()))) throw new IllegalArgumentException("Required restore artifact is unavailable: " + required.id());
        }
    }
    public static String encode(PluginIdentity identity) {
        return PREFIX + identity.id() + ":" + identity.version() + ":" + identity.artifactSha256();
    }
    private static PluginIdentity decode(String encoded) {
        var parts = encoded.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Malformed plugin prerequisite");
        return new PluginIdentity(parts[0], parts[1], parts[2]);
    }
}
