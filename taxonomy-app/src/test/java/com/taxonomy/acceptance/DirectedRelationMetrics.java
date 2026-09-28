package com.taxonomy.acceptance;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Exact, directed relation metrics for independently authored, closed-world fixtures.
 * These measures do not establish live-model quality, hierarchical recall or probability calibration.
 */
public final class DirectedRelationMetrics {
    private DirectedRelationMetrics() { }

    public enum RunState { SUCCESS, PARTIAL, FAILED, CANCELLED, NOT_RUN }
    public enum Verdict { PASS, FAIL, INCONCLUSIVE, NOT_RUN }

    public record Relation(String source, String type, String target) implements Comparable<Relation> {
        public Relation {
            source = identifier(source);
            type = identifier(type);
            target = identifier(target);
        }

        @Override public int compareTo(Relation other) {
            int compared = source.compareTo(other.source);
            if (compared == 0) compared = type.compareTo(other.type);
            return compared == 0 ? target.compareTo(other.target) : compared;
        }
    }

    /** Allowed relations may be present, but their absence does not lower required-relation recall. */
    public record Reference(Set<Relation> required, Set<Relation> allowed, Set<Relation> unresolved) {
        public Reference {
            required = immutableSorted(required);
            allowed = immutableSorted(allowed);
            unresolved = immutableSorted(unresolved);
            if (!Collections.disjoint(required, allowed) || !Collections.disjoint(required, unresolved)
                    || !Collections.disjoint(allowed, unresolved)) {
                throw new IllegalArgumentException("Reference classifications must be disjoint");
            }
        }
    }

    /**
     * Rates are null for unexecuted/incomplete runs or a zero denominator, never fabricated as 100%.
     * Precision covers classified predictions only; unresolved predictions are reported separately.
     * Not-observed requirements are not a claim that an incomplete search proved absence.
     */
    public record Evaluation(RunState state, Verdict verdict, int requiredCount, int predictedCount,
            int matchedRequired, int matchedAllowed, int unresolvedReferenceCount,
            List<Relation> falsePositives, List<Relation> notObservedRequired,
            List<Relation> unresolvedPredictions, Double precision, Double recall) {
        public Evaluation {
            falsePositives = List.copyOf(falsePositives);
            notObservedRequired = List.copyOf(notObservedRequired);
            unresolvedPredictions = List.copyOf(unresolvedPredictions);
        }
    }

    public static Evaluation evaluate(Reference reference, Collection<Relation> predictions, RunState state) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(predictions, "predictions");
        Objects.requireNonNull(state, "state");
        var predicted = new TreeSet<Relation>();
        for (Relation relation : predictions) {
            if (!predicted.add(Objects.requireNonNull(relation, "prediction"))) {
                throw new IllegalArgumentException("Duplicate directed relation prediction");
            }
        }
        if (state == RunState.NOT_RUN && !predicted.isEmpty()) {
            throw new IllegalArgumentException("An unexecuted run cannot contain predictions");
        }
        var missing = new TreeSet<>(reference.required());
        missing.removeAll(predicted);
        var falsePositives = new TreeSet<>(predicted);
        falsePositives.removeAll(reference.required());
        falsePositives.removeAll(reference.allowed());
        falsePositives.removeAll(reference.unresolved());
        var unresolved = new TreeSet<>(predicted);
        unresolved.retainAll(reference.unresolved());
        int matchedRequired = reference.required().size() - missing.size();
        int matchedAllowed = (int) predicted.stream().filter(reference.allowed()::contains).count();
        int classifiedPredictions = predicted.size() - unresolved.size();
        Double precision = null;
        Double recall = null;
        Verdict verdict;
        if (state == RunState.NOT_RUN) {
            verdict = Verdict.NOT_RUN;
        } else if (state != RunState.SUCCESS) {
            verdict = Verdict.INCONCLUSIVE;
        } else {
            if (classifiedPredictions > 0) precision = (matchedRequired + (double) matchedAllowed) / classifiedPredictions;
            if (!reference.required().isEmpty()) recall = matchedRequired / (double) reference.required().size();
            if (!falsePositives.isEmpty() || !missing.isEmpty()) verdict = Verdict.FAIL;
            else if (!reference.unresolved().isEmpty()) verdict = Verdict.INCONCLUSIVE;
            else verdict = Verdict.PASS;
        }
        return new Evaluation(state, verdict, reference.required().size(), predicted.size(), matchedRequired,
                matchedAllowed, reference.unresolved().size(), List.copyOf(falsePositives), List.copyOf(missing),
                List.copyOf(unresolved), precision, recall);
    }

    private static Set<Relation> immutableSorted(Set<Relation> values) {
        Objects.requireNonNull(values, "reference relations");
        var copy = new TreeSet<Relation>();
        values.forEach(value -> copy.add(Objects.requireNonNull(value, "reference relation")));
        return Collections.unmodifiableSortedSet(copy);
    }

    private static String identifier(String value) {
        Objects.requireNonNull(value, "relation identifier");
        if (value.isBlank() || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Relation identifiers must be nonblank canonical text without controls");
        }
        return value;
    }
}
