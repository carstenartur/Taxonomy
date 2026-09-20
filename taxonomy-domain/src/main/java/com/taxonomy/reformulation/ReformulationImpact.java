package com.taxonomy.reformulation;

import java.util.List;

/** A reviewable invalidation set, never an instruction to change active architecture. */
public record ReformulationImpact(List<String> statementIds, List<String> sectionIds,
        List<String> questionIds, List<String> boundaryEdgeIds, boolean global) {
    public ReformulationImpact {
        statementIds = List.copyOf(statementIds); sectionIds = List.copyOf(sectionIds);
        questionIds = List.copyOf(questionIds); boundaryEdgeIds = List.copyOf(boundaryEdgeIds);
    }
    public static ReformulationImpact empty() { return new ReformulationImpact(List.of(), List.of(), List.of(), List.of(), false); }
}
