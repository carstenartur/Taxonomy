package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Presentation only: colors and counts never imply model acceptance or change graph evidence. */
public final class DiagramPresentation {
    private DiagramPresentation() { }

    public static String fill(String type) {
        if (type == null) return "#F1F5F9";
        return switch (type) {
            case "Capabilities", "CP" -> "#DBEAFE";
            case "Business Processes", "Business Roles", "BP", "BR" -> "#DCFCE7";
            case "Core Services", "COI Services", "Services", "CR", "CI" -> "#FEF3C7";
            case "User Applications", "Applications", "UA" -> "#F3E8FF";
            case "Information Products", "IP" -> "#FFE4E6";
            case "Communications Services", "CO" -> "#CFFAFE";
            default -> "#F1F5F9";
        };
    }

    /** Scores are fractions of one; a positive value must never be presented as zero. */
    public static String percent(double relevance) {
        if (!Double.isFinite(relevance) || relevance < 0 || relevance > 1) return "n/a";
        BigDecimal value = BigDecimal.valueOf(relevance).movePointRight(2);
        if (value.signum() > 0 && value.compareTo(new BigDecimal("0.000001")) < 0)
            return "<0.000001%";
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    /** Counts refer only to this exported scene, not the full catalogue or duplicated detail pages. */
    public static List<String> inventorySummary(DiagramScene scene) {
        var nodes = new LinkedHashMap<String, com.taxonomy.diagram.DiagramSceneNode>();
        scene.nodes().forEach(node -> nodes.putIfAbsent(node.id(), node));
        long containers = nodes.values().stream().filter(node -> node.container()).count();
        long relationships = scene.edges().stream().map(edge -> edge.id()).distinct().count();
        Map<String, Integer> taxonomies = new TreeMap<>();
        nodes.values().stream().filter(node -> !node.container()).forEach(node ->
                taxonomies.merge(node.type() == null ? "Unclassified" : node.type(), 1, Integer::sum));
        var lines = new ArrayList<String>();
        lines.add("Exported view | Elements: " + (nodes.size() - containers) + " | Containers: " + containers
                + " | Relationships: " + relationships);
        taxonomies.forEach((type, count) -> lines.add(type + ": " + count));
        lines.add("Colors identify taxonomy families; a strong border marks a selection anchor, not approval.");
        lines.add("Human confirmation is not recorded in this diagram model. AI verification is not human approval.");
        lines.add("Depth is the recorded taxonomy depth, not the horizontal layout layer.");
        return List.copyOf(lines);
    }
}
