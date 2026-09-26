package com.taxonomy.dto;

import java.util.*;

/** Own assessment and completeness of descendants are intentionally separate axes. */
public record AnalysisCoverage(Map<String, NodeAssessment> nodes, int assessedNodes,
                               int unknownNodes, int failedOrBlockedNodes) {
    public enum State { RELEVANT, NOT_RELEVANT, UNKNOWN }
    public enum Descendants { COMPLETE, PARTIAL, UNASSESSED }
    public record NodeAssessment(State state, Integer score, Integer effectiveRelevance,
                                 Descendants descendants, String reason) {
        public NodeAssessment {
            if (state == null || descendants == null || (reason != null && reason.length() > 4096))
                throw new IllegalArgumentException("Invalid assessment state");
            if ((state == State.UNKNOWN && (score != null || effectiveRelevance != null))
                    || (state == State.RELEVANT && (score == null || score < 1 || score > 100))
                    || (state == State.NOT_RELEVANT && !Integer.valueOf(0).equals(score))
                    || (effectiveRelevance != null && (effectiveRelevance < 0 || effectiveRelevance > 100)))
                throw new IllegalArgumentException("Assessment state and score disagree");
        }
    }
    public AnalysisCoverage {
        if (nodes == null || nodes.size() > 25000 || nodes.entrySet().stream().anyMatch(e ->
                e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 256 || e.getValue() == null))
            throw new IllegalArgumentException("Invalid assessment coverage");
        int assessed = (int) nodes.values().stream().filter(n -> n.state() != State.UNKNOWN).count();
        int open = (int) nodes.values().stream().filter(n -> unresolved(n.reason())).count();
        if (assessedNodes != assessed || unknownNodes != nodes.size() - assessed || failedOrBlockedNodes != open)
            throw new IllegalArgumentException("Coverage counters do not match node evidence");
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }
    private static boolean unresolved(String reason) {
        return reason != null && (reason.startsWith("FAILED:") || reason.startsWith("LEFT_OPEN:")
                || reason.startsWith("BLOCKED_BY:"));
    }
    public boolean hasOpenEvaluations() { return failedOrBlockedNodes > 0; }

    public static AnalysisCoverage derive(List<TaxonomyNodeDto> tree, Map<String, Integer> raw,
                                         Map<String, Integer> effective, Map<String, String> failed) {
        Map<String, NodeAssessment> nodes = new TreeMap<>();
        for (TaxonomyNodeDto root : tree) visit(root, raw, effective, failed, null, false, nodes);
        int assessed = (int) nodes.values().stream().filter(n -> n.state() != State.UNKNOWN).count();
        int blocked = (int) nodes.values().stream().filter(n -> n.reason() != null
                && (n.reason().startsWith("FAILED") || n.reason().startsWith("LEFT_OPEN")
                    || n.reason().startsWith("BLOCKED_BY:"))).count();
        return new AnalysisCoverage(nodes, assessed, nodes.size() - assessed, blocked);
    }
    private record Visited(boolean complete, boolean anyAssessed) { }
    private static Visited visit(TaxonomyNodeDto node, Map<String, Integer> raw,
                                 Map<String, Integer> effective, Map<String, String> failed,
                                 String blockedBy, boolean pruned, Map<String, NodeAssessment> out) {
        String code = node.getCode();
        if (out.containsKey(code)) throw new IllegalArgumentException("Duplicate catalogue node " + code);
        Integer value = raw.get(code);
        String ownFailure = failed.get(code);
        String reason = value != null ? null : ownFailure != null ? ownFailure
                : blockedBy != null ? "BLOCKED_BY:" + blockedBy
                : pruned ? "NOT_VISITED_PARENT_EXCLUDED" : "NOT_EVALUATED";
        State state = value == null ? State.UNKNOWN : value > 0 ? State.RELEVANT : State.NOT_RELEVANT;
        boolean allChildren = true, anyChildren = false;
        out.put(code, new NodeAssessment(state, value, effective.get(code), Descendants.UNASSESSED, reason));
        for (TaxonomyNodeDto child : node.getChildren() == null ? List.<TaxonomyNodeDto>of() : node.getChildren()) {
            var visit = visit(child, raw, effective, failed,
                    ownFailure != null ? code : blockedBy, pruned || (value != null && value == 0), out);
            allChildren &= visit.complete(); anyChildren |= visit.anyAssessed();
        }
        Descendants descendants = allChildren ? Descendants.COMPLETE
                : anyChildren ? Descendants.PARTIAL : Descendants.UNASSESSED;
        out.put(code, new NodeAssessment(state, value, effective.get(code), descendants, reason));
        return new Visited((value != null || pruned) && ownFailure == null && blockedBy == null && allChildren,
                value != null || anyChildren);
    }
}
