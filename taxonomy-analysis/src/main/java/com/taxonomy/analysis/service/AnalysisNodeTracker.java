package com.taxonomy.analysis.service;

import com.taxonomy.dto.AnalysisNodeProgress;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyNodeDto;
import java.util.*;

/** Owned by one registry run; keeps scalar catalogue identities, never entity graphs. */
final class AnalysisNodeTracker {
    private record Node(String code, String root, String parent, boolean budgetLeaf) { }
    private record Root(String code, String en, String de) { }
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Root> roots = new ArrayList<>();
    private final Map<String, Double> scores = new HashMap<>();
    private boolean planned;
    private AnalysisNodeProgress cached;

    void plan(List<TaxonomyNodeDto> tree) {
        if (planned) return;
        for (var root : tree) {
            roots.add(new Root(root.getCode(), root.getNameEn(), root.getNameDe()));
            add(root, root.getCode(), null);
        }
        planned = true; cached = null;
    }
    private void add(TaxonomyNodeDto node, String root, String parent) {
        // Product suitability is independent of the hierarchy budget. A family
        // with only product children is the last allocation of that budget.
        boolean budgetLeaf = !"PRODUCT".equals(node.getAnalysisRole()) && (node.getChildren() == null
                || node.getChildren().stream().allMatch(child -> "PRODUCT".equals(child.getAnalysisRole())));
        if (nodes.putIfAbsent(node.getCode(), new Node(node.getCode(), root, parent, budgetLeaf)) != null)
            throw new IllegalArgumentException("Duplicate progress catalogue node " + node.getCode());
        if (node.getChildren() != null) for (var child : node.getChildren()) add(child, root, node.getCode());
    }
    void accept(LlmCallDetail detail) {
        if (detail.getError() != null && !detail.getError().isBlank()) return;
        if (detail.getScores() == null) return;
        detail.getScores().forEach((code, value) -> {
            if (code != null && value != null && value >= 0 && value <= 100
                    && (!planned || nodes.containsKey(code))) scores.put(code, value);
        });
        cached = null;
    }
    int assessed() { return scores.size(); }
    AnalysisNodeProgress snapshot() {
        if (!planned) return null;
        if (cached != null) return cached;
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Double> allocated = new HashMap<>();
        Map<String, Boolean> pruned = new HashMap<>();
        for (Node node : nodes.values()) {
            int[] count = counts.computeIfAbsent(node.root(), unused -> new int[4]);
            count[0]++;
            boolean excluded = Boolean.TRUE.equals(pruned.get(node.parent()));
            Double score = scores.get(node.code());
            if (score != null) count[1]++;
            else if (excluded) count[2]++;
            if (score != null && score > 0) count[3]++;
            if (score != null && node.budgetLeaf()) allocated.merge(node.root(), score, Double::sum);
            pruned.put(node.code(), excluded || score != null && score == 0d);
        }
        List<AnalysisNodeProgress.Taxonomy> parts = new ArrayList<>();
        int total = 0, assessed = 0, excluded = 0;
        for (Root root : roots) {
            int[] c = counts.get(root.code());
            if (c == null) continue;
            parts.add(new AnalysisNodeProgress.Taxonomy(root.code(), root.en(), root.de(),
                    c[0], c[1], c[2], c[0] - c[1] - c[2], c[3],
                    allocated.getOrDefault(root.code(), 0d), scores.get(root.code())));
            total += c[0]; assessed += c[1]; excluded += c[2];
        }
        cached = new AnalysisNodeProgress(total, assessed, excluded, total - assessed - excluded, parts);
        return cached;
    }
}
