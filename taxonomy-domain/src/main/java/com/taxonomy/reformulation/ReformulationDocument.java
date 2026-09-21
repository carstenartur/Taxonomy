package com.taxonomy.reformulation;
import java.util.List;
/** Immutable document with original Phase A evidence retained separately from Phase B revisions. */
public record ReformulationDocument(String text,List<Section> sections,List<Statement> statements,
        List<DecisionQuestion> questions,ValidationReport validation,List<NodeSynthesisResult> nodeResults,ReconciliationTrace reconciliation) {
    public record ReconciliationTrace(List<NodeSynthesisResult> phaseAResults,int rounds,List<String> affectedSectionIds) {
        public ReconciliationTrace {phaseAResults=List.copyOf(phaseAResults);affectedSectionIds=List.copyOf(affectedSectionIds);if(rounds<0 || rounds>2)throw new IllegalArgumentException("Invalid reconciliation round count");}
    }
    public ReformulationDocument(String text,List<Section> sections,List<Statement> statements,List<DecisionQuestion> questions,ValidationReport validation,List<NodeSynthesisResult> nodeResults) {
        this(text,sections,statements,questions,validation,nodeResults,null);
    }
    public ReformulationDocument { sections=List.copyOf(sections);statements=List.copyOf(statements);
        questions=List.copyOf(questions);nodeResults=List.copyOf(nodeResults); }
}
