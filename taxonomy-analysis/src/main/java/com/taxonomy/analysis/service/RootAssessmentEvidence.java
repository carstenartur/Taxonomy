package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;

import java.util.Collection;
import java.util.Map;

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

    /** A saved clarification belongs to its exact single offered root. */
    public static boolean isClarificationFor(LlmCallDetail detail, Collection<String> offered) {
        return isClarification(detail) && offered.size() == 1
                && detail.getRootAssessments().containsKey(offered.iterator().next());
    }

    /** Project only the reason bound to the root; the archived reply remains unchanged. */
    public static Map<String, String> clarificationReasons(LlmCallDetail detail) {
        if (!isClarification(detail) || detail.getReasons() == null) return Map.of();
        String root = detail.getRootAssessments().keySet().iterator().next();
        String reason = detail.getReasons().get(root);
        return reason == null ? Map.of() : Map.of(root, reason);
    }
}
