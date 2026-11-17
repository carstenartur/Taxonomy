package com.taxonomy.visio;

import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Native side ports and their cached page coordinates. Port rows are zero-based in XML. */
public final class VisioConnectionPoints {
    private VisioConnectionPoints() { }

    public enum Side {
        LEFT(1, 0, .5), RIGHT(2, 1, .5), BOTTOM(3, .5, 0), TOP(4, .5, 1);
        private final int number;
        private final double xFraction;
        private final double yFraction;
        Side(int number, double xFraction, double yFraction) {
            this.number = number; this.xFraction = xFraction; this.yFraction = yFraction;
        }
        public int number() { return number; }
        public double xFraction() { return xFraction; }
        public double yFraction() { return yFraction; }
        public Side opposite() { return switch (this) { case LEFT -> RIGHT; case RIGHT -> LEFT; case BOTTOM -> TOP; case TOP -> BOTTOM; }; }
    }

    public record Point(Side side, double x, double y) {
        public String formula(String shapeId) {
            return "PAR(PNT(Sheet." + shapeId + "!Connections.X" + side.number()
                    + ",Sheet." + shapeId + "!Connections.Y" + side.number() + "))";
        }
    }

    public record Endpoints(Point source, Point target) {
        public double length() { return Math.hypot(target.x() - source.x(), target.y() - source.y()); }
    }

    /** A null gutter denotes the original two-point geometry. */
    public record Route(Endpoints endpoints, Double gutterFraction) { }

    /** Bounds shared by every shape in a layout column, including wider siblings. */
    public static final class Columns {
        private final NavigableMap<Double, Column> columns = new TreeMap<>();

        public Columns(List<VisioShape> shapes) {
            for (var shape : shapes) {
                if (!VisioNativeShape.isPresentationOnly(shape) && !VisioNativeShape.isContainer(shape)) {
                    columns.computeIfAbsent(shape.getX(), ignored -> new Column(shape.getX())).add(shape);
                }
            }
        }

        public Route route(VisioConnect link, VisioShape source, VisioShape target) {
            var direct = new Route(between(source, target), null);
            double left = Math.min(source.getX(), target.getX());
            double right = Math.max(source.getX(), target.getX());
            var leftColumn = columns.get(left);
            if (leftColumn == null || !Double.valueOf(right).equals(columns.higherKey(left))
                    || source.getY() == target.getY() || VisioNativeShape.isContainer(source)
                    || VisioNativeShape.isContainer(target)) return direct;

            var rightColumn = columns.get(right);
            double start = left + leftColumn.halfWidth;
            double end = right - rightColumn.halfWidth;
            if (start >= end) return direct;
            boolean hierarchy = VisioProperty.text("DECISION_HIERARCHY").equals(link.getProperties().get("taxonomy.type"));
            if (!hierarchy && !leftColumn.obstructs(direct.endpoints(), source, target)
                    && !rightColumn.obstructs(direct.endpoints(), source, target)) return direct;

            Side side = source.getX() == left ? Side.RIGHT : Side.LEFT;
            var endpoints = new Endpoints(point(source, side), point(target, side.opposite()));
            double fraction = ((start + end) / 2 - endpoints.source().x())
                    / (endpoints.target().x() - endpoints.source().x());
            return new Route(endpoints, fraction);
        }

        private static final class Column {
            private final NavigableMap<Double, List<VisioShape>> rows = new TreeMap<>();
            private final double x;
            private double halfWidth;
            private double halfHeight;

            Column(double x) { this.x = x; }

            void add(VisioShape shape) {
                halfWidth = Math.max(halfWidth, shape.getWidth() / 2);
                halfHeight = Math.max(halfHeight, shape.getHeight() / 2);
                rows.computeIfAbsent(shape.getY(), ignored -> new ArrayList<>()).add(shape);
            }

            boolean obstructs(Endpoints edge, VisioShape source, VisioShape target) {
                var begin = edge.source();
                var end = edge.target();
                // Search only rows traversed while the direct line is inside this column.
                double dx = end.x() - begin.x();
                double a = (x - halfWidth - begin.x()) / dx;
                double b = (x + halfWidth - begin.x()) / dx;
                double first = Math.max(0, Math.min(a, b));
                double last = Math.min(1, Math.max(a, b));
                if (first >= last) return false;
                double dy = end.y() - begin.y();
                double firstY = begin.y() + first * dy;
                double lastY = begin.y() + last * dy;
                double bottom = Math.min(firstY, lastY) - halfHeight;
                double top = Math.max(firstY, lastY) + halfHeight;
                var line = new Line2D.Double(begin.x(), begin.y(), end.x(), end.y());
                for (var row : rows.subMap(bottom, true, top, true).values()) {
                    for (var shape : row) {
                        if (shape != source && shape != target && line.intersects(
                                shape.getX() - shape.getWidth() / 2, shape.getY() - shape.getHeight() / 2,
                                shape.getWidth(), shape.getHeight())) return true;
                    }
                }
                return false;
            }
        }
    }

    public static Endpoints between(VisioShape source, VisioShape target) {
        double dx = target.getX() - source.getX(), dy = target.getY() - source.getY();
        Side start = Math.abs(dx) >= Math.abs(dy) ? (dx >= 0 ? Side.RIGHT : Side.LEFT) : (dy >= 0 ? Side.TOP : Side.BOTTOM);
        Endpoints endpoints = new Endpoints(point(source, start), point(target, start.opposite()));
        if (endpoints.length() == 0 && (dx != 0 || dy != 0)) {
            // Touching shapes still need a visible, editable connector. Use the
            // adjacent sides instead of emitting a collapsed zero-length shape.
            start = start == Side.LEFT || start == Side.RIGHT ? Side.TOP : Side.RIGHT;
            endpoints = new Endpoints(point(source, start), point(target, start));
        }
        return endpoints;
    }

    private static Point point(VisioShape shape, Side side) {
        return new Point(side, shape.getX() + (side.xFraction() - .5) * shape.getWidth(),
                shape.getY() + (side.yFraction() - .5) * shape.getHeight());
    }
}
