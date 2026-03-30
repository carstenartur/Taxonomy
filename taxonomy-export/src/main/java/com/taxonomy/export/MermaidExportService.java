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
 * <p><strong>Selection vs. Rendering:</strong> Node/edge selection and curation are
 * handled by {@link DiagramSelectionPolicy} implementations <em>before</em> this
 * renderer is called.  This service is responsible only for rendering the curated
 * result.  The {@link #exportShowcase(DiagramModel, MermaidLabels)} method
 * internally applies a {@link LeafOnlyDiagramSelectionPolicy} for backward
 * compatibility.</p>
 *
 * <h3>Containment</h3>
 * <p>Nodes flagged with {@link DiagramNode#container()}{@code == true} are rendered
 * as nested Mermaid sub-graphs that visually contain their child nodes (identified
 * by {@link DiagramNode#parentId()}).  This produces the "container with children
 * inside" layout required for clustered impact diagrams.</p>
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
     * <p>Container nodes ({@link DiagramNode#container()}) are rendered as nested
     * sub-graphs whose children appear visually inside them.</p>
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

        renderLayerSubgraphs(sb, model.nodes(), labels, false);

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
     * <p>Selection logic (root/scaffolding suppression, edge re-routing) is
     * delegated to a {@link LeafOnlyDiagramSelectionPolicy}.  This method
     * handles only rendering concerns (TD layout, stadium shapes, multi-line
     * labels, abbreviated subgraph titles, etc.).</p>
     *
     * @param model  the neutral diagram model
     * @param labels locale-specific display labels
     * @return Mermaid-formatted diagram text optimized for showcase/README embedding
     */
    public String exportShowcase(DiagramModel model, MermaidLabels labels) {
        if (model == null || model.nodes().isEmpty()) {
            return "flowchart TD\n    empty[\"No data\"]\n";
        }

        // Delegate selection to the leaf-only policy
        DiagramModel curated = new LeafOnlyDiagramSelectionPolicy().apply(model);

        List<DiagramNode> showcaseNodes = curated.nodes();
        List<DiagramEdge> showcaseEdges = curated.edges();

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

    // ── Layer subgraph rendering with containment support ────────────────────

    /**
     * Renders nodes grouped by layer as Mermaid sub-graphs, with support for
     * nested containment when nodes are flagged as containers.
     */
    private void renderLayerSubgraphs(StringBuilder sb, List<DiagramNode> allNodes,
                                       MermaidLabels labels, boolean showcase) {
        // Index: parentId → children
        Map<String, List<DiagramNode>> childrenOf = new LinkedHashMap<>();
        Set<String> childIds = new LinkedHashSet<>();
        for (DiagramNode n : allNodes) {
            if (n.parentId() != null) {
                // Only link to parent if the parent is actually a container in the model
                boolean parentIsContainer = allNodes.stream()
                        .anyMatch(p -> p.id().equals(n.parentId()) && p.container());
                if (parentIsContainer) {
                    childrenOf.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n);
                    childIds.add(n.id());
                }
            }
        }

        // Group top-level nodes (non-children) by type
        Map<String, List<DiagramNode>> grouped = allNodes.stream()
                .filter(n -> !childIds.contains(n.id()))
                .collect(Collectors.groupingBy(
                        DiagramNode::type, LinkedHashMap::new, Collectors.toList()));

        List<Map.Entry<String, List<DiagramNode>>> sortedGroups = grouped.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> layerOrder(e.getKey())))
                .toList();

        for (Map.Entry<String, List<DiagramNode>> entry : sortedGroups) {
            String type = entry.getKey();
            List<DiagramNode> nodes = entry.getValue();
            String subId = sanitizeId(type);
            String displayType = showcase ? labels.showcaseLayerLabel(type) : labels.layerLabel(type);

            sb.append("    subgraph ").append(subId)
              .append("[\"").append(escape(displayType)).append("\"]\n");

            for (DiagramNode node : nodes) {
                if (node.container()) {
                    // Render as nested sub-graph containing its children
                    renderContainerSubgraph(sb, node, childrenOf, labels, showcase);
                } else {
                    renderNode(sb, node, labels, showcase, "        ");
                }
            }

            sb.append("    end\n");
        }
    }

    /**
     * Renders a container node as a nested Mermaid sub-graph with its children inside.
     */
    private void renderContainerSubgraph(StringBuilder sb, DiagramNode container,
                                          Map<String, List<DiagramNode>> childrenOf,
                                          MermaidLabels labels, boolean showcase) {
        String containerId = sanitizeId(container.id());
        sb.append("        subgraph ").append(containerId)
          .append("[\"").append(escape(container.label())).append("\"]\n");

        List<DiagramNode> children = childrenOf.getOrDefault(container.id(), List.of());
        for (DiagramNode child : children) {
            renderNode(sb, child, labels, showcase, "            ");
        }

        sb.append("        end\n");
    }

    /**
     * Renders a single non-container node with the appropriate shape and label.
     */
    private void renderNode(StringBuilder sb, DiagramNode node, MermaidLabels labels,
                             boolean showcase, String indent) {
        String nodeId = sanitizeId(node.id());
        if (showcase) {
            boolean isProminent = node.anchor() || node.relevance() >= MermaidLabels.HOTSPOT_THRESHOLD;
            String nameLine = truncateLabel(node.label(), MermaidLabels.SHOWCASE_MAX_LABEL_LENGTH);
            String metaLine = buildShowcaseMetaLine(node, labels);
            String label = nameLine + "<br/>" + metaLine;
            if (isProminent) {
                sb.append(indent).append(nodeId)
                  .append("([\"").append(escape(label)).append("\"])\n");
            } else {
                sb.append(indent).append(nodeId)
                  .append("[\"").append(escape(label)).append("\"]\n");
            }
        } else {
            String label = node.id() + " " + node.label();
            label += buildSuffix(node, labels);
            sb.append(indent).append(nodeId).append("[\"").append(escape(label)).append("\"]\n");
        }
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
            if (node.container()) continue; // containers are sub-graphs, not styled nodes
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

    /**
     * Returns {@code true} if the node ID matches the taxonomy scaffolding pattern
     * {@code XX-1000} (two uppercase letters, dash, 1000). These first-level
     * container nodes are semantically redundant with their root and should be
     * suppressed in showcase/impact views when concrete leaf nodes are available.
     */
    static boolean isScaffoldingId(String id) {
        if (id == null || id.length() != 7) {
            return false;
        }
        // Expect pattern [A-Z]{2}-1000
        if (id.charAt(2) != '-') {
            return false;
        }
        char c0 = id.charAt(0);
        char c1 = id.charAt(1);
        if (c0 < 'A' || c0 > 'Z' || c1 < 'A' || c1 > 'Z') {
            return false;
        }
        return id.endsWith("-1000");
    }
}
