package com.taxonomy.backup;

import java.util.*;

/** Fail-closed ownership catalogue, independent of any particular persistence framework. */
public final class BackupInventory {
    public record Category(String id, BackupComponentId owner, BackupStorageRule rule, String rationale) {
        public Category { BackupChecks.text(id, "category"); Objects.requireNonNull(owner); Objects.requireNonNull(rule); BackupChecks.text(rationale, "rationale"); }
    }
    private final Map<String, Category> categories;
    public BackupInventory(Collection<Category> categories) {
        var copy = new TreeMap<String, Category>();
        for (var category : categories) {
            if (copy.putIfAbsent(category.id(), category) != null) throw new IllegalArgumentException("Duplicate category: " + category.id());
        }
        this.categories = Collections.unmodifiableMap(copy);
    }
    public Collection<Category> categories() { return categories.values(); }
    public void requireClassified(Set<String> persistentCategories) {
        var missing = new TreeSet<>(persistentCategories); missing.removeAll(categories.keySet());
        if (!missing.isEmpty()) throw new IllegalStateException("Unclassified persistent data: " + missing);
    }
}
