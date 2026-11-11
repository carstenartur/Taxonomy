package com.taxonomy.dto;

import java.util.List;
import java.util.Set;
import java.util.Objects;

/** Versioned authored business obligations, independent of relation legality. */
public record ObligationProfile(String id, String version, String description, List<Rule> rules) {
    public ObligationProfile { rules = List.copyOf(rules); }
    public record Rule(String id, Set<String> sourceRoots, String relationType, Set<String> targetRoots,
                       int minCount, Integer maxCount, Severity severity, String requiredEvidence) {
        public Rule {
            sourceRoots = Set.copyOf(sourceRoots); targetRoots = Set.copyOf(targetRoots);
            Objects.requireNonNull(severity); Objects.requireNonNull(requiredEvidence);
            if (sourceRoots.isEmpty() || targetRoots.isEmpty() || minCount < 0 || maxCount != null && maxCount < minCount)
                throw new IllegalArgumentException("Invalid obligation domain or cardinality");
        }
    }
    public enum Severity { INFO, WARNING, ERROR }
}
