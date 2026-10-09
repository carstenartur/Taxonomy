package com.taxonomy.diagram;

/**
 * Represents a single node in a neutral diagram model.
 *
 * @param id                node identifier (typically a taxonomy code like "CP-1023")
 * @param label             human-readable display name
 * @param type              layer/category name (e.g. "Capabilities", "Core Services")
 * @param relevance         relevance score 0.0–1.0
 * @param anchor            whether this node is an anchor for the requirement
 * @param layer             numeric layer order for layout sorting
 * @param depth             taxonomy depth (0 = virtual root, 1 = first-level container, 2+ = concrete)
 * @param selectedForImpact whether the node was selected by the impact selector
 * @param parentId          taxonomy parent node ID within the diagram, or {@code null} for top-level nodes
 * @param container         {@code true} if this node exists only as a visual container/cluster
 *                          in the diagram — it groups child nodes but is <em>not</em> a
 *                          semantically valid architecture element.  Export formats such as
 *                          ArchiMate must skip container-only nodes (or map them to a
 *                          grouping construct) to avoid false architecture semantics.
 */
public record DiagramNode(
    String id,
    String label,
    String type,
    double relevance,
    boolean anchor,
    int layer,
    int depth,
    boolean selectedForImpact,
    String parentId,
    boolean container
) {
    /**
     * Backward-compatible constructor for existing code that does not need
     * the extended metadata fields.
     */
    public DiagramNode(String id, String label, String type,
                       double relevance, boolean anchor, int layer) {
        this(id, label, type, relevance, anchor, layer, 0, false, null, false);
    }
}
