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

        // Capability → Core Service
        addRule(RelationType.REALIZES,          "CP",  Set.of("CR"));
        // Core Service → Business Process
        addRule(RelationType.SUPPORTS,          "CR",  Set.of("BP"));
        // Business Process → Information Product
        addRule(RelationType.CONSUMES,          "BP",  Set.of("IP"));
        // User Application → Core Service
        addRule(RelationType.USES,              "UA",  Set.of("CR"));
        // COI Service → Capability
        addRule(RelationType.FULFILLS,          "CI", Set.of("CP"));
        // Business Role → Business Process
        addRule(RelationType.ASSIGNED_TO,       "BR",  Set.of("BP"));
        // Core Service → Core Service (same root)
        addRule(RelationType.DEPENDS_ON,        "CR",  Set.of("CR"));
        // Business Process → Information Product
        addRule(RelationType.PRODUCES,          "BP",  Set.of("IP"));
        // Communications Service → Core Service
        addRule(RelationType.COMMUNICATES_WITH, "CO",  Set.of("CR"));
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
     * Returns all expected outgoing relation types and their target roots
     * for a given source taxonomy root.
     *
     * @return map of RelationType → Set of target roots that should exist
     */
    public Map<RelationType, Set<String>> getExpectedOutgoingRelations(String sourceRoot) {
        Map<RelationType, Set<String>> expected = new EnumMap<>(RelationType.class);
        for (Map.Entry<RelationType, Map<String, Set<String>>> entry : matrix.entrySet()) {
            Set<String> targets = entry.getValue().get(sourceRoot);
            if (targets != null && !targets.isEmpty()) {
                expected.put(entry.getKey(), Collections.unmodifiableSet(targets));
            }
        }
        return expected;
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
