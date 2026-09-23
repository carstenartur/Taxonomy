package com.taxonomy.analysis.assessment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Common, score-independent contract for one complete assessment of offered children.
 * JSON decoding and duplicate JSON-key detection belong to the caller. Interpretation
 * belongs to the policy: this class never normalizes scores or infers relationships.
 */
public final class ChildAssessmentContract {
    private ChildAssessmentContract() { }

    /** Validate the entire batch before interpreting any answer; preserve offered order. */
    public static <T> Map<String, T> decode(List<String> candidateIds, Map<String, ?> answers,
                                          BiFunction<String, Object, T> decoder) {
        Objects.requireNonNull(candidateIds, "candidateIds");
        Objects.requireNonNull(answers, "answers");
        Objects.requireNonNull(decoder, "decoder");
        var expected = new LinkedHashSet<String>();
        for (String id : candidateIds) {
            if (id == null || id.isBlank() || !expected.add(id)) {
                throw new IllegalArgumentException("Child candidates contain a blank or duplicate ID");
            }
        }
        if (!expected.equals(answers.keySet())) {
            var missing = new LinkedHashSet<>(expected);
            missing.removeAll(answers.keySet());
            var unknown = new LinkedHashSet<>(answers.keySet());
            unknown.removeAll(expected);
            throw new IllegalArgumentException("Child assessment keys do not match candidates; missing="
                    + missing + ", unknown=" + unknown);
        }
        for (String id : expected) {
            if (answers.get(id) == null) {
                throw new IllegalArgumentException("Child assessment has no value for " + id);
            }
        }
        Map<String, T> result = new LinkedHashMap<>();
        for (String id : expected) {
            T value = decoder.apply(id, answers.get(id));
            if (value == null) {
                throw new IllegalArgumentException("Child assessment policy returned no decision for " + id);
            }
            result.put(id, value);
        }
        return Collections.unmodifiableMap(result);
    }
}
