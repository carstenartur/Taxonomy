package com.taxonomy.architecture.pipeline;

import com.taxonomy.catalog.service.RelevancePropagationService;
import com.taxonomy.dto.RequirementAnchor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Propagates anchor relevance through the taxonomy relation graph.
 *
 * <p>Converts anchor scores (0–100) to relevance values (0.0–1.0) and
 * delegates to {@link RelevancePropagationService}. The result is stored
 * in the pipeline context.
 *
 * <p><b>Core invariant</b> — this step must run after anchor-selection and before
 * element-build. The propagation result is consumed by subsequent steps.
 * Do not disable it.
 */
@Service
public class RelevancePropagationStep implements ArchitecturePipelineStep {

    /** Stable pipeline step ID. */
    public static final String STEP_ID = "relevance-propagation";

    private final RelevancePropagationService propagationService;

    public RelevancePropagationStep(RelevancePropagationService propagationService) {
        this.propagationService = propagationService;
    }

    @Override
    public String id() { return STEP_ID; }

    @Override
    public int order() { return 200; }

    @Override
    public ArchitecturePipelineStepDescriptor descriptor() {
        return new ArchitecturePipelineStepDescriptor(id(), order(), enabledByDefault(), true);
    }

    @Override
    public void apply(ArchitectureViewContext ctx) {
        Map<String, Double> anchorRelevances = new LinkedHashMap<>();
        for (RequirementAnchor anchor : ctx.getAnchors()) {
            anchorRelevances.put(anchor.getNodeCode(), anchor.getDirectScore() / 100.0);
        }
        ctx.setAnchorRelevances(anchorRelevances);
        ctx.setPropagation(propagationService.propagate(anchorRelevances));
    }
}
