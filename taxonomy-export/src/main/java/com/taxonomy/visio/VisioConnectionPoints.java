package com.taxonomy.visio;

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
