package com.taxonomy.dto;

import java.util.List;

/**
 * Represents a node in the APQC process hierarchy extracted from imported DSL documents.
 *
 * <p>Used by the graph visualization to render APQC elements in a tree layout
 * with level-based color coding.
 */
public record ApqcHierarchyNode(
        String id,
        String name,
        String level,
        String pcfId,
        String parentId,
        String taxonomyRoot,
        List<ApqcHierarchyNode> children
) {}
