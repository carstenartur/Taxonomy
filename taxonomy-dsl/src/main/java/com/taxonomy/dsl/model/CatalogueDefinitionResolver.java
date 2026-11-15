package com.taxonomy.dsl.model;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Resolve the immutable workbook definition that a canonical model pins. */
@FunctionalInterface
public interface CatalogueDefinitionResolver {
    CatalogueDefinition forModel(CanonicalArchitectureModel model);

    /** Parent codes for catalogue elements in this model, including intermediate catalogue entries. */
    default java.util.Map<String, String> parentsForModel(CanonicalArchitectureModel model) {
        return java.util.Map.of();
    }

    record Node(String code, String root, String parentCode, String nameEn, String nameDe, Integer sortOrder) {}
    record AssignmentTarget(boolean known, String catalogueRevision) {}

    static Map<String, String> parentLinks(List<Node> nodes) {
        var parents = new HashMap<String, String>();
        for (var node : nodes) {
            if (node.parentCode() != null) parents.put(node.code(), node.parentCode());
        }
        return parents;
    }

    /** Editable catalogue nodes in parent-first order for the selected revision. */
    default List<Node> catalogueNodes(CanonicalArchitectureModel model) { return List.of(); }

    default List<Node> analysisNodes(String revision) { return List.of(); }

    /** An editable code must belong to the actual catalogue selected by this model. */
    default AssignmentTarget assignmentTarget(CanonicalArchitectureModel model, String code) {
        var definition = forModel(model);
        return new AssignmentTarget(definition.rootCodes().contains(definition.rootFromId(code)), null);
    }

    /** Reads the applied workbook for an exact historical generation. */
    default CatalogueDefinition forRevision(String revision) {
        throw new IllegalStateException("Historical catalogue definitions are unavailable");
    }

    static CatalogueDefinitionResolver standard() { return model -> CatalogueDefinition.standard(); }
}
