package com.taxonomy.architecture.pipeline;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.PropagationResult;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries all intermediate state through each step of the
 * {@link ArchitectureViewPipeline}.
 *
 * <p>Created once per {@code build()} invocation and mutated in place by each
 * pipeline step. Only the final {@link #getView()} result is returned to the caller.
 */
public class ArchitectureViewContext {

    // --- Inputs (immutable after construction) -------------------------

    private final Map<String, Integer> scores;
    private final String businessText;
    private final int maxArchitectureNodes;

    /**
     * May be {@code null} when no provisional relations are provided.
     * Steps may augment (never replace) this list.
     */
    private List<RelationHypothesisDto> provisionalRelations;

    // --- Pipeline state (built up step by step) ------------------------

    private List<RequirementAnchor> anchors = new ArrayList<>();
    private Map<String, Double> anchorRelevances = new LinkedHashMap<>();
    private PropagationResult propagation;
    private final Map<String, String> pathCache = new HashMap<>();
    private List<RequirementElementView> elements = new ArrayList<>();
    private List<RequirementRelationshipView> relationships = new ArrayList<>();
    private boolean usedProvisional = false;

    // --- Output --------------------------------------------------------

    private final RequirementArchitectureView view = new RequirementArchitectureView();

    // --- Construction --------------------------------------------------

    public ArchitectureViewContext(Map<String, Integer> scores,
                                   String businessText,
                                   int maxArchitectureNodes,
                                   List<RelationHypothesisDto> provisionalRelations) {
        this.scores = scores;
        this.businessText = businessText;
        this.maxArchitectureNodes = maxArchitectureNodes;
        this.provisionalRelations = provisionalRelations;
    }

    // --- Utility -------------------------------------------------------

    /**
     * Builds (and caches) a hierarchy-path string for the given node code,
     * e.g. {@code "CP > CP-1000 > CP-1023"}, using the real taxonomy parent chain.
     *
     * @param nodeCode        taxonomy node code to resolve
     * @param taxonomyService service used to look up the path-to-root
     * @return the path string, or the code itself when no path is found
     */
    public String buildHierarchyPath(String nodeCode, TaxonomyService taxonomyService) {
        return pathCache.computeIfAbsent(nodeCode, code -> {
            List<TaxonomyNode> path = taxonomyService.getPathToRoot(code);
            if (path.isEmpty()) {
                return code;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) sb.append(" > ");
                sb.append(path.get(i).getCode());
            }
            return sb.toString();
        });
    }

    // --- Getters / setters ---------------------------------------------

    public Map<String, Integer> getScores() { return scores; }

    public String getBusinessText() { return businessText; }

    public int getMaxArchitectureNodes() { return maxArchitectureNodes; }

    public List<RelationHypothesisDto> getProvisionalRelations() { return provisionalRelations; }

    public void setProvisionalRelations(List<RelationHypothesisDto> provisionalRelations) {
        this.provisionalRelations = provisionalRelations;
    }

    public List<RequirementAnchor> getAnchors() { return anchors; }

    public void setAnchors(List<RequirementAnchor> anchors) { this.anchors = anchors; }

    public Map<String, Double> getAnchorRelevances() { return anchorRelevances; }

    public void setAnchorRelevances(Map<String, Double> anchorRelevances) {
        this.anchorRelevances = anchorRelevances;
    }

    public PropagationResult getPropagation() { return propagation; }

    public void setPropagation(PropagationResult propagation) { this.propagation = propagation; }

    public Map<String, String> getPathCache() { return pathCache; }

    public List<RequirementElementView> getElements() { return elements; }

    public void setElements(List<RequirementElementView> elements) { this.elements = elements; }

    public List<RequirementRelationshipView> getRelationships() { return relationships; }

    public void setRelationships(List<RequirementRelationshipView> relationships) {
        this.relationships = relationships;
    }

    public boolean isUsedProvisional() { return usedProvisional; }

    public void setUsedProvisional(boolean usedProvisional) { this.usedProvisional = usedProvisional; }

    public RequirementArchitectureView getView() { return view; }
}
