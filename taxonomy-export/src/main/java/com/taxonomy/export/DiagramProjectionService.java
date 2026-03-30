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
import java.util.List;
import java.util.Map;

/**
 * Projects a {@link RequirementArchitectureView} into a neutral {@link DiagramModel}
 * suitable for rendering to various diagram formats (Visio, Mermaid, GraphViz, etc.).
 *
 * <p>The projection converts domain-level {@link RequirementElementView} /
 * {@link RequirementRelationshipView} objects to the format-independent
 * {@link DiagramModel}.  Selection and curation (filtering, suppression,
 * containment) are delegated to a pluggable {@link DiagramSelectionPolicy}.</p>
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

    private DiagramSelectionPolicy policy =
            new ConfigurableDiagramSelectionPolicy(DiagramSelectionConfig.defaultImpact());

    /** Replaces the active selection policy. */
    public void setPolicy(DiagramSelectionPolicy policy) {
        this.policy = policy;
    }

    /** Returns the active selection policy. */
    public DiagramSelectionPolicy getPolicy() {
        return policy;
    }

    /**
     * Converts a {@link RequirementArchitectureView} to a {@link DiagramModel},
     * applying the active {@link DiagramSelectionPolicy} to curate the result.
     *
     * @param view  the architecture view produced by analysis
     * @param title diagram title (typically the business text summary)
     * @return a curated diagram model
     */
    public DiagramModel project(RequirementArchitectureView view, String title) {
        DiagramModel raw = projectRaw(view, title);
        DiagramModel curated = policy.apply(raw);
        log.info("DiagramProjection: {} nodes, {} edges from architecture view",
                curated.nodes().size(), curated.edges().size());
        return curated;
    }

    /**
     * Converts a {@link RequirementArchitectureView} to a raw (unfiltered)
     * {@link DiagramModel}.  All elements and relationships are included;
     * no selection or suppression is applied.
     *
     * @param view  the architecture view produced by analysis
     * @param title diagram title
     * @return a raw diagram model with all projected nodes and edges
     */
    DiagramModel projectRaw(RequirementArchitectureView view, String title) {
        if (view == null) {
            return new DiagramModel(title, List.of(), List.of(),
                    new DiagramLayout("LR", true));
        }

        // Build a lookup for parentId resolution from hierarchy paths
        Map<String, String> parentMap = buildParentMap(view.getIncludedElements());

        List<DiagramNode> nodes = new ArrayList<>();
        for (RequirementElementView el : view.getIncludedElements()) {
            String rawType = el.getTaxonomySheet() != null ? el.getTaxonomySheet() : "Unknown";
            String type = ROOT_CODE_TO_LAYER.getOrDefault(rawType, rawType);
            int layer = LAYER_MAP.getOrDefault(type, 0);
            String parentId = parentMap.get(el.getNodeCode());
            nodes.add(new DiagramNode(
                    el.getNodeCode(),
                    el.getTitle() != null ? el.getTitle() : el.getNodeCode(),
                    type,
                    el.getRelevance(),
                    el.isAnchor(),
                    layer,
                    el.getTaxonomyDepth(),
                    el.isSelectedForImpact(),
                    parentId,
                    false));
        }

        List<DiagramEdge> edges = new ArrayList<>();
        int edgeIdx = 0;
        for (RequirementRelationshipView rel : view.getIncludedRelationships()) {
            edges.add(new DiagramEdge(
                    "e" + (++edgeIdx),
                    rel.getSourceCode(),
                    rel.getTargetCode(),
                    rel.getRelationType(),
                    rel.getPropagatedRelevance(),
                    rel.getRelationCategory()));
        }

        return new DiagramModel(title, nodes, edges, new DiagramLayout("LR", true));
    }

    /**
     * Builds a map from node code to its nearest ancestor code that is also
     * present in the element list.  Uses the {@code hierarchyPath} field
     * (format: "CP &gt; CP-1000 &gt; CP-1023") to resolve parent codes.
     */
    private Map<String, String> buildParentMap(List<RequirementElementView> elements) {
        var codes = new java.util.HashSet<String>();
        for (RequirementElementView el : elements) {
            codes.add(el.getNodeCode());
        }

        Map<String, String> parentMap = new java.util.LinkedHashMap<>();
        for (RequirementElementView el : elements) {
            String hp = el.getHierarchyPath();
            if (hp == null || hp.isEmpty()) continue;
            String[] parts = hp.split("\\s*>\\s*");
            // Walk backwards from the element's position to find the nearest ancestor in the model
            for (int i = parts.length - 2; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (codes.contains(candidate)) {
                    parentMap.put(el.getNodeCode(), candidate);
                    break;
                }
            }
        }
        return parentMap;
    }
}
