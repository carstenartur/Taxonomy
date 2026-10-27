package com.taxonomy.visio;

import java.util.*;

/** Native container membership follows only explicitly visual ancestors present on this page. */
public final class VisioContainers {
    public static final double HEADER_HEIGHT = 1.0;
    private VisioContainers() { }

    public record Membership(Map<String, String> owners, Map<String, List<String>> members) {
        public String formula(String id) {
            List<String> relationships = new ArrayList<>();
            var children = members.getOrDefault(id, List.of());
            if (!children.isEmpty()) relationships.add("DEPENDSON(1," + children.stream()
                    .map(child -> "Sheet." + child + "!SheetRef()")
                    .collect(java.util.stream.Collectors.joining(",")) + ")");
            if (owners.containsKey(id)) relationships.add("DEPENDSON(4,Sheet." + owners.get(id) + "!SheetRef())");
            return relationships.isEmpty() ? null : "SUM(" + String.join(",", relationships) + ")";
        }
    }

    public static Membership membership(VisioPage page) {
        Map<String, VisioShape> identities = new HashMap<>(), shapes = new HashMap<>();
        for (VisioShape shape : page.getShapes()) {
            if (VisioNativeShape.isPresentationOnly(shape)) continue;
            shapes.put(shape.getId(), shape);
            var identity = shape.getProperties().get("taxonomy.id");
            if (identity != null) identities.put(identity.value(), shape);
        }
        Map<String, String> parents = new HashMap<>();
        for (VisioShape shape : shapes.values()) {
            var parent = shape.getProperties().get("taxonomy.parentId");
            VisioShape found = parent == null ? null : identities.get(parent.value());
            if (found != null) parents.put(shape.getId(), found.getId());
        }
        rejectCycles(parents);
        Map<String, String> owners = new HashMap<>();
        Set<String> known = new HashSet<>();
        for (VisioShape shape : shapes.values()) {
            List<String> path = new ArrayList<>();
            String current = shape.getId(), owner = null;
            while (current != null && !known.contains(current)) {
                path.add(current);
                String parent = parents.get(current);
                if (parent != null && VisioNativeShape.isContainer(shapes.get(parent))) {
                    owner = parent;
                    break;
                }
                current = parent;
            }
            if (owner == null && current != null && known.contains(current)) owner = owners.get(current);
            for (String id : path) {
                known.add(id);
                if (owner != null) owners.put(id, owner);
            }
        }
        Map<String, List<String>> members = new HashMap<>();
        owners.forEach((child, owner) -> members.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(child));
        members.values().forEach(list -> list.sort(Comparator.comparingLong(Long::parseLong)));
        return new Membership(owners, members);
    }

    /** Run before caption placement, so native container bounds and headers participate honestly. */
    public static void fit(VisioPage page) {
        Membership membership = membership(page);
        if (membership.members().isEmpty()) return;
        Map<String, VisioShape> shapes = new HashMap<>();
        page.getShapes().forEach(shape -> shapes.put(shape.getId(), shape));
        Map<String, Integer> remaining = new HashMap<>();
        var ready = new ArrayDeque<String>();
        for (String container : membership.members().keySet()) {
            int nested = (int) membership.members().get(container).stream().filter(membership.members()::containsKey).count();
            remaining.put(container, nested);
            if (nested == 0) ready.add(container);
        }
        while (!ready.isEmpty()) {
            String id = ready.remove();
            var children = membership.members().get(id).stream().map(shapes::get).toList();
            double left = children.stream().mapToDouble(s -> s.getX() - s.getWidth() / 2).min().orElseThrow() - .25;
            double right = children.stream().mapToDouble(s -> s.getX() + s.getWidth() / 2).max().orElseThrow() + .25;
            double bottom = children.stream().mapToDouble(s -> s.getY() - s.getHeight() / 2).min().orElseThrow() - .25;
            double top = children.stream().mapToDouble(s -> s.getY() + s.getHeight() / 2).max().orElseThrow() + HEADER_HEIGHT + .25;
            VisioShape original = shapes.get(id);
            shapes.put(id, copy(original, original.getText(), (left + right) / 2, (top + bottom) / 2, right - left, top - bottom));
            String owner = membership.owners().get(id);
            if (owner != null && remaining.computeIfPresent(owner, (ignored, count) -> count - 1) == 0) ready.add(owner);
        }
        // Deep nesting may extend past the page origin. Shift the entire page,
        // never clip a member or emit negative boundary endpoints.
        double shiftX = Math.max(0, .1 - shapes.values().stream().mapToDouble(s -> s.getX() - s.getWidth() / 2).min().orElse(.1));
        double shiftY = Math.max(0, .1 - shapes.values().stream().mapToDouble(s -> s.getY() - s.getHeight() / 2).min().orElse(.1));
        page.getShapes().replaceAll(original -> {
            VisioShape shape = shapes.get(original.getId());
            return shiftX == 0 && shiftY == 0 ? shape : copy(shape, shape.getText(), shape.getX() + shiftX,
                    shape.getY() + shiftY, shape.getWidth(), shape.getHeight());
        });
        if (page.getLegend() != null) {
            var old = page.getLegend();
            double top = page.getShapes().stream().mapToDouble(s -> s.getY() + s.getHeight() / 2).max().orElse(0);
            page.setLegend(new VisioTextBox(old.text(), old.x() + shiftX, top + 1.3, old.width(), old.height()));
        }
    }

    private static VisioShape copy(VisioShape source, String text, double x, double y, double width, double height) {
        var result = new VisioShape(source.getId(), text, x, y, width, height, source.getType(), source.isAnchor());
        result.getProperties().putAll(source.getProperties());
        return result;
    }

    private static void rejectCycles(Map<String, String> parents) {
        Set<String> done = new HashSet<>();
        for (String start : parents.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && !done.contains(current)) {
                if (!path.add(current)) throw new IllegalArgumentException("Visio parent hierarchy contains a cycle");
                current = parents.get(current);
            }
            done.addAll(path);
        }
    }
}
