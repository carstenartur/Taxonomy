package com.taxonomy.dsl.validation;

import com.taxonomy.dsl.model.*;

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
 *   <li>Invalid type combinations for relation source/target</li>
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
     * Type compatibility matrix: relation type → allowed (sourceRoot → targetRoots).
     * Mirrors {@code RelationCompatibilityMatrix} but usable without Spring.
     */
    private static final Map<String, Map<String, Set<String>>> TYPE_MATRIX;

    static {
        Map<String, Map<String, Set<String>>> m = new LinkedHashMap<>();
        m.put("REALIZES",          Map.of("CP", Set.of("CR")));
        m.put("SUPPORTS",          Map.of("CR", Set.of("BP")));
        m.put("CONSUMES",          Map.of("BP", Set.of("IP")));
        m.put("USES",              Map.of("UA", Set.of("CR")));
        m.put("FULFILLS",          Map.of("CI", Set.of("CP")));
        m.put("ASSIGNED_TO",       Map.of("BR", Set.of("BP")));
        m.put("DEPENDS_ON",        Map.of("CR", Set.of("CR")));
        m.put("PRODUCES",          Map.of("BP", Set.of("IP")));
        m.put("COMMUNICATES_WITH", Map.of("CO", Set.of("CR")));
        // RELATED_TO has no restrictions
        TYPE_MATRIX = Collections.unmodifiableMap(m);
    }

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

        // Build element type lookup for type-combination validation
        Map<String, String> elementTypeMap = new HashMap<>();
        for (ArchitectureElement el : model.getElements()) {
            if (el.getId() != null && el.getType() != null) {
                elementTypeMap.put(el.getId(), el.getType());
            }
        }

        validateRelations(model.getRelations(), allIds, elementTypeMap, result);
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
                                   Map<String, String> elementTypeMap,
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

            // Type-combination validation using the type matrix
            if (rel.getRelationType() != null && rel.getSourceId() != null && rel.getTargetId() != null) {
                validateTypeCombination(rel, elementTypeMap, result);
            }
        }
    }

    /**
     * Check that the source and target element types are compatible with the relation type,
     * using the type matrix (e.g., REALIZES requires CP→CR).
     */
    private void validateTypeCombination(ArchitectureRelation rel,
                                         Map<String, String> elementTypeMap,
                                         DslValidationResult result) {
        Map<String, Set<String>> rules = TYPE_MATRIX.get(rel.getRelationType());
        if (rules == null) {
            return; // No type matrix rule (e.g., RELATED_TO or unknown type)
        }

        String sourceRoot = resolveTaxonomyRootCode(rel.getSourceId(), elementTypeMap);
        String targetRoot = resolveTaxonomyRootCode(rel.getTargetId(), elementTypeMap);

        if (sourceRoot == null || targetRoot == null) {
            return; // Can't determine type — skip validation
        }

        Set<String> allowedTargets = rules.get(sourceRoot);
        if (allowedTargets == null) {
            result.addWarning("Relation " + rel.getRelationType() + ": source "
                    + rel.getSourceId() + " (root " + sourceRoot
                    + ") is not a valid source type for " + rel.getRelationType());
        } else if (!allowedTargets.contains(targetRoot)) {
            result.addWarning("Relation " + rel.getRelationType() + ": target "
                    + rel.getTargetId() + " (root " + targetRoot
                    + ") is not a valid target type; expected one of " + allowedTargets);
        }
    }

    /**
     * Resolve the taxonomy root code for an element ID, using either the
     * element type map (from DSL) or the ID prefix (e.g., CP-1001 → CP).
     */
    private String resolveTaxonomyRootCode(String elementId, Map<String, String> elementTypeMap) {
        // Try element type from DSL model first
        String typeName = elementTypeMap.get(elementId);
        if (typeName != null) {
            String root = TaxonomyRootTypes.rootFor(typeName);
            if (root != null) return root;
        }
        // Fall back to ID prefix
        return TaxonomyRootTypes.rootFromId(elementId);
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
