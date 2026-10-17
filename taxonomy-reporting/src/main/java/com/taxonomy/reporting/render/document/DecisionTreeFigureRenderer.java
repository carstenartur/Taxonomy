package com.taxonomy.reporting.render.document;

import com.taxonomy.dto.AnalysisPercentages;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;
import com.taxonomy.reporting.api.decision.DecisionReportOptions.TreeLayout;
import com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Complete ordered trees at a fixed reading size. Large trees paginate; no evidence is elided. */
public final class DecisionTreeFigureRenderer {
    private static final int FONT_SIZE = 10;
    private static final Font FONT = new Font("SansSerif", Font.PLAIN, FONT_SIZE);
    public record Panel(String rootCode, List<String> nodeCodes, String svg, byte[] png,
                        int width, int height, int fontSize, boolean a3, String description) {}
    private record Line(DecisionTreeRow row, List<String> text, int indent, int height, String parent) {}

    public List<Panel> render(DecisionTreeOverview tree, String language, TreeLayout layout) {
        var result = new ArrayList<Panel>();
        var roots = new ArrayList<List<DecisionTreeRow>>();
        for (var row : tree.rows()) {
            if (row.depth() == 0 || roots.isEmpty()) roots.add(new ArrayList<>());
            roots.getLast().add(row);
        }
        for (var rows : roots) {
            if (rows.size() > 2000) throw new WordReportLayoutException(
                    "Tree " + rows.getFirst().code() + " exceeds the per-taxonomy figure budget of 2000 nodes. Choose the table layout.");
            boolean a3 = layout == TreeLayout.A3_LANDSCAPE;
            List<Line> lines = layout(rows, language, a3 ? 1070 : 720);
            if (layout == TreeLayout.AUTO && totalHeight(lines) > 360) {
                // Keep the wider, taller reading area for continuation pages too.
                // Falling back to A4 for the longest roots creates avoidable sparse panels.
                a3 = true;
                lines = layout(rows, language, 1070);
            }
            int width = a3 ? 1070 : 720, budget = a3 ? 640 : 360;
            if (layout != TreeLayout.AUTO && totalHeight(lines) > budget) throw new WordReportLayoutException(
                    "The complete tree " + rows.getFirst().code() + " does not fit on one readable "
                            + (a3 ? "A3" : "A4") + " page. Choose Auto for continuation pages or A3 for more space.");
            var page = new ArrayList<Line>();
            int used = 28;
            for (var line : lines) {
                if (line.height() + 28 > budget) throw new WordReportLayoutException(
                        "A tree label is too long for a readable page. Choose the table layout.");
                if (used + line.height() > budget && !page.isEmpty()) {
                    result.add(draw(rows.getFirst().code(), page, width, used, a3));
                    page = new ArrayList<>(); used = 28;
                }
                page.add(line); used += line.height();
            }
            if (!page.isEmpty()) result.add(draw(rows.getFirst().code(), page, width, used, a3));
        }
        return List.copyOf(result);
    }

    /** Portrait chapter panels use the same measured, unabridged labels at ten printed points. */
    public List<Panel> renderChapter(DecisionTreeOverview tree, String language, int width) {
        if (width < 180) throw new WordReportLayoutException("Template is too narrow for a readable decision diagram at 10pt");
        var result = new ArrayList<Panel>();
        var lines = layout(tree.rows(), language, width);
        var page = new ArrayList<Line>();
        int used = 28;
        for (var line : lines) {
            if (line.height() + 28 > 360) throw new WordReportLayoutException("A chapter label exceeds a readable page; use the tree table");
            if (used + line.height() > 360 && !page.isEmpty()) {
                result.add(draw(tree.rows().getFirst().code(), page, width, used, false));
                page = new ArrayList<>(); used = 28;
            }
            page.add(line); used += line.height();
        }
        if (!page.isEmpty()) result.add(draw(tree.rows().getFirst().code(), page, width, used, false));
        return List.copyOf(result);
    }

    private static int totalHeight(List<Line> lines) { return 28 + lines.stream().mapToInt(Line::height).sum(); }

    private List<Line> layout(List<DecisionTreeRow> rows, String language, int width) {
        var labels = new DecisionReportLabels(language);
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setFont(FONT);
        try {
            var lines = new ArrayList<Line>();
            var ancestors = new ArrayList<String>();
            for (var row : rows) {
                while (ancestors.size() > row.depth()) ancestors.removeLast();
                String parent = ancestors.isEmpty() ? "" : ancestors.getLast();
                int indent = row.depth() * 20;
                if (width - indent < 180) throw new WordReportLayoutException(
                        "Tree is too deep for a readable figure. Choose the table layout.");
                String state = switch (row.disposition()) {
                    case CONTINUED -> labels.continued(); case LEAF_CANDIDATE -> labels.leafCandidate();
                    case REJECTED -> labels.rejected(); case NOT_EVALUATED -> labels.notEvaluated();
                };
                boolean stateIncludesScore = row.score() == null && row.disposition() == com.taxonomy.reporting.api.decision.DecisionRationaleReport.Disposition.NOT_EVALUATED
                        || (row.score() != null && row.score() == 0d) && row.disposition() == com.taxonomy.reporting.api.decision.DecisionRationaleReport.Disposition.REJECTED;
                String text = row.code() + " · " + row.title() + "  |  " + (stateIncludesScore ? state
                        : (row.score() == null ? labels.notEvaluated() : AnalysisPercentages.display(row.score())) + " · " + state);
                if (FONT.canDisplayUpTo(text) >= 0) throw new WordReportLayoutException(
                        "The server font cannot display a tree label. Choose the table layout or install a font covering its characters.");
                var wrapped = wrap(text, width - indent - 36, g.getFontMetrics());
                lines.add(new Line(row, wrapped, indent, wrapped.size() * 14 + 16, parent));
                ancestors.add(row.code());
            }
            return lines;
        } finally { g.dispose(); }
    }

    /** Wrap by measured glyph width, including unspaced Unicode; never shorten a label. */
    private static List<String> wrap(String text, int width, FontMetrics metrics) {
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (String word : text.split("(?<=\\s)")) {
            if (!current.isEmpty() && metrics.stringWidth(current + word) > width) {
                lines.add(current.toString().stripTrailing()); current.setLength(0);
            }
            for (int cp : word.codePoints().toArray()) {
                String glyph = new String(Character.toChars(cp));
                if (!current.isEmpty() && metrics.stringWidth(current + glyph) > width) {
                    lines.add(current.toString()); current.setLength(0);
                }
                current.append(glyph);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString().stripTrailing());
        return List.copyOf(lines);
    }

    private Panel draw(String root, List<Line> lines, int width, int height, boolean a3) {
        var image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.scale(2, 2);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, width, height); g.setFont(FONT);
        String description = root + ": " + lines.stream().map(l -> l.row().code()
                + (l.parent().isEmpty() ? "" : " (parent: " + l.parent() + ")")).collect(java.util.stream.Collectors.joining(", "));
        var svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height).append("\"><title>").append(xml(description))
                .append("</title><rect width=\"100%\" height=\"100%\" fill=\"white\"/>");
        String context = root + (lines.getFirst().row().depth() > 0 ? " · ↳ " + lines.getFirst().parent() : "");
        g.setColor(new Color(0x243746)); g.drawString(context, 8, 16);
        svg.append("<text x=\"8\" y=\"16\" font-family=\"sans-serif\" font-size=\"10\">").append(xml(context)).append("</text>");
        var positions = new java.util.HashMap<String, Integer>();
        int y = 28;
        for (var line : lines) {
            int x = 8 + line.indent(), h = line.height() - 5;
            if (!line.parent().isEmpty()) {
                int parentY = positions.getOrDefault(line.parent(), 20);
                g.setColor(new Color(0x78909C)); g.drawLine(x - 10, parentY, x - 10, y + 12); g.drawLine(x - 10, y + 12, x, y + 12);
                svg.append("<path d=\"M ").append(x-10).append(' ').append(parentY).append(" V ")
                        .append(y+12).append(" H ").append(x).append("\" fill=\"none\" stroke=\"#78909c\"/>");
            }
            String fill = line.row().score() == null ? "fff4d6" : line.row().score() == 0 ? "f0f3f6" : "e1f4f3";
            g.setColor(new Color(Integer.parseInt(fill, 16))); g.fillRoundRect(x, y, width-x-8, h, 5, 5);
            svg.append("<rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(width-x-8)
                    .append("\" height=\"").append(h).append("\" rx=\"5\" fill=\"#").append(fill).append("\"/>");
            if (line.row().score() != null) {
                // Numeric relevance remains authoritative; length is only a visual cue, never probability.
                // A common 120pt track fits the minimum supported row width and is invariant across depth and page sizes.
                int barWidth = (int) Math.round(120 * Math.max(0, Math.min(100, line.row().score())) / 100d);
                g.setColor(new Color(0xCDD5DC)); g.fillRect(x + 7, y + h - 3, 120, 3);
                svg.append("<rect class=\"relevance-track\" x=\"").append(x + 7).append("\" y=\"").append(y + h - 3)
                        .append("\" width=\"120\" height=\"3\" fill=\"#cdd5dc\"/>");
                g.setColor(new Color(0x007A78)); g.fillRect(x + 7, y + h - 3, barWidth, 3);
                svg.append("<rect class=\"relevance-bar\" x=\"").append(x + 7).append("\" y=\"").append(y + h - 3)
                        .append("\" width=\"").append(barWidth).append("\" height=\"3\" fill=\"#007a78\"/>");
            }
            g.setColor(new Color(0x243746));
            int textY = y + 14;
            for (var text : line.text()) {
                g.drawString(text, x + 7, textY);
                svg.append("<text x=\"").append(x+7).append("\" y=\"").append(textY)
                        .append("\" font-family=\"sans-serif\" font-size=\"10\" fill=\"#243746\">")
                        .append(xml(text)).append("</text>"); textY += 14;
            }
            positions.put(line.row().code(), y + h); y += line.height();
        }
        g.dispose(); svg.append("</svg>");
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return new Panel(root, lines.stream().map(l -> l.row().code()).toList(), svg.toString(), output.toByteArray(),
                    width, height, FONT_SIZE, a3, description);
        } catch (java.io.IOException e) { throw new IllegalStateException("Could not render decision tree", e); }
    }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
