package com.taxonomy.visio;

import com.taxonomy.diagram.VisioDrawingOptions;

import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;

/** Physical inches in Visio's page coordinate system. All lengths on a poster share one scale. */
public record VisioPageLayout(double width, double height, double scale, double left, double bottom) {
    public static final VisioPageLayout NATURAL = new VisioPageLayout(11, 8.5, 1, 0, 0);
    private static final VisioPageLayout EVIDENCE_SHEET = new VisioPageLayout(8.3, 11.7, 1, 0, 0);

    /** Scale standalone appendix content for viewers that open every sheet on one document-wide canvas. */
    public static Map<String, VisioPageLayout> documentLayouts(VisioDocument document) {
        var options = document.getDrawingOptions();
        var layouts = new HashMap<String, VisioPageLayout>();
        document.getPages().forEach(page -> layouts.put(page.getId(), of(page, options)));
        if (options != null || document.getPages().stream().noneMatch(VisioPage::isEvidencePage)) return layouts;
        double width = layouts.values().stream().mapToDouble(VisioPageLayout::width).max().orElseThrow();
        double height = layouts.values().stream().mapToDouble(VisioPageLayout::height).max().orElseThrow();
        // Normal-sized documents keep their nominal paper sizes. Magnify appended content
        // only when the main drawing extends beyond the longest edge of an A4 sheet.
        if (Math.max(width, height) <= EVIDENCE_SHEET.height()) return layouts;
        for (var page : document.getPages()) {
            if (!page.isEvidencePage() && !page.isRelationshipDetail()) continue;
            Rectangle2D bounds = contentBounds(page, false);
            if (bounds == null || bounds.isEmpty()) continue;
            double margin = .5;
            double fit = Math.min((width - 2 * margin) / bounds.getWidth(), (height - 2 * margin) / bounds.getHeight());
            layouts.put(page.getId(), centered(width, height, Math.min(3, fit), bounds));
        }
        return layouts;
    }

    public static VisioPageLayout of(VisioPage page, VisioDrawingOptions options) {
        boolean wall = options != null && options.mode() == VisioDrawingOptions.Mode.WALL;
        Rectangle2D bounds = contentBounds(page, wall);
        if (!wall) {
            if (page.isEvidencePage()) return EVIDENCE_SHEET;
            if (bounds == null) return NATURAL;
            return new VisioPageLayout(Math.max(11, bounds.getMaxX() + 1), Math.max(8.5, bounds.getMaxY() + 1), 1, 0, 0);
        }

        // ISO 216 landscape; expressed in inches only at the ShapeSheet boundary.
        double width = switch (options.paper()) {
            case A4 -> 297; case A3 -> 420; case A2 -> 594; case A1 -> 841; case A0 -> 1189;
        } / 25.4;
        double height = switch (options.paper()) {
            case A4 -> 210; case A3 -> 297; case A2 -> 420; case A1 -> 594; case A0 -> 841;
        } / 25.4;
        if (bounds == null) return new VisioPageLayout(width, height, 1, 0, 0);
        double margin = height * .03;
        // Native labels are 10–12 pt. Sparse sheets stay at or below 30–36 pt.
        double fit = Math.min((width - 2 * margin) / bounds.getWidth(), (height - 2 * margin) / bounds.getHeight());
        return centered(width, height, Math.min(3, fit), bounds);
    }

    private static Rectangle2D contentBounds(VisioPage page, boolean wall) {
        Rectangle2D bounds = null;
        for (var shape : page.getShapes()) {
            bounds = include(bounds, shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight());
        }
        if (page.getLegend() != null) bounds = include(bounds, page.getLegend());
        Map<String, VisioShape> shapes = null;
        VisioConnectionPoints.Columns columns = null;
        for (var connect : page.getConnects()) {
            var box = connect.getTextBox();
            if (box == null && wall && !connect.getRelationType().isBlank()) {
                // A relationship without an explicit caption falls back to its type at the midpoint.
                if (shapes == null) {
                    shapes = new HashMap<>();
                    for (var shape : page.getShapes()) shapes.put(shape.getId(), shape);
                    columns = new VisioConnectionPoints.Columns(page.getShapes());
                }
                var endpoints = columns.route(connect, shapes.get(connect.getFromShape()), shapes.get(connect.getToShape())).endpoints();
                box = connect.textBoxOrDefault(endpoints);
            }
            if (box != null && (!wall || !box.text().isBlank())) bounds = include(bounds, box);
        }
        return bounds;
    }

    private static VisioPageLayout centered(double width, double height, double scale, Rectangle2D bounds) {
        return new VisioPageLayout(width, height, scale, width / 2 - bounds.getCenterX() * scale,
                height / 2 - bounds.getCenterY() * scale);
    }

    public double x(double x) { return left + x * scale; }
    public double y(double y) { return bottom + y * scale; }
    public double length(double length) { return length * scale; }

    private static Rectangle2D include(Rectangle2D bounds, VisioTextBox box) {
        return include(bounds, box.x(), box.y(), box.width(), box.height());
    }

    private static Rectangle2D include(Rectangle2D bounds, double x, double y, double width, double height) {
        var rectangle = new Rectangle2D.Double(x - width / 2, y - height / 2, width, height);
        if (bounds == null) return rectangle;
        bounds.add(rectangle);
        return bounds;
    }
}
