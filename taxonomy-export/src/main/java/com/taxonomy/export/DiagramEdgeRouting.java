package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;

import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.*;

/** Render-time detours for the fixed side ports and gutters of a layered scene. */
public final class DiagramEdgeRouting {
    private static final double CARD_CLEARANCE = 4;
    private static final double ENDPOINT_INSET = .25;
    private static final double CANVAS_CLEARANCE = 4;
    private static final double TRACK_SPACING = 8;

    public record Point(double x, double y) { }
    public record Detour(List<Point> points) {
        public Detour { points = List.copyOf(points); }

        public Point labelAnchor() {
            Point a = points.get(2), b = points.get(3);
            return new Point((a.x() + b.x()) / 2, a.y());
        }

        public Path2D.Double shape() {
            var path = new Path2D.Double();
            path.moveTo(points.getFirst().x(), points.getFirst().y());
            for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i).x(), points.get(i).y());
            return path;
        }
    }

    private record Track(double from, double to) {
        boolean overlaps(Track other) {
            return Math.min(to, other.to) - Math.max(from, other.from) > TRACK_SPACING;
        }
    }

    private record Card(String id, Rectangle2D box, Rectangle2D interior) {
        Rectangle2D obstacle(DiagramSceneEdge edge) {
            return id.equals(edge.sourceId()) || id.equals(edge.targetId()) ? interior : box;
        }
    }

    private record Pair(String a, String b) {
        static Pair of(DiagramSceneEdge edge) {
            String source = edge.sourceId(), target = edge.targetId();
            return source.compareTo(target) <= 0 ? new Pair(source, target) : new Pair(target, source);
        }
    }

    private final DiagramScene scene;
    private final Map<String, DiagramSceneNode> byId = new HashMap<>();
    private final Map<String, Double> lanes = new HashMap<>();
    private final List<Card> cards;
    private final List<Double> tracks;
    private final NavigableMap<Double, List<Track>> occupiedTracks = new TreeMap<>();
    private final Map<DiagramSceneEdge, Optional<Detour>> lineRoutes = new HashMap<>();

    public DiagramEdgeRouting(DiagramScene scene) {
        this.scene = scene;
        var candidates = new TreeSet<Double>();
        cards = scene.nodes().stream().map(node -> {
            byId.put(node.id(), node);
            candidates.add(node.y() - 12);
            candidates.add(node.y() + node.height() + 16);
            return new Card(node.id(), cardBounds(node, CARD_CLEARANCE), cardBounds(node, -ENDPOINT_INSET));
        }).toList();
        tracks = List.copyOf(candidates);
        var byPair = new LinkedHashMap<Pair, List<DiagramSceneEdge>>();
        scene.edges().forEach(edge -> byPair.computeIfAbsent(Pair.of(edge), ignored -> new ArrayList<>()).add(edge));
        for (var group : byPair.values()) {
            for (int i = 0; i < group.size(); i++) lanes.put(group.get(i).id(), i - (group.size() - 1) / 2.0);
        }
    }

    /** Try the middle first, then look along a clear straight segment for space beside the edge. */
    static List<Point> captionAnchors(DiagramSceneEdge edge, Detour detour) {
        Point start = detour == null ? new Point(edge.sourceX(), edge.sourceY()) : detour.points().get(2);
        Point end = detour == null ? new Point(edge.targetX(), edge.targetY()) : detour.points().get(3);
        double dx = end.x() - start.x(), dy = end.y() - start.y();
        double centerX = start.x() + dx / 2, centerY = start.y() + dy / 2;
        var anchors = new ArrayList<Point>();
        anchors.add(new Point(centerX, centerY));
        int intervals = Math.min(32, (int) Math.ceil(Math.hypot(dx, dy) / 48));
        for (int i = 1; i < intervals; i++) {
            double fraction = .5 * i / intervals;
            anchors.add(new Point(centerX + dx * fraction, centerY + dy * fraction));
            anchors.add(new Point(centerX - dx * fraction, centerY - dy * fraction));
        }
        return anchors;
    }

    public Detour detourForLine(DiagramSceneEdge edge) {
        return lineRoutes.computeIfAbsent(edge, this::allocateLineRoute).orElse(null);
    }

    private Optional<Detour> allocateLineRoute(DiagramSceneEdge edge) {
        var original = new Line2D.Double(edge.sourceX(), edge.sourceY(), edge.targetX(), edge.targetY());
        var route = detour(edge, original);
        var points = route == null ? flatten(original) : route.points();
        for (int i = 1; i < points.size(); i++) {
            var start = points.get(i - 1);
            var end = points.get(i);
            if (start.y() == end.y() && Math.abs(start.x() - end.x()) > TRACK_SPACING)
                occupiedTracks.computeIfAbsent(start.y(), ignored -> new ArrayList<>()).add(track(start, end));
        }
        return Optional.ofNullable(route);
    }

    /** Returns null when the original path is clear or no supported gutter reaches the saved ports. */
    public Detour detour(DiagramSceneEdge edge, Shape original) {
        var source = byId.get(edge.sourceId());
        var target = byId.get(edge.targetId());
        if (source == null || target == null) return null;
        var originalPoints = flatten(original);
        if (clear(edge, originalPoints) && separated(originalPoints)) return null;
        Point start = new Point(edge.sourceX(), edge.sourceY()), end = new Point(edge.targetX(), edge.targetY());
        double lane = lanes.getOrDefault(edge.id(), 0d);
        double stagger = lane / (Math.abs(lane) + 2);
        double stub = 12 + 5 * stagger;
        double from = outside(start.x(), source, stub), to = outside(end.x(), target, stub);
        if (from < CANVAS_CLEARANCE || from > scene.width() - CANVAS_CLEARANCE
                || to < CANVAS_CLEARANCE || to > scene.width() - CANVAS_CLEARANCE) return null;
        double center = (start.y() + end.y()) / 2 + 45 * stagger;
        var candidates = tracks.stream().map(y -> y + 6 * stagger)
                .filter(y -> y >= CANVAS_CLEARANCE && y <= scene.height() - CANVAS_CLEARANCE)
                .sorted(Comparator.<Double>comparingDouble(y -> Math.abs(y - start.y()) + Math.abs(y - end.y())
                        + Math.abs(y - center)).thenComparing(Comparator.reverseOrder())).toList();
        // The authored grid has a 24-unit horizontal gutter and a 34-unit row gutter.
        Detour fallback = null;
        for (double offset : new double[]{0, TRACK_SPACING, -TRACK_SPACING}) {
            for (double candidate : candidates) {
                double y = candidate + offset;
                if (y < CANVAS_CLEARANCE || y > scene.height() - CANVAS_CLEARANCE) continue;
                var points = List.of(start, new Point(from, start.y()), new Point(from, y),
                        new Point(to, y), new Point(to, end.y()), end);
                if (!clear(edge, points)) continue;
                if (separated(points.subList(2, 4))) return new Detour(points);
                if (fallback == null) fallback = new Detour(points);
            }
        }
        return fallback;
    }

    private boolean separated(List<Point> points) {
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1), b = points.get(i);
            if (a.y() == b.y() && occupiedTracks.subMap(a.y() - TRACK_SPACING, false, a.y() + TRACK_SPACING, false)
                    .values().stream().flatMap(List::stream).anyMatch(track(a, b)::overlaps)) return false;
        }
        return true;
    }

    private static Track track(Point a, Point b) {
        return new Track(Math.min(a.x(), b.x()), Math.max(a.x(), b.x()));
    }

    private boolean clear(DiagramSceneEdge edge, List<Point> points) {
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1), b = points.get(i);
            for (var card : cards) {
                if (card.obstacle(edge).intersectsLine(a.x(), a.y(), b.x(), b.y())) return false;
            }
        }
        return true;
    }

    private static List<Point> flatten(Shape path) {
        var points = new ArrayList<Point>();
        double[] coordinates = new double[6];
        for (var iterator = path.getPathIterator(null, .25); !iterator.isDone(); iterator.next()) {
            int segment = iterator.currentSegment(coordinates);
            if (segment == PathIterator.SEG_MOVETO || segment == PathIterator.SEG_LINETO)
                points.add(new Point(coordinates[0], coordinates[1]));
        }
        return points;
    }

    private static double outside(double x, DiagramSceneNode node, double stub) {
        return x < node.x() + node.width() / 2 ? x - stub : x + stub;
    }

    private static Rectangle2D cardBounds(DiagramSceneNode node, double padding) {
        return new Rectangle2D.Double(node.x() - padding, node.y() - padding,
                node.width() + 2 * padding, node.height() + 2 * padding);
    }
}
