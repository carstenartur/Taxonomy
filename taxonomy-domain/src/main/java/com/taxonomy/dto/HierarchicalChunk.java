package com.taxonomy.dto;

/**
 * A leaf-level chunk produced by the {@code HierarchicalChunkingService}.
 *
 * <p>Retains the hierarchical context (section path, parent heading, level) so
 * that downstream consumers (LLM analysis, RAG retrieval) can reassemble the
 * surrounding context when needed.
 */
public class HierarchicalChunk {

    private String text;
    private String sectionPath;
    private int level;
    private String parentHeading;
    private String parentContext;
    private String parentId;

    public HierarchicalChunk() {}

    // ── Accessors ──────────────────────────────────────────────────────────────

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getSectionPath() { return sectionPath; }
    public void setSectionPath(String sectionPath) { this.sectionPath = sectionPath; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public String getParentHeading() { return parentHeading; }
    public void setParentHeading(String parentHeading) { this.parentHeading = parentHeading; }

    public String getParentContext() { return parentContext; }
    public void setParentContext(String parentContext) { this.parentContext = parentContext; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
}
