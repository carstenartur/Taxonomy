package com.taxonomy.reformulation;
import java.time.Instant;
import java.util.List;
/** Human evidence only; a model recommendation is not a DecisionAnswer. */
public record DecisionAnswer(String id,String questionId,String proposalId,long proposalRevision,
        List<String> values,DecisionQuestion.State state,String author,Instant occurredAt,String rationale) {
    public DecisionAnswer { values=List.copyOf(values); ReformulationBaseline.requireText(author,"human author"); java.util.Objects.requireNonNull(occurredAt); }
}
