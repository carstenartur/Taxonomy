package com.taxonomy.reformulation;
import java.util.List;
public record NodeSynthesisResult(String nodeId,String summary,List<Statement> statementProposals,
        List<String> preservedStatementIds,List<DecisionQuestion> questionProposals,List<String> preservedQuestionIds,
        List<Statement.SourceSpan> uncoveredSourceRefs,List<ValidationReport.Finding> conflictCandidates) {
    public NodeSynthesisResult { statementProposals=List.copyOf(statementProposals); preservedStatementIds=List.copyOf(preservedStatementIds);
        questionProposals=List.copyOf(questionProposals); preservedQuestionIds=List.copyOf(preservedQuestionIds);
        uncoveredSourceRefs=List.copyOf(uncoveredSourceRefs); conflictCandidates=List.copyOf(conflictCandidates); }
}
