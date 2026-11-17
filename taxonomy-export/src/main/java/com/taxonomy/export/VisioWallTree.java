package com.taxonomy.export;

import com.taxonomy.diagram.VisioDrawingOptions;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/** A wide, measured layout for a single poster. Each subtree owns a disjoint band of the page. */
final class VisioWallTree {
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final double COLUMN_GAP = .66;
    private static final double ROW_GAP = .09;
    private static final double A1_CONTENT_WIDTH = 31.7;
    private static final double LINE_HEIGHT = .215;
    private static final double VERTICAL_PADDING = .13;

    record Card(String text, double height, double width, double x, double y) {}

    private VisioWallTree() {}

    static List<Card> layout(List<VisioDecisionTree.Node> nodes, int[] parents, VisioDrawingOptions options) {
        int columns = 1 + nodes.stream().mapToInt(VisioDecisionTree.Node::depth).max().orElse(0);
        double width = Math.clamp((A1_CONTENT_WIDTH - (columns - 1) * COLUMN_GAP) / columns, 3.5, 6.8);
        // The application icon reserves the right fifth of the card. Use the same inset for a role hexagon.
        String taxonomy = nodes.getFirst().code();
        double textWidth = (width * ("UA".equals(taxonomy) || "BR".equals(taxonomy) ? .76 : .93) - .14) * 72;
        var captions = new String[nodes.size()];
        var heights = new double[nodes.size()];
        var subtree = new double[nodes.size()];
        var children = new double[nodes.size()];
        for (int index = nodes.size() - 1; index >= 0; index--) {
            var node = nodes.get(index);
            String heading = node.code() + " · " + VisioDecisionTree.rating(node.score(), options.german()) + " · " + node.title();
            var lines = new ArrayList<>(DiagramTextWrapper.wrapMeasured(heading, FONT, textWidth, 3, node.code()));
            if (node.responsibility() != null) {
                String owner = (options.german() ? "Zuständig: " : "Owner: ") + node.responsibility().name();
                lines.addAll(DiagramTextWrapper.wrapMeasured(owner, FONT, textWidth, 1, ""));
            }
            if (options.showsShortReasons() && node.reason() != null && !node.reason().isBlank()) {
                lines.addAll(DiagramTextWrapper.wrapMeasured(node.reason(), FONT, textWidth, 1, ""));
            }
            captions[index] = String.join("\n", lines);
            heights[index] = VERTICAL_PADDING + LINE_HEIGHT * lines.size();
            subtree[index] = Math.max(heights[index], children[index]);
            int parent = parents[index];
            if (parent >= 0) children[parent] += (children[parent] > 0 ? ROW_GAP : 0) + subtree[index];
        }

        var cards = new ArrayList<Card>(nodes.size());
        var childTop = new double[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) {
            int parent = parents[index];
            double bottom = parent < 0 ? .45 : childTop[parent] - subtree[index];
            if (parent >= 0) childTop[parent] = bottom - ROW_GAP;
            double y = bottom + subtree[index] / 2;
            childTop[index] = y + children[index] / 2;
            double x = .45 + width / 2 + nodes.get(index).depth() * (width + COLUMN_GAP);
            cards.add(new Card(captions[index], heights[index], width, x, y));
        }
        return cards;
    }
}
