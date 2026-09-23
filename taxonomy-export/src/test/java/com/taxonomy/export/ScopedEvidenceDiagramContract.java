package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.*;
import java.util.*;

/** A diagram selection policy may omit evidence, but must never rewire it into a new relation. */
public final class ScopedEvidenceDiagramContract {
    public static void main(String[] args) {
        var view = new RequirementArchitectureView();
        var source = node("source"); var target = node("target"); var other = node("other");
        view.setIncludedElements(List.of(source, target, other));
        var relation = new RequirementRelationshipView(); relation.setSourceCode("source"); relation.setTargetCode("target"); relation.setRelationType("CONSUMES");
        view.setIncludedRelationships(List.of(relation));
        view.setRelationSearchReport(new RelationSearchReport(1, "a".repeat(64), "test", List.of(),
                new RelationSearchModel.Result(List.of(), List.of(), List.of(), 0, 0, 0), 0, 10, 0, List.of(), ""));
        DiagramProjectionService projector = new DiagramProjectionService();
        projector.setPolicy(raw -> new DiagramModel(raw.title(), raw.nodes(), List.of(raw.edges().getFirst(),
                new DiagramEdge("rerouted", "source", "other", "CONSUMES", 0),
                new DiagramEdge("changed", "source", "target", "PRODUCES", 0)), raw.layout()));
        var diagram = projector.project(view, "Read evidence");
        if (diagram.edges().size() != 1 || !diagram.edges().getFirst().targetId().equals("target")) {
            throw new AssertionError("diagram policy introduced an unverified relationship");
        }
        // Legacy policies retain their established behavior when no scoped evidence is supplied.
        view.setRelationSearchReport(null);
        if (projector.project(view, "legacy").edges().size() != 3) throw new AssertionError("legacy policy changed");
        System.out.println("Scoped diagram contract: evidence preserved; legacy behavior unchanged");
    }
    private static RequirementElementView node(String id) {
        var n = new RequirementElementView(); n.setNodeCode(id); n.setTitle(id); n.setTaxonomySheet("IP"); n.setSelectedForImpact(true); return n;
    }
}
