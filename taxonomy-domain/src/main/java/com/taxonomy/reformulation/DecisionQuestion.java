package com.taxonomy.reformulation;
import java.util.List;
/** Semantic key and all discovery evidence survive aggregation. */
public record DecisionQuestion(String id, Key key, String wording, List<Discovery> discoveries,
        List<String> affectedStatementIds, AnswerSchema answerSchema, List<String> prerequisites,
        List<String> dependentQuestionIds, String consequences, State state) {
    public enum State { OPEN, ANSWERED, DEFERRED, NOT_APPLICABLE, CONFLICT }
    public record Key(String subject,String dimension,String scope) {}
    public record Discovery(String location,String context,String rationale,List<Statement.SourceSpan> sourceSpans,
            List<String> nodeIds,List<String> edgeIds) {
        public Discovery { sourceSpans=List.copyOf(sourceSpans); nodeIds=List.copyOf(nodeIds); edgeIds=List.copyOf(edgeIds); }
    }
    public record AnswerSchema(Kind kind,List<String> options,String unit,Double minimum,Double maximum) {
        public enum Kind { SINGLE_CHOICE, MULTIPLE_CHOICE, TEXT, NUMBER, BOOLEAN }
        public AnswerSchema { options=List.copyOf(options); java.util.Objects.requireNonNull(kind); }
    }
    public DecisionQuestion { discoveries=List.copyOf(discoveries); affectedStatementIds=List.copyOf(affectedStatementIds);
        prerequisites=List.copyOf(prerequisites); dependentQuestionIds=List.copyOf(dependentQuestionIds); }
}
