package com.taxonomy.reformulation;
import java.util.List;
public record ValidationReport(List<Finding> findings) {
    public ValidationReport { findings=List.copyOf(findings); }
    public enum Kind { STRUCTURAL_LOSS, UNMAPPED_SOURCE, CONFLICT, SEMANTIC_REVIEW }
    public record Finding(Kind kind,String code,String message,List<String> statementIds,List<Statement.SourceSpan> sourceSpans) {
        public Finding { statementIds=List.copyOf(statementIds); sourceSpans=List.copyOf(sourceSpans); }
    }
}
