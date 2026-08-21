package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReport.ChildDecision;
import com.taxonomy.architecture.decision.DecisionRationaleReport.DecisionChapter;
import com.taxonomy.architecture.decision.DecisionRationaleReport.Disposition;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministically renders one-level parent/children decision diagrams.
 *
 * <p>Large sibling sets are split into panels of at most six children. Every panel repeats
 * the parent node, so it remains understandable when a page break separates the images.</p>
 */
@Component
public class DecisionChapterDiagramRenderer {

    static final int WIDTH = 1200;
    static final int HEIGHT = 720;
    static final int CHILDREN_PER_PANEL = 6;

    private static final Color NAVY = new Color(11, 31, 51);
    private static final Color TEAL = new Color(0, 122, 120);
    private static final Color LIGHT_TEAL = new Color(225, 244, 243);
    private static final Color PALE_BLUE = new Color(233, 240, 247);
    private static final Color GRAY_900 = new Color(38, 48, 58);
    private static final Color GRAY_600 = new Color(100, 112, 124);
    private static final Color GRAY_300 = new Color(205, 213, 220);
    private static final Color GRAY_100 = new Color(244, 246, 248);
    private static final Color WHITE = Color.WHITE;

    public record DiagramPanel(
            int panelNumber,
            int panelCount,
            byte[] png,
            String svg,
            String altText) {
    }

    public List<DiagramPanel> render(DecisionChapter chapter, String languageTag) {
        DecisionReportLabels labels = new DecisionReportLabels(languageTag);
        List<ChildDecision> children = chapter.children();
        int panelCount = Math.max(1,
                (children.size() + CHILDREN_PER_PANEL - 1) / CHILDREN_PER_PANEL);
        List<DiagramPanel> panels = new ArrayList<>();
        for (int panel = 0; panel < panelCount; panel++) {
            int from = panel * CHILDREN_PER_PANEL;
            int to = Math.min(children.size(), from + CHILDREN_PER_PANEL);
            List<ChildDecision> panelChildren = children.subList(from, to);
            String altText = altText(chapter, panelChildren, labels, panel + 1, panelCount);
            panels.add(new DiagramPanel(
                    panel + 1,
                    panelCount,
                    renderPng(chapter, panelChildren, labels, panel + 1, panelCount),
                    renderSvg(chapter, panelChildren, labels, panel + 1, panelCount),
                    altText));
        }
        return List.copyOf(panels);
    }

    private byte[] renderPng(
            DecisionChapter chapter,
            List<ChildDecision> children,
            DecisionReportLabels labels,
            int panelNumber,
            int panelCount) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            graphics.setColor(NAVY);
            String heading = labels.parentNode() + " " + chapter.parentCode()
                    + (panelCount > 1 ? " · " + panelNumber + "/" + panelCount : "");
            graphics.drawString(heading, 50, 42);

            Card parentCard = new Card(370, 70, 460, 126);
            drawCard(graphics, parentCard, NAVY, NAVY, false);
            graphics.setColor(WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
            graphics.drawString(chapter.parentCode(), parentCard.x + 24, parentCard.y + 36);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            drawWrapped(graphics, chapter.parentTitle(), parentCard.x + 24,
                    parentCard.y + 66, parentCard.width - 48, 22, 2);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 21));
            String score = chapter.parentScore() == null
                    ? labels.notEvaluated() : chapter.parentScore() + " %";
            drawRight(graphics, score, parentCard.x + parentCard.width - 24,
                    parentCard.y + 38);

            List<Card> cards = childCards(children.size());
            graphics.setColor(GRAY_300);
            graphics.setStroke(new BasicStroke(3.0f));
            double parentX = parentCard.x + parentCard.width / 2.0;
            double parentY = parentCard.y + parentCard.height;
            double junctionY = 235;
            graphics.draw(new Line2D.Double(parentX, parentY, parentX, junctionY));
            if (!cards.isEmpty()) {
                double left = cards.stream().mapToDouble(card -> card.x + card.width / 2.0).min().orElse(parentX);
                double right = cards.stream().mapToDouble(card -> card.x + card.width / 2.0).max().orElse(parentX);
                graphics.draw(new Line2D.Double(left, junctionY, right, junctionY));
                for (Card card : cards) {
                    double center = card.x + card.width / 2.0;
                    graphics.draw(new Line2D.Double(center, junctionY, center, card.y));
                }
            }

            for (int index = 0; index < children.size(); index++) {
                drawChild(graphics, cards.get(index), children.get(index), labels);
            }

            graphics.setColor(GRAY_600);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            String legend = labels.german()
                    ? "Absoluter Relevanzwert · lokaler Anteil am Vaterwert · Status des Pfades"
                    : "Absolute relevance score · local share of parent score · path status";
            graphics.drawString(legend, 50, HEIGHT - 30);
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not render decision diagram PNG", exception);
        }
    }

    private void drawChild(
            Graphics2D graphics,
            Card card,
            ChildDecision child,
            DecisionReportLabels labels) {
        CardStyle style = style(child);
        drawCard(graphics, card, style.fill(), style.border(), style.dashed());

        graphics.setColor(style.text());
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        graphics.drawString(child.code(), card.x + 18, card.y + 31);

        String absolute = child.absoluteScore() == null
                ? "—" : child.absoluteScore() + " %";
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        drawRight(graphics, absolute, card.x + card.width - 18, card.y + 31);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        drawWrapped(graphics, child.title(), card.x + 18, card.y + 60,
                card.width - 36, 20, 3);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        graphics.setColor(style.secondaryText());
        String local = child.localSharePercent() == null
                ? labels.localShare() + ": —"
                : labels.localShare() + ": " + formatPercent(child.localSharePercent());
        graphics.drawString(local, card.x + 18, card.y + card.height - 42);

        String status = dispositionLabel(child, labels);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        graphics.setColor(style.text());
        graphics.drawString(status, card.x + 18, card.y + card.height - 17);
        if (child.leadingSibling()) {
            String leading = labels.leadingLabel();
            int width = graphics.getFontMetrics().stringWidth(leading) + 18;
            graphics.setColor(TEAL);
            graphics.fillRoundRect(card.x + card.width - width - 14,
                    card.y + card.height - 34, width, 24, 12, 12);
            graphics.setColor(WHITE);
            graphics.drawString(leading, card.x + card.width - width - 5,
                    card.y + card.height - 17);
        }
    }

    private void drawCard(
            Graphics2D graphics,
            Card card,
            Color fill,
            Color border,
            boolean dashed) {
        RoundRectangle2D shape = new RoundRectangle2D.Double(
                card.x, card.y, card.width, card.height, 24, 24);
        graphics.setColor(fill);
        graphics.fill(shape);
        Stroke previous = graphics.getStroke();
        graphics.setStroke(dashed
                ? new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    10.0f, new float[]{10.0f, 8.0f}, 0.0f)
                : new BasicStroke(2.5f));
        graphics.setColor(border);
        graphics.draw(shape);
        graphics.setStroke(previous);
    }

    private List<Card> childCards(int count) {
        if (count <= 0) {
            return List.of();
        }
        int columns = count == 1 ? 1 : count == 2 ? 2 : 3;
        int rows = (count + columns - 1) / columns;
        int cardWidth = columns == 1 ? 520 : columns == 2 ? 470 : 340;
        int cardHeight = rows == 1 ? 210 : 190;
        int gapX = 34;
        int totalWidth = columns * cardWidth + (columns - 1) * gapX;
        int startX = (WIDTH - totalWidth) / 2;
        int startY = rows == 1 ? 315 : 285;
        int gapY = 42;
        List<Card> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int row = index / columns;
            int column = index % columns;
            result.add(new Card(
                    startX + column * (cardWidth + gapX),
                    startY + row * (cardHeight + gapY),
                    cardWidth,
                    cardHeight));
        }
        return result;
    }

    private CardStyle style(ChildDecision child) {
        if (child.disposition() == Disposition.NOT_EVALUATED) {
            return new CardStyle(WHITE, GRAY_600, GRAY_900, GRAY_600, true);
        }
        if (child.disposition() == Disposition.REJECTED) {
            return new CardStyle(GRAY_100, GRAY_300, GRAY_900, GRAY_600, false);
        }
        if (child.leadingSibling()) {
            return new CardStyle(LIGHT_TEAL, TEAL, NAVY, GRAY_900, false);
        }
        return new CardStyle(PALE_BLUE, NAVY, NAVY, GRAY_900, false);
    }

    private String renderSvg(
            DecisionChapter chapter,
            List<ChildDecision> children,
            DecisionReportLabels labels,
            int panelNumber,
            int panelCount) {
        StringBuilder svg = new StringBuilder(8_192);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(WIDTH).append(' ').append(HEIGHT)
                .append("\" role=\"img\" aria-labelledby=\"title desc\">");
        svg.append("<title id=\"title\">").append(xml(labels.parentNode() + " "
                + chapter.parentCode())).append("</title>");
        svg.append("<desc id=\"desc\">").append(xml(altText(
                chapter, children, labels, panelNumber, panelCount))).append("</desc>");
        svg.append("<rect width=\"1200\" height=\"720\" fill=\"#ffffff\"/>");
        String heading = labels.parentNode() + " " + chapter.parentCode()
                + (panelCount > 1 ? " · " + panelNumber + "/" + panelCount : "");
        svg.append(text(50, 42, heading, 24, true, "#0b1f33", "start"));

        Card parent = new Card(370, 70, 460, 126);
        svg.append(rect(parent, "#0b1f33", "#0b1f33", false));
        svg.append(text(parent.x + 24, parent.y + 36, chapter.parentCode(), 25,
                true, "#ffffff", "start"));
        svg.append(wrappedSvgText(parent.x + 24, parent.y + 66,
                chapter.parentTitle(), 20, parent.width - 48, 2, "#ffffff"));
        String parentScore = chapter.parentScore() == null
                ? labels.notEvaluated() : chapter.parentScore() + " %";
        svg.append(text(parent.x + parent.width - 24, parent.y + 38,
                parentScore, 21, true, "#ffffff", "end"));

        List<Card> cards = childCards(children.size());
        double parentX = parent.x + parent.width / 2.0;
        double parentY = parent.y + parent.height;
        double junctionY = 235;
        svg.append(line(parentX, parentY, parentX, junctionY));
        if (!cards.isEmpty()) {
            double left = cards.stream().mapToDouble(card -> card.x + card.width / 2.0).min().orElse(parentX);
            double right = cards.stream().mapToDouble(card -> card.x + card.width / 2.0).max().orElse(parentX);
            svg.append(line(left, junctionY, right, junctionY));
            for (Card card : cards) {
                double center = card.x + card.width / 2.0;
                svg.append(line(center, junctionY, center, card.y));
            }
        }

        for (int index = 0; index < children.size(); index++) {
            ChildDecision child = children.get(index);
            Card card = cards.get(index);
            CardStyle style = style(child);
            svg.append(rect(card, hex(style.fill()), hex(style.border()), style.dashed()));
            svg.append(text(card.x + 18, card.y + 31, child.code(), 22, true,
                    hex(style.text()), "start"));
            String absolute = child.absoluteScore() == null ? "—" : child.absoluteScore() + " %";
            svg.append(text(card.x + card.width - 18, card.y + 31, absolute, 22,
                    true, hex(style.text()), "end"));
            svg.append(wrappedSvgText(card.x + 18, card.y + 60, child.title(), 18,
                    card.width - 36, 3, hex(style.text())));
            String local = child.localSharePercent() == null
                    ? labels.localShare() + ": —"
                    : labels.localShare() + ": " + formatPercent(child.localSharePercent());
            svg.append(text(card.x + 18, card.y + card.height - 42, local, 16,
                    false, hex(style.secondaryText()), "start"));
            svg.append(text(card.x + 18, card.y + card.height - 17,
                    dispositionLabel(child, labels), 15, true,
                    hex(style.text()), "start"));
            if (child.leadingSibling()) {
                int badgeWidth = 112;
                Card badge = new Card(card.x + card.width - badgeWidth - 14,
                        card.y + card.height - 36, badgeWidth, 26);
                svg.append(rect(badge, "#007a78", "#007a78", false));
                svg.append(text(badge.x + badge.width / 2,
                        badge.y + 19, labels.leadingLabel(), 14, true,
                        "#ffffff", "middle"));
            }
        }
        String legend = labels.german()
                ? "Absoluter Relevanzwert · lokaler Anteil am Vaterwert · Status des Pfades"
                : "Absolute relevance score · local share of parent score · path status";
        svg.append(text(50, HEIGHT - 30, legend, 15, false, "#64707c", "start"));
        svg.append("</svg>");
        return svg.toString();
    }

    private String dispositionLabel(ChildDecision child, DecisionReportLabels labels) {
        return switch (child.disposition()) {
            case CONTINUED -> labels.continuedLabel();
            case LEAF_CANDIDATE -> labels.leafLabel();
            case REJECTED -> labels.rejectedLabel();
            case NOT_EVALUATED -> labels.notEvaluatedLabel();
        };
    }

    private String altText(
            DecisionChapter chapter,
            List<ChildDecision> children,
            DecisionReportLabels labels,
            int panelNumber,
            int panelCount) {
        StringBuilder text = new StringBuilder();
        text.append(labels.parentNode()).append(' ').append(chapter.parentCode())
                .append(' ').append(chapter.parentTitle()).append(", ")
                .append(chapter.parentScore() == null ? labels.notEvaluated()
                        : chapter.parentScore() + "%");
        if (panelCount > 1) {
            text.append(", panel ").append(panelNumber).append(" of ").append(panelCount);
        }
        for (ChildDecision child : children) {
            text.append("; ").append(child.code()).append(' ').append(child.title())
                    .append(' ').append(child.absoluteScore() == null ? labels.notEvaluated()
                            : child.absoluteScore() + "%")
                    .append(' ').append(dispositionLabel(child, labels));
        }
        return text.toString();
    }

    private void drawWrapped(
            Graphics2D graphics,
            String value,
            int x,
            int y,
            int maxWidth,
            int lineHeight,
            int maxLines) {
        List<String> lines = wrap(value, graphics.getFontMetrics(), maxWidth, maxLines);
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(lines.get(index), x, y + index * lineHeight);
        }
    }

    private void drawRight(Graphics2D graphics, String value, int rightX, int baselineY) {
        graphics.drawString(value, rightX - graphics.getFontMetrics().stringWidth(value), baselineY);
    }

    private List<String> wrap(String value, FontMetrics metrics, int maxWidth, int maxLines) {
        String safe = value == null || value.isBlank() ? "—" : value.strip();
        String[] words = safe.split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (metrics.stringWidth(candidate) <= maxWidth || current.isEmpty()) {
                current.setLength(0);
                current.append(candidate);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
                if (lines.size() == maxLines - 1) {
                    break;
                }
            }
        }
        if (!current.isEmpty() && lines.size() < maxLines) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("—");
        }
        if (String.join(" ", lines).length() < safe.length()) {
            int last = lines.size() - 1;
            String shortened = lines.get(last);
            while (!shortened.isEmpty() && metrics.stringWidth(shortened + "…") > maxWidth) {
                shortened = shortened.substring(0, shortened.length() - 1);
            }
            lines.set(last, shortened.stripTrailing() + "…");
        }
        return lines;
    }

    private String wrappedSvgText(
            int x,
            int y,
            String value,
            int fontSize,
            int maxWidth,
            int maxLines,
            String color) {
        FontMetrics metrics = metrics(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
        List<String> lines = wrap(value, metrics, maxWidth, maxLines);
        StringBuilder result = new StringBuilder();
        result.append("<text x=\"").append(x).append("\" y=\"").append(y)
                .append("\" font-family=\"Arial, sans-serif\" font-size=\"")
                .append(fontSize).append("\" fill=\"").append(color).append("\">");
        for (int index = 0; index < lines.size(); index++) {
            result.append("<tspan x=\"").append(x).append("\" dy=\"")
                    .append(index == 0 ? 0 : fontSize + 2).append("\">")
                    .append(xml(lines.get(index))).append("</tspan>");
        }
        result.append("</text>");
        return result.toString();
    }

    private FontMetrics metrics(Font font) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(font);
            return graphics.getFontMetrics();
        } finally {
            graphics.dispose();
        }
    }

    private String rect(Card card, String fill, String stroke, boolean dashed) {
        return "<rect x=\"" + card.x + "\" y=\"" + card.y
                + "\" width=\"" + card.width + "\" height=\"" + card.height
                + "\" rx=\"18\" fill=\"" + fill + "\" stroke=\"" + stroke
                + "\" stroke-width=\"2.5\""
                + (dashed ? " stroke-dasharray=\"10 8\"" : "") + "/>";
    }

    private String line(double x1, double y1, double x2, double y2) {
        return "<line x1=\"" + x1 + "\" y1=\"" + y1 + "\" x2=\"" + x2
                + "\" y2=\"" + y2 + "\" stroke=\"#cdd5dc\" stroke-width=\"3\"/>";
    }

    private String text(
            int x,
            int y,
            String value,
            int fontSize,
            boolean bold,
            String fill,
            String anchor) {
        return "<text x=\"" + x + "\" y=\"" + y
                + "\" font-family=\"Arial, sans-serif\" font-size=\"" + fontSize
                + "\" font-weight=\"" + (bold ? "700" : "400")
                + "\" fill=\"" + fill + "\" text-anchor=\"" + anchor + "\">"
                + xml(value) + "</text>";
    }

    private String formatPercent(Double value) {
        if (value == null) {
            return "—";
        }
        return Math.abs(value - Math.rint(value)) < 0.05
                ? String.format(java.util.Locale.ROOT, "%.0f%%", value)
                : String.format(java.util.Locale.ROOT, "%.1f%%", value);
    }

    private String hex(Color color) {
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
                color.getRed(), color.getGreen(), color.getBlue());
    }

    private String xml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private record Card(int x, int y, int width, int height) {
    }

    private record CardStyle(
            Color fill,
            Color border,
            Color text,
            Color secondaryText,
            boolean dashed) {
    }
}
