package com.taxonomy.export;

import com.taxonomy.diagram.VisioDrawingOptions;
import com.taxonomy.visio.*;

import java.util.*;

/** Bounded presentation policy for native Visio shapes; canonical data stays on the overview. */
final class VisioPresentation {
    private static final MermaidLabels ENGLISH = MermaidLabels.english();
    private static final MermaidLabels GERMAN = MermaidLabels.german();

    private VisioPresentation() { }

    static void apply(VisioDocument document) {
        apply(document, null, null);
    }

    static void apply(VisioDocument document, VisioDecisionTree decisionTree, VisioDrawingOptions options) {
        document.getPages().forEach(VisioContainers::fit);
        VisioPage overview = document.getPages().getFirst();
        Map<String, VisioShape> nodes = new LinkedHashMap<>();
        for (var shape : overview.getShapes()) nodes.put(shape.getId(), shape);
        Map<String, String> relationKeys = new HashMap<>();
        int ordinal = 0;
        for (var connect : overview.getConnects()) relationKeys.put(id(connect), "R" + (++ordinal));
        boolean dense = overview.getConnects().size() > 6 || overview.getShapes().size() > 12;
        boolean german = document.isGerman();
        int unplaced = 0;
        boolean shortenedOverviewType = false;
        for (var page : document.getPages()) {
            // Container fitting can shift each page independently, including impact subsets.
            Map<String, VisioShape> pageNodes = new HashMap<>();
            page.getShapes().forEach(shape -> pageNodes.put(shape.getId(), shape));
            var occupied = new Occupancy();
            var columns = options == null ? null : new VisioConnectionPoints.Columns(page.getShapes());
            for (var shape : page.getShapes()) {
                // A container's interior is intentionally occupied by members;
                // only its title blocks captions. Avoid huge spatial bucket
                // allocations for a container around a large graph.
                if (VisioNativeShape.isContainer(shape)) {
                    double header = Math.min(shape.getHeight(), VisioContainers.HEADER_HEIGHT);
                    occupied.add(new Box(shape.getX(), shape.getY() + shape.getHeight() / 2 - header / 2,
                            Math.min(shape.getWidth(), 20) + .12, header + .12));
                } else occupied.add(new Box(shape.getX(), shape.getY(), shape.getWidth() + .12, shape.getHeight() + .12));
            }
            for (var connect : page.getConnects()) {
                String key = relationKeys.get(id(connect));
                connect.getProperties().put("taxonomy.displayKey", VisioProperty.text(key));
                String fullCaption = key + " " + (german ? relationLabel(connect.getRelationType(), true) : connect.getRelationType())
                        + (connect.getProperties().containsKey("taxonomy.provenanceStatus") ? " | "
                        + statusLabel(connect.getProperties().get("taxonomy.provenanceStatus").value(), german) : "");
                String text = dense ? key : display(fullCaption, 24, 2);
                if (options != null) {
                    fullCaption = relationLabel(connect.getRelationType(), german);
                    text = display(fullCaption, 22, 2);
                }
                boolean shortened = (options != null || !dense) && shortened(fullCaption, text);
                if (shortened && page == overview) {
                    shortenedOverviewType = true;
                    document.getLosses().add(new VisioLoss("relationship", id(connect), "overviewType", "TRUNCATED",
                            options == null ? "Overview type shortened; consult native relationship details and full taxonomy.type."
                                    : "Overview type shortened; full relation type retained in taxonomy.type."));
                }
                double width = dense ? .7 : 2.0, height = dense ? .3 : .5;
                if (options != null) {
                    int longest = text.lines().mapToInt(String::length).max().orElse(1);
                    width = Math.min(2, Math.max(.55, .13 + longest * .09));
                    height = text.contains("\n") ? .46 : .30;
                }
                var from = pageNodes.get(connect.getFromShape()); var to = pageNodes.get(connect.getToShape());
                Box position = columns == null ? null : placeOnRoute(occupied, columns.route(connect, from, to), width, height);
                if (position == null) position = place(occupied, from, to, width, height);
                if (position == null) {
                    // An omitted caption is explicit; its canonical connector is never omitted.
                    text = "";
                    position = new Box((from.getX() + to.getX()) / 2, (from.getY() + to.getY()) / 2, width, height);
                    connect.getProperties().put("taxonomy.captionDisposition", VisioProperty.text(options == null ? "DETAIL_REQUIRED" : "SHAPE_DATA"));
                    if (page == overview) unplaced++;
                } else {
                    occupied.add(position);
                    connect.getProperties().put("taxonomy.captionDisposition", VisioProperty.text(options != null ? "TYPE" : dense ? "COMPACT_KEY" : shortened ? "TYPE_TRUNCATED" : "KEY_AND_TYPE"));
                }
                connect.setTextBox(new VisioTextBox(text, position.x, position.y, width, height));
            }
        }
        if (unplaced > 0) document.getLosses().add(new VisioLoss("document", "", "overviewCaptions", "OMITTED",
                unplaced + (options == null
                        ? " overview captions could not be placed without collision. Native connectors/data remain; consult relationship details."
                        : " overview captions could not be placed without collision. Native connectors and their type remain available in Shape Data.")));
        // Mandatory readable evidence reserves page capacity before optional diagram details.
        boolean appendix = options == null || options.appendix();
        boolean contextOnDrawings = options == null && decisionTree == null;
        if (appendix) {
            VisioEvidencePages.append(document, overview);
            VisioContextPages.append(document, contextOnDrawings);
        }
        if (decisionTree != null) decisionTree.appendTo(document, options);
        if (appendix && (dense || unplaced > 0 || shortenedOverviewType)) {
            addDetails(document, overview, nodes, contextOnDrawings && VisioContextPages.hasContext(document));
        }
        if (contextOnDrawings) VisioContextPages.decorateDiagrams(document);
    }

    /** Start with the actual native path, including the vertical segment of a routed gutter. */
    private static Box placeOnRoute(Occupancy occupied, VisioConnectionPoints.Route route, double width, double height) {
        var start = route.endpoints().source();
        var end = route.endpoints().target();
        double dx = end.x() - start.x(), dy = end.y() - start.y();
        for (double fraction : new double[]{.5, .30, .70, .15, .85}) {
            double x = start.x() + dx * (route.gutterFraction() == null ? fraction : route.gutterFraction());
            var candidate = new Box(x, start.y() + dy * fraction, width, height);
            if (candidate.left() >= .1 && candidate.bottom() >= .1 && !occupied.intersects(candidate)) return candidate;
        }
        return null;
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

    private static void addDetails(VisioDocument document, VisioPage overview, Map<String, VisioShape> nodes,
                                   boolean contextOnDrawings) {
        int shapes = document.getPages().stream().mapToInt(p -> p.getShapes().size() + (p.getLegend() == null ? 0 : 1)).sum();
        int connectors = document.getPages().stream().mapToInt(p -> p.getConnects().size()).sum();
        int covered = 0;
        int contextShapesPerDetail = contextOnDrawings ? 1 : 0;
        int diagramPages = (int) document.getPages().stream().filter(page -> !page.isEvidencePage()).count();
        while (covered < overview.getConnects().size() && diagramPages < VisioPackageValidator.MAX_DIAGRAM_PAGES) {
            List<VisioConnect> batch = new ArrayList<>();
            var endpoints = new TreeSet<String>(Comparator.comparingLong(Long::parseLong));
            for (int next = covered; next < overview.getConnects().size() && batch.size() < 6; next++) {
                var c = overview.getConnects().get(next);
                var candidate = new HashSet<>(endpoints); candidate.add(c.getFromShape()); candidate.add(c.getToShape());
                if (candidate.size() > 6) break;
                endpoints.addAll(candidate); batch.add(c);
            }
            boolean german = document.isGerman();
            boolean compact = contextOnDrawings && batch.size() == 1 && endpoints.size() == 2
                    && batch.getFirst().getRelationType().length() <= 24;
            var excerpt = compact ? VisioEvidencePages.relationExcerpt(
                    Long.toString(1 + Long.parseLong(endpoints.last())), batch.getFirst(), german) : null;
            int addedShapes = endpoints.size() + contextShapesPerDetail + (excerpt == null ? 0 : 1);
            if (shapes + addedShapes > 20_000 || connectors + batch.size() > 60_000) break;
            double compactRow = 1.25 + (excerpt == null ? 0 : excerpt.getHeight() + .35);
            String start = key(batch.getFirst());
            String range = batch.size() == 1 ? start : start + "–" + key(batch.getLast());
            VisioPage page = new VisioPage(Integer.toString(document.getPages().size()),
                    (german ? "Beziehungen " : "Relationships ") + range);
            page.setRelationshipDetail(true);
            int row = 0;
            for (String endpoint : endpoints) {
                var source = nodes.get(endpoint);
                // Six unique endpoints at generous fixed positions; captions occupy a separate rail.
                double x = compact ? (endpoint.equals(batch.getFirst().getFromShape()) ? 2.2 : 8.0)
                        : row % 2 == 0 ? 2.2 : 6.2;
                double y = compact ? compactRow : 9.4 - (row / 2) * 3.3;
                String label = source.getProperties().get("taxonomy.label").value();
                String text = source.getProperties().get("taxonomy.displayKey").value() + " | "
                        + display(source.getProperties().get("taxonomy.id").value(), 26, 1) + "\n" + display(label, 32, 3)
                        + "\n" + nodeFooter(source.getProperties().get("taxonomy.depth").value(),
                                Optional.ofNullable(source.getProperties().get("taxonomy.responsibilityName"))
                                        .map(VisioProperty::value).orElse(null), 32, german);
                var shape = new VisioShape(source.getId(), text, x, y, 3.2, 1.4, source.getType(), source.isAnchor());
                shape.getProperties().putAll(source.getProperties()); page.getShapes().add(shape); row++;
            }
            row = 0;
            for (var source : batch) {
                var connect = new VisioConnect(source.getFromShape(), source.getToShape(), source.getRelationType());
                connect.getProperties().putAll(source.getProperties());
                String direction = nodes.get(source.getFromShape()).getProperties().get("taxonomy.displayKey").value()
                        + " → " + nodes.get(source.getToShape()).getProperties().get("taxonomy.displayKey").value();
                String sourceType = german ? relationLabel(source.getRelationType(), true) : source.getRelationType();
                String type = display(sourceType, 52, 4);
                String text = key(source) + "  " + direction + "\n" + type
                        + (source.getProperties().containsKey("taxonomy.provenanceStatus") ? "\n"
                        + statusLabel(source.getProperties().get("taxonomy.provenanceStatus").value(), german) : "");
                connect.setTextBox(compact ? new VisioTextBox(text, 5.1, compactRow + .65, 2.5, .85)
                        : new VisioTextBox(text, 11.3, 10 - row * 1.65, 5.2, 1.25));
                connect.getProperties().put("taxonomy.captionDisposition", VisioProperty.text(shortened(sourceType, type) ? "TYPE_TRUNCATED" : "READABLE_DETAIL"));
                page.getConnects().add(connect); row++;
            }
            if (excerpt != null) page.getShapes().add(excerpt);
            shapes += addedShapes; connectors += batch.size(); covered += batch.size();
            VisioContainers.fit(page);
            document.getPages().add(page); diagramPages++;
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
                        : "Optional detail " + capacity + " exhausted (32 diagram pages / 128 additional evidence pages / 20000 nodes / 60000 connectors / 32 MiB uncompressed package bytes). Remaining keys R" + (covered + 1)
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

    static String drawingNodeCaption(String code, String score, String title, String owner, String reason,
                                     VisioDrawingOptions options) {
        String caption = code + " · " + score + "\n" + title + drawingOwnerCaption(owner, options);
        if (options.showsShortReasons() && reason != null && !reason.isBlank()) {
            caption += "\n" + display(reason.strip().replaceAll("\\s+", " "), 40, 2);
        }
        return caption;
    }

    static String drawingOwnerCaption(String owner, VisioDrawingOptions options) {
        if (owner == null || owner.isBlank()) return "";
        int columns = options.mode() == VisioDrawingOptions.Mode.WALL ? 40 : 46;
        return "\n" + display((options.german() ? "Zuständig: " : "Owner: ") + owner, columns, 1);
    }

    static double screenCardHeight(boolean assigned) {
        // Leave room for the owner line when the application icon narrows the text area.
        return assigned ? 1.42 : 1.05;
    }

    static String nodeFooter(String depth, String unitName, int columns, boolean german) {
        return unitName == null ? (german ? "Tiefe " : "Depth ") + depth : display(unitLabel(unitName, german), columns, 1);
    }

    static String unitLabel(String unitName, boolean german) {
        return (german ? "Einheit: " : "Unit: ") + unitName;
    }

    static String relationLabel(String type, boolean german) {
        return switch (type) {
            case "CONTAINS" -> german ? "enthält" : "contains";
            case "REQUIRES" -> german ? "benötigt" : "requires";
            default -> (german ? GERMAN : ENGLISH).relationLabel(type);
        };
    }

    static String statusLabel(String status, boolean german) {
        if (!german) return status;
        return switch (status) {
            case "AI-proposed" -> "KI-Vorschlag";
            case "Catalogue seed" -> "Katalogbeziehung";
            case "Propagated trace" -> "Fortgeführte Herleitung";
            case "Derived impact" -> "Abgeleitete Auswirkung";
            case "Origin unrecorded" -> "Herkunft nicht aufgezeichnet";
            default -> status;
        };
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
            int minX = cell(box.left() - .1), maxX = cell(box.x + box.width / 2 + .1);
            int minY = cell(box.bottom() - .1), maxY = cell(box.y + box.height / 2 + .1);
            long columns = (long) maxX - minX + 1;
            long rows = (long) maxY - minY + 1;
            // Finite int coordinates can still describe billions of cells. Check
            // before any callback/allocation and divide to avoid area overflow.
            if (columns <= 0 || rows <= 0 || columns > 4096 || rows > 4096 / columns)
                throw new IllegalArgumentException("Visio geometry exceeds the 4096-cell spatial query budget");
            // Widen the counters, not the bounds: incrementing Integer.MAX_VALUE
            // must terminate rather than wrap. Reject invalid geometry before use.
            for (long x = minX; x <= maxX; x++)
                for (long y = minY; y <= maxY; y++)
                    if (visitor.visit((int) x, (int) y)) return true;
            return false;
        }
        private int cell(double coordinate) {
            double floored = Math.floor(coordinate);
            if (!Double.isFinite(floored) || floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Visio coordinates must be finite and within the integer grid");
            }
            return (int) floored;
        }
        private long bucket(int x, int y) { return ((long)x << 32) ^ (y & 0xffffffffL); }
        private interface CellVisitor { boolean visit(int x, int y); }
    }
}
