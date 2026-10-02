package com.taxonomy.backup;

import java.io.IOException;
import java.util.*;

/**
 * Verifies the declared inventory coverage of one module before reading its sources.
 * This does not prove source consistency, reference closure or profile-specific completeness.
 */
public final class CompositeBackupDataContributor implements BackupDataContributor {
    private final BackupComponentId id;
    private final int version;
    private final List<BackupDataContributor> delegates;
    private final Set<String> categories;
    private final List<String> inventoryOmissions;

    public CompositeBackupDataContributor(BackupComponentId id, int version, BackupInventory inventory,
                                          List<? extends BackupDataContributor> delegates) {
        this.id = Objects.requireNonNull(id);
        if (version < 1) throw new IllegalArgumentException("Invalid component schema version");
        this.version = version;
        this.delegates = List.copyOf(delegates);
        var required = new TreeSet<String>();
        var excluded = new ArrayList<String>();
        boolean knownOwner = false;
        for (var category : Objects.requireNonNull(inventory).categories()) {
            if (!id.equals(category.owner())) continue;
            knownOwner = true;
            switch (category.rule()) {
                case PORTABLE_PRIMARY, GIT_PRIMARY, EXTERNAL_DEPENDENCY -> required.add(category.id());
                case REBUILDABLE, TRANSIENT -> excluded.add(category.id() + ": excluded (" + category.rule() + "); " + category.rationale());
            }
        }
        if (!knownOwner) throw new IllegalArgumentException("Component has no inventory owner");
        var claimed = new TreeSet<String>();
        for (var delegate : this.delegates) {
            if (!id.equals(delegate.componentId()) || version != delegate.schemaVersion())
                throw new IllegalArgumentException("Adapter component or schema differs from composition");
            Set<String> declared = Set.copyOf(delegate.categories());
            if (declared.isEmpty()) throw new IllegalArgumentException("Adapter declares no inventory categories");
            for (String category : declared) {
                if (!required.contains(category)) throw new IllegalArgumentException("Adapter claims a category outside its owned capture inventory: " + category);
                if (!claimed.add(category)) throw new IllegalArgumentException("Duplicate capture category: " + category);
            }
        }
        var missing = new TreeSet<>(required);
        missing.removeAll(claimed);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing capture categories: " + missing);
        this.categories = Collections.unmodifiableSet(required);
        this.inventoryOmissions = List.copyOf(excluded);
    }
    @Override public BackupComponentId componentId() { return id; }
    @Override public int schemaVersion() { return version; }
    @Override public Set<String> categories() { return categories; }
    @Override public List<String> omissions(BackupProfile profile) {
        Objects.requireNonNull(profile);
        var result = new ArrayList<>(inventoryOmissions);
        for (var delegate : delegates) result.addAll(delegate.omissions(profile));
        return List.copyOf(result);
    }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(sink);
        if (!Integer.valueOf(version).equals(snapshot.componentVersions().get(id)))
            throw new IOException("Captured component schema differs from composition");
        for (var delegate : delegates) {
            sink.checkpoint();
            delegate.write(snapshot, sink);
        }
        // Includes cancellation after the final adapter and components containing only exclusions.
        sink.checkpoint();
    }
}
