package com.taxonomy.analysis.assessment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, requirement-scoped semantics for a single relation assessment batch. */
public final class RelationAssessment {
    private RelationAssessment() { }

    public enum Phase { NAVIGATE, VERIFY }
    public enum Direction { OUTGOING, INCOMING }
    public enum Decision { DESCEND, ACCEPT, REJECT, UNRESOLVED }
    public enum Combination { REQUIRED, ALTERNATIVE, OPTIONAL, UNSPECIFIED }

    public record Context(String requirementVersion, String originalText,
                          String sourceCode, String sourceRoot, String sourceContribution,
                          List<String> sourceEvidence, String relationType,
                          Direction direction, Phase phase) {
        public Context {
            text(requirementVersion, "requirementVersion");
            text(originalText, "originalText");
            text(sourceCode, "sourceCode");
            text(sourceRoot, "sourceRoot");
            text(sourceContribution, "sourceContribution");
            text(relationType, "relationType");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(phase, "phase");
            sourceEvidence = evidence(sourceEvidence, originalText);
            if (sourceEvidence.isEmpty()) {
                throw new IllegalArgumentException("A source contribution needs original requirement evidence");
            }
        }
    }

    public record Candidate(String id, String name, String description, String taxonomyRoot,
                            boolean hasChildren) {
        public Candidate {
            text(id, "candidate ID");
            text(name, "candidate name");
            text(taxonomyRoot, "candidate taxonomy root");
            description = description == null ? "" : description;
        }
    }

    /** ACCEPT in NAVIGATE proposes an endpoint; it is not a verified architecture edge. */
    public record ChildDecision(Decision decision, String contribution, List<String> evidence,
                                String reason, String question, Combination combination,
                                String alternativeGroup) {
        public ChildDecision {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(combination, "combination");
            evidence = List.copyOf(evidence);
        }
    }

    private static final Set<String> FIELDS = Set.of("decision", "contribution", "evidence",
            "reason", "question", "combination", "alternativeGroup");

    /** Policy decoder used through the same child-set contract as score policies. */
    public static ChildDecision decode(Context context, Candidate candidate, Object raw) {
        if (!(raw instanceof Map<?, ?> values) || !FIELDS.equals(values.keySet())) {
            throw new IllegalArgumentException("Relation decision fields do not match the schema for " + candidate.id());
        }
        Decision decision = enumValue(Decision.class, values.get("decision"), "decision");
        Combination combination = enumValue(Combination.class, values.get("combination"), "combination");
        String contribution = string(values.get("contribution"), "contribution");
        String reason = text(string(values.get("reason"), "reason"), "reason");
        String question = string(values.get("question"), "question");
        String group = string(values.get("alternativeGroup"), "alternativeGroup");
        List<String> quotes = evidence(values.get("evidence"), context.originalText());
        if (decision == Decision.DESCEND && (!candidate.hasChildren() || context.phase() == Phase.VERIFY)) {
            throw new IllegalArgumentException("DESCEND requires a non-leaf in the navigation phase");
        }
        if (decision == Decision.ACCEPT || decision == Decision.DESCEND) {
            text(contribution, "contribution");
            if (quotes.isEmpty()) throw new IllegalArgumentException("A positive decision needs requirement evidence");
        }
        if (decision == Decision.UNRESOLVED) text(question, "unresolved question");
        if (combination == Combination.ALTERNATIVE) text(group, "alternative group");
        else if (!group.isBlank()) throw new IllegalArgumentException("Only alternatives may specify an alternative group");
        return new ChildDecision(decision, contribution, quotes, reason, question, combination, group);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String field) {
        return Enum.valueOf(type, string(value, field));
    }

    private static List<String> evidence(Object value, String original) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Evidence must be an array");
        return values.stream().map(item -> {
            String quote = text(string(item, "evidence quote"), "evidence quote");
            if (!original.contains(quote)) throw new IllegalArgumentException("Evidence is not present in the original requirement");
            return quote;
        }).toList();
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String result)) throw new IllegalArgumentException(field + " must be a string");
        return result;
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
