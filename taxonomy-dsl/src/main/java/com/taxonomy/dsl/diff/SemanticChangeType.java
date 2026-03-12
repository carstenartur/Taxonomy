package com.taxonomy.dsl.diff;

/**
 * Categorizes architectural changes at a semantic level.
 *
 * <p>Unlike raw line-based diffs, these types describe the <em>meaning</em>
 * of a change in domain terms—useful for reviewability, change documentation,
 * release notes, and impact analysis.
 */
public enum SemanticChangeType {

    // ── Element lifecycle ────────────────────────────────────────────
    ELEMENT_ADDED("Element added"),
    ELEMENT_REMOVED("Element removed"),
    ELEMENT_TITLE_CHANGED("Element title changed"),
    ELEMENT_DESCRIPTION_CHANGED("Element description changed"),
    ELEMENT_TYPE_CHANGED("Element type changed"),
    ELEMENT_TAXONOMY_CHANGED("Element taxonomy changed"),
    ELEMENT_EXTENSIONS_CHANGED("Element extensions changed"),

    // ── Relation lifecycle ───────────────────────────────────────────
    RELATION_ADDED("Relation added"),
    RELATION_REMOVED("Relation removed"),
    RELATION_STATUS_CHANGED("Relation status changed"),
    RELATION_CONFIDENCE_CHANGED("Relation confidence changed"),
    RELATION_PROVENANCE_CHANGED("Relation provenance changed"),
    RELATION_EXTENSIONS_CHANGED("Relation extensions changed");

    private final String label;

    SemanticChangeType(String label) {
        this.label = label;
    }

    /** Human-readable label for display purposes. */
    public String getLabel() {
        return label;
    }
}
