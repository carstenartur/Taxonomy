package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.*;
import java.util.*;

/** Behavioral contract: a valid parent survives an unknown child, and imports cannot forge completeness. */
public final class AssessmentCoverageProbe {
    static TaxonomyNodeDto node(String code, TaxonomyNodeDto... children) {
        var node = new TaxonomyNodeDto(); node.setCode(code); node.setNameEn(code);
        node.setChildren(List.of(children)); return node;
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void invalid(Runnable input) {
        try { input.run(); throw new AssertionError("invalid coverage was accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void verify() {
        var child = node("BP-1", node("BP-1-1"));
        var other = node("BP-2"); var tree = List.of(node("BP", child, other));
        var coverage = AnalysisCoverage.derive(tree, Map.of("BP", 70, "BP-2", 0),
                Map.of("BP", 70, "BP-2", 0), Map.of("BP-1", "LEFT_OPEN:q1"));
        check(coverage.nodes().get("BP").state() == AnalysisCoverage.State.RELEVANT, "parent evidence lost");
        check(coverage.nodes().get("BP").score() == 70, "parent score changed");
        check(coverage.nodes().get("BP").descendants() == AnalysisCoverage.Descendants.PARTIAL, "missing descendant qualification");
        check(coverage.nodes().get("BP-1").state() == AnalysisCoverage.State.UNKNOWN, "error became negative");
        check(coverage.nodes().get("BP-1").score() == null, "error became a zero score");
        check(coverage.nodes().get("BP-1-1").reason().equals("BLOCKED_BY:BP-1"), "unvisited descendant treated as a failed call");
        check(coverage.nodes().get("BP-2").state() == AnalysisCoverage.State.NOT_RELEVANT, "explicit zero lost");
        check(coverage.assessedNodes() == 2 && coverage.unknownNodes() == 2 && coverage.failedOrBlockedNodes() == 2, "wrong coverage counters");
        invalid(() -> new AnalysisCoverage(coverage.nodes(), 4, 0, 0));
        invalid(() -> new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, 0, null,
                AnalysisCoverage.Descendants.UNASSESSED, "FAILED:q1"));
        invalid(() -> new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.RELEVANT, 0, 0,
                AnalysisCoverage.Descendants.COMPLETE, null));
        invalid(() -> new AnalysisCoverage.NodeAssessment(null, null, null, null, null));
        var mutable = new LinkedHashMap<>(coverage.nodes());
        var copied = new AnalysisCoverage(mutable, 2, 2, 2); mutable.clear();
        check(copied.nodes().size() == 4, "mutable coverage escaped");
    }
    public static void main(String[] args) { verify(); System.out.println("Assessment coverage contracts passed"); }
}
