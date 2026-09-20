package com.taxonomy.dsl.model;

import java.util.*;
import java.util.function.BiConsumer;

/** Shared final-state validation for neutral package hierarchy and ordered membership. */
public final class ArchitecturePackageHierarchy {
    private ArchitecturePackageHierarchy() {}
    public static void validate(CanonicalArchitectureModel model, BiConsumer<String, String> error) {
        Map<String, ArchitecturePackage> packages = new LinkedHashMap<>();
        Map<String, SortedSet<Integer>> positions = new LinkedHashMap<>();
        for (ArchitecturePackage pkg : model.getPackages()) {
            if (pkg.id() == null || !pkg.id().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) error.accept("INVALID_ID", "Invalid package identity");
            if (packages.putIfAbsent(pkg.id(), pkg) != null) error.accept("DUPLICATE_ID", "Duplicate package identity");
        }
        for (ArchitecturePackage pkg : model.getPackages()) {
            member(pkg.parentId(), pkg.position(), packages, positions, error);
            Set<String> visited = new HashSet<>();
            ArchitecturePackage cursor = pkg;
            while (cursor != null) {
                if (!visited.add(cursor.id())) { error.accept("PACKAGE_CYCLE", "Package hierarchy must be acyclic"); break; }
                if (visited.size() > 80) { error.accept("PACKAGE_DEPTH", "Package hierarchy exceeds depth 80"); break; }
                cursor = packages.get(cursor.parentId());
            }
        }
        for (ArchitectureElement element : model.getElements()) {
            if (element.getPackageId() != null) member(element.getPackageId(), element.getPackagePosition(), packages, positions, error);
            else if (element.getPackagePosition() != -1) error.accept("PACKAGE_POSITION", "Detached element cannot have a position");
        }
        for (var scope : positions.values()) {
            int next = 0;
            for (int position : scope) if (position != next++) { error.accept("PACKAGE_POSITION", "Sibling positions must be contiguous from zero"); break; }
        }
    }
    private static void member(String parent, int position, Map<String, ArchitecturePackage> packages,
                               Map<String, SortedSet<Integer>> positions, BiConsumer<String, String> error) {
        if (parent == null || !parent.isEmpty() && !packages.containsKey(parent)) {
            error.accept("PACKAGE_PARENT", "Package parent must be root or an existing package"); return;
        }
        if (position < 0 || !positions.computeIfAbsent(parent, ignored -> new TreeSet<>()).add(position))
            error.accept("PACKAGE_POSITION", "Sibling positions must be unique and nonnegative");
    }
}
