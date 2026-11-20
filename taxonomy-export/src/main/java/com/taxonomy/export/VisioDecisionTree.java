package com.taxonomy.export;

import com.taxonomy.diagram.VisioDrawingOptions;
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

    void appendTo(VisioDocument document, VisioDrawingOptions options) {
        int firstPage = document.getPages().size();
        int drawingPages = (int) document.getPages().stream().filter(page -> !page.isEvidencePage()).count();
        for (int start = 0; start < nodes.size();) {
            if (++drawingPages > VisioPackageValidator.MAX_DIAGRAM_PAGES) {
                throw new IllegalArgumentException("Too many Visio decision pages. Select fewer subtaxonomies.");
            }
            int end = start + 1;
            while (end < nodes.size() && nodes.get(end).depth() != 0) end++;
            document.getPages().add(layout(nodes.subList(start, end), document.getPages().size(), options));
            start = end;
        }
        if (options != null && options.appendix()) {
            VisioEvidencePages.appendTreeReasons(document, firstPage, options.german());
        }
    }

    private VisioPage layout(List<Node> section, int pageId, VisioDrawingOptions options) {
        String code = section.getFirst().code();
        boolean germanLabels = options == null ? german : options.german();
        var page = new VisioPage(Integer.toString(pageId), (germanLabels ? "Entscheidungsbaum – " : "Decision tree – ") + code);
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
        var wall = options != null && options.mode() == VisioDrawingOptions.Mode.WALL
                ? VisioWallTree.layout(section, parents, options) : List.<VisioWallTree.Card>of();
        double screenHeight = VisioPresentation.screenCardHeight(options != null && wall.isEmpty()
                && section.stream().anyMatch(node -> node.responsibility() != null));
        double[] rows = new double[section.size()];
        int leafRow = 0;
        // Reverse preorder visits the last leaf first, at the bottom of the Visio page.
        for (int index = section.size() - 1; index >= 0; index--) {
            rows[index] = firstChild[index] == -1 ? leafRow++ : (rows[firstChild[index]] + rows[lastChild[index]]) / 2;
        }
        for (int index = 0; index < section.size(); index++) {
            Node node = section.get(index);
            String shapeId = Integer.toString(index + 1);
            var card = wall.isEmpty() ? null : wall.get(index);
            var shape = shape(node, code, shapeId, rows[index], screenHeight, options, card);
            page.getShapes().add(shape);
            int parent = parents[index];
            if (parent >= 0) {
                var link = new VisioConnect(Integer.toString(parent + 1), shapeId, "");
                link.getProperties().put("taxonomy.type", VisioProperty.text("DECISION_HIERARCHY"));
                link.getProperties().put("taxonomy.sourceId", VisioProperty.text(section.get(parent).code()));
                link.getProperties().put("taxonomy.targetId", VisioProperty.text(node.code()));
                if (options != null) {
                    Node parentNode = section.get(parent);
                    link.getProperties().put("taxonomy.sourceLabel", VisioProperty.text(parentNode.title()));
                    link.getProperties().put("taxonomy.targetLabel", VisioProperty.text(node.title()));
                    shape.getProperties().put("taxonomy.parentId", VisioProperty.text(parentNode.code()));
                }
                page.getConnects().add(link);
            }
        }
        return page;
    }

    static String rating(Double score, boolean german) {
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 100)) {
            throw new IllegalArgumentException("Decision scores must be percentages between 0 and 100");
        }
        return score == null ? (german ? "Nicht bewertet" : "Not evaluated")
                : BigDecimal.valueOf(score).stripTrailingZeros().toPlainString() + "%";
    }

    private VisioShape shape(Node node, String type, String shapeId, double row, double screenHeight,
                             VisioDrawingOptions options, VisioWallTree.Card card) {
        Double score = node.score();
        VisioShape shape;
        if (card == null) {
            String text = node.code() + " · " + rating(score, options == null ? german : options.german())
                    + "\n" + VisioPresentation.display(node.title(), 46, 3);
            if (options != null && node.responsibility() != null) {
                text += VisioPresentation.drawingOwnerCaption(node.responsibility().name(), options);
            }
            shape = new VisioShape(shapeId, text, 2.2 + node.depth() * 4.2,
                    1.25 + row * (screenHeight + .25), 3.3, screenHeight, type, false);
        } else {
            shape = new VisioShape(shapeId, card.text(), card.x(), card.y(), card.width(), card.height(), type, false);
        }
        var properties = shape.getProperties();
        properties.put("taxonomy.id", VisioProperty.text(node.code()));
        properties.put("taxonomy.label", VisioProperty.text(node.title()));
        properties.put("taxonomy.depth", VisioProperty.number(node.depth()));
        if (options != null) {
            properties.put("taxonomy.type", VisioProperty.text(type));
            properties.put("taxonomy.evaluationStatus", VisioProperty.text(score == null ? "NOT_EVALUATED" : "EVALUATED"));
        }
        if (score != null) properties.put("taxonomy.relevance", VisioProperty.number(score / 100));
        if (node.reason() != null && !node.reason().isBlank()) {
            properties.put("taxonomy.presenceReason", VisioProperty.text(node.reason()));
        }
        if (node.responsibility() != null) {
            if (options != null) properties.put("taxonomy.responsibilitySourceId", VisioProperty.text(node.responsibility().sourceId()));
            properties.put("taxonomy.responsibilityUnitId", VisioProperty.text(node.responsibility().unitId()));
            properties.put("taxonomy.responsibilityName", VisioProperty.text(node.responsibility().name()));
            properties.put("taxonomy.responsibilityColor", VisioProperty.text(node.responsibility().color()));
        }
        return shape;
    }
}
