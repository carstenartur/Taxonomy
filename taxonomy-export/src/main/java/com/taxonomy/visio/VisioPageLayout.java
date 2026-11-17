package com.taxonomy.visio;

import com.taxonomy.diagram.VisioDrawingOptions;

import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;

/** Physical inches in Visio's page coordinate system. All lengths on a poster share one scale. */
public record VisioPageLayout(double width, double height, double scale, double left, double bottom) {
    public static final VisioPageLayout NATURAL = new VisioPageLayout(11, 8.5, 1, 0, 0);

    public static VisioPageLayout of(VisioPage page, VisioDrawingOptions options) {
        boolean wall = options != null && options.mode() == VisioDrawingOptions.Mode.WALL;
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
        if (!wall) {
            if (page.isEvidencePage()) return new VisioPageLayout(8.3, 11.7, 1, 0, 0);
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
        double scale = Math.min(3, fit);
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
