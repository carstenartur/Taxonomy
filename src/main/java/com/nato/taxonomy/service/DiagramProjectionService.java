package com.nato.taxonomy.service;

import com.nato.taxonomy.diagram.DiagramEdge;
import com.nato.taxonomy.diagram.DiagramLayout;
import com.nato.taxonomy.diagram.DiagramModel;
import com.nato.taxonomy.diagram.DiagramNode;
import com.nato.taxonomy.dto.RequirementArchitectureView;
import com.nato.taxonomy.dto.RequirementElementView;
import com.nato.taxonomy.dto.RequirementRelationshipView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Projects a {@link RequirementArchitectureView} into a neutral {@link DiagramModel}
 * suitable for rendering to various diagram formats (Visio, Mermaid, GraphViz, etc.).
 */
@Service
public class DiagramProjectionService {

    private static final Logger log = LoggerFactory.getLogger(DiagramProjectionService.class);

    static final int MAX_NODES = 25;
    static final int MAX_EDGES = 40;
    static final double MIN_RELEVANCE = 0.35;

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
            if (el.getRelevance() < MIN_RELEVANCE && !el.isAnchor()) {
                continue;
            }
            int layer = el.getTaxonomySheet() != null
                    ? LAYER_MAP.getOrDefault(el.getTaxonomySheet(), 0) : 0;
            nodes.add(new DiagramNode(
                    el.getNodeCode(),
                    el.getTitle() != null ? el.getTitle() : el.getNodeCode(),
                    el.getTaxonomySheet() != null ? el.getTaxonomySheet() : "Unknown",
                    el.getRelevance(),
                    el.isAnchor(),
                    layer));
        }

        // Sort by relevance descending, then limit
        nodes.sort(Comparator.comparingDouble(DiagramNode::relevance).reversed());
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
                    rel.getPropagatedRelevance()));
            if (edges.size() >= MAX_EDGES) {
                break;
            }
        }

        log.info("DiagramProjection: {} nodes, {} edges from architecture view", nodes.size(), edges.size());

        return new DiagramModel(
                title,
                nodes,
                edges,
                new DiagramLayout("LR", true));
    }
}
