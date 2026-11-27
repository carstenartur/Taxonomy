package com.taxonomy.composition.report;

import com.taxonomy.dto.RequirementNodeAssessment;

import java.util.List;
import java.util.Map;

/** Validates optional caller-supplied evidence before generating a live report. */
final class ReportAssessmentInput {
    private ReportAssessmentInput() {}

    static boolean hasClaims(Map<String, List<RequirementNodeAssessment>> assessments) {
        return assessments != null && assessments.values().stream().anyMatch(claims -> claims != null && !claims.isEmpty());
    }

    static boolean valid(Map<String, List<RequirementNodeAssessment>> assessments, String requirement) {
        if (assessments == null || assessments.isEmpty()) return true;
        if (requirement == null || requirement.isBlank() || assessments.size() > 25_000) return false;
        try {
            RequirementNodeAssessment.immutableMap(assessments).forEach((code, claims) -> {
                if (code.length() > 256 || !code.equals(code.strip()))
                    throw new IllegalArgumentException("Invalid node assessment identity");
                claims.forEach(claim -> claim.requireOriginalSource(requirement));
            });
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
