package com.taxonomy.export;

import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioProperty;
import com.taxonomy.visio.VisioShape;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** Ordered decision evidence rendered as native nodes and glued hierarchy connectors. */
public record VisioDecisionTree(List<Node> nodes, boolean german) {
    public VisioDecisionTree {
        nodes = List.copyOf(nodes);
    }

    public record Node(int depth, String code, String title, Double score, String reason, Assignment responsibility) { }

    void appendTo(VisioDocument document) {
        int drawingPages = (int) document.getPages().stream().filter(page -> !page.isEvidencePage()).count();
        for (int start = 0; start < nodes.size();) {
            if (++drawingPages > VisioPackageValidator.MAX_DIAGRAM_PAGES) {
                throw new IllegalArgumentException("Too many Visio decision pages. Select fewer subtaxonomies.");
            }
            int end = start + 1;
            while (end < nodes.size() && nodes.get(end).depth() != 0) end++;
            document.getPages().add(layout(nodes.subList(start, end), document.getPages().size()));
            start = end;
        }
    }

    private VisioPage layout(List<Node> section, int pageId) {
        String code = section.getFirst().code();
        var page = new VisioPage(Integer.toString(pageId), (german ? "Entscheidungsbaum – " : "Decision tree – ") + code);
        var parents = new int[section.size()];
        var firstChild = new int[section.size()];
        var lastChild = new int[section.size()];
        Arrays.fill(firstChild, -1);
        var stack = new ArrayDeque<Integer>();
        for (int index = 0; index < section.size(); index++) {
            int depth = section.get(index).depth();
            if (depth < 0 || depth > stack.size()) throw new IllegalArgumentException("Invalid decision tree depth");
            while (stack.size() > depth) stack.removeLast();
            parents[index] = stack.isEmpty() ? -1 : stack.getLast();
            if (parents[index] >= 0) {
                if (firstChild[parents[index]] == -1) firstChild[parents[index]] = index;
                lastChild[parents[index]] = index;
            }
            stack.addLast(index);
        }
        double[] rows = new double[section.size()];
        int leafRow = 0;
        // Reverse preorder visits the last leaf first, at the bottom of the Visio page.
        for (int index = section.size() - 1; index >= 0; index--) {
            rows[index] = firstChild[index] == -1 ? leafRow++ : (rows[firstChild[index]] + rows[lastChild[index]]) / 2;
        }
        for (int index = 0; index < section.size(); index++) {
            Node node = section.get(index);
            String shapeId = Integer.toString(index + 1);
            var shape = shape(node, code, shapeId, rows[index]);
            page.getShapes().add(shape);
            int parent = parents[index];
            if (parent >= 0) {
                var link = new VisioConnect(Integer.toString(parent + 1), shapeId, "");
                link.getProperties().put("taxonomy.type", VisioProperty.text("DECISION_HIERARCHY"));
                link.getProperties().put("taxonomy.sourceId", VisioProperty.text(section.get(parent).code()));
                link.getProperties().put("taxonomy.targetId", VisioProperty.text(node.code()));
                page.getConnects().add(link);
            }
        }
        return page;
    }

    private VisioShape shape(Node node, String type, String shapeId, double row) {
        Double score = node.score();
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 100)) {
            throw new IllegalArgumentException("Decision scores must be percentages between 0 and 100");
        }
        String rating = score == null ? (german ? "Nicht bewertet" : "Not evaluated")
                : BigDecimal.valueOf(score).stripTrailingZeros().toPlainString() + "%";
        String title = VisioPresentation.display(node.title(), 46, 3);
        var shape = new VisioShape(shapeId, node.code() + " · " + rating + "\n" + title,
                2.2 + node.depth() * 4.2, 1.25 + row * 1.3, 3.3, 1.05, type, false);
        var properties = shape.getProperties();
        properties.put("taxonomy.id", VisioProperty.text(node.code()));
        properties.put("taxonomy.label", VisioProperty.text(node.title()));
        properties.put("taxonomy.depth", VisioProperty.number(node.depth()));
        if (score != null) properties.put("taxonomy.relevance", VisioProperty.number(score / 100));
        if (node.reason() != null && !node.reason().isBlank()) {
            properties.put("taxonomy.presenceReason", VisioProperty.text(node.reason()));
        }
        if (node.responsibility() != null) {
            properties.put("taxonomy.responsibilityUnitId", VisioProperty.text(node.responsibility().unitId()));
            properties.put("taxonomy.responsibilityName", VisioProperty.text(node.responsibility().name()));
            properties.put("taxonomy.responsibilityColor", VisioProperty.text(node.responsibility().color()));
        }
        return shape;
    }
}
