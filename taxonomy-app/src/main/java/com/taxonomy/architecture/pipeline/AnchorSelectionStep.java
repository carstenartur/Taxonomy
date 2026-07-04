package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.pipeline.PipelineConstants;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Selects anchor nodes from the LLM score map according to the two-tier
 * threshold rules.
 *
 * <ol>
 *   <li>All nodes with score ≥ {@link PipelineConstants#ANCHOR_THRESHOLD_HIGH} are anchors.</li>
 *   <li>If fewer than {@link PipelineConstants#MIN_ANCHORS} qualify, fall back to the
 *       top-{@code MIN_ANCHORS} nodes with score ≥ {@link PipelineConstants#ANCHOR_THRESHOLD_LOW}.</li>
 * </ol>
 *
 * <p>This step is a pure function with no external dependencies and can be
 * unit-tested without a Spring context.
 *
 * <p><b>Core invariant</b> — this step must run first and its result determines whether
 * the pipeline continues at all. Do not disable or reorder it.
 */
@Service
public class AnchorSelectionStep implements ArchitecturePipelineStep {

    /** Stable pipeline step ID. */
    public static final String STEP_ID = "anchor-selection";

    private static final int ANCHOR_THRESHOLD_HIGH = PipelineConstants.ANCHOR_THRESHOLD_HIGH;
    private static final int ANCHOR_THRESHOLD_LOW  = PipelineConstants.ANCHOR_THRESHOLD_LOW;
    private static final int MIN_ANCHORS           = PipelineConstants.MIN_ANCHORS;

    @Override
    public String id() { return STEP_ID; }

    @Override
    public int order() { return 100; }

    @Override
    public ArchitecturePipelineStepDescriptor descriptor() {
        return new ArchitecturePipelineStepDescriptor(id(), order(), enabledByDefault(), true);
    }

    /**
     * Selects anchors and stores them in {@code ctx.anchors}.
     */
    @Override
    public void apply(ArchitectureViewContext ctx) {
        ctx.setAnchors(select(ctx.getScores()));
    }

    /**
     * Selects anchors from the given score map.
     * Package-private for direct unit testing.
     */
    List<RequirementAnchor> select(Map<String, Integer> scores) {
        List<RequirementAnchor> highAnchors = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() >= ANCHOR_THRESHOLD_HIGH) {
                highAnchors.add(new RequirementAnchor(
                        entry.getKey(), entry.getValue(), "high direct match"));
            }
        }

        if (highAnchors.size() >= MIN_ANCHORS) {
            highAnchors.sort(Comparator.comparingInt(RequirementAnchor::getDirectScore).reversed());
            return highAnchors;
        }

        // Fallback: collect top-MIN_ANCHORS with score >= ANCHOR_THRESHOLD_LOW
        List<Map.Entry<String, Integer>> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() >= ANCHOR_THRESHOLD_LOW) {
                candidates.add(entry);
            }
        }
        candidates.sort(
                Comparator.<Map.Entry<String, Integer>, Integer>comparing(Map.Entry::getValue)
                          .reversed());

        List<RequirementAnchor> anchors = new ArrayList<>();
        for (int i = 0; i < Math.min(MIN_ANCHORS, candidates.size()); i++) {
            Map.Entry<String, Integer> entry = candidates.get(i);
            anchors.add(new RequirementAnchor(
                    entry.getKey(), entry.getValue(), "top candidate (fallback)"));
        }

        return anchors;
    }
}
