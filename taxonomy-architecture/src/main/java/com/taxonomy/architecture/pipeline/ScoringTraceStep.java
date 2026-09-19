package com.taxonomy.architecture.pipeline;

import com.taxonomy.architecture.service.ScoringTraceSelector;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RequirementElementView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Merges the scoring-trace metadata from {@link ScoringTraceSelector} into the
 * main element list.
 *
 * <p>For every anchor node the full path (root → intermediate → anchor) is
 * reconstructed and used to populate the {@code scoringPath} and refine the
 * {@code origin} on elements that are already present in the view.
 * Trace entries that are not yet in the element list are not added — they only
 * provide metadata enrichment for elements that survived propagation.
 *
 * <p><b>Safe extension point</b> — this step may be replaced or augmented to
 * change how scoring-path metadata is populated.
 */
@Service
public class ScoringTraceStep implements ArchitecturePipelineStep {

    /** Stable pipeline step ID. */
    public static final String STEP_ID = "scoring-trace";

    private final ScoringTraceSelector scoringTraceSelector;

    public ScoringTraceStep(ScoringTraceSelector scoringTraceSelector) {
        this.scoringTraceSelector = scoringTraceSelector;
    }

    @Override
    public String id() { return STEP_ID; }

    @Override
    public int order() { return 900; }

    @Override
    public void apply(ArchitectureViewContext ctx) {
        List<RequirementElementView> traceEntries =
                scoringTraceSelector.buildTrace(ctx.getScores(), ctx.getAnchors());
        mergeTraceOrigins(ctx.getElements(), traceEntries);
    }

    /**
     * Updates {@code scoringPath} and {@code origin} in the main element list
     * using the scoring trace produced for the anchor nodes.
     */
    private static void mergeTraceOrigins(List<RequirementElementView> elements,
                                           List<RequirementElementView> traceEntries) {
        Map<String, RequirementElementView> traceByCode = new LinkedHashMap<>();
        for (RequirementElementView te : traceEntries) {
            traceByCode.put(te.getNodeCode(), te);
        }

        for (RequirementElementView el : elements) {
            RequirementElementView traceEntry = traceByCode.get(el.getNodeCode());
            if (traceEntry == null) continue;

            // Propagate scoring path if not already set
            if (el.getScoringPath() == null && traceEntry.getScoringPath() != null) {
                el.setScoringPath(traceEntry.getScoringPath());
            }

            // Upgrade origin from trace if the element does not already have a more
            // specific origin (ENRICHED_LEAF should not be overridden).
            if (el.getOrigin() == null || el.getOrigin() == NodeOrigin.PROPAGATED) {
                el.setOrigin(traceEntry.getOrigin());
            }
        }
    }
}
