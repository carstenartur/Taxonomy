package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts a neutral {@link DiagramModel} into Mermaid flowchart syntax.
 *
 * <p>The generated output is suitable for embedding in GitHub READMEs, Confluence pages,
 * and any Markdown renderer that supports Mermaid diagrams.</p>
 */
public class MermaidExportService {

    private static final Map<String, String> LAYER_STYLE = Map.of(
            "Capabilities", "cap",
            "Business Processes", "proc",
            "Business Roles", "role",
            "Services", "svc",
            "COI Services", "svc",
            "Core Services", "svc",
            "Applications", "app",
            "User Applications", "app",
            "Information Products", "info",
            "Communications Services", "comm"
    );

    /**
     * Exports a {@link DiagramModel} as a Mermaid flowchart string.
     *
     * @param model the neutral diagram model
     * @return Mermaid-formatted diagram text
     */
    public String export(DiagramModel model) {
        if (model == null || model.nodes().isEmpty()) {
            return "flowchart LR\n    empty[\"No data\"]\n";
        }

        StringBuilder sb = new StringBuilder();
        String direction = model.layout() != null ? model.layout().direction() : "LR";
        sb.append("flowchart ").append(direction).append('\n');

        // Group nodes by layer for subgraph rendering
        Map<String, List<DiagramNode>> grouped = model.nodes().stream()
                .collect(Collectors.groupingBy(
                        DiagramNode::type,
                        LinkedHashMap::new,
                        Collectors.toList()));

        // Sort groups by layer order
        List<Map.Entry<String, List<DiagramNode>>> sortedGroups = grouped.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> layerOrder(e.getKey())))
                .toList();

        // Render subgraphs
        for (Map.Entry<String, List<DiagramNode>> entry : sortedGroups) {
            String type = entry.getKey();
            List<DiagramNode> nodes = entry.getValue();
            String subId = sanitizeId(type);

            sb.append("    subgraph ").append(subId).append("[\"").append(escape(type)).append("\"]\n");
            for (DiagramNode node : nodes) {
                String nodeId = sanitizeId(node.id());
                String label = node.id() + " " + node.label();
                if (node.anchor()) {
                    label += " ★";
                }
                double pct = node.relevance() * 100;
                if (pct > 0) {
                    label += " [" + String.format("%.0f%%", pct) + "]";
                }
                sb.append("        ").append(nodeId).append("[\"").append(escape(label)).append("\"]\n");
            }
            sb.append("    end\n");
        }

        // Render edges
        for (DiagramEdge edge : model.edges()) {
            String src = sanitizeId(edge.sourceId());
            String tgt = sanitizeId(edge.targetId());
            sb.append("    ").append(src).append(" -->|").append(escape(edge.relationType())).append("| ").append(tgt).append('\n');
        }

        // Class definitions for styling
        sb.append("    classDef cap fill:#4A90D9,color:#fff,stroke:#2171B5\n");
        sb.append("    classDef proc fill:#27AE60,color:#fff,stroke:#1E8449\n");
        sb.append("    classDef role fill:#27AE60,color:#fff,stroke:#1E8449\n");
        sb.append("    classDef svc fill:#F39C12,color:#fff,stroke:#D68910\n");
        sb.append("    classDef app fill:#8E44AD,color:#fff,stroke:#6C3483\n");
        sb.append("    classDef info fill:#3498DB,color:#fff,stroke:#2980B9\n");
        sb.append("    classDef comm fill:#E74C3C,color:#fff,stroke:#C0392B\n");

        // Apply classes to nodes
        for (DiagramNode node : model.nodes()) {
            String cls = LAYER_STYLE.getOrDefault(node.type(), "");
            if (!cls.isEmpty()) {
                sb.append("    class ").append(sanitizeId(node.id())).append(" ").append(cls).append('\n');
            }
        }

        return sb.toString();
    }

    private int layerOrder(String type) {
        return switch (type) {
            case "Capabilities" -> 1;
            case "Business Processes", "Business Roles" -> 2;
            case "Services", "COI Services", "Core Services" -> 3;
            case "Applications", "User Applications" -> 4;
            case "Information Products" -> 5;
            case "Communications Services" -> 6;
            default -> 99;
        };
    }

    /**
     * Sanitize a node/subgraph ID for Mermaid (alphanumeric + underscores only).
     */
    static String sanitizeId(String id) {
        if (id == null) return "unknown";
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Escape special characters for Mermaid labels.
     */
    static String escape(String text) {
        if (text == null) return "";
        return text.replace("\"", "&quot;").replace("#", "&num;");
    }
}
