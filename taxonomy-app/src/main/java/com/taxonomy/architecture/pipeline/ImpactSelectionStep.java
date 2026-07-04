package com.taxonomy.architecture.pipeline;

import com.taxonomy.architecture.service.ArchitectureImpactSelector;
import com.taxonomy.dto.RequirementRelationshipView;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects the most semantically valuable nodes for the final impact presentation
 * using a composite scoring formula.
 *
 * <p>Delegates the per-element scoring and selection to
 * {@link ArchitectureImpactSelector}, after first collecting the set of node codes
 * that participate in cross-category relationships (used as a bonus factor).
 *
 * <p><b>Safe extension point</b> — this step may be replaced or augmented to
 * change the impact-selection strategy, provided the {@code selectedForImpact}
 * flag is populated correctly on elements.
 */
@Service
public class ImpactSelectionStep implements ArchitecturePipelineStep {

    /** Stable pipeline step ID. */
    public static final String STEP_ID = "impact-selection";

    private final ArchitectureImpactSelector impactSelector;

    public ImpactSelectionStep(ArchitectureImpactSelector impactSelector) {
        this.impactSelector = impactSelector;
    }

    @Override
    public String id() { return STEP_ID; }

    @Override
    public int order() { return 1000; }

    @Override
    public void apply(ArchitectureViewContext ctx) {
        Set<String> crossCategoryCodes = collectCrossCategoryCodes(ctx.getRelationships());
        impactSelector.selectForImpact(ctx.getElements(), ctx.getScores(), crossCategoryCodes);
    }

    /**
     * Collects node codes that participate in cross-category relationships.
     */
    private static Set<String> collectCrossCategoryCodes(
            List<RequirementRelationshipView> relationships) {
        Set<String> codes = new LinkedHashSet<>();
        for (RequirementRelationshipView rel : relationships) {
            String srcRoot = rootOf(rel.getSourceCode());
            String tgtRoot = rootOf(rel.getTargetCode());
            if (srcRoot != null && tgtRoot != null && !srcRoot.equals(tgtRoot)) {
                codes.add(rel.getSourceCode());
                codes.add(rel.getTargetCode());
            }
        }
        return codes;
    }

    private static String rootOf(String code) {
        if (code == null) return null;
        int dash = code.indexOf('-');
        return dash >= 0 ? code.substring(0, dash) : code;
    }
}
