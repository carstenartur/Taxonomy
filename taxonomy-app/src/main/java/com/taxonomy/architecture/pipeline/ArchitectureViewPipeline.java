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
 * Executes the ordered architecture-view steps and validates their shared
 * context after every stage. Extension steps may enrich the pipeline but cannot
 * silently violate core element, relation, anchor, score, or node-limit invariants.
 */
@Service
public class ArchitectureViewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureViewPipeline.class);

    private final ArchitecturePipelineStepRegistry registry;
    private final ArchitecturePipelineInvariantValidator invariantValidator;

    public ArchitectureViewPipeline(ArchitecturePipelineStepRegistry registry,
                                    ArchitecturePipelineInvariantValidator invariantValidator) {
        this.registry = registry;
        this.invariantValidator = invariantValidator;
    }

    public RequirementArchitectureView execute(ArchitectureViewContext context) {
        RequirementArchitectureView view = context.getView();
        List<ArchitecturePipelineStep> enabledSteps = registry.getEnabledSteps();

        ArchitecturePipelineStep firstEnabledStep = enabledSteps.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Architecture pipeline requires at least one enabled step, specifically %s as the first step"
                                .formatted(AnchorSelectionStep.STEP_ID)));
        if (!AnchorSelectionStep.STEP_ID.equals(firstEnabledStep.id())) {
            throw new IllegalStateException(
                    "Architecture pipeline must execute %s first, but found %s"
                            .formatted(AnchorSelectionStep.STEP_ID, firstEnabledStep.id()));
        }

        firstEnabledStep.apply(context);
        invariantValidator.afterStep(firstEnabledStep, context);
        view.setAnchors(context.getAnchors());
        if (context.getAnchors().isEmpty()) {
            view.getNotes().add("No nodes met the anchor threshold; architecture view is empty.");
            invariantValidator.beforeReturn(context);
            return view;
        }

        for (ArchitecturePipelineStep step : enabledSteps.subList(1, enabledSteps.size())) {
            step.apply(context);
            invariantValidator.afterStep(step, context);
        }

        populatePresenceReasons(context.getElements(), context.getRelationships());
        populateParentNodeCodes(context.getElements());
        invariantValidator.beforeReturn(context);

        view.setIncludedElements(context.getElements());
        view.setIncludedRelationships(context.getRelationships());

        if (context.isUsedProvisional()) {
            view.getNotes().add("Architecture view built using AI-suggested provisional relations "
                    + "(not yet confirmed).");
        } else if (context.getRelationships().isEmpty()
                && context.getElements().size() == context.getAnchors().size()) {
            view.getNotes().add("No traversable relations found for anchor nodes; "
                    + "only direct matches are included.");
        }

        int maxHop = context.getElements().stream()
                .mapToInt(RequirementElementView::getHopDistance).max().orElse(0);
        view.setTotalAnchors(context.getAnchors().size());
        view.setTotalElements(context.getElements().size());
        view.setTotalRelationships(context.getRelationships().size());
        view.setMaxHopDistance(maxHop);

        log.info("RequirementArchitectureView summary: anchors={}, elements={}, relationships={}, maxHopDistance={}",
                context.getAnchors().size(), context.getElements().size(),
                context.getRelationships().size(), maxHop);
        logPropagationSummary(context);
        return view;
    }

    private static void logPropagationSummary(ArchitectureViewContext context) {
        for (var anchor : context.getAnchors()) {
            List<RequirementElementView> propagated = context.getElements().stream()
                    .filter(element -> !element.isAnchor()
                            && element.getIncludedBecause() != null
                            && element.getIncludedBecause().contains(anchor.getNodeCode()))
                    .toList();
            if (propagated.isEmpty()) continue;
            StringBuilder summary = new StringBuilder("anchor ").append(anchor.getNodeCode());
            for (RequirementElementView element : propagated) {
                summary.append("\n  → ").append(element.getNodeCode())
                        .append(" (")
                        .append(String.format(Locale.US, "%.2f", element.getRelevance()))
                        .append(')');
            }
            log.debug(summary.toString());
        }
    }

    private static void populatePresenceReasons(List<RequirementElementView> elements,
                                                 List<RequirementRelationshipView> relationships) {
        for (RequirementElementView element : elements) {
            if (element.getPresenceReason() != null) continue;
            StringBuilder reason = new StringBuilder();
            reason.append(element.getNodeCode());
            if (element.getTitle() != null) reason.append(" (").append(element.getTitle()).append(')');
            reason.append(": ");
            if (element.getOrigin() != null) {
                reason.append(element.getOrigin().name().toLowerCase().replace('_', ' '));
            } else {
                reason.append("included via propagation");
            }
            if (element.getDirectLlmScore() > 0) {
                reason.append(", LLM score ").append(element.getDirectLlmScore());
            }
            if (element.getHopDistance() > 0) {
                reason.append(", ").append(element.getHopDistance()).append(" hop(s)");
            }
            element.setPresenceReason(reason.toString());
        }

        for (RequirementRelationshipView relationship : relationships) {
            if (relationship.getPresenceReason() != null) continue;
            StringBuilder reason = new StringBuilder();
            reason.append(relationship.getSourceCode())
                    .append(" → ").append(relationship.getTargetCode())
                    .append(": ").append(relationship.getRelationCategory());
            if (relationship.getOrigin() != null) {
                reason.append(", ")
                        .append(relationship.getOrigin().name().toLowerCase().replace('_', ' '));
            }
            if (relationship.getConfidence() > 0) {
                reason.append(String.format(", confidence %.0f%%", relationship.getConfidence() * 100));
            }
            relationship.setPresenceReason(reason.toString());
        }
    }

    private static void populateParentNodeCodes(List<RequirementElementView> elements) {
        Set<String> codes = new HashSet<>();
        for (RequirementElementView element : elements) {
            codes.add(element.getNodeCode());
        }
        for (RequirementElementView element : elements) {
            String hierarchyPath = element.getHierarchyPath();
            if (hierarchyPath == null || hierarchyPath.isEmpty()) continue;
            String[] parts = hierarchyPath.split("\\s*>\\s*");
            for (int index = parts.length - 2; index >= 0; index--) {
                String candidate = parts[index].trim();
                if (codes.contains(candidate)) {
                    element.setParentNodeCode(candidate);
                    break;
                }
            }
        }
    }
}
