package com.taxonomy.reporting.render.document;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counts the captured report selection, never a top-N ranking or repeated figure detail panels. */
public final class ReportInventory {
    private ReportInventory() { }

    public static List<String> diagram(DiagramModel graph, boolean german) {
        var nodes = new LinkedHashMap<String, com.taxonomy.diagram.DiagramNode>();
        graph.nodes().forEach(node -> nodes.putIfAbsent(node.id(), node));
        long containers = nodes.values().stream().filter(node -> node.container()).count();
        long relations = graph.edges().stream().map(edge -> edge.id()).distinct().count();
        Map<String, Integer> types = new TreeMap<>();
        nodes.values().stream().filter(node -> !node.container()).forEach(node -> types.merge(
                node.type() == null ? (german ? "Nicht zugeordnet" : "Unclassified") : node.type(), 1, Integer::sum));
        var lines = new ArrayList<String>();
        lines.add((german ? "Gespeicherte Architektur: " : "Saved architecture: ") + (nodes.size() - containers)
                + (german ? " Elemente · Navigationsknoten: " : " elements · Navigation nodes: ") + containers
                + (german ? " · Beziehungen: " : " · Relationships: ") + relations);
        types.forEach((type, count) -> lines.add(type + ": " + count));
        lines.add(german ? "Fachliche Bestätigung ist in diesem Diagrammmodell nicht aufgezeichnet. KI-Prüfung ist keine Freigabe."
                : "Human confirmation is not recorded in this diagram model. AI verification is not approval.");
        return List.copyOf(lines);
    }

    public static List<String> tree(DecisionTreeOverview tree, boolean german) {
        var seen = new java.util.HashSet<String>();
        Map<String, Integer> roots = new LinkedHashMap<>();
        String root = german ? "Nicht zugeordnet" : "Unclassified";
        for (var row : tree.rows()) {
            if (row.depth() == 0) root = row.code();
            if (seen.add(row.code())) roots.merge(root, 1, Integer::sum);
        }
        var lines = new ArrayList<String>();
        lines.add((german ? "Knoten der Berichtsauswahl (einschließlich Navigation): "
                : "Report selection nodes (including navigation): ") + seen.size());
        roots.forEach((code, count) -> lines.add(code + ": " + count));
        return List.copyOf(lines);
    }
}
