package com.taxonomy.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One source-bound interpretation of a requirement passage's contribution to a catalogue node. */
public record RequirementNodeAssessment(
        String requirementQuote,
        List<SubjectKind> subjectKinds,
        List<RequirementKind> requirementKinds,
        ContributionType contributionType,
        EvidenceStatus evidenceStatus,
        EvidenceBasis evidenceBasis,
        Necessity necessity,
        Scope scope,
        String condition,
        String rationale,
        String question) {

    public enum SubjectKind { PRODUCT, SERVICE, PROCESS, INFORMATION, ROLE, CAPABILITY, UNSPECIFIED }
    public enum RequirementKind { FUNCTIONAL, QUALITY, CONSTRAINT, ORGANIZATIONAL, CONTEXT, GOAL, UNSPECIFIED }
    public enum ContributionType { DIRECT_SUBJECT, PROVIDES, SUPPORTS, REQUIRES, CONSTRAINS, UNDETERMINED }
    public enum EvidenceStatus { SUPPORTED, UNRESOLVED, CONFLICT, NOT_APPLICABLE, OUT_OF_SCOPE }
    public enum EvidenceBasis { EXPLICIT, INFERRED, UNSPECIFIED }
    public enum Necessity { REQUIRED, OPTIONAL, ALTERNATIVE, CONTEXT, UNDETERMINED }
    public enum Scope { DELIVERY, EXISTING_ENVIRONMENT, UNSPECIFIED, EXCLUDED }

    public static final int MAX_PER_NODE = 8;
    public static final int MAX_QUOTE_LENGTH = 8000;
    public static final int MAX_RATIONALE_LENGTH = 2000;
    public static final int MAX_CONDITION_LENGTH = 1000;
    public static final int MAX_QUESTION_LENGTH = 1000;

    public RequirementNodeAssessment {
        subjectKinds = distinctKinds(subjectKinds, "subjectKinds");
        requirementKinds = distinctKinds(requirementKinds, "requirementKinds");
        if (contributionType == null || evidenceStatus == null || evidenceBasis == null
                || necessity == null || scope == null) {
            throw new IllegalArgumentException("A node assessment requires contribution, status, basis, necessity and scope");
        }

        // Preserve the quote byte for byte at the character level, including surrounding spaces.
        requirementQuote = bounded(requirementQuote, MAX_QUOTE_LENGTH, "requirementQuote");
        rationale = bounded(rationale, MAX_RATIONALE_LENGTH, "rationale");
        condition = bounded(condition, MAX_CONDITION_LENGTH, "condition");
        question = bounded(question, MAX_QUESTION_LENGTH, "question");
        if (rationale.isBlank()) throw new IllegalArgumentException("A node assessment requires a rationale");
        if (!requirementQuote.isEmpty() && requirementQuote.isBlank())
            throw new IllegalArgumentException("A nonempty requirement quote must contain text");
        if (requirementQuote.isEmpty() && evidenceBasis == EvidenceBasis.EXPLICIT)
            throw new IllegalArgumentException("Explicit node evidence requires an original quote");
        if ((evidenceStatus == EvidenceStatus.UNRESOLVED) == question.isBlank())
            throw new IllegalArgumentException("Only unresolved node evidence requires a question");
        if (evidenceStatus == EvidenceStatus.SUPPORTED) {
            if (requirementQuote.isEmpty() || contributionType == ContributionType.UNDETERMINED
                    || evidenceBasis == EvidenceBasis.UNSPECIFIED) {
                throw new IllegalArgumentException("Supported node evidence requires an original quote, contribution and basis");
            }
            if ((necessity == Necessity.OPTIONAL || necessity == Necessity.ALTERNATIVE) && condition.isBlank())
                throw new IllegalArgumentException("Supported optional or alternative node evidence requires a condition");
        }
        if (evidenceStatus == EvidenceStatus.CONFLICT && requirementQuote.isEmpty())
            throw new IllegalArgumentException("Conflicting node evidence requires an original quote");
    }

    /** Tests exact membership of the verbatim quote in the analysed source. */
    public void requireOriginalSource(String originalRequirement) {
        if (!requirementQuote.isEmpty() && (originalRequirement == null || !originalRequirement.contains(requirementQuote)))
            throw new IllegalArgumentException("Requirement quote must occur verbatim in the original analysed text");
    }

    /** Freezes maps and lists while preserving the owning workflow's stable node and passage order. */
    public static Map<String, List<RequirementNodeAssessment>> immutableMap(
            Map<String, List<RequirementNodeAssessment>> values) {
        if (values == null || values.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, List<RequirementNodeAssessment>>();
        values.forEach((code, assessments) -> {
            if (code == null || code.isBlank() || assessments == null || assessments.size() > MAX_PER_NODE
                    || assessments.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Invalid node assessment identity or list");
            }
            if (!assessments.isEmpty()) result.put(code, List.copyOf(assessments));
        });
        return Collections.unmodifiableMap(result);
    }

    private static String bounded(String value, int maximum, String field) {
        if (value == null || value.length() > maximum)
            throw new IllegalArgumentException("Invalid or oversized " + field);
        return value;
    }

    private static <E extends Enum<E>> List<E> distinctKinds(List<E> kinds, String field) {
        if (kinds == null || kinds.isEmpty() || kinds.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("A node assessment requires nonempty " + field);
        return List.copyOf(new LinkedHashSet<>(kinds));
    }
}
