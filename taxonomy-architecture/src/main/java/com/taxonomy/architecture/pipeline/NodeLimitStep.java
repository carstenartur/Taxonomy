package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies a hard output limit to the architecture-view element, anchor, and
 * relationship lists.
 *
 * <p>When {@code maxArchitectureNodes} is positive and the element list exceeds
 * that limit, endpoints of the strongest existing relationships are retained
 * first. The remaining budget is filled from the already ranked element list.
 * Anchors and relationships are then projected onto exactly that retained node
 * set, while the complete analysis score map remains unchanged.
 *
 * <p>This graph-aware projection avoids turning a useful architecture into a
 * disconnected collection of high-scoring nodes. It also keeps impact-relation
 * generation bounded because this step continues to run before step 800.
 *
 * <p>This step is a pure function with no external dependencies and can be
 * unit-tested without a Spring context.
 *
 * <p><b>Core invariant</b> — this step enforces the caller-requested node-count
 * limit. Do not disable or reorder it relative to impact-relation (step 800),
 * which must run <em>after</em> this bounded graph projection.
 */
@Service
public class NodeLimitStep implements ArchitecturePipelineStep {

    /** Stable pipeline step ID. */
    public static final String STEP_ID = "node-limit";

    private static final Comparator<String> NULL_SAFE_TEXT =
            Comparator.nullsLast(Comparator.naturalOrder());

    private static final Comparator<RequirementRelationshipView> RELATION_PRIORITY =
            Comparator.comparingDouble(NodeLimitStep::relationEvidence).reversed()
                    .thenComparing(RequirementRelationshipView::getSourceCode, NULL_SAFE_TEXT)
                    .thenComparing(RequirementRelationshipView::getTargetCode, NULL_SAFE_TEXT)
                    .thenComparing(RequirementRelationshipView::getRelationType, NULL_SAFE_TEXT);

    @Override
    public String id() { return STEP_ID; }

    @Override
    public int order() { return 700; }

    @Override
    public ArchitecturePipelineStepDescriptor descriptor() {
        return new ArchitecturePipelineStepDescriptor(id(), order(), enabledByDefault(), true);
    }

    /**
     * Applies the node-count limit stored in {@code ctx.maxArchitectureNodes}.
     * Has no effect when the limit is 0 or when the element count is within the limit.
     */
    @Override
    public void apply(ArchitectureViewContext ctx) {
        int maxNodes = ctx.getMaxArchitectureNodes();
        List<RequirementElementView> elements = ctx.getElements();
        List<RequirementRelationshipView> relationships = ctx.getRelationships();

        if (maxNodes <= 0 || elements.size() <= maxNodes) {
            return;
        }

        Set<String> availableCodes = elements.stream()
                .map(RequirementElementView::getNodeCode)
                .collect(Collectors.toSet());
        Set<String> keptCodes = selectRelationshipEndpoints(
                relationships, availableCodes, maxNodes);

        // Fill the remaining budget from the canonical element ranking produced
        // by the preceding pipeline steps.
        for (RequirementElementView element : elements) {
            if (keptCodes.size() >= maxNodes) {
                break;
            }
            keptCodes.add(element.getNodeCode());
        }

        List<RequirementElementView> keptElements = elements.stream()
                .filter(element -> keptCodes.contains(element.getNodeCode()))
                .collect(Collectors.toCollection(ArrayList::new));

        List<RequirementAnchor> originalAnchors = ctx.getAnchors();
        List<RequirementAnchor> keptAnchors = originalAnchors.stream()
                .filter(anchor -> keptCodes.contains(anchor.getNodeCode()))
                .collect(Collectors.toCollection(ArrayList::new));
        int omittedAnchors = originalAnchors.size() - keptAnchors.size();

        ctx.setElements(keptElements);
        ctx.setAnchors(keptAnchors);
        // The view receives the anchor list immediately after anchor selection.
        // Replacing the context list therefore requires explicit synchronization.
        ctx.getView().setAnchors(keptAnchors);
        ctx.setRelationships(relationships.stream()
                .filter(r -> keptCodes.contains(r.getSourceCode())
                        && keptCodes.contains(r.getTargetCode()))
                .collect(Collectors.toList()));

        String note = "Architecture view limited to " + maxNodes + " elements.";
        if (omittedAnchors > 0) {
            note += " Additional anchor nodes outside this bounded view: "
                    + omittedAnchors + "; their scores remain in the complete analysis.";
        }
        ctx.getView().getNotes().add(note);
    }

    private static Set<String> selectRelationshipEndpoints(
            List<RequirementRelationshipView> relationships,
            Set<String> availableCodes,
            int maxNodes) {
        List<RequirementRelationshipView> rankedRelationships =
                new ArrayList<>(relationships);
        rankedRelationships.sort(RELATION_PRIORITY);

        Set<String> selectedCodes = new LinkedHashSet<>();
        for (RequirementRelationshipView relationship : rankedRelationships) {
            String sourceCode = relationship.getSourceCode();
            String targetCode = relationship.getTargetCode();
            if (!availableCodes.contains(sourceCode) || !availableCodes.contains(targetCode)) {
                continue;
            }

            int missingEndpoints = 0;
            if (!selectedCodes.contains(sourceCode)) {
                missingEndpoints++;
            }
            if (!sourceCode.equals(targetCode) && !selectedCodes.contains(targetCode)) {
                missingEndpoints++;
            }
            if (selectedCodes.size() + missingEndpoints > maxNodes) {
                continue;
            }

            selectedCodes.add(sourceCode);
            selectedCodes.add(targetCode);
        }
        return selectedCodes;
    }

    private static double relationEvidence(RequirementRelationshipView relationship) {
        return Math.max(relationship.getConfidence(),
                relationship.getPropagatedRelevance());
    }
}
