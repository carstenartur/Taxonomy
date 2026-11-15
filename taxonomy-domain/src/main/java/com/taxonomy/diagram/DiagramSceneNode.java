package com.taxonomy.diagram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;

/** A diagram node with deterministic render coordinates. */
public record DiagramSceneNode(
        String id,
        String label,
        String type,
        double relevance,
        boolean anchor,
        int layer,
        int depth,
        boolean selectedForImpact,
        String parentId,
        boolean container,
        double x,
        double y,
        double width,
        double height,
        @JsonInclude(JsonInclude.Include.NON_NULL) Assignment responsibility) {

    public DiagramSceneNode(String id, String label, String type, double relevance, boolean anchor,
                            int layer, int depth, boolean selectedForImpact, String parentId, boolean container,
                            double x, double y, double width, double height) {
        this(id, label, type, relevance, anchor, layer, depth, selectedForImpact, parentId, container,
                x, y, width, height, null);
    }
}
