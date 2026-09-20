package com.taxonomy.reformulation;

import java.util.List;
/** Immutable Phase A result. Node results retain full detail for later reconciliation. */
public record ReformulationDocument(String text,List<Section> sections,List<Statement> statements,
        List<DecisionQuestion> questions,ValidationReport validation,List<NodeSynthesisResult> nodeResults) {
    public ReformulationDocument { sections=List.copyOf(sections);statements=List.copyOf(statements);
        questions=List.copyOf(questions);nodeResults=List.copyOf(nodeResults); }
}
