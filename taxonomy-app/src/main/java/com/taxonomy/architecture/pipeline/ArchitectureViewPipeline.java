package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Orchestrates all architecture-view pipeline steps and produces the final
 * {@link RequirementArchitectureView}.
 *
 * <p>Steps are discovered from the {@link ArchitecturePipelineStepRegistry} and
 * executed in ascending {@link ArchitecturePipelineStep#order()} order.  Only
 * steps where {@link ArchitecturePipelineStep#enabledByDefault()} returns
 * {@code true} are run.
 *
 * <p>Deterministic default step order:
 * <ol>
 *   <li>100 {@link AnchorSelectionStep} — select anchor nodes from LLM scores</li>
 *   <li>200 {@link RelevancePropagationStep} — propagate relevance through relations</li>
 *   <li>300 {@link ElementBuildStep} — build initial element list from propagation</li>
 *   <li>400 {@link LeafEnrichmentStep} — add top-scoring concrete leaf nodes</li>
 *   <li>500 {@link RelationshipBuildStep} — build relationships from propagation traversal</li>
 *   <li>600 {@link ProvisionalRelationStep} — inject AI-suggested virtual edges if needed</li>
 *   <li>700 {@link NodeLimitStep} — truncate to max node count if requested</li>
 *   <li>800 {@link ImpactRelationStep} — derive cross-category impact relations</li>
 *   <li>900 {@link ScoringTraceStep} — merge scoring-trace origin metadata</li>
 *   <li>1000 {@link ImpactSelectionStep} — mark most valuable nodes for impact display</li>
 *   <li>Finalization — presence reasons, parent codes, notes, summary stats, logging</li>
 * </ol>
 *
 * <p><b>Core invariant</b>: after anchor-selection (order&nbsp;100) the pipeline checks
 * for an empty anchor list and short-circuits with an empty view when no anchors are found.
 * This invariant is enforced here and must not be bypassed by custom steps.
 */
@Service
public class ArchitectureViewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureViewPipeline.class);

    private final ArchitecturePipelineStepRegistry registry;

    public ArchitectureViewPipeline(ArchitecturePipelineStepRegistry registry) {
        this.registry = registry;
    }

    /**
     * Runs all enabled pipeline steps in order and returns the populated
     * {@link RequirementArchitectureView}.
     */
    public RequirementArchitectureView execute(ArchitectureViewContext ctx) {
        RequirementArchitectureView view = ctx.getView();
        List<ArchitecturePipelineStep> enabledSteps = registry.getEnabledSteps();

        ArchitecturePipelineStep firstEnabledStep = enabledSteps.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Architecture pipeline must enable %s as its first step"
                                .formatted(AnchorSelectionStep.STEP_ID)));
        if (!AnchorSelectionStep.STEP_ID.equals(firstEnabledStep.id())) {
            throw new IllegalStateException(
                    "Architecture pipeline must execute %s first, but found %s"
                            .formatted(AnchorSelectionStep.STEP_ID, firstEnabledStep.id()));
        }

        firstEnabledStep.apply(ctx);
        view.setAnchors(ctx.getAnchors());
        if (ctx.getAnchors().isEmpty()) {
            view.getNotes().add(
                    "No nodes met the anchor threshold; architecture view is empty.");
            return view;
        }

        for (ArchitecturePipelineStep step : enabledSteps.subList(1, enabledSteps.size())) {
            step.apply(ctx);
        }

        // Finalization (core invariant — not a pipeline step)
        populatePresenceReasons(ctx.getElements(), ctx.getRelationships());
        populateParentNodeCodes(ctx.getElements());

        view.setIncludedElements(ctx.getElements());
        view.setIncludedRelationships(ctx.getRelationships());

        // Notes
        if (ctx.isUsedProvisional()) {
            view.getNotes().add("Architecture view built using AI-suggested provisional relations "
                    + "(not yet confirmed).");
        } else if (ctx.getRelationships().isEmpty()
                && ctx.getElements().size() == ctx.getAnchors().size()) {
            view.getNotes().add("No traversable relations found for anchor nodes; "
                    + "only direct matches are included.");
        }

        // Summary statistics
        int maxHop = ctx.getElements().stream()
                .mapToInt(RequirementElementView::getHopDistance).max().orElse(0);
        view.setTotalAnchors(ctx.getAnchors().size());
        view.setTotalElements(ctx.getElements().size());
        view.setTotalRelationships(ctx.getRelationships().size());
        view.setMaxHopDistance(maxHop);

        // Structured logging
        log.info("RequirementArchitectureView summary: anchors={}, elements={}, "
                + "relationships={}, maxHopDistance={}",
                ctx.getAnchors().size(), ctx.getElements().size(),
                ctx.getRelationships().size(), maxHop);

        for (var anchor : ctx.getAnchors()) {
            List<RequirementElementView> propagated = ctx.getElements().stream()
                    .filter(e -> !e.isAnchor() && e.getIncludedBecause() != null
                            && e.getIncludedBecause().contains(anchor.getNodeCode()))
                    .toList();
            if (!propagated.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("anchor ").append(anchor.getNodeCode());
                for (RequirementElementView e : propagated) {
                    sb.append("\n  → ").append(e.getNodeCode())
                      .append(" (").append(String.format(Locale.US, "%.2f", e.getRelevance()))
                      .append(")");
                }
                log.debug(sb.toString());
            }
        }

        return view;
    }

    // --- Finalization helpers ------------------------------------------

    private static void populatePresenceReasons(List<RequirementElementView> elements,
                                                 List<RequirementRelationshipView> relationships) {
        for (RequirementElementView el : elements) {
            if (el.getPresenceReason() != null) continue;
            StringBuilder sb = new StringBuilder();
            sb.append(el.getNodeCode());
            if (el.getTitle() != null) sb.append(" (").append(el.getTitle()).append(")");
            sb.append(": ");
            if (el.getOrigin() != null) {
                sb.append(el.getOrigin().name().toLowerCase().replace('_', ' '));
            } else {
                sb.append("included via propagation");
            }
            if (el.getDirectLlmScore() > 0) {
                sb.append(", LLM score ").append(el.getDirectLlmScore());
            }
            if (el.getHopDistance() > 0) {
                sb.append(", ").append(el.getHopDistance()).append(" hop(s)");
            }
            el.setPresenceReason(sb.toString());
        }

        for (RequirementRelationshipView rel : relationships) {
            if (rel.getPresenceReason() != null) continue;
            StringBuilder sb = new StringBuilder();
            sb.append(rel.getSourceCode()).append(" → ").append(rel.getTargetCode());
            sb.append(": ").append(rel.getRelationCategory());
            if (rel.getOrigin() != null) {
                sb.append(", ").append(rel.getOrigin().name().toLowerCase().replace('_', ' '));
            }
            if (rel.getConfidence() > 0) {
                sb.append(String.format(", confidence %.0f%%", rel.getConfidence() * 100));
            }
            rel.setPresenceReason(sb.toString());
        }
    }

    private static void populateParentNodeCodes(List<RequirementElementView> elements) {
        Set<String> codes = new HashSet<>();
        for (RequirementElementView el : elements) {
            codes.add(el.getNodeCode());
        }
        for (RequirementElementView el : elements) {
            String hp = el.getHierarchyPath();
            if (hp == null || hp.isEmpty()) continue;
            String[] parts = hp.split("\\s*>\\s*");
            for (int i = parts.length - 2; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (codes.contains(candidate)) {
                    el.setParentNodeCode(candidate);
                    break;
                }
            }
        }
    }
}
