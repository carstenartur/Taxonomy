package com.taxonomy.reformulation;
import java.util.List;
import java.util.Map;
/** Entire original remains present in every call; external text is untrusted data. */
public record NodeSynthesisInput(ReformulationBaseline baseline,String nodeId,String parentId,
        String nodeDescription,List<Statement.SourceSpan> sourceAnchors,List<Statement> directContributions,
        List<NodeSynthesisResult> children,Map<String,String> boundaryEdges,List<DecisionAnswer> answers,
        List<DecisionQuestion> openDecisions,String preservationContract) {
    public NodeSynthesisInput { java.util.Objects.requireNonNull(baseline); sourceAnchors=List.copyOf(sourceAnchors);
        directContributions=List.copyOf(directContributions); children=List.copyOf(children); boundaryEdges=Map.copyOf(boundaryEdges);
        answers=List.copyOf(answers); openDecisions=List.copyOf(openDecisions); }
}
