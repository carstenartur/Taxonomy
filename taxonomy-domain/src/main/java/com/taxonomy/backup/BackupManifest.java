package com.taxonomy.backup;

import java.time.Instant;
import java.util.*;

/** Versioned wire contract. Parsing must use explicit DTOs, never class names from the input. */
public record BackupManifest(int formatVersion, String applicationVersion, String build, BackupId backupId,
                             String sourceInstallationId, BackupRequest request, Instant captureStartedAt,
                             Instant captureCompletedAt, String consistencyEvidence,
                             Set<String> requiredFeatures, List<Component> components,
                             List<Repository> repositories, List<BackupEntry> entries,
                             List<String> dependencies, List<String> omissions) {
    public static final int FORMAT_VERSION = 1;
    public static final Set<String> SUPPORTED_FEATURES = Set.of("component-sha256", "explicit-scope", "writer-barrier-v1", "plugin-prerequisites-v1");

    public record Component(BackupComponentId id, int version, BackupCompleteness completeness,
                            List<String> entryPaths, Set<BackupComponentId> dependencies) {
        public Component {
            Objects.requireNonNull(id); Objects.requireNonNull(completeness);
            if (version < 1) throw new IllegalArgumentException("Invalid component version");
            entryPaths = List.copyOf(entryPaths); dependencies = Set.copyOf(dependencies);
            BackupChecks.count(entryPaths.size()); BackupChecks.count(dependencies.size());
            entryPaths.forEach(path -> BackupChecks.text(path, "entryPath"));
            if (new HashSet<>(entryPaths).size() != entryPaths.size()) throw new IllegalArgumentException("Duplicate component entry");
        }
    }
    public record Repository(BackupRepositoryKey id, String archiveId, GitRepresentation representation,
                             SnapshotContext.RepositoryState captured, BackupRepositoryKey sourceRepositoryId,
                             String exportedHead, String sourceCommit) {
        public Repository {
            Objects.requireNonNull(id);
            BackupChecks.archiveId(archiveId);
            Objects.requireNonNull(representation); Objects.requireNonNull(captured);
            if (exportedHead != null) BackupChecks.hash(exportedHead, 40, "exportedHead");
            if (sourceCommit != null) BackupChecks.hash(sourceCommit, 40, "sourceCommit");
        }
    }
    public BackupManifest {
        if (formatVersion != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported backup format");
        BackupChecks.text(applicationVersion, "applicationVersion"); BackupChecks.text(build, "build"); Objects.requireNonNull(backupId);
        BackupChecks.text(sourceInstallationId, "sourceInstallationId"); Objects.requireNonNull(request);
        Objects.requireNonNull(captureStartedAt); Objects.requireNonNull(captureCompletedAt);
        if (captureCompletedAt.isBefore(captureStartedAt)) throw new IllegalArgumentException("Invalid capture interval");
        BackupChecks.text(consistencyEvidence, "consistencyEvidence"); requiredFeatures = Set.copyOf(requiredFeatures);
        if (!SUPPORTED_FEATURES.containsAll(requiredFeatures)) throw new IllegalArgumentException("Unknown required feature");
        components = List.copyOf(components); repositories = List.copyOf(repositories); entries = List.copyOf(entries);
        dependencies = List.copyOf(dependencies); omissions = List.copyOf(omissions);
        for (var items : List.of(requiredFeatures, components, repositories, entries, dependencies, omissions)) BackupChecks.count(items.size());
        dependencies.forEach(value -> BackupChecks.text(value, "dependency"));
        omissions.forEach(value -> BackupChecks.text(value, "omission"));
        var ids = new HashSet<BackupComponentId>();
        for (var c : components) if (!ids.add(c.id())) throw new IllegalArgumentException("Duplicate component");
        var paths = new HashSet<String>();
        for (var e : entries) if (!paths.add(e.path())) throw new IllegalArgumentException("Duplicate entry");
        var assigned = new HashSet<String>();
        for (var c : components) {
            if (!ids.containsAll(c.dependencies())) throw new IllegalArgumentException("Missing component dependency");
            if (!paths.containsAll(c.entryPaths())) throw new IllegalArgumentException("Missing component payload");
            for (String p : c.entryPaths()) if (!assigned.add(p)) throw new IllegalArgumentException("Entry owned by multiple components");
        }
        if (!assigned.equals(paths)) throw new IllegalArgumentException("Unclassified archive entries");
        var resolved = new HashSet<BackupComponentId>();
        while (resolved.size() < components.size()) {
            int before = resolved.size();
            for (var component : components) {
                if (resolved.containsAll(component.dependencies())) resolved.add(component.id());
            }
            if (resolved.size() == before) throw new IllegalArgumentException("Cyclic component dependencies");
        }
        var repos = new HashSet<BackupRepositoryKey>(); var archiveIds = new HashSet<String>();
        for (var r : repositories) {
            if (!repos.add(r.id()) || !archiveIds.add(r.archiveId().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate repository");
            if (r.representation() != request.gitRepresentation()) throw new IllegalArgumentException("Repository Git representation differs from the request");
            if (request.profile().includesHistory() && r.representation() == GitRepresentation.NONE) throw new IllegalArgumentException("Missing history representation");
        }
        if (!(request.scope() instanceof BackupScope.Installation) && !repos.equals(request.scope().selectedRepositories())) {
            throw new IllegalArgumentException("Manifest repositories differ from selected scope");
        }
    }
    /** First release supports exactly the same application version; converters must be explicit. */
    public void requireCompatible(String targetApplicationVersion, Map<BackupComponentId, Integer> supportedComponents) {
        if (!applicationVersion.equals(targetApplicationVersion)) throw new IllegalArgumentException("Application version requires a converter");
        for (var c : components) if (!Objects.equals(supportedComponents.get(c.id()), c.version())) {
            throw new IllegalArgumentException("Unsupported component version: " + c.id().value());
        }
    }
}
