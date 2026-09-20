package com.taxonomy.reformulation;
import java.util.*;
/** Frozen peers for one round. No mutation or ambient workspace lookups. */
public record ReconciliationInput(ReformulationBaseline baseline,int round,List<Section> sections,List<Statement> statements,
        List<DecisionQuestion> questions,List<DecisionAnswer> answers,Map<String,String> boundaryEdges,
        Map<String,List<String>> sharedInformation,List<String> globalConstraintIds) {
    public ReconciliationInput {sections=List.copyOf(sections);statements=List.copyOf(statements);questions=List.copyOf(questions);
        answers=List.copyOf(answers);boundaryEdges=Map.copyOf(boundaryEdges);
        var shared=new TreeMap<String,List<String>>();sharedInformation.forEach((k,v)->shared.put(k,List.copyOf(v)));sharedInformation=Map.copyOf(shared);
        globalConstraintIds=List.copyOf(globalConstraintIds);}
}
