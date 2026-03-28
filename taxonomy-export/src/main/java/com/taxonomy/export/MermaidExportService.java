package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts a neutral {@link DiagramModel} into Mermaid flowchart syntax.
 *
 * <p>The generated output is suitable for embedding in GitHub READMEs, Confluence pages,
 * and any Markdown renderer that supports Mermaid diagrams.</p>
 *
 * <h3>Internationalization</h3>
 * <p>All overloads accept an optional {@link MermaidLabels} parameter.
 * When provided, display labels (layer titles, relation edge labels, anchor/hotspot
 * markers) are resolved through localized labels while internal identifiers
 * (node IDs, classDef names, edge source/target references) remain stable.</p>
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

    // ── Standard export ─────────────────────────────────────────────────────

    /**
     * Exports a {@link DiagramModel} as a Mermaid flowchart string using default English labels.
     *
     * @param model the neutral diagram model
     * @return Mermaid-formatted diagram text
     */
    public String export(DiagramModel model) {
        return export(model, MermaidLabels.english());
    }

    /**
     * Exports a {@link DiagramModel} as a Mermaid flowchart string using the given localized labels.
     *
     * @param model  the neutral diagram model
     * @param labels locale-specific display labels
     * @return Mermaid-formatted diagram text
     */
    public String export(DiagramModel model, MermaidLabels labels) {
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
            String displayType = labels.layerLabel(type);

            sb.append("    subgraph ").append(subId).append("[\"").append(escape(displayType)).append("\"]\n");
            for (DiagramNode node : nodes) {
                String nodeId = sanitizeId(node.id());
                String label = node.id() + " " + node.label();
                label += buildSuffix(node, labels);
                sb.append("        ").append(nodeId).append("[\"").append(escape(label)).append("\"]\n");
            }
            sb.append("    end\n");
        }

        // Render edges
        for (DiagramEdge edge : model.edges()) {
            String src = sanitizeId(edge.sourceId());
            String tgt = sanitizeId(edge.targetId());
            String relLabel = labels.relationLabel(edge.relationType());
            sb.append("    ").append(src).append(" -->|").append(escape(relLabel)).append("| ").append(tgt).append('\n');
        }

        appendStyleDefinitions(sb);
        appendClassAssignments(sb, model.nodes(), labels);

        return sb.toString();
    }

    // ── Showcase export ─────────────────────────────────────────────────────

    /**
     * Exports a {@link DiagramModel} optimized for README/showcase rendering
     * using default English labels.
     *
     * @param model the neutral diagram model
     * @return Mermaid-formatted diagram text optimized for showcase/README embedding
     */
    public String exportShowcase(DiagramModel model) {
        return exportShowcase(model, MermaidLabels.english());
    }

    /**
     * Exports a {@link DiagramModel} optimized for README/showcase rendering
     * using the given localized labels.
     *
     * <p>Differences from {@link #export(DiagramModel, MermaidLabels)}:
     * <ul>
     *   <li>Uses top-down (TD) layout for multi-layer readability</li>
     *   <li>Suppresses root-level nodes (two-letter codes like "CP") when
     *       concrete leaf nodes exist in the same layer</li>
     *   <li>Re-routes edges from suppressed root nodes to the highest-relevance
     *       leaf node in the same layer, so cross-layer relations remain visible</li>
     *   <li>Marks high-relevance nodes (≥ 80%) as hotspots with a visual marker</li>
     *   <li>Uses display-friendly relation labels (e.g. "realizes" instead of "REALIZES")</li>
     *   <li>Uses abbreviated subgraph titles with emoji for quick visual scanning</li>
     *   <li>Uses multi-line node labels (name + metadata) for richer cards</li>
     *   <li>Uses stadium shapes for anchor/hotspot nodes for visual emphasis</li>
     *   <li>Limits edges to top N most relevant for cleaner diagrams</li>
     * </ul>
     *
     * @param model  the neutral diagram model
     * @param labels locale-specific display labels
     * @return Mermaid-formatted diagram text optimized for showcase/README embedding
     */
    public String exportShowcase(DiagramModel model, MermaidLabels labels) {
        if (model == null || model.nodes().isEmpty()) {
            return "flowchart TD\n    empty[\"No data\"]\n";
        }

        // ── Partition nodes into root-level and leaf-level per layer ────────
        Map<String, List<DiagramNode>> leafByType = new LinkedHashMap<>();
        Map<String, List<DiagramNode>> rootByType = new LinkedHashMap<>();
        for (DiagramNode node : model.nodes()) {
            if (node.id().contains("-")) {
                leafByType.computeIfAbsent(node.type(), k -> new ArrayList<>()).add(node);
            } else {
                rootByType.computeIfAbsent(node.type(), k -> new ArrayList<>()).add(node);
            }
        }

        // ── Build showcase node list: prefer leaf nodes, keep root only if no leaves ──
        List<DiagramNode> showcaseNodes = new ArrayList<>();
        Map<String, String> rootToLeafReroute = new LinkedHashMap<>();
        Set<String> allTypes = new LinkedHashSet<>();
        model.nodes().stream()
                .sorted(Comparator.comparingInt(DiagramNode::layer))
                .forEach(n -> allTypes.add(n.type()));

        for (String type : allTypes) {
            List<DiagramNode> leaves = leafByType.getOrDefault(type, List.of());
            List<DiagramNode> roots = rootByType.getOrDefault(type, List.of());
            if (!leaves.isEmpty()) {
                showcaseNodes.addAll(leaves);
                DiagramNode bestLeaf = leaves.stream()
                        .max(Comparator.comparingDouble(DiagramNode::relevance))
                        .orElse(leaves.get(0));
                for (DiagramNode root : roots) {
                    rootToLeafReroute.put(root.id(), bestLeaf.id());
                }
            } else {
                showcaseNodes.addAll(roots);
            }
        }

        Set<String> showcaseIds = showcaseNodes.stream()
                .map(DiagramNode::id).collect(Collectors.toCollection(LinkedHashSet::new));

        // ── Re-route, filter and limit edges ────────────────────────────────
        List<DiagramEdge> showcaseEdges = new ArrayList<>();
        Set<String> edgeSignatures = new LinkedHashSet<>();
        for (DiagramEdge edge : model.edges()) {
            String src = rootToLeafReroute.getOrDefault(edge.sourceId(), edge.sourceId());
            String tgt = rootToLeafReroute.getOrDefault(edge.targetId(), edge.targetId());
            if (!showcaseIds.contains(src) || !showcaseIds.contains(tgt)) continue;
            if (src.equals(tgt)) continue;
            String sig = src + "->" + tgt + ":" + edge.relationType();
            if (edgeSignatures.add(sig)) {
                showcaseEdges.add(new DiagramEdge(edge.id(), src, tgt,
                        edge.relationType(), edge.relevance()));
            }
        }

        // Limit edges to top N — prefer impact edges over trace edges, then by relevance
        if (showcaseEdges.size() > MermaidLabels.SHOWCASE_MAX_EDGES) {
            showcaseEdges.sort(Comparator
                    .comparing((DiagramEdge e) -> "impact".equals(e.relationCategory()) ? 0 : 1)
                    .thenComparing(Comparator.comparingDouble(DiagramEdge::relevance).reversed()));
            showcaseEdges = new ArrayList<>(showcaseEdges.subList(0, MermaidLabels.SHOWCASE_MAX_EDGES));
        }

        // ── Render ──────────────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");

        Map<String, List<DiagramNode>> grouped = showcaseNodes.stream()
                .collect(Collectors.groupingBy(
                        DiagramNode::type, LinkedHashMap::new, Collectors.toList()));

        List<Map.Entry<String, List<DiagramNode>>> sortedGroups = grouped.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> layerOrder(e.getKey())))
                .toList();

        for (Map.Entry<String, List<DiagramNode>> entry : sortedGroups) {
            String type = entry.getKey();
            List<DiagramNode> nodes = entry.getValue();
            String subId = sanitizeId(type);
            String displayType = labels.showcaseLayerLabel(type);

            sb.append("    subgraph ").append(subId)
              .append("[\"").append(escape(displayType)).append("\"]\n");
            for (DiagramNode node : nodes) {
                String nodeId = sanitizeId(node.id());
                boolean isProminent = node.anchor() || node.relevance() >= MermaidLabels.HOTSPOT_THRESHOLD;
                String nameLine = truncateLabel(node.label(), MermaidLabels.SHOWCASE_MAX_LABEL_LENGTH);
                String metaLine = buildShowcaseMetaLine(node, labels);
                String label = nameLine + "<br/>" + metaLine;

                // Use stadium shape (["..."]) for prominent nodes, regular ["..."] for others
                if (isProminent) {
                    sb.append("        ").append(nodeId)
                      .append("([\"").append(escape(label)).append("\"])\n");
                } else {
                    sb.append("        ").append(nodeId)
                      .append("[\"").append(escape(label)).append("\"]\n");
                }
            }
            sb.append("    end\n");
        }

        for (DiagramEdge edge : showcaseEdges) {
            String src = sanitizeId(edge.sourceId());
            String tgt = sanitizeId(edge.targetId());
            String relLabel = labels.relationLabel(edge.relationType());
            sb.append("    ").append(src).append(" -->|")
              .append(escape(relLabel)).append("| ").append(tgt).append('\n');
        }

        appendStyleDefinitions(sb);
        appendClassAssignments(sb, showcaseNodes, labels);

        return sb.toString();
    }

    // ── Shared helpers ──────────────────────────────────────────────────────

    /**
     * Builds the suffix for a node label: anchor marker, hotspot marker, relevance percentage.
     */
    private String buildSuffix(DiagramNode node, MermaidLabels labels) {
        StringBuilder suffix = new StringBuilder();
        if (node.anchor()) {
            suffix.append(' ').append(labels.anchorMarker());
        }
        if (node.relevance() >= MermaidLabels.HOTSPOT_THRESHOLD) {
            suffix.append(' ').append(labels.hotspotMarker());
        }
        double pct = node.relevance() * 100;
        if (pct > 0) {
            suffix.append(" [").append(String.format("%.0f%%", pct)).append(']');
        }
        return suffix.toString();
    }

    /**
     * Builds the second line of a showcase node label (metadata line).
     * Format: "★ ⚠ 85%" or "65%" depending on the node's role.
     */
    private String buildShowcaseMetaLine(DiagramNode node, MermaidLabels labels) {
        StringBuilder meta = new StringBuilder();
        if (node.anchor()) {
            meta.append(labels.anchorMarker());
        }
        if (node.relevance() >= MermaidLabels.HOTSPOT_THRESHOLD) {
            if (!meta.isEmpty()) meta.append(' ');
            meta.append(labels.hotspotMarker());
        }
        double pct = node.relevance() * 100;
        if (pct > 0) {
            if (!meta.isEmpty()) meta.append(' ');
            meta.append(String.format("%.0f%%", pct));
        }
        return meta.toString();
    }

    /**
     * Truncates a label to the given max length, appending "…" if truncated.
     */
    static String truncateLabel(String label, int maxLength) {
        if (label == null) return "";
        if (label.length() <= maxLength) return label;
        return label.substring(0, maxLength - 1) + "\u2026";
    }

    private void appendStyleDefinitions(StringBuilder sb) {
        sb.append("    classDef cap fill:#4A90D9,color:#fff,stroke:#2171B5\n");
        sb.append("    classDef proc fill:#27AE60,color:#fff,stroke:#1E8449\n");
        sb.append("    classDef role fill:#27AE60,color:#fff,stroke:#1E8449\n");
        sb.append("    classDef svc fill:#F39C12,color:#fff,stroke:#D68910\n");
        sb.append("    classDef app fill:#8E44AD,color:#fff,stroke:#6C3483\n");
        sb.append("    classDef info fill:#3498DB,color:#fff,stroke:#2980B9\n");
        sb.append("    classDef comm fill:#E74C3C,color:#fff,stroke:#C0392B\n");
        sb.append("    classDef hotspot fill:#D32F2F,color:#fff,stroke:#B71C1C,stroke-width:3px\n");
    }

    private void appendClassAssignments(StringBuilder sb, List<DiagramNode> nodes,
                                         MermaidLabels labels) {
        for (DiagramNode node : nodes) {
            String cls = LAYER_STYLE.getOrDefault(node.type(), "");
            if (!cls.isEmpty()) {
                sb.append("    class ").append(sanitizeId(node.id())).append(' ').append(cls).append('\n');
            }
            if (node.relevance() >= MermaidLabels.HOTSPOT_THRESHOLD) {
                sb.append("    class ").append(sanitizeId(node.id())).append(" hotspot\n");
            }
        }
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
