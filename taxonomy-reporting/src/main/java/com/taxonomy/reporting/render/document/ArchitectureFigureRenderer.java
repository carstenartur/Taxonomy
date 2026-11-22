package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;
import com.taxonomy.diagram.*;
import com.taxonomy.export.DiagramEdgeRouting;
import com.taxonomy.export.DiagramTextWrapper;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/** Bounded Java2D renderer. One panel is allocated and released at a time. */
public final class ArchitectureFigureRenderer {
    public static final int MAX_WIDTH = 1800, MAX_HEIGHT = 1300;
    static final int NODE_TITLE_SIZE = 20;

    public record RenderedFigure(
            String id,
            byte[] png,
            int width,
            int height,
            double rasterScale,
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
        Rectangle2D bounds = null;
        for (var node : scene.nodes()) {
            var occupied = new Rectangle2D.Double(node.x(), node.y(), node.width(), node.height());
            if (bounds == null) bounds = occupied;
            else bounds.add(occupied);
        }
        if (bounds == null) throw new IllegalArgumentException("Architecture figure has no elements");
        var routing = new DiagramEdgeRouting(scene);
        var figureEdges = scene.edges().stream().map(edge -> figureEdge(edge, scene, routing)).toList();
        for (var edge : figureEdges) bounds.add(edge.path().getBounds2D());
        bounds = new Rectangle2D.Double(bounds.getX() - 24, bounds.getY() - 24,
                bounds.getWidth() + 48, bounds.getHeight() + 48);
        // Keep the same node identifiers in the whole-graph map and in the readable detail panels.
        boolean compactOverview = panel.overview() && (bounds.getWidth() > ArchitectureFigurePlanner.MAX_DETAIL_WIDTH
                || bounds.getHeight() > ArchitectureFigurePlanner.MAX_DETAIL_HEIGHT);
        double scale =
                Math.min(
                        2,
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
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (var edge : figureEdges) {
                g.setColor(new Color(0x64748B));
                g.setStroke(stroke(edge.edge().relationCategory()));
                g.draw(edge.path());
                double dx = Math.cos(edge.angle()), dy = Math.sin(edge.angle());
                double x = edge.targetX(), y = edge.targetY();
                var arrow = new Path2D.Double();
                arrow.moveTo(x, y);
                arrow.lineTo(x - 11 * dx + 6 * dy, y - 11 * dy - 6 * dx);
                arrow.lineTo(x - 11 * dx - 6 * dy, y - 11 * dy + 6 * dx);
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
                if (compactOverview) {
                    g.setColor(new Color(0x1E293B));
                    g.setFont(new Font("SansSerif", Font.BOLD, 26));
                    var metrics = g.getFontMetrics();
                    float centerY = (float) (node.y() + (node.height() + metrics.getAscent() - metrics.getDescent()) / 2);
                    float left = (float) (node.x() + Math.max(10, (node.width() - metrics.stringWidth(node.id())) / 2));
                    drawBounded(g, node.id(), left, centerY, (int) node.width() - 20);
                    continue;
                }
                g.setFont(new Font("SansSerif", Font.BOLD, 20));
                String score = node.relevanceRecorded()
                        ? com.taxonomy.dto.AnalysisPercentages.display(node.relevance() * 100) : "";
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
                g.setFont(new Font("SansSerif", Font.PLAIN, NODE_TITLE_SIZE));
                var assignment = node.responsibility();
                float x = (float) node.x() + 10;
                float titleY = (float) node.y() + 47;
                int textWidth = (int) node.width() - 20;
                if (assignment == null) {
                    var titleLines = DiagramTextWrapper.wrapMeasured(node.label(), g.getFont(), textWidth, 3, node.id());
                    for (int i = 0; i < titleLines.size(); i++)
                        g.drawString(titleLines.get(i), x, (float) node.y() + 42 + i * 18);
                } else {
                    drawBounded(g, node.label(), x, titleY, textWidth);
                    g.setColor(Color.decode(assignment.color()));
                    g.fill(new Ellipse2D.Double(x, node.y() + 62, 9, 9));
                    g.setColor(new Color(0x334155));
                    g.setFont(new Font("SansSerif", Font.PLAIN, 16));
                    drawBounded(g, assignment.name(), x + 14, (float) node.y() + 72, textWidth - 14);
                }
            }
            // Compact stable IDs cross-reference the adjacent native relation key and full
            // inventory.
            // Long source identifiers remain in those tables rather than covering nearby graph
            // evidence.
            if (!panel.overview()) {
                var captions = new ArrayList<Rectangle2D>();
                g.setFont(new Font("SansSerif", Font.BOLD, 20));
                for (var edge : figureEdges) {
                    String key = edge.edge().id();
                    int keyWidth = g.getFontMetrics().stringWidth(key);
                    if (keyWidth > 120) continue;
                    double x = edge.labelX();
                    for (int offset : edge.detoured() ? new int[]{0, 28} : new int[]{0}) {
                        double y = edge.labelY() + offset;
                        var labelBounds = new Rectangle2D.Double(x - keyWidth / 2.0 - 3, y - 18, keyWidth + 6, 24);
                        if (!bounds.contains(labelBounds) || captions.stream().anyMatch(labelBounds::intersects)
                                || scene.nodes().stream().anyMatch(node ->
                                        labelBounds.intersects(node.x(), node.y(), node.width(), node.height()))) continue;
                        g.setColor(Color.WHITE);
                        g.fill(labelBounds);
                        g.setColor(new Color(0x334155));
                        g.drawString(key, (float) (x - keyWidth / 2.0), (float) y);
                        captions.add(labelBounds);
                        break;
                    }
                }
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
                    scale,
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

    private record FigureEdge(DiagramSceneEdge edge, Shape path, double targetX, double targetY,
                              double angle, double labelX, double labelY, boolean detoured) { }

    private static FigureEdge figureEdge(DiagramSceneEdge edge, DiagramScene scene, DiagramEdgeRouting routing) {
        var curve = curve(edge, scene);
        var detour = routing.detour(edge, curve);
        if (detour != null) {
            var points = detour.points();
            var target = points.getLast();
            var beforeTarget = points.get(points.size() - 2);
            var label = detour.labelAnchor();
            double angle = Math.atan2(target.y() - beforeTarget.y(), target.x() - beforeTarget.x());
            return new FigureEdge(edge, detour.shape(), target.x(), target.y(), angle, label.x(), label.y() - 8, true);
        }
        double angle = Math.atan2(curve.y2 - curve.ctrly2, curve.x2 - curve.ctrlx2);
        if (curve.x2 == curve.ctrlx2 && curve.y2 == curve.ctrly2)
            angle = Math.atan2(curve.y2 - curve.y1, curve.x2 - curve.x1);
        double labelX = (curve.x1 + 3 * curve.ctrlx1 + 3 * curve.ctrlx2 + curve.x2) / 8;
        double labelY = (curve.y1 + 3 * curve.ctrly1 + 3 * curve.ctrly2 + curve.y2) / 8;
        return new FigureEdge(edge, curve, curve.x2, curve.y2, angle, labelX, labelY, false);
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
