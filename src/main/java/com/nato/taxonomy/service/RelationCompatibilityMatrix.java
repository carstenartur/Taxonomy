package com.nato.taxonomy.service;

import com.nato.taxonomy.model.RelationType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Defines which {@link RelationType} is valid between which taxonomy root pairs.
 *
 * <p>Not every relation type may connect arbitrary node combinations.
 * This matrix encodes domain knowledge about which source taxonomy root
 * may be linked to which target taxonomy root for a given relation type.
 *
 * <p>An empty set means "no restriction" (any root is allowed).
 */
@Component
public class RelationCompatibilityMatrix {

    /**
     * Map: RelationType → Map&lt;sourceRoot, Set&lt;targetRoot&gt;&gt;.
     * If a relation type is absent, or the sourceRoot is absent, all targets are allowed.
     */
    private final Map<RelationType, Map<String, Set<String>>> matrix;

    public RelationCompatibilityMatrix() {
        matrix = new EnumMap<>(RelationType.class);

        // Capability → Service
        addRule(RelationType.REALIZES,          "CP",  Set.of("CS"));
        // Service → Business Process
        addRule(RelationType.SUPPORTS,          "CS",  Set.of("BP"));
        // Business Process → Information Product (COI taxonomy)
        addRule(RelationType.CONSUMES,          "BP",  Set.of("COI"));
        // User Application → Core Service
        addRule(RelationType.USES,              "UA",  Set.of("CS"));
        // COI Service → Capability
        addRule(RelationType.FULFILLS,          "COI", Set.of("CP"));
        // Business Role → Business Process
        addRule(RelationType.ASSIGNED_TO,       "BR",  Set.of("BP"));
        // Service → Service (same root)
        addRule(RelationType.DEPENDS_ON,        "CS",  Set.of("CS"));
        // Business Process → Information Product
        addRule(RelationType.PRODUCES,          "BP",  Set.of("COI"));
        // Communications Service → Core Service
        addRule(RelationType.COMMUNICATES_WITH, "CR",  Set.of("CS"));
        // RELATED_TO has no restrictions
    }

    private void addRule(RelationType type, String sourceRoot, Set<String> targetRoots) {
        matrix.computeIfAbsent(type, k -> new HashMap<>())
              .put(sourceRoot, targetRoots);
    }

    /**
     * Returns the set of allowed target taxonomy roots for a given source root
     * and relation type.
     *
     * <p>An empty set is returned in two cases:
     * <ol>
     *   <li>The relation type has no rules at all — meaning all roots are allowed
     *       (use {@link #isCompatible} for the definitive check).</li>
     *   <li>The source root is not listed for a relation type that does have rules —
     *       meaning no targets are allowed for that source root.</li>
     * </ol>
     *
     * <p>Use {@link #isCompatible} for a clear yes/no answer that handles both cases.
     */
    public Set<String> allowedTargetRoots(String sourceRoot, RelationType relationType) {
        Map<String, Set<String>> bySource = matrix.get(relationType);
        if (bySource == null) {
            // No rules for this relation type → all roots allowed
            return Collections.emptySet();
        }
        Set<String> targets = bySource.get(sourceRoot);
        return targets != null ? Collections.unmodifiableSet(targets) : Collections.emptySet();
    }

    /**
     * Checks whether a specific source→target root combination is compatible
     * with the given relation type.
     */
    public boolean isCompatible(String sourceRoot, String targetRoot, RelationType relationType) {
        Map<String, Set<String>> bySource = matrix.get(relationType);
        if (bySource == null) {
            // No rules at all for this relation type → no restriction
            return true;
        }
        Set<String> targets = bySource.get(sourceRoot);
        if (targets == null) {
            // The relation type has rules but this sourceRoot is not listed →
            // the sourceRoot is not a valid origin for this relation type
            return false;
        }
        return targets.contains(targetRoot);
    }
}
