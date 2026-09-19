package com.taxonomy.architecture.pipeline;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.PropagationResult;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RequirementElementView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the initial list of {@link RequirementElementView} instances from
 * the relevance propagation result.
 *
 * <p>Each node in the propagation result is looked up in the taxonomy to
 * populate its title, taxonomy sheet, depth, and hierarchy path. Elements
 * are sorted anchors-first, then by relevance descending.
 *
 * <p><b>Core invariant</b> — this step must run after relevance-propagation and
 * before leaf-enrichment. Do not disable it.
 */
@Service
public class ElementBuildStep implements ArchitecturePipelineStep {

    /** Stable pipeline step ID. */
    public static final String STEP_ID = "element-build";

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyService taxonomyService;

    public ElementBuildStep(TaxonomyNodeRepository nodeRepository,
                            TaxonomyService taxonomyService) {
        this.nodeRepository = nodeRepository;
        this.taxonomyService = taxonomyService;
    }

    @Override
    public String id() { return STEP_ID; }

    @Override
    public int order() { return 300; }

    @Override
    public ArchitecturePipelineStepDescriptor descriptor() {
        return new ArchitecturePipelineStepDescriptor(id(), order(), enabledByDefault(), true);
    }

    @Override
    public void apply(ArchitectureViewContext ctx) {
        PropagationResult propagation = ctx.getPropagation();
        List<RequirementElementView> elements = new ArrayList<>();

        for (Map.Entry<String, Double> entry : propagation.getRelevanceMap().entrySet()) {
            String nodeCode = entry.getKey();
            double relevance = entry.getValue();
            int hopDistance = propagation.getHopDistanceMap().getOrDefault(nodeCode, 0);
            String reason = propagation.getReasonMap().getOrDefault(nodeCode, "unknown");

            RequirementElementView element = new RequirementElementView();
            element.setNodeCode(nodeCode);
            element.setRelevance(relevance);
            element.setHopDistance(hopDistance);
            element.setAnchor(hopDistance == 0);
            element.setIncludedBecause(reason);
            element.setOrigin(hopDistance == 0 ? NodeOrigin.DIRECT_SCORED : NodeOrigin.PROPAGATED);

            // Refine origin: non-anchor root codes (no '-') reached through propagation
            // are seed-context nodes rather than independently propagated results.
            if (hopDistance > 0 && !nodeCode.contains("-")) {
                element.setOrigin(NodeOrigin.SEED_CONTEXT);
            }

            Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(nodeCode);
            if (nodeOpt.isPresent()) {
                TaxonomyNode node = nodeOpt.get();
                element.setTitle(node.getNameEn());
                element.setTaxonomySheet(node.getTaxonomyRoot());
                element.setTaxonomyDepth(node.getLevel());
            }

            element.setDirectLlmScore((int) Math.round(relevance * 100));
            element.setHierarchyPath(ctx.buildHierarchyPath(nodeCode, taxonomyService));

            elements.add(element);
        }

        // Sort: anchors first, then by relevance descending
        elements.sort(Comparator
                .comparing(RequirementElementView::isAnchor).reversed()
                .thenComparing(Comparator.comparingDouble(RequirementElementView::getRelevance).reversed()));

        ctx.setElements(elements);
    }
}
