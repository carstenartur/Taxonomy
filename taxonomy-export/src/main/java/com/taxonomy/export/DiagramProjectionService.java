package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Projects a {@link RequirementArchitectureView} into a neutral {@link DiagramModel}
 * suitable for rendering to various diagram formats (Visio, Mermaid, GraphViz, etc.).
 */
public class DiagramProjectionService {

    private static final Logger log = LoggerFactory.getLogger(DiagramProjectionService.class);

    static final int MAX_NODES = 25;
    static final int MAX_EDGES = 40;
    static final double MIN_RELEVANCE = 0.35;

    /**
     * Maps two-letter taxonomy root codes to human-readable layer names.
     * The architecture view pipeline stores root codes (e.g. "BP") in the
     * {@code taxonomySheet} field; this mapping resolves them to the full
     * English names expected by downstream exporters (Mermaid, Visio, etc.).
     */
    private static final Map<String, String> ROOT_CODE_TO_LAYER = Map.ofEntries(
            Map.entry("CP", "Capabilities"),
            Map.entry("BP", "Business Processes"),
            Map.entry("BR", "Business Roles"),
            Map.entry("CR", "Core Services"),
            Map.entry("CI", "COI Services"),
            Map.entry("CO", "Communications Services"),
            Map.entry("UA", "User Applications"),
            Map.entry("IP", "Information Products")
    );

    private static final Map<String, Integer> LAYER_MAP = Map.ofEntries(
            Map.entry("Capabilities", 1),
            Map.entry("Business Processes", 2),
            Map.entry("Business Roles", 2),
            Map.entry("Services", 3),
            Map.entry("COI Services", 3),
            Map.entry("Core Services", 3),
            Map.entry("Applications", 4),
            Map.entry("User Applications", 4),
            Map.entry("Information Products", 5),
            Map.entry("Communications Services", 6)
    );

    /**
     * Converts a {@link RequirementArchitectureView} to a {@link DiagramModel}.
     *
     * @param view  the architecture view produced by analysis
     * @param title diagram title (typically the business text summary)
     * @return a neutral diagram model
     */
    public DiagramModel project(RequirementArchitectureView view, String title) {
        if (view == null) {
            return new DiagramModel(title, List.of(), List.of(),
                    new DiagramLayout("LR", true));
        }

        List<DiagramNode> nodes = new ArrayList<>();
        for (RequirementElementView el : view.getIncludedElements()) {
            // Include if: sufficient relevance, is anchor, or was selected for impact
            if (el.getRelevance() < MIN_RELEVANCE && !el.isAnchor() && !el.isSelectedForImpact()) {
                continue;
            }
            String rawType = el.getTaxonomySheet() != null ? el.getTaxonomySheet() : "Unknown";
            String type = ROOT_CODE_TO_LAYER.getOrDefault(rawType, rawType);
            int layer = LAYER_MAP.getOrDefault(type, 0);
            nodes.add(new DiagramNode(
                    el.getNodeCode(),
                    el.getTitle() != null ? el.getTitle() : el.getNodeCode(),
                    type,
                    el.getRelevance(),
                    el.isAnchor(),
                    layer));
        }

        // Sort: impact-selected nodes first, then anchors, then by relevance descending.
        // This ensures the most semantically valuable nodes survive the MAX_NODES limit.
        nodes.sort(Comparator
                .comparing((DiagramNode n) -> n.anchor() ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(DiagramNode::relevance).reversed()));
        if (nodes.size() > MAX_NODES) {
            nodes = new ArrayList<>(nodes.subList(0, MAX_NODES));
        }

        // Build a set of included node IDs for filtering edges
        var nodeIds = nodes.stream().map(DiagramNode::id).collect(java.util.stream.Collectors.toSet());

        List<DiagramEdge> edges = new ArrayList<>();
        int edgeIdx = 0;
        for (RequirementRelationshipView rel : view.getIncludedRelationships()) {
            if (!nodeIds.contains(rel.getSourceCode()) || !nodeIds.contains(rel.getTargetCode())) {
                continue;
            }
            edges.add(new DiagramEdge(
                    "e" + (++edgeIdx),
                    rel.getSourceCode(),
                    rel.getTargetCode(),
                    rel.getRelationType(),
                    rel.getPropagatedRelevance(),
                    rel.getRelationCategory()));
            if (edges.size() >= MAX_EDGES) {
                break;
            }
        }

        // Sort: impact relations first (more concrete), then by relevance descending
        edges.sort(Comparator
                .comparing((DiagramEdge e) -> "impact".equals(e.relationCategory()) ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(DiagramEdge::relevance).reversed()));

        log.info("DiagramProjection: {} nodes, {} edges from architecture view", nodes.size(), edges.size());

        return new DiagramModel(
                title,
                nodes,
                edges,
                new DiagramLayout("LR", true));
    }
}
