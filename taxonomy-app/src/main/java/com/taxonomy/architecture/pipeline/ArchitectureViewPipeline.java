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
 * <p>Deterministic step order:
 * <ol>
 *   <li>{@link AnchorSelectionStep} — select anchor nodes from LLM scores</li>
 *   <li>{@link RelevancePropagationStep} — propagate relevance through relations</li>
 *   <li>{@link ElementBuildStep} — build initial element list from propagation</li>
 *   <li>{@link LeafEnrichmentStep} — add top-scoring concrete leaf nodes</li>
 *   <li>{@link RelationshipBuildStep} — build relationships from propagation traversal</li>
 *   <li>{@link ProvisionalRelationStep} — inject AI-suggested virtual edges if needed</li>
 *   <li>{@link NodeLimitStep} — truncate to max node count if requested</li>
 *   <li>{@link ImpactRelationStep} — derive cross-category impact relations</li>
 *   <li>{@link ScoringTraceStep} — merge scoring-trace origin metadata</li>
 *   <li>{@link ImpactSelectionStep} — mark most valuable nodes for impact display</li>
 *   <li>Finalization — presence reasons, parent codes, notes, summary stats, logging</li>
 * </ol>
 */
@Service
public class ArchitectureViewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureViewPipeline.class);

    // --- Pure steps (no Spring dependencies) ---------------------------
    private final AnchorSelectionStep anchorSelectionStep   = new AnchorSelectionStep();
    private final RelationshipBuildStep relationshipBuildStep = new RelationshipBuildStep();
    private final NodeLimitStep nodeLimitStep               = new NodeLimitStep();

    // --- Spring-managed steps ------------------------------------------
    private final RelevancePropagationStep relevancePropagationStep;
    private final ElementBuildStep elementBuildStep;
    private final LeafEnrichmentStep leafEnrichmentStep;
    private final ProvisionalRelationStep provisionalRelationStep;
    private final ImpactRelationStep impactRelationStep;
    private final ScoringTraceStep scoringTraceStep;
    private final ImpactSelectionStep impactSelectionStep;

    public ArchitectureViewPipeline(RelevancePropagationStep relevancePropagationStep,
                                    ElementBuildStep elementBuildStep,
                                    LeafEnrichmentStep leafEnrichmentStep,
                                    ProvisionalRelationStep provisionalRelationStep,
                                    ImpactRelationStep impactRelationStep,
                                    ScoringTraceStep scoringTraceStep,
                                    ImpactSelectionStep impactSelectionStep) {
        this.relevancePropagationStep = relevancePropagationStep;
        this.elementBuildStep         = elementBuildStep;
        this.leafEnrichmentStep       = leafEnrichmentStep;
        this.provisionalRelationStep  = provisionalRelationStep;
        this.impactRelationStep       = impactRelationStep;
        this.scoringTraceStep         = scoringTraceStep;
        this.impactSelectionStep      = impactSelectionStep;
    }

    /**
     * Runs all pipeline steps in order and returns the populated
     * {@link RequirementArchitectureView}.
     */
    public RequirementArchitectureView execute(ArchitectureViewContext ctx) {
        RequirementArchitectureView view = ctx.getView();

        // Step 1: Anchor selection
        anchorSelectionStep.execute(ctx);
        view.setAnchors(ctx.getAnchors());

        if (ctx.getAnchors().isEmpty()) {
            view.getNotes().add("No nodes met the anchor threshold; architecture view is empty.");
            return view;
        }

        // Step 2: Relevance propagation
        relevancePropagationStep.execute(ctx);

        // Step 3: Build elements
        elementBuildStep.execute(ctx);

        // Step 4: Leaf enrichment
        leafEnrichmentStep.execute(ctx);

        // Step 5: Build relationships
        relationshipBuildStep.execute(ctx);

        // Step 6: Provisional relation injection
        provisionalRelationStep.execute(ctx);

        // Step 7: Node limit truncation
        nodeLimitStep.execute(ctx);

        // Step 8: Impact relation generation (after truncation so only kept nodes are used)
        impactRelationStep.execute(ctx);

        // Step 9: Scoring trace merge
        scoringTraceStep.execute(ctx);

        // Step 10: Impact selection
        impactSelectionStep.execute(ctx);

        // Step 11: Finalization
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
