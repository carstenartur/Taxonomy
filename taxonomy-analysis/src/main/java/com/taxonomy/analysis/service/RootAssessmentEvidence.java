package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;

/** Recognizes a validated semantic root clarification without interpreting diagnostic text as evidence. */
public final class RootAssessmentEvidence {
    public static final String DIAGNOSTIC_PREFIX = "ROOT_UNRESOLVED:";

    private RootAssessmentEvidence() { }

    public static boolean isClarification(LlmCallDetail detail) {
        return detail != null && detail.getError() != null
                && detail.getError().startsWith(DIAGNOSTIC_PREFIX)
                && detail.getScores() != null && detail.getScores().isEmpty()
                && detail.getRootAssessments().size() == 1
                && detail.getRootAssessments().values().iterator().next().unresolved();
    }
}
