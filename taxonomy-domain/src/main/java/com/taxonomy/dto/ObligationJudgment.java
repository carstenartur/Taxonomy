package com.taxonomy.dto;

import java.util.Set;

/** One cardinality across the union of target alternatives; counts distinct target elements. */
public record ObligationJudgment(String ruleId, String sourceNodeCode, String sourceRoot,
        String relationType, Set<String> targetRoots, int minCount, Integer maxCount, int observedCount,
        ObligationProfile.Severity severity, Status status, String description) {
    public ObligationJudgment { targetRoots = Set.copyOf(targetRoots); }
    public enum Status { SATISFIED, VIOLATED, INCONCLUSIVE }
}
