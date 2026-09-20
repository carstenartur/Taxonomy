package com.taxonomy.architecture.report;

import com.taxonomy.architecture.decision.DecisionRationaleReport.*;

import java.util.*;

/** Complete, ordered navigation evidence, independent of the leading decision path. */
public record DecisionTreeOverview(List<DecisionTreeRow> rows, List<String> warnings) {
    public DecisionTreeOverview {
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    public record DecisionTreeRow(
            int depth,
            String code,
            String title,
            Integer score,
            Disposition disposition,
            Integer chapterNumber,
            String bookmark) {}

    private record Node(String code, String title, Integer score, Disposition disposition) {}

    public static String bookmark(DecisionChapter chapter) {
        String code = chapter.parentCode().replaceAll("[^A-Za-z0-9_]", "_");
        // Chapter numbers are unique; truncation cannot collide even for non-Latin codes.
        return "decision_chapter_"
                + chapter.number()
                + "_"
                + code.substring(0, Math.min(12, code.length()));
    }

    public static DecisionTreeOverview from(List<DecisionChapter> chapters) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, DecisionChapter> parents = new LinkedHashMap<>();
        Map<String, String> parentOf = new HashMap<>();
        Map<String, List<String>> children = new LinkedHashMap<>();
        Set<Integer> numbers = new HashSet<>();
        for (var chapter : chapters) {
            if (chapter.number() < 1
                    || !numbers.add(chapter.number())
                    || parents.putIfAbsent(chapter.parentCode(), chapter) != null)
                throw new IllegalArgumentException("Contradictory decision chapter identity");
            put(
                    nodes,
                    new Node(
                            chapter.parentCode(),
                            chapter.parentTitle(),
                            chapter.parentScore(),
                            disposition(chapter.parentScore(), false)));
            List<String> direct = new ArrayList<>();
            for (var child : chapter.children()) {
                put(
                        nodes,
                        new Node(
                                child.code(),
                                child.title(),
                                child.absoluteScore(),
                                disposition(child.absoluteScore(), child.leaf())));
                String previous = parentOf.putIfAbsent(child.code(), chapter.parentCode());
                if (previous != null && !previous.equals(chapter.parentCode()))
                    throw new IllegalArgumentException(
                            "Contradictory decision parent for " + child.code());
                if (!direct.contains(child.code())) direct.add(child.code());
            }
            children.put(chapter.parentCode(), direct);
        }
        // Check every component, including components that have no root due to a cycle.
        Map<String, Integer> state = new HashMap<>();
        for (String code : nodes.keySet()) detectCycle(code, children, state);
        List<DecisionTreeRow> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String root : nodes.keySet()) {
            if (parentOf.containsKey(root)) continue;
            var chapter = parents.get(root);
            if (chapter != null && chapter.hierarchyLevel() > 0) warnings.add(root);
            append(root, 0, nodes, parents, children, rows);
        }
        return new DecisionTreeOverview(rows, warnings);
    }

    private static void put(Map<String, Node> nodes, Node node) {
        if (node.code() == null || node.code().isBlank())
            throw new IllegalArgumentException("Missing decision node ID");
        Node previous = nodes.putIfAbsent(node.code(), node);
        if (previous != null
                && (!Objects.equals(previous.title(), node.title())
                        || !Objects.equals(previous.score(), node.score())))
            throw new IllegalArgumentException("Contradictory decision node " + node.code());
    }

    private static Disposition disposition(Integer score, boolean leaf) {
        return score == null
                ? Disposition.NOT_EVALUATED
                : score == 0
                        ? Disposition.REJECTED
                        : leaf ? Disposition.LEAF_CANDIDATE : Disposition.CONTINUED;
    }

    private static void detectCycle(
            String code, Map<String, List<String>> children, Map<String, Integer> state) {
        if (state.getOrDefault(code, 0) == 1)
            throw new IllegalArgumentException("Decision tree cycle at " + code);
        if (state.getOrDefault(code, 0) == 2) return;
        state.put(code, 1);
        for (String child : children.getOrDefault(code, List.of()))
            detectCycle(child, children, state);
        state.put(code, 2);
    }

    private static void append(
            String code,
            int depth,
            Map<String, Node> nodes,
            Map<String, DecisionChapter> chapters,
            Map<String, List<String>> children,
            List<DecisionTreeRow> rows) {
        Node node = nodes.get(code);
        DecisionChapter chapter = chapters.get(code);
        rows.add(
                new DecisionTreeRow(
                        depth,
                        code,
                        node.title(),
                        node.score(),
                        node.disposition(),
                        chapter == null ? null : chapter.number(),
                        chapter == null ? null : bookmark(chapter)));
        for (String child : children.getOrDefault(code, List.of()))
            append(child, depth + 1, nodes, chapters, children, rows);
    }
}
