package com.nato.taxonomy.dsl.validation;

import com.nato.taxonomy.dsl.model.*;

import java.util.*;

/**
 * Validates a {@link CanonicalArchitectureModel} for structural and semantic correctness.
 *
 * <p>Checks include:
 * <ul>
 *   <li>Duplicate element/requirement/view/evidence IDs</li>
 *   <li>Relations referencing unknown element IDs</li>
 *   <li>Mappings referencing unknown requirement or element IDs</li>
 *   <li>Invalid relation types against the type matrix</li>
 *   <li>Invalid status values on relations</li>
 *   <li>Missing required fields</li>
 * </ul>
 */
public class DslValidator {

    /** Recognized relation types from the type matrix. */
    private static final Set<String> VALID_RELATION_TYPES = Set.of(
            "REALIZES", "SUPPORTS", "CONSUMES", "USES", "FULFILLS",
            "ASSIGNED_TO", "DEPENDS_ON", "PRODUCES", "COMMUNICATES_WITH", "RELATED_TO");

    /** Recognized relation statuses. */
    private static final Set<String> VALID_STATUSES = Set.of(
            "proposed", "provisional", "accepted", "rejected");

    /**
     * Validate a canonical architecture model.
     */
    public DslValidationResult validate(CanonicalArchitectureModel model) {
        DslValidationResult result = new DslValidationResult();

        Set<String> elementIds = checkDuplicateIds(model.getElements(), "element", result);
        Set<String> requirementIds = checkDuplicateRequirementIds(model.getRequirements(), result);
        checkDuplicateViewIds(model.getViews(), result);
        checkDuplicateEvidenceIds(model.getEvidence(), result);

        // Combine all known IDs for reference resolution
        Set<String> allIds = new HashSet<>(elementIds);
        allIds.addAll(requirementIds);

        validateRelations(model.getRelations(), allIds, result);
        validateMappings(model.getMappings(), requirementIds, elementIds, result);
        validateElements(model.getElements(), result);

        return result;
    }

    private Set<String> checkDuplicateIds(List<ArchitectureElement> elements, String kind,
                                          DslValidationResult result) {
        Set<String> seen = new LinkedHashSet<>();
        for (ArchitectureElement el : elements) {
            if (el.getId() == null || el.getId().isBlank()) {
                result.addError("Element is missing an ID");
            } else if (!seen.add(el.getId())) {
                result.addError("Duplicate " + kind + " ID: " + el.getId());
            }
        }
        return seen;
    }

    private Set<String> checkDuplicateRequirementIds(List<ArchitectureRequirement> requirements,
                                                     DslValidationResult result) {
        Set<String> seen = new LinkedHashSet<>();
        for (ArchitectureRequirement req : requirements) {
            if (req.getId() == null || req.getId().isBlank()) {
                result.addError("Requirement is missing an ID");
            } else if (!seen.add(req.getId())) {
                result.addError("Duplicate requirement ID: " + req.getId());
            }
        }
        return seen;
    }

    private void checkDuplicateViewIds(List<ArchitectureView> views, DslValidationResult result) {
        Set<String> seen = new HashSet<>();
        for (ArchitectureView view : views) {
            if (view.getId() == null || view.getId().isBlank()) {
                result.addError("View is missing an ID");
            } else if (!seen.add(view.getId())) {
                result.addError("Duplicate view ID: " + view.getId());
            }
        }
    }

    private void checkDuplicateEvidenceIds(List<ArchitectureEvidence> evidence, DslValidationResult result) {
        Set<String> seen = new HashSet<>();
        for (ArchitectureEvidence ev : evidence) {
            if (ev.getId() == null || ev.getId().isBlank()) {
                result.addError("Evidence is missing an ID");
            } else if (!seen.add(ev.getId())) {
                result.addError("Duplicate evidence ID: " + ev.getId());
            }
        }
    }

    private void validateRelations(List<ArchitectureRelation> relations, Set<String> allIds,
                                   DslValidationResult result) {
        for (ArchitectureRelation rel : relations) {
            if (rel.getSourceId() == null || rel.getSourceId().isBlank()) {
                result.addError("Relation is missing a source ID");
            } else if (!allIds.contains(rel.getSourceId())) {
                result.addWarning("Relation references unknown source ID: " + rel.getSourceId());
            }

            if (rel.getTargetId() == null || rel.getTargetId().isBlank()) {
                result.addError("Relation is missing a target ID");
            } else if (!allIds.contains(rel.getTargetId())) {
                result.addWarning("Relation references unknown target ID: " + rel.getTargetId());
            }

            if (rel.getRelationType() != null && !VALID_RELATION_TYPES.contains(rel.getRelationType())) {
                result.addWarning("Unknown relation type: " + rel.getRelationType());
            }

            if (rel.getStatus() != null && !VALID_STATUSES.contains(rel.getStatus())) {
                result.addError("Invalid relation status: " + rel.getStatus()
                        + " (valid: " + VALID_STATUSES + ")");
            }
        }
    }

    private void validateMappings(List<RequirementMapping> mappings,
                                  Set<String> requirementIds,
                                  Set<String> elementIds,
                                  DslValidationResult result) {
        for (RequirementMapping m : mappings) {
            if (m.getRequirementId() == null || m.getRequirementId().isBlank()) {
                result.addError("Mapping is missing a requirement ID");
            } else if (!requirementIds.contains(m.getRequirementId())) {
                result.addWarning("Mapping references unknown requirement ID: " + m.getRequirementId());
            }

            if (m.getElementId() == null || m.getElementId().isBlank()) {
                result.addError("Mapping is missing an element ID");
            } else if (!elementIds.contains(m.getElementId())) {
                result.addWarning("Mapping references unknown element ID: " + m.getElementId());
            }
        }
    }

    private void validateElements(List<ArchitectureElement> elements, DslValidationResult result) {
        for (ArchitectureElement el : elements) {
            if (el.getTitle() == null || el.getTitle().isBlank()) {
                result.addWarning("Element " + el.getId() + " is missing a title");
            }
        }
    }
}
