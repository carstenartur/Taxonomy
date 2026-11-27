package com.taxonomy.analysis.assessment;

import com.taxonomy.dto.RequirementNodeAssessment;
import com.taxonomy.dto.RequirementNodeAssessment.*;
import static com.taxonomy.dto.RequirementNodeAssessment.MAX_PER_NODE;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict provider wire validation before the scoring result is accepted. */
public final class RequirementAssessmentContract {
    private static final Set<String> FIELDS = Set.of(
            "requirementQuote", "subjectKinds", "requirementKinds", "contributionType", "evidenceStatus",
            "evidenceBasis", "necessity", "scope", "condition", "rationale", "question");

    private RequirementAssessmentContract() { }

    public static List<RequirementNodeAssessment> decode(Map<?, ?> node, String originalRequirement) {
        if (!node.containsKey("assessments")) return List.of();
        var assessments = decodeRecorded(node.get("assessments"));
        assessments.forEach(assessment -> assessment.requireOriginalSource(originalRequirement));
        return assessments;
    }

    /** Revalidates the shape of retained evidence at a boundary that only has the source fingerprint. */
    public static List<RequirementNodeAssessment> decodeRecorded(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() > MAX_PER_NODE)
            throw new IllegalArgumentException("Node assessments must be an array of at most " + MAX_PER_NODE + " entries");

        var assessments = new LinkedHashSet<RequirementNodeAssessment>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> object) || !object.keySet().equals(FIELDS))
                throw new IllegalArgumentException("Node assessment fields do not match the evidence contract");
            var assessment = new RequirementNodeAssessment(
                    text(object.get("requirementQuote"), "requirementQuote"),
                    kinds(object, "subjectKinds", SubjectKind.class),
                    kinds(object, "requirementKinds", RequirementKind.class),
                    enumValue(object.get("contributionType"), "contributionType", ContributionType.class),
                    enumValue(object.get("evidenceStatus"), "evidenceStatus", EvidenceStatus.class),
                    enumValue(object.get("evidenceBasis"), "evidenceBasis", EvidenceBasis.class),
                    enumValue(object.get("necessity"), "necessity", Necessity.class),
                    enumValue(object.get("scope"), "scope", Scope.class),
                    text(object.get("condition"), "condition"),
                    text(object.get("rationale"), "rationale"),
                    text(object.get("question"), "question"));
            if (!assessments.add(assessment)) throw new IllegalArgumentException("Duplicate node assessment");
        }
        return List.copyOf(assessments);
    }

    /** Validate archived or observed evidence against the text and its recorded catalogue authority. */
    public static void validateRecorded(Map<String, List<RequirementNodeAssessment>> assessments,
                                        String originalRequirement, Set<String> offeredCodes) {
        if (!offeredCodes.containsAll(assessments.keySet()))
            throw new IllegalArgumentException("Requirement evidence contains a foreign catalogue identity");
        for (var claims : assessments.values()) {
            for (var claim : claims) claim.requireOriginalSource(originalRequirement);
        }
    }

    /** Copies may be absent in older records. Two recorded copies must agree exactly. */
    public static void mergeRecorded(Map<String, List<RequirementNodeAssessment>> retained,
                                     Map<String, List<RequirementNodeAssessment>> incoming) {
        incoming.forEach((code, claims) -> {
            var prior = retained.putIfAbsent(code, claims);
            if (prior != null && !prior.equals(claims))
                throw new IllegalArgumentException("Conflicting archived requirement evidence");
        });
    }

    private static <E extends Enum<E>> List<E> kinds(Map<?, ?> object, String field, Class<E> type) {
        if (!(object.get(field) instanceof List<?> values) || values.isEmpty()
                || values.size() > type.getEnumConstants().length) {
            throw new IllegalArgumentException("Node assessment requires a nonempty " + field + " array");
        }
        var kinds = new LinkedHashSet<E>();
        for (var value : values) {
            if (!kinds.add(enumValue(value, field, type)))
                throw new IllegalArgumentException("Duplicate " + field + " entry in node assessment");
        }
        return List.copyOf(kinds);
    }

    private static <E extends Enum<E>> E enumValue(Object value, String field, Class<E> type) {
        return Enum.valueOf(type, text(value, field));
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String string))
            throw new IllegalArgumentException("Node assessment requires a string for " + field);
        return string;
    }
}
