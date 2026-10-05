package com.taxonomy.catalog.snapshot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Keeps worker catalogue/index reads independent from the application's transport adapter. */
@Component
public class CatalogueRuntimePolicy {
    public enum Role { ALL, COORDINATOR, WORKER }

    private final Role role;
    private final Set<CatalogueRoot> shards;

    public CatalogueRuntimePolicy(
            @Value("${taxonomy.analysis.runtime-role:all}") String role,
            @Value("${taxonomy.analysis.worker.shards:BP,BR,CP,CI,CO,CR,IP,UA}") String shards) {
        this.role = Role.valueOf(role.toUpperCase(java.util.Locale.ROOT));
        String configuredShards = shards == null || shards.isBlank() ? "BP,BR,CP,CI,CO,CR,IP,UA" : shards;
        List<CatalogueRoot> parsedShards = Arrays.stream(configuredShards.split(",", -1)).map(String::strip)
                .map(CatalogueRoot::require).toList();
        if (new HashSet<>(parsedShards).size() != parsedShards.size()) {
            throw new IllegalArgumentException("Duplicate catalogue worker shard");
        }
        this.shards = Set.copyOf(parsedShards);
    }

    /** Default for directly constructed services in the existing local API. */
    public static CatalogueRuntimePolicy fullCatalogue() {
        return new CatalogueRuntimePolicy("all", "BP,BR,CP,CI,CO,CR,IP,UA");
    }

    public boolean workerOnly() { return role == Role.WORKER; }

    public void requireConfiguredRoots(Set<String> roots) {
        Set<CatalogueRoot> requested = roots.stream().map(CatalogueRoot::require)
                .collect(Collectors.toUnmodifiableSet());
        if (workerOnly() && !shards.containsAll(requested)) {
            throw new IllegalStateException("Required roots are not configured for this catalogue worker");
        }
    }

    public void requireCurrentCatalogueAllowed() {
        if (workerOnly()) {
            throw new IllegalStateException("Catalogue worker requires a bound exact-source root snapshot");
        }
    }

    public void requireGlobalIndexAllowed() {
        if (workerOnly() || FrozenCatalogueContext.current() != null) {
            throw new IllegalStateException(
                    "Global search/embedding index has no exact-source authority for this catalogue task");
        }
    }
}
