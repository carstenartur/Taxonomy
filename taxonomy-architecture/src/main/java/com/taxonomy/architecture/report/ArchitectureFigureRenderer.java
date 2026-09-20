package com.taxonomy.architecture.report;

import com.taxonomy.architecture.decision.DecisionReportLabels;
import com.taxonomy.diagram.*;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

/** Bounded Java2D renderer. One panel is allocated and released at a time. */
public final class ArchitectureFigureRenderer {
    public static final int MAX_WIDTH = 1800, MAX_HEIGHT = 1300;

    public record RenderedFigure(
            String id,
            byte[] png,
            int width,
            int height,
            String caption,
            String altDescription,
            List<String> nodeIds,
            List<String> edgeIds) {
        public RenderedFigure {
            png = png.clone();
            nodeIds = List.copyOf(nodeIds);
            edgeIds = List.copyOf(edgeIds);
        }

        @Override
        public byte[] png() {
            return png.clone();
        }
    }

    public RenderedFigure render(ArchitectureFigurePlanner.Panel panel, String language) {
        var labels = new DecisionReportLabels(language);
        var scene = panel.scene();
        if (!Double.isFinite(scene.width())
                || !Double.isFinite(scene.height())
                || scene.width() <= 0
                || scene.height() <= 0)
            throw new IllegalArgumentException("Invalid architecture figure dimensions");
        // Keep the overview's frozen frame. Details remove only unused canvas around their
        // evidence.
        Rectangle2D bounds = new Rectangle2D.Double(0, 0, scene.width(), scene.height());
        if (!panel.overview()) {
            bounds = null;
            for (var node : scene.nodes()) {
                var occupied =
                        new Rectangle2D.Double(node.x(), node.y(), node.width(), node.height());
                if (bounds == null) bounds = occupied;
                else bounds.add(occupied);
            }
            for (var edge : scene.edges()) bounds.add(curve(edge, scene).getBounds2D());
            bounds =
                    new Rectangle2D.Double(
                            bounds.getX() - 24,
                            bounds.getY() - 24,
                            bounds.getWidth() + 48,
                            bounds.getHeight() + 48);
        }
        double scale =
                Math.min(
                        1.5,
                        Math.min(MAX_WIDTH / bounds.getWidth(), MAX_HEIGHT / bounds.getHeight()));
        int width = (int) Math.ceil(bounds.getWidth() * scale),
                height = (int) Math.ceil(bounds.getHeight() * scale);
        if (width < 1 || height < 1 || (long) width * height > 2_340_000)
            throw new IllegalArgumentException("Word figure pixel policy ceiling exceeded");
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.scale(scale, scale);
            g.translate(-bounds.getX(), -bounds.getY());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (var edge : scene.edges()) {
                g.setColor(new Color(0x64748B));
                g.setStroke(stroke(edge.relationCategory()));
                var path = curve(edge, scene);
                g.draw(path);
                double angle = Math.atan2(path.y2 - path.ctrly2, path.x2 - path.ctrlx2);
                if (path.x2 == path.ctrlx2 && path.y2 == path.ctrly2)
                    angle = Math.atan2(path.y2 - path.y1, path.x2 - path.x1);
                double dx = Math.cos(angle), dy = Math.sin(angle);
                var arrow = new Path2D.Double();
                arrow.moveTo(path.x2, path.y2);
                arrow.lineTo(path.x2 - 11 * dx + 6 * dy, path.y2 - 11 * dy - 6 * dx);
                arrow.lineTo(path.x2 - 11 * dx - 6 * dy, path.y2 - 11 * dy + 6 * dx);
                arrow.closePath();
                g.fill(arrow);
            }
            for (var node : scene.nodes()) {
                var box =
                        new RoundRectangle2D.Double(
                                node.x(), node.y(), node.width(), node.height(), 12, 12);
                g.setColor(color(node.type()));
                g.fill(box);
                g.setColor(node.anchor() ? new Color(0x007A78) : new Color(0x475569));
                g.setStroke(
                        node.container()
                                ? new BasicStroke(2, 0, 0, 10, new float[] {7, 4}, 0)
                                : new BasicStroke(node.anchor() ? 3 : 2));
                g.draw(box);
                g.setFont(new Font("SansSerif", Font.BOLD, 20));
                String score = Math.round(node.relevance() * 100) + "%";
                int scoreWidth = g.getFontMetrics().stringWidth(score);
                drawBounded(
                        g,
                        node.id(),
                        (float) node.x() + 10,
                        (float) node.y() + 23,
                        (int) node.width() - scoreWidth - 30);
                g.drawString(
                        score,
                        (float) (node.x() + node.width() - scoreWidth - 10),
                        (float) node.y() + 23);
                g.setFont(new Font("SansSerif", Font.PLAIN, 20));
                drawTitle(
                        g,
                        node.label(),
                        (float) node.x() + 10,
                        (float) node.y() + 47,
                        (int) node.width() - 20);
            }
            // Compact stable IDs cross-reference the adjacent native relation key and full
            // inventory.
            // Long source identifiers remain in those tables rather than covering nearby graph
            // evidence.
            if (!panel.overview())
                for (var edge : scene.edges()) {
                    var path = curve(edge, scene);
                    double x = (path.x1 + 3 * path.ctrlx1 + 3 * path.ctrlx2 + path.x2) / 8;
                    double y = (path.y1 + 3 * path.ctrly1 + 3 * path.ctrly2 + path.y2) / 8;
                    g.setFont(new Font("SansSerif", Font.BOLD, 20));
                    String key = edge.id();
                    int keyWidth = g.getFontMetrics().stringWidth(key);
                    if (keyWidth > 120) continue;
                    var labelBounds =
                            new Rectangle2D.Double(
                                    x - keyWidth / 2.0 - 3, y - 18, keyWidth + 6, 24);
                    if (scene.nodes().stream()
                            .anyMatch(
                                    node ->
                                            labelBounds.intersects(
                                                    node.x(),
                                                    node.y(),
                                                    node.width(),
                                                    node.height()))) continue;
                    g.setColor(Color.WHITE);
                    g.fill(
                            new Rectangle2D.Double(
                                    x - keyWidth / 2.0 - 3, y - 18, keyWidth + 6, 24));
                    g.setColor(new Color(0x334155));
                    g.drawString(key, (float) (x - keyWidth / 2.0), (float) y);
                }
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            String caption =
                    panel.overview()
                            ? labels.architectureOverview()
                            : labels.architectureDetail()
                                    + " "
                                    + panel.id().substring(panel.id().lastIndexOf('-') + 1);
            String description =
                    (panel.overview() ? labels.overviewPurpose() : labels.detailPurpose())
                            + " "
                            + caption
                            + ". "
                            + labels.includedNodes()
                            + ": "
                            + String.join(", ", panel.nodeIds())
                            + ". "
                            + labels.relationTypes()
                            + ": "
                            + String.join(
                                    "; ",
                                    panel.diagram().edges().stream()
                                            .map(
                                                    e ->
                                                            e.id()
                                                                    + ": "
                                                                    + e.sourceId()
                                                                    + " → "
                                                                    + e.targetId()
                                                                    + " / "
                                                                    + e.relationType()
                                                                    + " / "
                                                                    + e.relationCategory())
                                            .toList());
            return new RenderedFigure(
                    panel.id(),
                    output.toByteArray(),
                    width,
                    height,
                    caption,
                    description,
                    panel.nodeIds(),
                    panel.edgeIds());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not render architecture figure", exception);
        } finally {
            g.dispose();
            image.flush();
        }
    }

    static CubicCurve2D.Double curve(DiagramSceneEdge edge, DiagramScene scene) {
        if (edge.sourceId().equals(edge.targetId())) {
            var node =
                    scene.nodes().stream()
                            .filter(n -> n.id().equals(edge.sourceId()))
                            .findFirst()
                            .orElseThrow();
            double right = node.x() + node.width(), top = node.y();
            // Right side to top, entirely outside the node fill; arrow approaches the top edge.
            return new CubicCurve2D.Double(
                    right,
                    top + node.height() * .35,
                    right + 44,
                    top - 44,
                    node.x() + node.width() * .65,
                    top - 44,
                    node.x() + node.width() * .65,
                    top);
        }
        double sx = edge.sourceX(), sy = edge.sourceY(), tx = edge.targetX(), ty = edge.targetY();
        if (Math.abs(tx - sx) >= Math.abs(ty - sy))
            return new CubicCurve2D.Double(sx, sy, (sx + tx) / 2, sy, (sx + tx) / 2, ty, tx, ty);
        return new CubicCurve2D.Double(sx, sy, sx, (sy + ty) / 2, tx, (sy + ty) / 2, tx, ty);
    }

    private BasicStroke stroke(String category) {
        if ("trace".equals(category)) return new BasicStroke(2, 0, 0, 10, new float[] {8, 5}, 0);
        if ("seed".equals(category)) return new BasicStroke(2, 0, 0, 10, new float[] {2, 5}, 0);
        return new BasicStroke(2);
    }

    private Color color(String type) {
        if (type == null) return new Color(0xF1F5F9);
        return new Color(
                switch (type) {
                    case "Capabilities" -> 0xDBEAFE;
                    case "Business Processes", "Business Roles" -> 0xDCFCE7;
                    case "Core Services", "COI Services", "Services" -> 0xFEF3C7;
                    case "User Applications", "Applications" -> 0xF3E8FF;
                    case "Information Products" -> 0xFFE4E6;
                    case "Communications Services" -> 0xCFFAFE;
                    default -> 0xF1F5F9;
                });
    }

    private void drawTitle(Graphics2D g, String title, float x, float y, int width) {
        if (title == null) return;
        int end = title.length();
        while (end > 0 && g.getFontMetrics().stringWidth(title.substring(0, end)) > width) end--;
        if (end == title.length()) {
            g.drawString(title, x, y);
            return;
        }
        int space = title.lastIndexOf(' ', end);
        if (space > 0) end = space;
        g.drawString(title.substring(0, end), x, y);
        drawBounded(g, title.substring(end).stripLeading(), x, y + 23, width);
    }

    private void drawBounded(Graphics2D g, String text, float x, float y, int width) {
        if (text == null) return;
        int end = text.length();
        while (end > 0
                && g.getFontMetrics()
                                .stringWidth(
                                        text.substring(0, end) + (end < text.length() ? "…" : ""))
                        > width) end--;
        g.drawString(text.substring(0, end) + (end < text.length() ? "…" : ""), x, y);
    }
}
