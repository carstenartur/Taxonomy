package com.taxonomy.export;

import com.taxonomy.visio.*;

import java.util.*;

/** Bounded presentation policy for native Visio shapes; canonical data stays on the overview. */
final class VisioPresentation {
    private VisioPresentation() { }

    static void apply(VisioDocument document) {
        VisioPage overview = document.getPages().getFirst();
        Map<String, VisioShape> nodes = new LinkedHashMap<>();
        for (var shape : overview.getShapes()) nodes.put(shape.getId(), shape);
        Map<String, String> relationKeys = new HashMap<>();
        int ordinal = 0;
        for (var connect : overview.getConnects()) relationKeys.put(id(connect), "R" + (++ordinal));
        boolean dense = overview.getConnects().size() > 6 || overview.getShapes().size() > 12;
        int unplaced = 0;
        boolean shortenedOverviewType = false;
        for (var page : document.getPages()) {
            var occupied = new Occupancy();
            for (var shape : page.getShapes()) occupied.add(new Box(shape.getX(), shape.getY(), shape.getWidth() + .12, shape.getHeight() + .12));
            for (var connect : page.getConnects()) {
                String key = relationKeys.get(id(connect));
                connect.getProperties().put("taxonomy.displayKey", VisioProperty.text(key));
                String fullCaption = key + " " + connect.getRelationType();
                String text = dense ? key : display(fullCaption, 24, 2);
                boolean shortened = !dense && shortened(fullCaption, text);
                if (shortened && page == overview) {
                    shortenedOverviewType = true;
                    document.getLosses().add(new VisioLoss("relationship", id(connect), "overviewType", "TRUNCATED",
                            "Overview type shortened; consult native relationship details and full taxonomy.type."));
                }
                double width = dense ? .7 : 2.0, height = dense ? .3 : .5;
                var from = nodes.get(connect.getFromShape()); var to = nodes.get(connect.getToShape());
                Box position = place(occupied, from, to, width, height);
                if (position == null) {
                    // An omitted caption is explicit; its canonical connector is never omitted.
                    text = "";
                    position = new Box((from.getX() + to.getX()) / 2, (from.getY() + to.getY()) / 2, width, height);
                    connect.getProperties().put("taxonomy.captionDisposition", VisioProperty.text("DETAIL_REQUIRED"));
                    if (page == overview) unplaced++;
                } else {
                    occupied.add(position);
                    connect.getProperties().put("taxonomy.captionDisposition", VisioProperty.text(dense ? "COMPACT_KEY" : shortened ? "TYPE_TRUNCATED" : "KEY_AND_TYPE"));
                }
                connect.setTextBox(new VisioTextBox(text, position.x, position.y, width, height));
            }
        }
        if (unplaced > 0) document.getLosses().add(new VisioLoss("document", "", "overviewCaptions", "OMITTED",
                unplaced + " overview captions could not be placed without collision. Native connectors/data remain; consult relationship details."));
        if (dense || unplaced > 0 || shortenedOverviewType) addDetails(document, overview, nodes);
    }

    private static Box place(Occupancy occupied, VisioShape from, VisioShape to, double width, double height) {
        // Fixed 35 candidates; bounded local spatial lookups avoid O(nodes*edges).
        for (double fraction : new double[]{.5, .35, .65, .2, .8}) {
            for (double offset : new double[]{0, .4, -.4, .8, -.8, 1.2, -1.2}) {
                Box candidate = new Box(from.getX() + fraction * (to.getX() - from.getX()),
                        from.getY() + fraction * (to.getY() - from.getY()) + offset, width, height);
                if (candidate.left() >= .1 && candidate.bottom() >= .1 && !occupied.intersects(candidate)) return candidate;
            }
        }
        return null;
    }

    private static void addDetails(VisioDocument document, VisioPage overview, Map<String, VisioShape> nodes) {
        int shapes = document.getPages().stream().mapToInt(p -> p.getShapes().size()).sum();
        int connectors = document.getPages().stream().mapToInt(p -> p.getConnects().size()).sum();
        int covered = 0;
        while (covered < overview.getConnects().size() && document.getPages().size() < 32) {
            List<VisioConnect> batch = new ArrayList<>();
            Set<String> endpoints = new TreeSet<>(Comparator.comparingLong(Long::parseLong));
            for (int next = covered; next < overview.getConnects().size() && batch.size() < 6; next++) {
                var c = overview.getConnects().get(next);
                var candidate = new HashSet<>(endpoints); candidate.add(c.getFromShape()); candidate.add(c.getToShape());
                if (candidate.size() > 6) break;
                endpoints.addAll(candidate); batch.add(c);
            }
            if (shapes + endpoints.size() > 20_000 || connectors + batch.size() > 60_000) break;
            String range = key(batch.getFirst()) + "–" + key(batch.getLast());
            VisioPage page = new VisioPage(Integer.toString(document.getPages().size()), "Relationships " + range);
            page.setRelationshipDetail(true);
            int row = 0;
            for (String endpoint : endpoints) {
                var source = nodes.get(endpoint);
                // Six unique endpoints at generous fixed positions; captions occupy a separate rail.
                double x = row % 2 == 0 ? 2.2 : 6.2, y = 9.4 - (row / 2) * 3.3;
                String label = source.getProperties().get("taxonomy.label").value();
                String text = source.getProperties().get("taxonomy.displayKey").value() + "\n" + display(label, 32, 4);
                var shape = new VisioShape(source.getId(), text, x, y, 3.2, 1.4, source.getType(), source.isAnchor());
                shape.getProperties().putAll(source.getProperties()); page.getShapes().add(shape); row++;
            }
            row = 0;
            for (var source : batch) {
                var connect = new VisioConnect(source.getFromShape(), source.getToShape(), source.getRelationType());
                connect.getProperties().putAll(source.getProperties());
                String direction = nodes.get(source.getFromShape()).getProperties().get("taxonomy.displayKey").value()
                        + " → " + nodes.get(source.getToShape()).getProperties().get("taxonomy.displayKey").value();
                String type = display(source.getRelationType(), 52, 4);
                String text = key(source) + "  " + direction + "\n" + type;
                connect.setTextBox(new VisioTextBox(text, 11.3, 10 - row * 1.65, 5.2, 1.25));
                connect.getProperties().put("taxonomy.captionDisposition", VisioProperty.text(shortened(source.getRelationType(), type) ? "TYPE_TRUNCATED" : "READABLE_DETAIL"));
                page.getConnects().add(connect); row++;
            }
            shapes += endpoints.size(); connectors += batch.size(); covered += batch.size();
            document.getPages().add(page);
        }
        updateDetailCoverage(document, "page/node/connector capacity");
    }

    static void updateDetailCoverage(VisioDocument document, String capacity) {
        int covered = document.getPages().stream().filter(VisioPage::isRelationshipDetail).mapToInt(p -> p.getConnects().size()).sum();
        int total = document.getPages().getFirst().getConnects().size();
        document.getProperties().put("taxonomy.detailRelationshipsCovered", VisioProperty.number(covered));
        document.getProperties().put("taxonomy.detailRelationshipsTotal", VisioProperty.number(total));
        document.getLosses().removeIf(l -> l.field().equals("relationshipDetailCoverage"));
        String range = covered == 0 ? "none" : "R1–R" + covered;
        document.getLosses().add(new VisioLoss("document", "", "relationshipDetailCoverage", covered == total ? "MAPPED" : "TRUNCATED",
                "Native relationship detail coverage " + covered + "/" + total + "; covered keys " + range + ". "
                        + (covered == total ? "All relationships have a detail caption (long types explicitly marked if shortened)."
                        : "Optional detail " + capacity + " exhausted (32 pages / 20000 nodes / 60000 connectors / 32 MiB uncompressed package bytes). Remaining keys R" + (covered + 1)
                        + "–R" + total + " remain complete in overview native shape data. Inspect taxonomy.id/type/sourceId/targetId or the complete report inventory.")));
    }

    static String display(String source, int columns, int lines) {
        Objects.requireNonNull(source, "Canonical label required");
        StringBuilder text = new StringBuilder();
        int used = 0, line = 1, lineStart = 0;
        for (int offset = 0; offset < source.length();) {
            int cp = source.codePointAt(offset);
            int weight = glyphUnits(cp);
            if (cp == '\n' || used + weight > columns) {
                if (line == lines) return text + "…";
                int space = text.lastIndexOf(" ");
                if (cp != '\n' && space >= lineStart && space + 1 < text.length()) {
                    // Keep source spaces and prefer complete words; long unbroken
                    // Unicode strings still receive bounded code-point wrapping.
                    text.insert(space + 1, '\n');
                    lineStart = space + 2;
                    used = text.substring(lineStart).codePoints().map(VisioPresentation::glyphUnits).sum();
                } else {
                    text.append('\n'); used = 0; lineStart = text.length();
                }
                line++;
                if (cp == '\n') { offset++; continue; }
            }
            text.appendCodePoint(cp); used += weight; offset += Character.charCount(cp);
        }
        return text.toString();
    }

    private static int glyphUnits(int codePoint) {
        // Conservative at the fixed 10/12pt sizes: wide Latin capitals, m/w,
        // and non-ASCII glyphs receive twice the ordinary lowercase allowance.
        return codePoint > 127 || Character.isUpperCase(codePoint) || codePoint == 'm' || codePoint == 'w' ? 2 : 1;
    }

    private static boolean shortened(String original, String displayed) {
        return !original.replace("\n", "").equals(displayed.replace("\n", ""));
    }

    static void labelLoss(VisioDocument doc, String id, String original, String displayed) {
        if (shortened(original, displayed)) doc.getLosses().add(new VisioLoss("element", id, "displayLabel", "TRUNCATED",
                "Display label shortened; full saved label retained in taxonomy.label on native shape."));
    }

    private static String id(VisioConnect c) { return c.getProperties().get("taxonomy.id").value(); }
    private static String key(VisioConnect c) { return c.getProperties().get("taxonomy.displayKey").value(); }

    private record Box(double x, double y, double width, double height) {
        double left() { return x - width / 2; }
        double bottom() { return y - height / 2; }
        boolean intersects(Box other) { return Math.abs(x - other.x) < (width + other.width) / 2 + .06
                && Math.abs(y - other.y) < (height + other.height) / 2 + .06; }
    }

    /** Fixed-size spatial buckets bound each caption query even on the 10000-node overview. */
    private static final class Occupancy {
        private final Map<Long, List<Box>> buckets = new HashMap<>();
        void add(Box box) { visit(box, (x, y) -> { buckets.computeIfAbsent(bucket(x, y), ignored -> new ArrayList<>()).add(box); return false; }); }
        boolean intersects(Box box) { return visit(box, (x, y) -> buckets.getOrDefault(bucket(x, y), List.of()).stream().anyMatch(box::intersects)); }
        private boolean visit(Box box, CellVisitor visitor) {
            for (int x = (int)Math.floor(box.left() - .1); x <= Math.floor(box.x + box.width / 2 + .1); x++)
                for (int y = (int)Math.floor(box.bottom() - .1); y <= Math.floor(box.y + box.height / 2 + .1); y++)
                    if (visitor.visit(x, y)) return true;
            return false;
        }
        private long bucket(int x, int y) { return ((long)x << 32) ^ (y & 0xffffffffL); }
        private interface CellVisitor { boolean visit(int x, int y); }
    }
}
