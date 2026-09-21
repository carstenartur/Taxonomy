package com.taxonomy.reformulation;

import java.time.Instant;
import java.util.List;

/** Human evidence only. Corrections explicitly supersede events; history is never removed. */
public record DecisionAnswer(String id, String questionId, String proposalId, long proposalRevision,
        List<String> values, DecisionQuestion.State state, String author, Instant occurredAt, String rationale,
        String otherText, String disposition, List<String> supersedes) {
    public DecisionAnswer(String id, String questionId, String proposalId, long proposalRevision,
            List<String> values, DecisionQuestion.State state, String author, Instant occurredAt, String rationale) {
        this(id, questionId, proposalId, proposalRevision, values, state, author, occurredAt, rationale, null, "ANSWER", List.of());
    }
    public DecisionAnswer {
        values = List.copyOf(values);
        supersedes = supersedes == null ? List.of() : List.copyOf(supersedes);
        ReformulationBaseline.requireText(author, "human author");
        java.util.Objects.requireNonNull(occurredAt);
    }
    public static List<DecisionAnswer> active(List<DecisionAnswer> history) {
        var superseded = history.stream().flatMap(a -> a.supersedes().stream()).collect(java.util.stream.Collectors.toSet());
        return history.stream().filter(a -> !superseded.contains(a.id())).toList();
    }
}
