package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates cross-step invariants after each architecture pipeline stage and
 * before the final view is returned. A faulty custom step therefore fails at
 * the point where it corrupts the shared context instead of producing a
 * plausible but inconsistent architecture result.
 */
@Component
public class ArchitecturePipelineInvariantValidator {

    public void afterStep(ArchitecturePipelineStep step, ArchitectureViewContext context) {
        if (step == null) {
            throw new IllegalStateException("Architecture pipeline executed a null step");
        }
        validateCollections(context, step.id());
        validateAnchors(context.getAnchors(), step.id());
        validateElements(context.getElements(), step.id());
        validateRelationships(context.getRelationships(), step.id());

        if (step.order() >= 700 && context.getMaxArchitectureNodes() > 0
                && context.getElements().size() > context.getMaxArchitectureNodes()) {
            throw new IllegalStateException(
                    "Pipeline invariant failed after '%s': %d elements exceed maxArchitectureNodes=%d"
                            .formatted(step.id(), context.getElements().size(),
                                    context.getMaxArchitectureNodes()));
        }
    }

    public void beforeReturn(ArchitectureViewContext context) {
        validateCollections(context, "finalization");
        validateAnchors(context.getAnchors(), "finalization");
        validateElements(context.getElements(), "finalization");
        validateRelationships(context.getRelationships(), "finalization");

        Set<String> elementCodes = new HashSet<>();
        for (RequirementElementView element : context.getElements()) {
            elementCodes.add(element.getNodeCode());
        }
        for (RequirementRelationshipView relationship : context.getRelationships()) {
            if (!elementCodes.contains(relationship.getSourceCode())
                    || !elementCodes.contains(relationship.getTargetCode())) {
                throw new IllegalStateException(
                        "Pipeline invariant failed during finalization: relation %s -> %s references an element outside the view"
                                .formatted(relationship.getSourceCode(), relationship.getTargetCode()));
            }
        }
        for (RequirementAnchor anchor : context.getAnchors()) {
            if (!elementCodes.contains(anchor.getNodeCode())) {
                throw new IllegalStateException(
                        "Pipeline invariant failed during finalization: anchor %s is absent from included elements"
                                .formatted(anchor.getNodeCode()));
            }
        }
    }

    private void validateCollections(ArchitectureViewContext context, String phase) {
        if (context == null) {
            throw new IllegalStateException("Pipeline invariant failed after '" + phase + "': context is null");
        }
        if (context.getAnchors() == null || context.getElements() == null
                || context.getRelationships() == null) {
            throw new IllegalStateException(
                    "Pipeline invariant failed after '%s': anchors, elements and relationships must not be null"
                            .formatted(phase));
        }
    }

    private void validateAnchors(List<RequirementAnchor> anchors, String phase) {
        Set<String> seen = new HashSet<>();
        for (RequirementAnchor anchor : anchors) {
            if (anchor == null || isBlank(anchor.getNodeCode())) {
                throw new IllegalStateException(
                        "Pipeline invariant failed after '%s': anchor code must not be blank"
                                .formatted(phase));
            }
            if (!seen.add(anchor.getNodeCode())) {
                throw new IllegalStateException(
                        "Pipeline invariant failed after '%s': duplicate anchor %s"
                                .formatted(phase, anchor.getNodeCode()));
            }
        }
    }

    private void validateElements(List<RequirementElementView> elements, String phase) {
        Set<String> seen = new HashSet<>();
        for (RequirementElementView element : elements) {
            if (element == null || isBlank(element.getNodeCode())) {
                throw new IllegalStateException(
                        "Pipeline invariant failed after '%s': element code must not be blank"
                                .formatted(phase));
            }
            if (!seen.add(element.getNodeCode())) {
                throw new IllegalStateException(
                        "Pipeline invariant failed after '%s': duplicate element %s"
                                .formatted(phase, element.getNodeCode()));
            }
            if (!Double.isFinite(element.getRelevance()) || element.getRelevance() < 0) {
                throw new IllegalStateException(
                        "Pipeline invariant failed after '%s': invalid relevance for %s"
                                .formatted(phase, element.getNodeCode()));
            }
            if (element.getHopDistance() < 0 || element.getDirectLlmScore() < 0
                    || element.getDirectLlmScore() > 100) {
                throw new IllegalStateException(
                        "Pipeline invariant failed after '%s': invalid score/hop values for %s"
                                .formatted(phase, element.getNodeCode()));
            }
        }
    }

    private void validateRelationships(List<RequirementRelationshipView> relationships, String phase) {
        for (RequirementRelationshipView relationship : relationships) {
            if (relationship == null || isBlank(relationship.getSourceCode())
                    || isBlank(relationship.getTargetCode())
                    || isBlank(relationship.getRelationType())) {
                throw new IllegalStateException(
                        "Pipeline invariant failed after '%s': relation endpoints and type must not be blank"
                                .formatted(phase));
            }
            if (!Double.isFinite(relationship.getPropagatedRelevance())
                    || relationship.getPropagatedRelevance() < 0
                    || relationship.getHopDistance() < 0
                    || relationship.getConfidence() < 0
                    || relationship.getConfidence() > 1) {
                throw new IllegalStateException(
                        "Pipeline invariant failed after '%s': invalid values for relation %s -> %s"
                                .formatted(phase, relationship.getSourceCode(), relationship.getTargetCode()));
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
