package com.taxonomy.catalog.snapshot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.dsl.model.CatalogueDefinition;
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
    private final Set<String> shards;

    public CatalogueRuntimePolicy(String role, String shards) {
        this(role, shards, CatalogueDefinition.standard());
    }

    /** Broker subscription validates its selected roots. The independent read guard limits worker-only processes. */
    @Autowired
    public CatalogueRuntimePolicy(@Value("${taxonomy.analysis.runtime-role:all}") String role,
            @Value("${taxonomy.analysis.worker.shards:}") String shards,
            ObjectProvider<RetainedCatalogueDefinitions> definitions) {
        this(role, Role.WORKER.name().equalsIgnoreCase(role) ? shards : null,
                Role.WORKER.name().equalsIgnoreCase(role) ? definitions.getObject().current() : CatalogueDefinition.standard());
    }

    public CatalogueRuntimePolicy(String role, String shards, CatalogueDefinition definition) {
        this.role = Role.valueOf(role.toUpperCase(java.util.Locale.ROOT));
        List<String> parsedShards = shards == null || shards.isBlank() ? List.copyOf(definition.rootCodes())
                : Arrays.stream(shards.split(",", -1)).map(String::strip).map(CatalogueRoot::validateIdentifier).toList();
        if (!definition.rootCodes().containsAll(parsedShards)) throw new IllegalArgumentException("Unknown catalogue worker shard");
        if (new HashSet<>(parsedShards).size() != parsedShards.size()) {
            throw new IllegalArgumentException("Duplicate catalogue worker shard");
        }
        this.shards = Set.copyOf(parsedShards);
    }

    /** Default for directly constructed services in the existing local API. */
    public static CatalogueRuntimePolicy fullCatalogue() {
        return new CatalogueRuntimePolicy("all", "");
    }

    public boolean workerOnly() { return role == Role.WORKER; }

    public void requireConfiguredRoots(Set<String> roots) {
        Set<String> requested = roots.stream().map(CatalogueRoot::validateIdentifier)
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
